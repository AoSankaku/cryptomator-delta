package org.deltavault.android;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.FrameLayout;

import org.deltavault.core.VaultStream;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Embedded decoding only; the player receives a private FD, never a URL or cleartext path. */
final class VideoPreview implements Playback {
    final LinearLayout view;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Activity activity;
    private final Runnable failure;
    private VideoSource source;
    private LibVLC vlc;
    private MediaPlayer player;
    private VLCVideoLayout surface;
    private final Button toggle;
    private final TextView time;
    private final SeekBar seek;
    private boolean closed, dragging;
    private boolean playRequested = true;

    static String demux(String filename) {
        String name = filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".mp4") || name.endsWith(".m4v") || name.endsWith(".mov")) return "mp4";
        if (name.endsWith(".mkv") || name.endsWith(".webm")) return "mkv";
        if (name.endsWith(".avi")) return "avi";
        if (name.endsWith(".ts") || name.endsWith(".m2ts")) return "ts";
        if (name.endsWith(".ogv")) return "ogg";
        return null;
    }

    VideoPreview(Activity activity, SecureViews views, VaultStream stream, String filename, Runnable failure) throws IOException {
        this.activity = activity; this.failure = failure;
        view = views.column(); view.setSaveEnabled(false);
        surface = new VLCVideoLayout(activity); surface.setBackgroundColor(Color.BLACK); surface.setSaveEnabled(false);
        FrameLayout frame = new FrameLayout(activity); frame.setSaveEnabled(false);
        frame.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        view.addView(frame, new LinearLayout.LayoutParams(-1, views.dp(240)));
        time = views.text("0:00 / 0:00", 14, SecureViews.MUTED); view.addView(time);
        seek = new SeekBar(activity); seek.setMax(1000); seek.setSaveEnabled(false); seek.setContentDescription("動画の再生位置"); view.addView(seek);
        toggle = views.button("一時停止", () -> {
            if (closed) return;
            if (playRequested) pause();
            else { playRequested = true; player.play(); activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); }
        }); view.addView(toggle);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { }
            @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                if (!closed && player.isSeekable() && player.getLength() > 0) player.setTime((long) (player.getLength() * (bar.getProgress() / 1000.0)));
            }
        });
        try {
            source = new VideoSource(activity, stream, () -> main.post(this::failed));
            // VLC creates a text renderer even with SPU disabled; avoid font database scans and caches.
            vlc = new LibVLC(new VlcNoStorageContext(activity), new ArrayList<>(List.of("--verbose=-1", "--no-auto-preparse",
                    "--no-metadata-network-access", "--no-sub-autodetect-file", "--no-spu", "--no-osd", "--text-renderer=none",
                    "--no-video-title-show", "--no-snapshot-preview", "--no-media-library", "--ignore-config",
                    "--file-caching=300")));
            player = new MediaPlayer(vlc);
            player.attachViews(surface, null, false, false);
            secureSurfaces(surface);
            player.setEventListener(event -> {
                if (closed) return;
                if (event.type == MediaPlayer.Event.EncounteredError) failed();
                else {
                    // Opening is asynchronous; retain a pause request made before decoding begins.
                    if (event.type == MediaPlayer.Event.Playing && !playRequested && player.isPlaying()) player.pause();
                    if (event.type == MediaPlayer.Event.EndReached) playRequested = false;
                    if (event.type == MediaPlayer.Event.EndReached || event.type == MediaPlayer.Event.Paused
                            || event.type == MediaPlayer.Event.Stopped) activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    update();
                }
            });
            Media media = new Media(vlc, source.fd());
            try {
                // Force an actual video container; disguised playlists cannot delegate URL playback.
                media.addOption(":demux=" + demux(filename));
                media.addOption(":no-sub-autodetect-file");
                media.addOption(":no-metadata-network-access");
                media.addOption(":codec=avcodec");
                media.addOption(":avcodec-hw=none");
                player.setMedia(media);
            } finally { media.release(); }
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            player.play();
        } catch (IOException | RuntimeException | LinkageError e) {
            try { close(); } catch (RuntimeException | LinkageError ignored) { }
            stream.close();
            throw new IOException("動画プレイヤーを開始できません。", e);
        }
    }
    private static void secureSurfaces(View view) {
        if (view instanceof SurfaceView) ((SurfaceView) view).setSecure(true);
        view.setSaveEnabled(false);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) secureSurfaces(group.getChildAt(i));
        }
    }
    private void update() {
        if (closed) return;
        long duration = Math.max(0, player.getLength()), position = Math.max(0, player.getTime());
        toggle.setText(playRequested ? "一時停止" : "再生");
        seek.setEnabled(duration > 0 && player.isSeekable());
        if (!dragging) seek.setProgress(duration > 0 ? (int) Math.min(1000, 1000.0 * position / duration) : 0);
        time.setText(String.format(Locale.ROOT, "%s / %s", clock(position), clock(duration)));
    }
    private static String clock(long millis) { long seconds = millis / 1000; return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60); }
    private void failed() {
        if (!closed) {
            try { close(); } catch (RuntimeException | LinkageError ignored) { }
            failure.run();
        }
    }
    @Override public void pause() {
        if (!closed && player != null) {
            playRequested = false;
            if (player.isPlaying()) player.pause();
            toggle.setText("再生");
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        try { if (source != null) source.close(); }
        finally {
            source = null;
            try {
                MediaPlayer owned = player; player = null;
                if (owned != null) {
                    try { owned.setEventListener(null); owned.stop(); }
                    finally { try { owned.detachViews(); } finally { owned.release(); } }
                }
            } finally {
                try { if (vlc != null) vlc.release(); }
                finally {
                    vlc = null; surface.setVisibility(View.INVISIBLE);
                    view.removeAllViews(); time.setText("");
                    activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            }
        }
    }
}
