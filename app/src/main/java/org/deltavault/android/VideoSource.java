package org.deltavault.android;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.storage.StorageManager;

import org.deltavault.core.VaultStream;

import java.io.IOException;

/** Android's proxy descriptor delivers authenticated RAM bytes to LibVLC on a dedicated thread. */
final class VideoSource implements AutoCloseable {
    final VideoReadCallback callback;
    private final HandlerThread thread = new HandlerThread("vault-video-read");
    private ParcelFileDescriptor descriptor;
    VideoSource(Context context, VaultStream stream, Runnable failure) throws IOException {
        callback = new VideoReadCallback(stream, failure);
        thread.start();
        try {
            StorageManager manager = context.getSystemService(StorageManager.class);
            if (manager == null) throw new IOException("動画用のファイル記述子を作成できません。");
            descriptor = manager.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, new Handler(thread.getLooper()));
        } catch (IOException | RuntimeException e) { close(); throw e; }
    }
    java.io.FileDescriptor fd() { return descriptor.getFileDescriptor(); }
    @Override public void close() {
        try { callback.close(); } // Revoke before the decoder can request more plaintext.
        finally {
            if (descriptor != null) {
                try { descriptor.close(); } catch (IOException ignored) { }
                descriptor = null;
            }
            thread.quitSafely();
        }
    }
}
