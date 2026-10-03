package org.deltavault.android;

import android.os.ProxyFileDescriptorCallback;
import android.system.ErrnoException;
import android.system.OsConstants;

import org.deltavault.core.RemoteStore;
import org.deltavault.core.VaultStream;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** A private seekable FD endpoint. No ContentProvider or localhost server exposes plaintext. */
final class VideoReadCallback extends ProxyFileDescriptorCallback implements AutoCloseable {
    private final VaultStream stream;
    private final Runnable failure;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object buffers = new Object();
    private byte[] lastBuffer;

    VideoReadCallback(VaultStream stream, Runnable failure) { this.stream = stream; this.failure = failure; }
    @Override public long onGetSize() throws ErrnoException {
        if (closed.get()) throw new ErrnoException("video", OsConstants.EBADF);
        try { return stream.size(); }
        catch (IOException e) { throw new ErrnoException("video", OsConstants.EBADF); }
    }
    @Override public int onRead(long offset, int size, byte[] data) throws ErrnoException {
        if (size < 0 || data == null || size > data.length || offset < 0) throw new ErrnoException("video", OsConstants.EINVAL);
        synchronized (buffers) {
            if (closed.get()) { Arrays.fill(data, (byte) 0); throw new ErrnoException("video", OsConstants.EBADF); }
            if (lastBuffer != null) Arrays.fill(lastBuffer, (byte) 0);
            lastBuffer = data;
        }
        try {
            // POSIX readers may seek beyond EOF while probing a container.
            if (offset >= stream.size()) { Arrays.fill(data, (byte) 0); return 0; }
            int count = stream.read(offset, data, 0, Math.min(size, RemoteStore.MAX_RANGE_BYTES));
            synchronized (buffers) {
                if (closed.get()) { Arrays.fill(data, (byte) 0); throw new ErrnoException("video", OsConstants.EBADF); }
                Arrays.fill(data, count, data.length, (byte) 0);
                return count;
            }
        } catch (IOException | RuntimeException e) {
            Arrays.fill(data, (byte) 0);
            boolean notify = !closed.get(); close();
            if (notify) failure.run();
            throw new ErrnoException("video", OsConstants.EIO);
        }
    }
    @Override public void onRelease() { close(); }
    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            stream.close();
            synchronized (buffers) {
                if (lastBuffer != null) Arrays.fill(lastBuffer, (byte) 0);
                lastBuffer = null;
            }
        }
    }
}
