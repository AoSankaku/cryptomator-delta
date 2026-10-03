package org.deltavault.android;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Persists only the user's background auto-lock timeout in app-private, non-backed-up storage. */
final class AutoLockSettings {
    static final int DEFAULT_SECONDS = 30;
    private static final int MAX_FILE_BYTES = 1024;
    private static final int[] ALLOWED_SECONDS = {0, 15, 30, 60, 120, 300};
    private static final String KEY_TIMEOUT_SECONDS = "timeout_seconds";

    private final AtomicFile file;
    private int seconds;

    AutoLockSettings(Context context) {
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "auto-lock.json"));
        seconds = readSeconds();
    }

    static int[] choices() {
        return ALLOWED_SECONDS.clone();
    }

    static String label(int seconds) {
        if (seconds == 0) {
            return "すぐに";
        }
        if (!isAllowed(seconds)) {
            throw new IllegalArgumentException("Unsupported auto-lock timeout");
        }
        return seconds + "秒";
    }

    int seconds() {
        return seconds;
    }

    void setSeconds(int seconds) throws IOException {
        if (!isAllowed(seconds)) {
            throw new IllegalArgumentException("Unsupported auto-lock timeout");
        }

        byte[] contents;
        try {
            contents = new JSONObject().put(KEY_TIMEOUT_SECONDS, seconds).toString()
                    .getBytes(StandardCharsets.UTF_8);
        } catch (JSONException impossible) {
            throw new IOException("Could not encode auto-lock setting", impossible);
        }

        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(contents);
            file.finishWrite(output);
            output = null;
            this.seconds = seconds;
        } catch (IOException failure) {
            if (output != null) {
                file.failWrite(output);
            }
            throw failure;
        }
    }

    private int readSeconds() {
        try (InputStream input = file.openRead()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > MAX_FILE_BYTES) {
                    return DEFAULT_SECONDS;
                }
                bytes.write(buffer, 0, count);
            }
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            JSONObject object = new JSONObject(json);
            if (object.length() != 1 || !object.has(KEY_TIMEOUT_SECONDS)) {
                return DEFAULT_SECONDS;
            }
            Object value = object.get(KEY_TIMEOUT_SECONDS);
            if (!(value instanceof Integer)) {
                return DEFAULT_SECONDS;
            }
            int parsed = (Integer) value;
            return isAllowed(parsed) ? parsed : DEFAULT_SECONDS;
        } catch (IOException | JSONException | RuntimeException ignored) {
            return DEFAULT_SECONDS;
        }
    }

    private static boolean isAllowed(int seconds) {
        for (int allowed : ALLOWED_SECONDS) {
            if (seconds == allowed) {
                return true;
            }
        }
        return false;
    }
}
