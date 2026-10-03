package org.deltavault.android;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.system.ErrnoException;
import android.system.OsConstants;

import org.deltavault.core.RemoteStore;
import org.deltavault.core.VaultSession;
import org.deltavault.core.VaultStream;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Private seekable plaintext FD for the isolated renderer. It creates no plaintext files. */
final class PreviewSource implements AutoCloseable {
    static final int MAX_IMAGE_BYTES = 16 * 1024 * 1024;
    static final long MAX_PDF_BYTES = 256L * 1024 * 1024;
    private final HandlerThread thread = new HandlerThread("vault-preview-read");
    private final ReadCallback callback;
    private ParcelFileDescriptor descriptor;

    PreviewSource(Context context, VaultStream stream) throws IOException {
        if (stream == null) throw new IOException("Missing PDF stream");
        long length;
        try { length = stream.size(); }
        catch (IOException | RuntimeException e) { stream.close(); throw e; }
        if (length < 0 || length > MAX_PDF_BYTES) { stream.close(); throw new IOException("PDF exceeds the preview size limit"); }
        callback = new ReadCallback(stream, null, length);
        open(context);
    }

    PreviewSource(Context context, VaultSession.ReadFile image) throws IOException {
        if (image == null || image.bytes == null || image.bytes.length > MAX_IMAGE_BYTES) {
            if (image != null) image.close();
            throw new IOException("Image exceeds the preview size limit");
        }
        callback = new ReadCallback(null, image, image.bytes.length);
        open(context);
    }

    ParcelFileDescriptor descriptor() {
        ParcelFileDescriptor current = descriptor;
        if (current == null) throw new IllegalStateException("Preview source is closed");
        return current;
    }

    private void open(Context context) throws IOException {
        thread.start();
        try {
            StorageManager manager = context.getSystemService(StorageManager.class);
            if (manager == null) throw new IOException("Unable to create a private preview descriptor");
            descriptor = manager.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, new Handler(thread.getLooper()));
        } catch (IOException | RuntimeException e) {
            close();
            if (e instanceof IOException io) throw io;
            throw new IOException("Unable to create a private preview descriptor", e);
        }
    }

    @Override public void close() {
        try { callback.close(); }
        finally {
            ParcelFileDescriptor current = descriptor;
            descriptor = null;
            try { if (current != null) current.close(); }
            catch (IOException ignored) { }
            finally { thread.quitSafely(); }
        }
    }

    private static final class ReadCallback extends ProxyFileDescriptorCallback implements AutoCloseable {
        private final VaultStream stream;
        private final VaultSession.ReadFile image;
        private final long length;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Object gate = new Object();
        private byte[] lastBuffer;

        ReadCallback(VaultStream stream, VaultSession.ReadFile image, long length) {
            this.stream = stream; this.image = image; this.length = length;
        }

        @Override public long onGetSize() throws ErrnoException {
            if (closed.get()) throw new ErrnoException("preview", OsConstants.EBADF);
            return length;
        }

        @Override public int onRead(long offset, int size, byte[] data) throws ErrnoException {
            if (data == null || size < 0 || size > data.length || offset < 0) throw new ErrnoException("preview", OsConstants.EINVAL);
            synchronized (gate) {
                if (closed.get()) { Arrays.fill(data, (byte) 0); throw new ErrnoException("preview", OsConstants.EBADF); }
                if (lastBuffer != null && lastBuffer != data) Arrays.fill(lastBuffer, (byte) 0);
                lastBuffer = data;
            }
            try {
                if (offset >= length || size == 0) { Arrays.fill(data, (byte) 0); return 0; }
                int wanted = (int) Math.min((long) size, length - offset);
                int copied = 0;
                while (copied < wanted) {
                    if (closed.get()) throw new IOException("Preview source closed");
                    int amount = Math.min(wanted - copied, RemoteStore.MAX_RANGE_BYTES);
                    if (image != null) {
                        System.arraycopy(image.bytes, Math.toIntExact(offset + copied), data, copied, amount);
                        copied += amount;
                    } else {
                        int count = stream.read(offset + copied, data, copied, amount);
                        if (count <= 0) throw new IOException("Unexpected end of PDF stream");
                        copied += count;
                    }
                }
                synchronized (gate) {
                    if (closed.get()) { Arrays.fill(data, (byte) 0); throw new IOException("Preview source closed"); }
                    Arrays.fill(data, copied, data.length, (byte) 0);
                }
                return copied;
            } catch (IOException | RuntimeException e) {
                Arrays.fill(data, (byte) 0);
                close();
                throw new ErrnoException("preview", OsConstants.EIO);
            }
        }

        @Override public void onRelease() { close(); }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            try { if (stream != null) stream.close(); }
            catch (RuntimeException ignored) { }
            finally {
                try { if (image != null) image.close(); }
                catch (RuntimeException ignored) { }
                finally {
                    synchronized (gate) {
                        if (lastBuffer != null) Arrays.fill(lastBuffer, (byte) 0);
                        lastBuffer = null;
                    }
                }
            }
        }
    }
}
