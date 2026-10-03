package org.deltavault.core;

import okhttp3.Credentials;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import org.kxml2.io.KXmlParser;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

/** HTTPS only, no HTTP cache, redirects, cookies, credential persistence or response logging. */
public final class WebDavStore implements RemoteStore {
    private static final MediaType BINARY = MediaType.get("application/octet-stream");
    private static final MediaType XML = MediaType.get("application/xml; charset=utf-8");
    private static final String PROPFIND = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>";
    private final HttpUrl root;
    private final OkHttpClient client;
    private String authorization;
    private volatile boolean closed;
    private final Object callGate = new Object();
    private final Set<Call> activeCalls = new HashSet<>();
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)");

    public WebDavStore(String vaultUrl, String username, char[] password) {
        this(vaultUrl, username, password, new OkHttpClient.Builder()
                .followRedirects(false).followSslRedirects(false)
                .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build());
    }

    // Client injection is package-private for HTTPS transport tests only.
    WebDavStore(String vaultUrl, String username, char[] password, OkHttpClient client) {
        HttpUrl url = HttpUrl.get(vaultUrl);
        if (!url.isHttps() || !url.username().isEmpty() || !url.password().isEmpty()
                || url.query() != null || url.fragment() != null || username.contains(":")) {
            throw new IllegalArgumentException("HTTPSの保管庫URLと有効なユーザー名を指定してください。");
        }
        this.root = url.encodedPath().endsWith("/") ? url : url.newBuilder().addPathSegment("").build();
        this.client = client;
        this.authorization = Credentials.basic(username, new String(password), StandardCharsets.UTF_8);
    }

    @Override public Blob get(String path, int maxBytes) throws IOException {
        try (Response response = execute(request(path).get().build())) {
            require(response, 200);
            if (response.body() == null) throw new IOException("WebDAV応答が空です。");
            return new Blob(readBounded(response.body().byteStream(), response.body().contentLength(), maxBytes), response.header("ETag"));
        }
    }

    @Override public Reader openReader(String path) throws IOException {
        // A one-byte range establishes the version and length without downloading the movie.
        try (Response response = execute(request(path).header("Accept-Encoding", "identity")
                .header("Range", "bytes=0-0").get().build())) {
            long size = rangeSize(response, 0, 1);
            String etag = response.header("ETag");
            if (!strongEtag(etag)) throw new IOException("動画再生にはWebDAVの強いETagが必要です。");
            byte[] probe = rangeBytes(response, 1);
            Arrays.fill(probe, (byte) 0);
            synchronized (callGate) {
                if (closed) throw new IOException("接続はロックされています。");
                return new RangeReader(path, size, etag);
            }
        }
    }

    private final class RangeReader implements Reader {
        private final String path, etag;
        private final long size;
        private final Set<Call> calls = new HashSet<>();
        private boolean ended;
        RangeReader(String path, long size, String etag) { this.path = path; this.size = size; this.etag = etag; }
        @Override public long size() { return size; }
        @Override public void readFully(long position, byte[] destination, int offset, int length) throws IOException {
            RemoteStore.checkRange(size, position, destination, offset, length);
            synchronized (callGate) { if (closed || ended) throw new IOException("動画の読み込みは終了しました。"); }
            if (length == 0) return;
            Request request = request(path).header("Accept-Encoding", "identity").header("If-Match", etag)
                    .header("Range", "bytes=" + position + "-" + (position + length - 1)).get().build();
            try (Response response = execute(request, this)) {
                if (rangeSize(response, position, length) != size || !etag.equals(response.header("ETag"))) {
                    throw new IOException("再生中に動画が変更されました。開き直してください。");
                }
                byte[] bytes = rangeBytes(response, length);
                try {
                    synchronized (callGate) {
                        if (closed || ended) throw new IOException("動画の読み込みは終了しました。");
                        System.arraycopy(bytes, 0, destination, offset, length);
                    }
                } finally { Arrays.fill(bytes, (byte) 0); }
            }
        }
        @Override public void close() {
            synchronized (callGate) {
                ended = true;
                for (Call call : calls) call.cancel();
                calls.clear();
            }
        }
    }

    private static long rangeSize(Response response, long position, int length) throws IOException {
        if (response.code() != 206) {
            if (response.code() == 200) throw new IOException("このWebDAVサーバーは動画のRange取得に対応していません。");
            require(response, 206);
        }
        String encoding = response.header("Content-Encoding");
        if (encoding != null && !"identity".equalsIgnoreCase(encoding)) throw new IOException("Invalid range encoding");
        Matcher range = CONTENT_RANGE.matcher(response.header("Content-Range", ""));
        try {
            if (!range.matches() || Long.parseLong(range.group(1)) != position
                    || Long.parseLong(range.group(2)) != position + length - 1) throw new IOException("Invalid Content-Range");
            long size = Long.parseLong(range.group(3));
            if (size <= position || length > size - position || size > (1L << 40)) throw new IOException("Invalid movie size");
            return size;
        } catch (NumberFormatException e) { throw new IOException("Invalid Content-Range", e); }
    }
    private static byte[] rangeBytes(Response response, int length) throws IOException {
        if (response.body() == null) throw new IOException("Empty range response");
        byte[] bytes = readBounded(response.body().byteStream(), response.body().contentLength(), length);
        if (bytes.length != length) { Arrays.fill(bytes, (byte) 0); throw new IOException("Truncated range response"); }
        return bytes;
    }

    @Override public List<Entry> list(String path) throws IOException {
        HttpUrl parent = resource(path, true);
        Request request = authorized(parent).header("Depth", "1")
                .method("PROPFIND", RequestBody.create(PROPFIND, XML)).build();
        try (Response response = execute(request)) {
            require(response, 207);
            if (response.body() == null) throw new IOException("WebDAV応答が空です。");
            byte[] bytes = readBounded(response.body().byteStream(), response.body().contentLength(), 2 * 1024 * 1024);
            return parseListing(parent, new String(bytes, StandardCharsets.UTF_8));
        }
    }

    @Override public void put(String path, byte[] bytes, String expectedEtag, boolean createOnly) throws IOException {
        Request.Builder request = request(path).put(RequestBody.create(bytes, BINARY));
        if (createOnly) request.header("If-None-Match", "*");
        else {
            if (!strongEtag(expectedEtag)) throw new IOException("安全な上書きにはサーバーの強いETagが必要です。");
            request.header("If-Match", expectedEtag);
        }
        try (Response response = execute(request.build())) { require(response, 200, 201, 204); }
    }

    @Override public void mkdir(String path) throws IOException {
        HttpUrl url = resource(path, true);
        try (Response response = execute(authorized(url).method("MKCOL", null).build())) {
            if (response.code() == 405) {
                // 405 also means an existing FILE: verify that this resource is a collection.
                try (Response probe = execute(authorized(url).header("Depth", "0")
                        .method("PROPFIND", RequestBody.create(PROPFIND, XML)).build())) {
                    require(probe, 207);
                    if (probe.body() == null) throw new IOException("WebDAV応答が空です。");
                    String xml = new String(readBounded(probe.body().byteStream(), probe.body().contentLength(), 64 * 1024), StandardCharsets.UTF_8);
                    List<DavResponse> rows = parseXml(xml);
                    for (DavResponse row : rows) {
                        if (row.success && row.collection && sameResource(url, resolveHref(url, row.href))) return;
                    }
                    throw new IOException("フォルダーの作成先に別のファイルがあります。");
                }
            }
            require(response, 201);
        }
    }

    @Override public void move(String sourcePath, String destinationPath) throws IOException {
        HttpUrl destination = resource(destinationPath, false);
        Request request = request(sourcePath).header("Destination", destination.toString())
                .header("Overwrite", "F").method("MOVE", null).build();
        try (Response response = execute(request)) { require(response, 201); }
    }

    private Request.Builder request(String path) { return authorized(resource(path, false)); }
    private Request.Builder authorized(HttpUrl url) {
        if (closed) throw new IllegalStateException("接続はロックされています。");
        return new Request.Builder().url(url).header("Authorization", authorization).header("Cache-Control", "no-store");
    }
    private Response execute(Request request) throws IOException { return execute(request, null); }
    private Response execute(Request request, RangeReader reader) throws IOException {
        final Call call;
        synchronized (callGate) {
            if (closed || reader != null && reader.ended) throw new IOException("接続はロックされています。");
            call = client.newCall(request); activeCalls.add(call);
            if (reader != null) reader.calls.add(call);
        }
        try {
            Response response = call.execute();
            ResponseBody body = response.body();
            if (body == null) { forget(call, reader); return response; }
            // Keep the Call tracked through body consumption, after synchronous execute returns.
            BufferedSource source = Okio.buffer(new ForwardingSource(body.source()) {
                @Override public void close() throws IOException { try { super.close(); } finally { forget(call, reader); } }
            });
            return response.newBuilder().body(new ResponseBody() {
                @Override public MediaType contentType() { return body.contentType(); }
                @Override public long contentLength() { return body.contentLength(); }
                @Override public BufferedSource source() { return source; }
            }).build();
        } catch (IOException | RuntimeException e) { forget(call, reader); throw e; }
    }
    private void forget(Call call, RangeReader reader) {
        synchronized (callGate) { activeCalls.remove(call); if (reader != null) reader.calls.remove(call); }
    }
    private HttpUrl resource(String path, boolean directory) {
        validatePath(path);
        HttpUrl.Builder builder = root.newBuilder();
        if (!path.isEmpty()) for (String segment : path.split("/")) builder.addPathSegment(segment);
        if (directory && !path.isEmpty()) builder.addPathSegment("");
        return builder.build();
    }
    static void validatePath(String path) {
        if (path == null || path.startsWith("/") || path.endsWith("/") || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("無効な保管庫パスです。");
        }
        if (!path.isEmpty()) for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IllegalArgumentException("無効な保管庫パスです。");
        }
    }
    static boolean strongEtag(String etag) {
        return etag != null && etag.length() >= 2 && etag.startsWith("\"") && etag.endsWith("\"")
                && etag.indexOf('\n') < 0 && etag.indexOf('\r') < 0;
    }
    static byte[] readBounded(InputStream stream, long reportedSize, int maxBytes) throws IOException {
        if (maxBytes < 0 || reportedSize > maxBytes) throw new IOException("ファイルがメモリー上限を超えています。");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            if (output.size() > maxBytes - count) throw new IOException("ファイルがメモリー上限を超えています。");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
    private static void require(Response response, int... accepted) throws IOException {
        for (int code : accepted) if (response.code() == code) return;
        switch (response.code()) {
            case 401: case 403: throw new IOException("WebDAVの認証またはアクセス権を確認してください。");
            case 404: throw new IOException("保管庫またはファイルが見つかりません。");
            case 409: case 412: throw new IOException("同名ファイルまたは他端末の変更と競合しました。再読み込みしてください。");
            default: throw new IOException("WebDAV操作に失敗しました (HTTP " + response.code() + ")。");
        }
    }

    private List<Entry> parseListing(HttpUrl parent, String xml) throws IOException {
        List<Entry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (DavResponse row : parseXml(xml)) {
            if (!row.success) continue;
            HttpUrl child = resolveHref(parent, row.href);
            if (sameResource(parent, child)) continue;
            String childPath = child.encodedPath();
            if (childPath.endsWith("/")) childPath = childPath.substring(0, childPath.length() - 1);
            if (!childPath.startsWith(parent.encodedPath())) throw new IOException("WebDAVが保管庫外のパスを返しました。");
            String tail = childPath.substring(parent.encodedPath().length());
            if (tail.isEmpty() || tail.contains("/")) throw new IOException("WebDAVの一覧に無効なパスがあります。");
            List<String> segments = child.pathSegments();
            StringBuilder relative = new StringBuilder();
            int rootDepth = root.pathSegments().size() - 1;
            for (int i = rootDepth; i < segments.size(); i++) {
                String segment = segments.get(i);
                if (segment.isEmpty() && i == segments.size() - 1) continue;
                if (segment.contains("/")) throw new IOException("WebDAVの一覧に無効なパスがあります。");
                if (relative.length() > 0) relative.append('/');
                relative.append(segment);
            }
            String path = relative.toString();
            try { validatePath(path); } catch (IllegalArgumentException e) { throw new IOException("WebDAVの一覧に無効なパスがあります。"); }
            if (!seen.add(path)) throw new IOException("WebDAVの一覧に重複パスがあります。");
            entries.add(new Entry(path, row.collection));
        }
        return entries;
    }
    private HttpUrl resolveHref(HttpUrl base, String href) throws IOException {
        HttpUrl resolved = href == null ? null : base.resolve(href);
        if (resolved == null || !resolved.scheme().equals(root.scheme()) || !resolved.host().equals(root.host())
                || resolved.port() != root.port() || !resolved.username().isEmpty() || !resolved.password().isEmpty()
                || resolved.query() != null || resolved.fragment() != null || !resolved.encodedPath().startsWith(root.encodedPath())) {
            throw new IOException("WebDAVが保管庫外のパスを返しました。");
        }
        return resolved;
    }
    private static boolean sameResource(HttpUrl first, HttpUrl second) {
        return first.encodedPath().replaceAll("/$", "").equals(second.encodedPath().replaceAll("/$", ""));
    }
    private static final class DavResponse {
        String href;
        boolean collection;
        boolean success;
    }
    private static List<DavResponse> parseXml(String xml) throws IOException {
        try {
            KXmlParser parser = new KXmlParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
            parser.setInput(new StringReader(xml));
            List<DavResponse> rows = new ArrayList<>();
            DavResponse row = null;
            boolean inPropstat = false, collection = false, success = false;
            boolean multistatus = false;
            for (int event = parser.nextToken(); event != XmlPullParser.END_DOCUMENT; event = parser.nextToken()) {
                if (event == XmlPullParser.DOCDECL) throw new IOException("DTDを含むWebDAV応答は受け付けません。");
                if (event == XmlPullParser.START_TAG && "DAV:".equals(parser.getNamespace())) {
                    switch (parser.getName()) {
                        case "multistatus": multistatus = true; break;
                        case "response": row = new DavResponse(); break;
                        case "href": if (row != null) row.href = parser.nextText(); break;
                        case "propstat": inPropstat = true; collection = false; success = false; break;
                        case "collection": if (inPropstat) collection = true; break;
                        case "status":
                            String status = parser.nextText();
                            if (inPropstat) success = status.matches("HTTP/\\d(?:\\.\\d)? 200(?: .*|)");
                            break;
                    }
                } else if (event == XmlPullParser.END_TAG && "DAV:".equals(parser.getNamespace())) {
                    if ("propstat".equals(parser.getName())) {
                        if (row != null && success) { row.success = true; row.collection |= collection; }
                        inPropstat = false;
                    } else if ("response".equals(parser.getName()) && row != null) {
                        if (rows.size() >= 10000) throw new IOException("WebDAVの一覧が大きすぎます。");
                        rows.add(row); row = null;
                    }
                }
            }
            if (!multistatus) throw new IOException("WebDAVの一覧形式が不正です。");
            return rows;
        } catch (XmlPullParserException e) { throw new IOException("WebDAVのXML応答を読み取れません。"); }
    }
    @Override public void close() {
        synchronized (callGate) {
            closed = true; authorization = null;
            for (Call call : activeCalls) call.cancel();
            activeCalls.clear();
        }
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
    }
}
