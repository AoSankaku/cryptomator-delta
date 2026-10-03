package org.deltavault.android;

import android.content.Context;
import android.content.ContextWrapper;
import android.system.ErrnoException;
import android.system.Os;

import java.io.File;
import java.io.IOException;

/** VLC3 extracts embedded cover art during playback even with metadata lookup disabled. */
final class VlcNoStorageContext extends ContextWrapper {
    VlcNoStorageContext(Context context) throws IOException {
        super(context.getApplicationContext());
        try {
            // JNI sets HOME from getDir("vlc"). A character device cannot contain cache files.
            // XDG variables must also point to it so they cannot override the HOME restriction.
            Os.setenv("XDG_CACHE_HOME", "/dev/null", true);
            Os.setenv("XDG_DATA_HOME", "/dev/null", true);
            Os.setenv("XDG_CONFIG_HOME", "/dev/null", true);
        } catch (ErrnoException e) { throw new IOException("動画キャッシュの保存を無効にできません。", e); }
    }
    @Override public File getDir(String name, int mode) {
        if ("vlc".equals(name)) return new File("/dev/null");
        return super.getDir(name, mode);
    }
}
