package org.deltavault.android;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.deltavault.core.VaultSession;
import org.deltavault.core.VaultStream;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** Key-owning UI shell. All untrusted document parsing runs in PreviewRendererService. */
final class DocumentPreview implements DocumentViewer {
    private static final long RENDER_TIMEOUT_MS = 20_000L;
    private static final AtomicLong NEXT_TOKEN = new AtomicLong(1);
    final LinearLayout view;
    final ZoomImageView image;
    final Button previous;
    final Button next;
    final TextView pageCount;
    final TextView status;
    final long token = NEXT_TOKEN.getAndIncrement();
    volatile int rendererUid = -1;
    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String kind;
    private final Runnable failure;
    private final IPreviewCallback callback = new IPreviewCallback.Stub() {
        @Override public void onLoaded(long callbackToken, int pages, int page, Bitmap bitmap) {
            receiveBitmap(callbackToken, pages, page, bitmap);
        }

        @Override public void onFailure(long callbackToken) { main.post(() -> failIfCurrent(callbackToken)); }
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (closed) return;
            renderer = IPreviewRenderer.Stub.asInterface(binder);
            if (renderer == null) { fail(); return; }
            try {
                renderer.open(token, source.descriptor(), kind, callback);
            } catch (RemoteException | RuntimeException e) { fail(); }
        }

        @Override public void onServiceDisconnected(ComponentName name) { renderer = null; if (!closed) fail(); }
        @Override public void onBindingDied(ComponentName name) { renderer = null; if (!closed) fail(); }
        @Override public void onNullBinding(ComponentName name) { renderer = null; if (!closed) fail(); }
    };

    PreviewSource source;
    private IPreviewRenderer renderer;
    private boolean binding;
    private volatile boolean closed;
    private volatile boolean foreground = true;
    private boolean loading = true;
    private boolean failureSent;
    private int currentPage;
    private int totalPages;
    private final Runnable timeout = this::onTimeout;

    DocumentPreview(Activity activity, SecureViews views, VaultStream stream, String filename, Runnable failure) throws IOException {
        this(activity, views, new PreviewSource(activity, stream), "pdf", filename, failure);
    }

    DocumentPreview(Activity activity, SecureViews views, VaultSession.ReadFile imageFile, String filename, Runnable failure) throws IOException {
        this(activity, views, new PreviewSource(activity, imageFile), PreviewImageDecoder.expectedKind(filename), filename, failure);
    }

    private DocumentPreview(Activity activity, SecureViews views, PreviewSource source, String kind, String filename, Runnable failure) throws IOException {
        if (kind == null || !("pdf".equals(kind) || PreviewImageDecoder.isKind(kind))) {
            source.close();
            throw new IOException("Unsupported preview format");
        }
        this.activity = activity;
        this.source = source;
        this.kind = kind;
        this.failure = failure == null ? () -> { } : failure;
        view = views.column();
        view.setSaveEnabled(false);
        view.setFocusable(false);
        TextView heading = views.text(filename, 16, SecureViews.INK);
        status = views.text("読み込み中…", 14, SecureViews.MUTED);
        image = new ZoomImageView(activity);
        previous = views.button("前のページ", () -> changePage(-1));
        next = views.button("次のページ", () -> changePage(1));
        pageCount = views.text("", 14, SecureViews.MUTED);
        LinearLayout pager = new LinearLayout(activity);
        pager.setOrientation(LinearLayout.HORIZONTAL);
        pager.addView(previous, new LinearLayout.LayoutParams(0, views.dp(48), 1f));
        pager.addView(pageCount, new LinearLayout.LayoutParams(0, views.dp(48), 1f));
        pager.addView(next, new LinearLayout.LayoutParams(0, views.dp(48), 1f));
        view.addView(heading);
        view.addView(status);
        view.addView(image, new LinearLayout.LayoutParams(-1, 0, 1f));
        view.addView(pager);
        previous.setEnabled(false);
        next.setEnabled(false);
        pageCount.setVisibility("pdf".equals(kind) ? android.view.View.VISIBLE : android.view.View.GONE);
        previous.setVisibility("pdf".equals(kind) ? android.view.View.VISIBLE : android.view.View.GONE);
        next.setVisibility("pdf".equals(kind) ? android.view.View.VISIBLE : android.view.View.GONE);
        bind();
    }

    static boolean isImage(String filename) { return PreviewImageDecoder.expectedKind(filename) != null; }
    static boolean isPdf(String filename) { return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".pdf"); }

    private void bind() throws IOException {
        Intent intent = new Intent(activity, PreviewRendererService.class);
        try {
            boolean result;
            if (Build.VERSION.SDK_INT >= 29) {
                String instance = "p" + Long.toUnsignedString(token, 36);
                result = activity.bindIsolatedService(intent, Context.BIND_AUTO_CREATE, instance, activity.getMainExecutor(), connection);
            } else {
                result = activity.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            }
            if (!result) throw new IOException("Unable to connect to isolated document renderer");
            binding = true;
            startTimeout();
        } catch (IOException | RuntimeException e) {
            close();
            if (e instanceof IOException io) throw io;
            throw new IOException("Unable to connect to isolated document renderer", e);
        }
    }

    private void accept(long callbackToken, int pages, int page, Bitmap bitmap) {
        if (closed || callbackToken != token) { PreviewImageDecoder.wipe(bitmap); return; }
        if (!foreground) {
            PreviewImageDecoder.wipe(bitmap);
            acceptInBackground(callbackToken, pages, page);
            return;
        }
        if (pages <= 0 || pages > 10_000 || page < 0 || page >= pages) {
            PreviewImageDecoder.wipe(bitmap); fail(); return;
        }
        if (bitmap.isRecycled() || !bitmap.isMutable() || bitmap.getConfig() != Bitmap.Config.ARGB_8888
                || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0
                || bitmap.getWidth() > PreviewImageDecoder.OUTPUT_LIMIT || bitmap.getHeight() > PreviewImageDecoder.OUTPUT_LIMIT) {
            PreviewImageDecoder.wipe(bitmap); fail(); return;
        }
        if (!"pdf".equals(kind) && (pages != 1 || page != 0)) {
            PreviewImageDecoder.wipe(bitmap); fail(); return;
        }
        cancelTimeout();
        loading = false;
        totalPages = pages;
        currentPage = page;
        status.setText("");
        image.setImage(bitmap);
        updatePager();
        if (!"pdf".equals(kind)) closeSource();
    }

    /**
     * Copies the Binder bitmap before returning to the isolated process. Bitmap parcels may
     * refer to shared memory, which the renderer wipes as soon as this synchronous callback
     * returns. The UI therefore only receives a separately-owned bitmap.
     */
    void receiveBitmap(long callbackToken, int pages, int page, Bitmap bitmap) {
        rendererUid = Binder.getCallingUid();
        if (bitmap == null) {
            main.post(() -> failIfCurrent(callbackToken));
            return;
        }
        if (closed || callbackToken != token) {
            PreviewImageDecoder.wipe(bitmap);
            return;
        }
        if (!foreground) {
            PreviewImageDecoder.wipe(bitmap);
            main.post(() -> acceptInBackground(callbackToken, pages, page));
            return;
        }

        Bitmap owned = null;
        try {
            if (!isSupportedParcelBitmap(bitmap)) throw new IllegalArgumentException("Unsupported preview bitmap");
            owned = bitmap.copy(Bitmap.Config.ARGB_8888, true);
            if (owned == null || !owned.isMutable() || owned.getConfig() != Bitmap.Config.ARGB_8888
                    || owned.getWidth() <= 0 || owned.getHeight() <= 0
                    || owned.getWidth() > PreviewImageDecoder.OUTPUT_LIMIT
                    || owned.getHeight() > PreviewImageDecoder.OUTPUT_LIMIT
                    || (long) owned.getWidth() * owned.getHeight()
                    > (long) PreviewImageDecoder.OUTPUT_LIMIT * PreviewImageDecoder.OUTPUT_LIMIT) {
                PreviewImageDecoder.wipe(owned);
                owned = null;
                throw new IllegalArgumentException("Unsupported preview bitmap");
            }
        } catch (RuntimeException | LinkageError | OutOfMemoryError e) {
            PreviewImageDecoder.wipe(owned);
            owned = null;
        } finally {
            // This may be an immutable or ashmem-backed parcel bitmap. Recycle it after the
            // independent copy completes; the sender remains blocked until this method returns.
            PreviewImageDecoder.wipe(bitmap);
        }

        if (owned == null) {
            main.post(() -> failIfCurrent(callbackToken));
            return;
        }
        Bitmap result = owned;
        if (!main.post(() -> accept(callbackToken, pages, page, result))) PreviewImageDecoder.wipe(result);
    }

    private static boolean isSupportedParcelBitmap(Bitmap bitmap) {
        if (bitmap.isRecycled() || bitmap.getConfig() != Bitmap.Config.ARGB_8888) return false;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        return width > 0 && height > 0
                && width <= PreviewImageDecoder.OUTPUT_LIMIT && height <= PreviewImageDecoder.OUTPUT_LIMIT
                && (long) width * height <= (long) PreviewImageDecoder.OUTPUT_LIMIT * PreviewImageDecoder.OUTPUT_LIMIT;
    }

    private void acceptInBackground(long callbackToken, int pages, int page) {
        if (closed || callbackToken != token) return;
        if (pages <= 0 || pages > 10_000 || page < 0 || page >= pages
                || (!"pdf".equals(kind) && (pages != 1 || page != 0))) { fail(); return; }
        cancelTimeout();
        loading = false;
        totalPages = pages;
        currentPage = page;
        if (!"pdf".equals(kind)) closeSource();
        updatePager();
        // The callback's bitmap was discarded while hidden. If the Activity resumed before
        // this main-thread bookkeeping ran, request a fresh result now.
        if (foreground) {
            status.setText("読み込み中…");
            requestPage(currentPage);
        }
    }

    private void changePage(int delta) {
        if (closed || loading || !foreground || !"pdf".equals(kind)) return;
        int requested = currentPage + delta;
        if (requested < 0 || requested >= totalPages) return;
        requestPage(requested);
    }

    private void requestPage(int page) {
        IPreviewRenderer current = renderer;
        if (closed || loading || current == null || page < 0 || page >= totalPages) return;
        loading = true;
        image.clearImage();
        status.setText("読み込み中…");
        updatePager();
        try {
            current.renderPage(token, page);
            startTimeout();
        } catch (RemoteException | RuntimeException e) { fail(); }
    }

    private void updatePager() {
        if (!"pdf".equals(kind)) return;
        pageCount.setText(totalPages <= 0 ? "" : (currentPage + 1) + " / " + totalPages);
        previous.setEnabled(!loading && currentPage > 0);
        next.setEnabled(!loading && totalPages > 0 && currentPage + 1 < totalPages);
    }

    private void startTimeout() {
        main.removeCallbacks(timeout);
        main.postDelayed(timeout, RENDER_TIMEOUT_MS);
    }

    private void cancelTimeout() { main.removeCallbacks(timeout); }

    private void onTimeout() {
        if (!closed && loading) fail();
    }

    private void failIfCurrent(long callbackToken) { if (callbackToken == token) fail(); }

    private void closeSource() {
        PreviewSource old = source;
        source = null;
        if (old != null) try { old.close(); } catch (RuntimeException ignored) { }
    }

    private void fail() {
        if (closed || failureSent) return;
        failureSent = true;
        close();
        failure.run();
    }

    @Override public void setForeground(boolean foreground) {
        if (closed || this.foreground == foreground) return;
        this.foreground = foreground;
        if (!foreground) {
            // Keep an in-flight deadline active. Otherwise a stuck parser hidden in the
            // background could lose its timeout and remain bound after the Activity returns.
            image.clearImage();
            status.setText("一時停止中");
            updatePager();
        } else if (image.currentImage() == null && totalPages > 0) {
            status.setText("読み込み中…");
            requestPage(currentPage);
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        foreground = false;
        cancelTimeout();
        image.clearImage();
        closeSource();
        try {
            IPreviewRenderer oldRenderer = renderer;
            renderer = null;
            if (oldRenderer != null) try { oldRenderer.close(token); } catch (RemoteException | RuntimeException ignored) { }
        } finally {
            if (binding) {
                binding = false;
                try { activity.unbindService(connection); } catch (RuntimeException ignored) { }
            }
        }
    }
}
