package org.deltavault.core;

import java.io.IOException;
import java.util.List;

/** Paths are relative to the selected vault root, never URLs or absolute paths. */
public interface RemoteStore extends AutoCloseable {
    /** Ciphertext-only, fixed-length random access. Close cancels outstanding reads. */
    interface Reader extends AutoCloseable {
        long size();
        void readFully(long position, byte[] destination, int offset, int length) throws IOException;
        @Override void close();
    }
    int MAX_RANGE_BYTES = 256 * 1024;
    static void checkRange(long size, long position, byte[] destination, int offset, int length) throws IOException {
        if (destination == null || offset < 0 || length < 0 || length > destination.length - offset
                || length > MAX_RANGE_BYTES || position < 0 || position > size || length > size - position) {
            throw new IOException("Invalid or oversized ciphertext range");
        }
    }
    default Reader openReader(String path) throws IOException {
        throw new IOException("This storage provider does not support seekable video playback");
    }
    final class Blob implements AutoCloseable {
        public final byte[] bytes;
        public final String etag;
        public Blob(byte[] bytes, String etag) { this.bytes = bytes; this.etag = etag; }
        @Override public void close() { java.util.Arrays.fill(bytes, (byte) 0); }
    }
    final class Entry {
        public final String path;
        public final boolean directory;
        public Entry(String path, boolean directory) { this.path = path; this.directory = directory; }
    }
    Blob get(String path, int maxBytes) throws IOException;
    List<Entry> list(String path) throws IOException;
    /** createOnly uses If-None-Match: *, updates require a strong expected ETag. */
    void put(String path, byte[] bytes, String expectedEtag, boolean createOnly) throws IOException;
    void mkdir(String path) throws IOException;
    /** Publish a completed staged object atomically; never replace a destination. */
    void move(String sourcePath, String destinationPath) throws IOException;
    @Override void close();
}
