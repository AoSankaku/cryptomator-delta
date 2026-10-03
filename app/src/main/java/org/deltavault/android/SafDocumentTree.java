package org.deltavault.android;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Build;
import android.content.res.AssetFileDescriptor;
import android.provider.DocumentsContract;
import android.system.Os;
import android.system.OsConstants;
import android.system.ErrnoException;
import android.system.StructStat;

import org.deltavault.core.LocalStore;
import org.deltavault.core.RemoteStore;

import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Accesses only ciphertext and format metadata through the selected SAF tree grant. */
final class SafDocumentTree implements LocalStore.DocumentTree {
    private static final String[] COLUMNS = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS
    };
    private final ContentResolver resolver;
    private final Uri tree;
    private final String root;
    private final CancellationSignal cancellation = new CancellationSignal();
    private final Object gate = new Object();
    private final Set<Closeable> streams = new HashSet<>();
    private volatile boolean closed;

    SafDocumentTree(ContentResolver resolver, Uri tree) throws IOException {
        if (tree == null || !"content".equals(tree.getScheme()) || !DocumentsContract.isTreeUri(tree)
                || tree.getAuthority() == null || tree.getQuery() != null || tree.getFragment() != null) {
            throw new IOException("有効な保管庫フォルダーを選択してください。");
        }
        this.resolver = resolver; this.tree = tree; root = DocumentsContract.getTreeDocumentId(tree);
    }

    @Override public String rootId() { return root; }

    @Override public List<LocalStore.Document> children(String id) throws IOException {
        if (root.equals(id)) {
            // Query only after MainActivity has registered this cancellable connection.
            Record rootRecord = record(root);
            if (!rootRecord.document.directory) throw new IOException("保管庫のフォルダーを選択してください。");
            requireFlag(rootRecord, DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE);
        }
        documentUri(id);
        List<LocalStore.Document> result = new ArrayList<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
        try (Cursor cursor = resolver.query(children, COLUMNS, null, cancellation)) {
            if (cursor == null) throw new IOException("フォルダーを読み取れません。");
            while (cursor.moveToNext()) {
                ensureOpen();
                if (result.size() >= 10000) throw new IOException("フォルダーの項目が多すぎます。");
                result.add(fromCursor(cursor).document);
            }
            return result;
        } catch (RuntimeException e) { throw providerFailure(e); }
    }

    @Override public InputStream read(String id) throws IOException {
        Record record = record(id);
        if (record.document.directory || (record.flags & DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT) != 0) {
            throw new IOException("保管庫のファイルを通常のファイルとして開けません。");
        }
        AssetFileDescriptor descriptor = null;
        try {
            descriptor = resolver.openAssetFileDescriptor(documentUri(id), "r", cancellation);
            if (descriptor == null) throw new IOException("ファイルを開けません。");
            InputStream input = descriptor.createInputStream();
            track(input);
            AssetFileDescriptor owned = descriptor;
            return new FilterInputStream(input) {
                @Override public int read() throws IOException { ensureOpen(); int value = super.read(); ensureOpen(); return value; }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    ensureOpen(); int count = in.read(bytes, offset, length); ensureOpen(); return count;
                }
                @Override public void close() throws IOException {
                    try { super.close(); } finally { untrack(input); owned.close(); }
                }
            };
        } catch (IOException | RuntimeException e) {
            if (descriptor != null) try { descriptor.close(); } catch (IOException ignored) { }
            if (e instanceof IOException) throw (IOException) e;
            throw providerFailure((RuntimeException) e);
        }
    }

    @Override public RemoteStore.Reader openReader(String id) throws IOException {
        Record record = record(id);
        if (record.document.directory || (record.flags & DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT) != 0) {
            throw new IOException("動画を通常のファイルとして開けません。");
        }
        AssetFileDescriptor asset = null;
        try {
            asset = resolver.openAssetFileDescriptor(documentUri(id), "r", cancellation);
            if (asset == null) throw new IOException("動画を開けません。");
            FileDescriptor fd = asset.getFileDescriptor();
            Os.lseek(fd, 0, OsConstants.SEEK_CUR); // Refuse pipes and nonseekable providers.
            StructStat stat = Os.fstat(fd);
            long start = asset.getStartOffset();
            long size = asset.getDeclaredLength() < 0 ? stat.st_size - start : asset.getDeclaredLength();
            if (!OsConstants.S_ISREG(stat.st_mode) || start < 0 || size < 0 || size > (1L << 40)
                    || start > stat.st_size || size > stat.st_size - start) {
                throw new IOException("このフォルダー提供元は動画のシークに対応していません。");
            }
            LocalReader reader = new LocalReader(asset, start, size, stat.st_size);
            track(reader); return reader;
        } catch (IOException | ErrnoException | RuntimeException e) {
            if (asset != null) try { asset.close(); } catch (IOException ignored) { }
            throw new IOException("動画にはシーク可能なローカルファイルが必要です。", e);
        }
    }

    private final class LocalReader implements RemoteStore.Reader, Closeable {
        private final AssetFileDescriptor asset;
        private final long start, size, containerSize;
        private volatile boolean ended;
        LocalReader(AssetFileDescriptor asset, long start, long size, long containerSize) {
            this.asset = asset; this.start = start; this.size = size; this.containerSize = containerSize;
        }
        @Override public long size() { return size; }
        @Override public void readFully(long position, byte[] destination, int offset, int length) throws IOException {
            RemoteStore.checkRange(size, position, destination, offset, length);
            try {
                check();
                if (Os.fstat(asset.getFileDescriptor()).st_size != containerSize) throw new IOException("再生中に動画が変更されました。");
                int used = 0;
                while (used < length) {
                    check();
                    int count = Os.pread(asset.getFileDescriptor(), destination, offset + used, length - used, start + position + used);
                    if (count <= 0) throw new IOException("動画が途中で切れています。");
                    used += count;
                }
                check();
            } catch (ErrnoException e) { throw new IOException("動画の読み込みが中断されました。", e); }
        }
        private void check() throws IOException { ensureOpen(); if (ended) throw new IOException("動画の読み込みは終了しました。"); }
        @Override public void close() {
            ended = true; untrack(this);
            try { asset.close(); } catch (IOException ignored) { }
        }
    }

    @Override public void write(String id, byte[] ciphertext) throws IOException {
        Record record = record(id);
        if (record.document.directory) throw new IOException("ファイルの保存先が不正です。");
        requireFlag(record, DocumentsContract.Document.FLAG_SUPPORTS_WRITE);
        try (AssetFileDescriptor descriptor = resolver.openAssetFileDescriptor(documentUri(id), "wt", cancellation)) {
            if (descriptor == null) throw new IOException("ファイルを書き込めません。");
            try (OutputStream output = descriptor.createOutputStream()) {
                track(output);
                try {
                    for (int offset = 0; offset < ciphertext.length; offset += 8192) {
                        ensureOpen(); output.write(ciphertext, offset, Math.min(8192, ciphertext.length - offset));
                    }
                    ensureOpen(); output.flush();
                } finally { untrack(output); }
            }
            ensureOpen();
        } catch (RuntimeException e) { throw providerFailure(e); }
    }

    @Override public LocalStore.Document create(String parentId, String name, boolean directory) throws IOException {
        Record parent = record(parentId);
        if (!parent.document.directory) throw new IOException("保存先がフォルダーではありません。");
        requireFlag(parent, DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE);
        try {
            ensureOpen();
            Uri created = DocumentsContract.createDocument(resolver, documentUri(parentId),
                    directory ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream", name);
            return returnedDocument(created);
        } catch (RuntimeException e) { throw providerFailure(e); }
    }

    @Override public LocalStore.Document rename(String id, String name) throws IOException {
        requireFlag(record(id), DocumentsContract.Document.FLAG_SUPPORTS_RENAME);
        try {
            ensureOpen();
            return returnedDocument(DocumentsContract.renameDocument(resolver, documentUri(id), name));
        } catch (RuntimeException e) { throw providerFailure(e); }
    }

    @Override public void delete(String id) throws IOException {
        if (root.equals(id)) throw new IOException("保管庫ルートは削除できません。");
        requireFlag(record(id), DocumentsContract.Document.FLAG_SUPPORTS_DELETE);
        try {
            ensureOpen();
            if (!DocumentsContract.deleteDocument(resolver, documentUri(id))) throw new IOException("作業ファイルを削除できません。");
        } catch (RuntimeException e) { throw providerFailure(e); }
    }

    private LocalStore.Document returnedDocument(Uri uri) throws IOException {
        if (uri == null || !"content".equals(uri.getScheme()) || !tree.getAuthority().equals(uri.getAuthority())) {
            throw new IOException("フォルダー提供元が無効な結果を返しました。");
        }
        return record(DocumentsContract.getDocumentId(uri)).document;
    }
    private Uri documentUri(String id) throws IOException {
        ensureOpen();
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
        // Document IDs are opaque: never turn them into filesystem paths or concatenate URLs.
        // Before API 29 the framework enforces the tree grant when these URIs are opened.
        // All IDs come from traversing this tree or from its create/rename operations.
        if (!root.equals(id) && Build.VERSION.SDK_INT >= 29) {
            try {
                Uri rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, root);
                if (!DocumentsContract.isChildDocument(resolver, rootUri, uri)) {
                    throw new IOException("選択した保管庫の外へのアクセスを拒否しました。");
                }
            } catch (RuntimeException e) { throw providerFailure(e); }
        }
        return uri;
    }
    private Record record(String id) throws IOException {
        try (Cursor cursor = resolver.query(documentUri(id), COLUMNS, null, cancellation)) {
            if (cursor == null || !cursor.moveToFirst()) throw new IOException("保管庫のファイルが見つかりません。");
            Record record = fromCursor(cursor);
            if (!id.equals(record.document.id) || cursor.moveToNext()) throw new IOException("フォルダーの応答が不正です。");
            ensureOpen(); return record;
        } catch (RuntimeException e) { throw providerFailure(e); }
    }
    private static Record fromCursor(Cursor cursor) throws IOException {
        String id = cursor.getString(cursor.getColumnIndexOrThrow(COLUMNS[0]));
        String name = cursor.getString(cursor.getColumnIndexOrThrow(COLUMNS[1]));
        String mime = cursor.getString(cursor.getColumnIndexOrThrow(COLUMNS[2]));
        if (id == null || name == null || mime == null) throw new IOException("フォルダーの応答が不正です。");
        return new Record(new LocalStore.Document(id, name, DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)),
                cursor.getInt(cursor.getColumnIndexOrThrow(COLUMNS[3])));
    }
    private static final class Record {
        final LocalStore.Document document;
        final int flags;
        Record(LocalStore.Document document, int flags) { this.document = document; this.flags = flags; }
    }
    private static void requireFlag(Record record, int flag) throws IOException {
        if ((record.flags & flag) == 0) throw new IOException("このフォルダー提供元は必要な書き込み操作に対応していません。");
    }
    private static IOException providerFailure(RuntimeException cause) {
        return new IOException("フォルダーへのアクセス権または提供元の対応機能を確認してください。", cause);
    }
    private void ensureOpen() throws IOException { if (closed) throw new IOException("保管庫はロックされています。"); }
    private void track(Closeable stream) throws IOException {
        synchronized (gate) {
            if (closed) { stream.close(); throw new IOException("保管庫はロックされています。"); }
            streams.add(stream);
        }
    }
    private void untrack(Closeable stream) { synchronized (gate) { streams.remove(stream); } }
    @Override public void close() {
        List<Closeable> owned;
        synchronized (gate) { closed = true; owned = new ArrayList<>(streams); streams.clear(); }
        try { cancellation.cancel(); } catch (RuntimeException ignored) { /* Still close every owned descriptor. */ }
        for (Closeable stream : owned) try { stream.close(); } catch (IOException | RuntimeException ignored) { }
    }
}
