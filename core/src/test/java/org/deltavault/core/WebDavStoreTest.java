package org.deltavault.core;

import okhttp3.OkHttpClient;
import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static org.junit.Assert.*;

public class WebDavStoreTest {
    private MockWebServer server;
    private WebDavStore store;
    private OkHttpClient testClient;

    @Before public void setUp() throws Exception {
        server = new MockWebServer(); server.start();
        HeldCertificate certificate = new HeldCertificate.Builder().addSubjectAlternativeName(server.getHostName()).build();
        HandshakeCertificates serverTls = new HandshakeCertificates.Builder().heldCertificate(certificate).build();
        HandshakeCertificates clientTls = new HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate()).build();
        server.useHttps(serverTls.sslSocketFactory(), false);
        OkHttpClient client = new OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager())
                .followRedirects(false).followSslRedirects(false).callTimeout(3, TimeUnit.SECONDS).build();
        store = new WebDavStore(server.url("/vault/").toString(), "user", "test-password".toCharArray(), client);
        testClient = client;
    }
    @After public void tearDown() throws Exception { store.close(); server.shutdown(); }

    @Test public void refusesHttpAndUrlCredentials() {
        assertThrows(IllegalArgumentException.class, () -> new WebDavStore("http://host/vault/", "", new char[0]));
        assertThrows(IllegalArgumentException.class, () -> new WebDavStore("https://user:pass@host/vault/", "", new char[0]));
        assertThrows(IllegalArgumentException.class, () -> new WebDavStore("https://host/vault/?secret=x", "", new char[0]));
    }
    @Test public void rejectsTraversalBeforeSending() {
        for (String path : new String[]{"../secret", "d/../secret", "/secret", "d//secret", "d\\secret", "d/"}) {
            assertThrows(IllegalArgumentException.class, () -> store.get(path, 32));
        }
        assertEquals(0, server.getRequestCount());
    }
    @Test public void readsBytesWithEtagAndWipesOnClose() throws Exception {
        server.enqueue(new MockResponse().setBody("ciphertext").addHeader("ETag", "\"v1\""));
        RemoteStore.Blob blob = store.get("d/AB/object.c9r", 32);
        assertEquals("\"v1\"", blob.etag);
        assertEquals("ciphertext", new String(blob.bytes, StandardCharsets.UTF_8));
        blob.close(); assertArrayEquals(new byte[10], blob.bytes);
        RecordedRequest request = takeRequest();
        assertEquals("/vault/d/AB/object.c9r", request.getPath());
        assertEquals("no-store", request.getHeader("Cache-Control"));
        assertNotNull(request.getHeader("Authorization"));
    }
    @Test public void neverFollowsRedirects() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", "https://attacker.invalid/"));
        assertThrows(IOException.class, () -> store.get("vault.cryptomator", 1024));
        assertEquals(1, server.getRequestCount());
    }
    @Test public void enforcesSizeEvenForChunkedResponse() throws Exception {
        server.enqueue(new MockResponse().setChunkedBody("too much data", 2));
        assertThrows(IOException.class, () -> store.get("file.c9r", 4));
        assertThrows(IOException.class, () -> WebDavStore.readBounded(new ByteArrayInputStream(new byte[5]), -1, 4));
    }
    @Test public void newPutHasNoOverwriteCondition() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(201));
        store.put("new.c9r", new byte[]{1, 2}, null, true);
        RecordedRequest request = takeRequest();
        assertEquals("PUT", request.getMethod());
        assertEquals("*", request.getHeader("If-None-Match"));
        assertNull(request.getHeader("If-Match"));
    }
    @Test public void overwriteUsesReadVersionAndReportsConflict() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(412));
        IOException conflict = assertThrows(IOException.class, () -> store.put("file.c9r", new byte[]{1}, "\"old\"", false));
        assertEquals("Unexpected pre-request failure: " + conflict, 1, server.getRequestCount());
        assertEquals("\"old\"", takeRequest().getHeader("If-Match"));
        assertThrows(IOException.class, () -> store.put("file.c9r", new byte[]{1}, null, false));
        assertThrows(IOException.class, () -> store.put("file.c9r", new byte[]{1}, "W/\"weak\"", false));
        assertEquals(1, server.getRequestCount());
    }
    @Test public void parsesNamespacePropertiesAndDecodesExactlyOnce() throws Exception {
        server.enqueue(multistatus(row("/vault/d/AB/", true) + row("/vault/d/AB/a%20b.c9r", false) + row("/vault/d/AB/folder.c9r/", true)));
        List<RemoteStore.Entry> entries = store.list("d/AB");
        assertEquals(2, entries.size());
        assertEquals("d/AB/a b.c9r", entries.get(0).path); assertFalse(entries.get(0).directory);
        assertEquals("d/AB/folder.c9r", entries.get(1).path); assertTrue(entries.get(1).directory);
        RecordedRequest request = takeRequest();
        assertEquals("PROPFIND", request.getMethod()); assertEquals("1", request.getHeader("Depth"));
    }
    @Test public void rejectsHostEscapesDepthEscapesAndEncodedSlash() throws Exception {
        for (String href : new String[]{"https://evil.invalid/vault/d/AB/file", "/outside/file", "/vault/d/AB/sub/file", "/vault/d/AB/a%2Fb.c9r", "/vault/d/AB/a%5Cb.c9r"}) {
            server.enqueue(multistatus(row(href, false)));
            assertThrows(IOException.class, () -> store.list("d/AB"));
        }
    }
    @Test public void ignores404Properties() throws Exception {
        server.enqueue(multistatus("<d:response><d:href>/vault/d/AB/ghost.c9r</d:href><d:propstat><d:prop/><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>"));
        assertTrue(store.list("d/AB").isEmpty());
    }
    @Test public void rejectsDtdAndDuplicatePaths() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(207).setBody("<!DOCTYPE d:multistatus [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><d:multistatus xmlns:d='DAV:'>&x;</d:multistatus>"));
        assertThrows(IOException.class, () -> store.list("d/AB"));
        server.enqueue(multistatus(row("/vault/d/AB/same.c9r", false) + row("/vault/d/AB/same.c9r", false)));
        assertThrows(IOException.class, () -> store.list("d/AB"));
    }
    @Test public void existingMkcolIsAllowedOnlyForCollections() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(405)); server.enqueue(multistatus(row("/vault/d/AB/", true)));
        store.mkdir("d/AB");
        assertEquals("MKCOL", takeRequest().getMethod());
        assertEquals("0", takeRequest().getHeader("Depth"));
        server.enqueue(new MockResponse().setResponseCode(405)); server.enqueue(multistatus(row("/vault/d/AB/", false)));
        assertThrows(IOException.class, () -> store.mkdir("d/AB"));
    }
    @Test public void movePublishesWithoutReplacingAnExistingObject() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(201));
        store.move("d/AB/random.upload", "d/AB/final.c9s");
        RecordedRequest request = takeRequest();
        assertEquals("MOVE", request.getMethod());
        assertEquals("F", request.getHeader("Overwrite"));
        assertEquals(server.url("/vault/d/AB/final.c9s").toString(), request.getHeader("Destination"));
        server.enqueue(new MockResponse().setResponseCode(412));
        assertThrows(IOException.class, () -> store.move("d/AB/random.upload", "d/AB/final.c9s"));
    }
    @Test public void lockCancelsBodyConsumptionAfterHeaders() throws Exception {
        store.close();
        CountDownLatch bodyStarted = new CountDownLatch(1);
        OkHttpClient client = testClient.newBuilder().eventListener(new EventListener() {
            @Override public void responseBodyStart(Call call) { bodyStarted.countDown(); }
        }).build();
        store = new WebDavStore(server.url("/vault/").toString(), "user", new char[0], client);
        server.enqueue(new MockResponse().setBody(new String(new char[1024]).replace('\0', 'x')).throttleBody(1, 100, TimeUnit.MILLISECONDS));
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            Future<RemoteStore.Blob> read = reader.submit(() -> store.get("file.c9r", 2048));
            assertTrue(bodyStarted.await(2, TimeUnit.SECONDS)); store.close();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> read.get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IOException);
            assertThrows(IllegalStateException.class, () -> store.get("another.c9r", 8));
        } finally { reader.shutdownNow(); }
    }
    private static MockResponse multistatus(String rows) {
        return new MockResponse().setResponseCode(207).setBody("<?xml version='1.0'?><d:multistatus xmlns:d='DAV:'>" + rows + "</d:multistatus>");
    }
    private static MockResponse range(String bytes, int start, int total, String etag) {
        return new MockResponse().setResponseCode(206).setBody(bytes).addHeader("ETag", etag)
                .addHeader("Content-Range", "bytes " + start + "-" + (start + bytes.length() - 1) + "/" + total);
    }
    @Test public void movieRangesPinVersionAndBoundReads() throws Exception {
        server.enqueue(range("a", 0, 100000000, "\"movie\""));
        try (RemoteStore.Reader reader = store.openReader("movie.c9r")) {
            assertEquals(100000000L, reader.size());
            assertEquals("bytes=0-0", takeRequest().getHeader("Range"));
            server.enqueue(range("xyz", 90000000, 100000000, "\"movie\""));
            byte[] bytes = new byte[5]; reader.readFully(90000000, bytes, 1, 3);
            assertArrayEquals(new byte[]{0, 'x', 'y', 'z', 0}, bytes);
            RecordedRequest request = takeRequest();
            assertEquals("bytes=90000000-90000002", request.getHeader("Range"));
            assertEquals("\"movie\"", request.getHeader("If-Match"));
            assertEquals("identity", request.getHeader("Accept-Encoding"));
            assertThrows(IOException.class, () -> reader.readFully(100000000, bytes, 0, 1));
            assertThrows(IOException.class, () -> reader.readFully(-1, bytes, 0, 1));
            assertThrows(IOException.class, () -> reader.readFully(0, new byte[RemoteStore.MAX_RANGE_BYTES + 1], 0, RemoteStore.MAX_RANGE_BYTES + 1));
            assertEquals(2, server.getRequestCount());
        }
    }
    @Test public void movieRangesRejectUnavailableAndUnversionedServers() throws Exception {
        server.enqueue(new MockResponse().setBody("whole movie"));
        assertThrows(IOException.class, () -> store.openReader("movie.c9r"));
        server.enqueue(range("a", 0, 99, "W/\"weak\""));
        assertThrows(IOException.class, () -> store.openReader("movie.c9r"));
        server.enqueue(range("a", 0, 99, "\"ok\"").removeHeader("ETag"));
        assertThrows(IOException.class, () -> store.openReader("movie.c9r"));
    }
    @Test public void movieRangesRejectWrongOffsetsLengthsVersionsAndEncoding() throws Exception {
        server.enqueue(range("a", 0, 100, "\"v1\""));
        try (RemoteStore.Reader reader = store.openReader("movie.c9r")) {
            for (MockResponse response : new MockResponse[]{
                    range("ab", 11, 100, "\"v1\""), range("ab", 10, 101, "\"v1\""),
                    range("ab", 10, 100, "\"v2\""), range("ab", 10, 100, "\"v1\"").addHeader("Content-Encoding", "gzip"),
                    range("a", 10, 100, "\"v1\"").setHeader("Content-Range", "bytes 10-11/100"),
                    range("abc", 10, 100, "\"v1\"").setHeader("Content-Range", "bytes 10-11/100"),
                    new MockResponse().setResponseCode(412), new MockResponse().setResponseCode(302).addHeader("Location", "https://attacker.invalid/")}) {
                server.enqueue(response); assertThrows(IOException.class, () -> reader.readFully(10, new byte[2], 0, 2));
            }
            assertEquals(9, server.getRequestCount());
        }
    }
    @Test public void closingMovieCancelsOnlyItsOutstandingRead() throws Exception {
        server.enqueue(range("a", 0, 1000, "\"v1\""));
        RemoteStore.Reader movie = store.openReader("movie.c9r"); takeRequest();
        server.enqueue(range("xxxxxxxxxx", 1, 1000, "\"v1\"").throttleBody(1, 1, TimeUnit.SECONDS));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> reading = worker.submit(() -> { movie.readFully(1, new byte[10], 0, 10); return null; });
            takeRequest(); movie.close();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> reading.get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IOException);
            assertThrows(IOException.class, () -> movie.readFully(0, new byte[1], 0, 1));
            server.enqueue(new MockResponse().setBody("still connected"));
            try (RemoteStore.Blob blob = store.get("another.c9r", 64)) { assertEquals(15, blob.bytes.length); }
        } finally { worker.shutdownNow(); movie.close(); }
    }
    private RecordedRequest takeRequest() throws InterruptedException {
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull("Expected HTTPS request was not sent", request);
        return request;
    }
    private static String row(String href, boolean directory) {
        return "<d:response><d:href>" + href + "</d:href><d:propstat><d:prop><d:resourcetype>" + (directory ? "<d:collection/>" : "")
                + "</d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>";
    }
}

