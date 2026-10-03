package org.deltavault.android;

import android.app.Service;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.content.pm.ServiceInfo;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Isolated, keyless process for parsing PDFs and still images. */
public final class PreviewRendererService extends Service {
    private static final int MAX_PDF_PAGES = 10_000;
    private static final int MAX_PAGE_COORDINATE = 100_000;
    private static final int MAX_RENDER_DIMENSION = 2048;
    private final Map<Long, State> states = new HashMap<>();
    private HandlerThread workerThread;
    private Handler worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable idleStop = this::stopSelf;
    private volatile boolean destroyed;

    private final IPreviewRenderer.Stub binder = new IPreviewRenderer.Stub() {
        @Override public void open(long token, ParcelFileDescriptor source, String kind, IPreviewCallback callback) {
            if (source == null || callback == null || token <= 0 || !("pdf".equals(kind) || PreviewImageDecoder.isKind(kind))) {
                closeQuietly(source); fail(callback, token); return;
            }
            Handler target = worker;
            if (target == null || destroyed) { closeQuietly(source); fail(callback, token); return; }
            if (!target.post(() -> openOnWorker(token, source, kind, callback))) closeQuietly(source);
        }

        @Override public void renderPage(long token, int pageIndex) {
            Handler target = worker;
            if (target != null && !destroyed) target.post(() -> renderOnWorker(token, pageIndex));
        }

        @Override public void close(long token) {
            Handler target = worker;
            if (target != null) target.post(() -> closeState(token));
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        workerThread = new HandlerThread("isolated-preview-render", Process.THREAD_PRIORITY_BACKGROUND);
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
    }

    @Override public IBinder onBind(android.content.Intent intent) {
        main.removeCallbacks(idleStop);
        return binder;
    }

    @Override public boolean onUnbind(android.content.Intent intent) {
        // Give an immediately following viewer a bounded chance to bind the same API 26-28
        // isolated service process. Per-request tokens keep queued cleanup scoped to old work.
        main.postDelayed(idleStop, 100L);
        return false;
    }

    private void openOnWorker(long token, ParcelFileDescriptor input, String kind, IPreviewCallback callback) {
        closeState(token);
        if (destroyed) { closeQuietly(input); fail(callback, token); return; }
        State state = new State(token, kind, callback);
        states.put(token, state);
        try {
            if ("pdf".equals(kind)) {
                state.pdf = new PdfRenderer(input); // PdfRenderer owns the transferred descriptor.
                input = null;
                int pages = state.pdf.getPageCount();
                if (pages <= 0 || pages > MAX_PDF_PAGES) throw new IOException("Unsupported PDF page count");
                state.pageCount = pages;
                render(state, 0);
            } else {
                state.pageCount = 1;
                state.image = PreviewImageDecoder.decode(input, kind);
                send(state, 0);
            }
        } catch (IOException | RuntimeException | LinkageError | OutOfMemoryError e) {
            closeState(token);
            fail(callback, token);
        } finally {
            closeQuietly(input);
        }
    }

    private void renderOnWorker(long token, int pageIndex) {
        State state = states.get(token);
        if (state == null || state.closed) return;
        if (state.pdf == null) {
            if (pageIndex != 0 || state.image == null) failAndClose(state);
            else send(state, 0);
            return;
        }
        if (pageIndex < 0 || pageIndex >= state.pageCount) { failAndClose(state); return; }
        try { render(state, pageIndex); }
        catch (IOException | RuntimeException | LinkageError | OutOfMemoryError e) { failAndClose(state); }
    }

    private void render(State state, int pageIndex) throws IOException {
        if (state.closed || state.pdf == null || pageIndex < 0 || pageIndex >= state.pageCount) {
            throw new IOException("Invalid PDF page request");
        }
        PdfRenderer.Page page = state.pdf.openPage(pageIndex);
        Bitmap rendered = null;
        try {
            int pageWidth = page.getWidth(), pageHeight = page.getHeight();
            if (pageWidth <= 0 || pageHeight <= 0 || pageWidth > MAX_PAGE_COORDINATE || pageHeight > MAX_PAGE_COORDINATE) {
                throw new IOException("PDF page dimensions exceed supported bounds");
            }
            double scale = Math.min((double) MAX_RENDER_DIMENSION / pageWidth,
                    (double) MAX_RENDER_DIMENSION / pageHeight);
            int width = Math.max(1, (int) Math.floor(pageWidth * scale));
            int height = Math.max(1, (int) Math.floor(pageHeight * scale));
            if (width > MAX_RENDER_DIMENSION || height > MAX_RENDER_DIMENSION
                    || (long) width * height > (long) MAX_RENDER_DIMENSION * MAX_RENDER_DIMENSION) {
                throw new IOException("PDF render dimensions exceed supported bounds");
            }
            rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(rendered);
            canvas.drawColor(Color.WHITE);
            page.render(rendered, new Rect(0, 0, width, height), null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            if (state.closed) throw new IOException("Preview was closed while rendering");
            Bitmap old = state.image;
            state.image = rendered;
            rendered = null;
            PreviewImageDecoder.wipe(old);
            state.currentPage = pageIndex;
            send(state, pageIndex);
        } finally {
            PreviewImageDecoder.wipe(rendered);
            page.close();
        }
    }

    private void send(State state, int pageIndex) {
        if (state == null || state.closed || state.image == null || state.image.isRecycled()) return;
        Bitmap transfer = null;
        try {
            transfer = state.image.copy(Bitmap.Config.ARGB_8888, true);
            if (transfer == null) throw new IllegalStateException("Unable to copy preview image");
            state.callback.onLoaded(state.token, state.pageCount, pageIndex, transfer);
        } catch (RemoteException | RuntimeException | LinkageError | OutOfMemoryError e) {
            // Activity may have been destroyed while the isolated process was rendering.
        } finally {
            PreviewImageDecoder.wipe(transfer);
        }
    }

    private void failAndClose(State state) {
        if (state == null) return;
        fail(state.callback, state.token);
        closeState(state.token);
    }

    private void closeState(long token) {
        State state = states.remove(token);
        if (state == null || state.closed) return;
        state.closed = true;
        PreviewImageDecoder.wipe(state.image);
        state.image = null;
        PdfRenderer renderer = state.pdf;
        state.pdf = null;
        if (renderer != null) try { renderer.close(); } catch (RuntimeException ignored) { }
    }

    private static void fail(IPreviewCallback callback, long token) {
        if (callback != null) try { callback.onFailure(token); } catch (RemoteException | RuntimeException ignored) { }
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor != null) try { descriptor.close(); } catch (IOException ignored) { }
    }

    @Override public void onDestroy() {
        destroyed = true;
        Handler target = worker;
        if (target != null) {
            target.post(() -> {
                for (Long token : states.keySet().toArray(new Long[0])) closeState(token);
                if (workerThread != null) workerThread.quitSafely();
            });
        }
        super.onDestroy();
        // Give queued cleanup time to wipe bitmaps, while bounding a native parser hang.
        if (isDeclaredIsolated()) {
            main.postDelayed(() -> Process.killProcess(Process.myPid()), 500L);
        }
    }

    private boolean isDeclaredIsolated() {
        try {
            int flags = getPackageManager().getServiceInfo(new ComponentName(this, PreviewRendererService.class), 0).flags;
            return (flags & ServiceInfo.FLAG_ISOLATED_PROCESS) != 0;
        } catch (Exception e) { return false; }
    }

    private static final class State {
        final long token;
        final String kind;
        final IPreviewCallback callback;
        PdfRenderer pdf;
        Bitmap image;
        int pageCount;
        int currentPage;
        boolean closed;
        State(long token, String kind, IPreviewCallback callback) {
            this.token = token; this.kind = kind; this.callback = callback;
        }
    }
}
