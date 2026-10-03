package org.deltavault.android;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.widget.Button;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 28)
public class DocumentPreviewRaceTest {
    @Test public void initialLoadTimeoutSurvivesBackgroundReturnAndClosesResourcesOnce() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            byte[] callbackBuffer = new byte[32]; Arrays.fill(callbackBuffer, (byte) 9);
            FakeRenderer renderer = new FakeRenderer(); AtomicInteger failures = new AtomicInteger();
            DocumentPreview preview = preview(controller.get(), "png", renderer, failures, callbackBuffer);
            invoke(preview, "startTimeout");
            preview.setForeground(false);
            assertTrue((boolean) get(preview, "loading"));
            preview.setForeground(true); // Initial load has no page metadata yet.
            assertEquals(0, (int) get(preview, "totalPages"));

            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20_001));
            assertTrue((boolean) get(preview, "closed"));
            assertEquals(1, failures.get());
            assertEquals(List.of(0L), renderer.closedTokens);
            assertArrayEquals(new byte[callbackBuffer.length], callbackBuffer);
            invoke(preview, "onTimeout");
            assertEquals(1, failures.get());
            assertEquals(1, renderer.closedTokens.size());
        }
    }

    @Test public void lateBackgroundImageIsWipedAndRenderedAgainOnReturn() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            FakeRenderer renderer = new FakeRenderer(); AtomicInteger failures = new AtomicInteger();
            byte[] callbackBuffer = new byte[16]; Arrays.fill(callbackBuffer, (byte) 4);
            DocumentPreview preview = preview(controller.get(), "png", renderer, failures, callbackBuffer);
            preview.setForeground(false);
            Bitmap delivered = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
            delivered.eraseColor(Color.GREEN);

            invoke(preview, "accept", 0L, 1, 0, delivered);
            assertTrue(delivered.isRecycled());
            assertNull(preview.image.currentImage());
            assertEquals(1, (int) get(preview, "totalPages"));
            assertNull(get(preview, "source")); // Image input plaintext was wiped after decode.
            assertArrayEquals(new byte[callbackBuffer.length], callbackBuffer);
            assertTrue(renderer.pages.isEmpty());

            preview.setForeground(true);
            assertEquals(List.of(0), renderer.pages);
            assertTrue((boolean) get(preview, "loading"));
            preview.close();
            assertEquals(List.of(0L), renderer.closedTokens);
            preview.close(); // A second close cannot close a stale token.
            assertEquals(1, renderer.closedTokens.size());
        }
    }

    @Test public void backgroundCallbackQueuedUntilAfterReturnRequestsFreshCurrentPage() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            FakeRenderer renderer = new FakeRenderer(); AtomicInteger failures = new AtomicInteger();
            DocumentPreview preview = preview(controller.get(), "pdf", renderer, failures, null);
            preview.setForeground(false);
            preview.setForeground(true); // No request yet because the initial callback is still in flight.
            assertTrue(renderer.pages.isEmpty());

            // This is the main-thread task posted by onLoaded after its Binder thread discarded pixels.
            invoke(preview, "acceptInBackground", 0L, 2, 1);
            assertEquals(List.of(1), renderer.pages);
            assertEquals("2 / 2", preview.pageCount.getText().toString());
            assertTrue((boolean) get(preview, "loading"));
            preview.close();
        }
    }

    @Test public void binderBitmapIsCopiedBeforeCallbackReturnsAndOwnedUntilClose() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            FakeRenderer renderer = new FakeRenderer(); AtomicInteger failures = new AtomicInteger();
            DocumentPreview preview = preview(controller.get(), "png", renderer, failures, null);
            Bitmap mutable = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888);
            mutable.eraseColor(Color.rgb(20, 170, 40));
            Bitmap parcelBitmap = mutable.copy(Bitmap.Config.ARGB_8888, false);
            mutable.recycle();

            // receiveBitmap synchronously creates an independent mutable image before the
            // service can wipe its parcel/ashmem transfer when the callback returns.
            preview.receiveBitmap(0L, 1, 0, parcelBitmap);
            assertTrue(parcelBitmap.isRecycled());
            shadowOf(Looper.getMainLooper()).idle();

            Bitmap owned = preview.image.currentImage();
            assertNotNull(owned);
            assertNotSame(parcelBitmap, owned);
            assertTrue(owned.isMutable());
            assertEquals(Bitmap.Config.ARGB_8888, owned.getConfig());
            assertEquals(Color.rgb(20, 170, 40), owned.getPixel(0, 0));
            assertEquals(0, failures.get());
            preview.close();
            assertTrue(owned.isRecycled());
        }
    }

    @Test public void unsupportedParcelBitmapIsRecycledAndFailsOnce() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            FakeRenderer renderer = new FakeRenderer(); AtomicInteger failures = new AtomicInteger();
            DocumentPreview preview = preview(controller.get(), "png", renderer, failures, null);
            Bitmap invalid = Bitmap.createBitmap(4, 4, Bitmap.Config.RGB_565);
            invalid.eraseColor(Color.RED);

            preview.receiveBitmap(0L, 1, 0, invalid);
            assertTrue(invalid.isRecycled());
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue((boolean) get(preview, "closed"));
            assertNull(preview.image.currentImage());
            assertEquals(1, failures.get());
        }
    }

    @Test public void staleParcelBitmapIsDiscardedWithoutAffectingCurrentPreview() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            FakeRenderer renderer = new FakeRenderer(); AtomicInteger failures = new AtomicInteger();
            DocumentPreview preview = preview(controller.get(), "png", renderer, failures, null);
            Bitmap stale = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
            stale.eraseColor(Color.BLUE);

            preview.receiveBitmap(1L, 1, 0, stale); // This allocated test viewer's token is 0.
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(stale.isRecycled());
            assertNull(preview.image.currentImage());
            assertFalse((boolean) get(preview, "closed"));
            assertEquals(0, failures.get());
            preview.close();
        }
    }

    @Test public void parcelBitmapDeliveredInBackgroundIsDiscardedThenRerendered() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            FakeRenderer renderer = new FakeRenderer(); AtomicInteger failures = new AtomicInteger();
            DocumentPreview preview = preview(controller.get(), "png", renderer, failures, null);
            preview.setForeground(false);
            Bitmap hidden = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
            hidden.eraseColor(Color.YELLOW);

            preview.receiveBitmap(0L, 1, 0, hidden);
            assertTrue(hidden.isRecycled());
            shadowOf(Looper.getMainLooper()).idle();
            assertNull(preview.image.currentImage());
            assertEquals(1, (int) get(preview, "totalPages"));
            assertTrue(renderer.pages.isEmpty());
            preview.setForeground(true);
            assertEquals(List.of(0), renderer.pages);
            preview.close();
        }
    }

    private static DocumentPreview preview(Activity activity, String kind, FakeRenderer renderer,
                                           AtomicInteger failures, byte[] callbackBuffer) throws Exception {
        DocumentPreview value = (DocumentPreview) allocate(DocumentPreview.class);
        put(value, "activity", activity);
        put(value, "main", new Handler(Looper.getMainLooper()));
        put(value, "kind", kind);
        put(value, "failure", (Runnable) failures::incrementAndGet);
        put(value, "image", new ZoomImageView(activity));
        put(value, "previous", new Button(activity));
        put(value, "next", new Button(activity));
        put(value, "pageCount", new TextView(activity));
        put(value, "status", new TextView(activity));
        put(value, "renderer", renderer);
        put(value, "source", callbackBuffer == null ? null : fakeSource(callbackBuffer));
        put(value, "closed", false);
        put(value, "foreground", true);
        put(value, "loading", true);
        put(value, "failureSent", false);
        put(value, "currentPage", 0);
        put(value, "totalPages", 0);
        put(value, "binding", false);
        put(value, "timeout", (Runnable) () -> {
            try { invoke(value, "onTimeout"); }
            catch (Exception e) { throw new AssertionError(e); }
        });
        return value;
    }

    private static PreviewSource fakeSource(byte[] buffer) throws Exception {
        PreviewSource source = (PreviewSource) allocate(PreviewSource.class);
        Class<?> callbackType = Class.forName("org.deltavault.android.PreviewSource$ReadCallback");
        Constructor<?> callbackConstructor = callbackType.getDeclaredConstructor(
                org.deltavault.core.VaultStream.class, org.deltavault.core.VaultSession.ReadFile.class, long.class);
        callbackConstructor.setAccessible(true);
        Object callback = callbackConstructor.newInstance(null, null, (long) buffer.length);
        put(callback, "lastBuffer", buffer);
        put(source, "thread", new HandlerThread("test-preview-read"));
        put(source, "callback", callback);
        return source;
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeType.getDeclaredField("theUnsafe"); unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        return unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe, type);
    }

    private static void put(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }

    private static void invoke(Object target, String name, Object... arguments) throws Exception {
        Method found = null;
        for (Method method : target.getClass().getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == arguments.length) { found = method; break; }
        }
        if (found == null) throw new NoSuchMethodException(name);
        found.setAccessible(true);
        try { found.invoke(target, arguments); }
        catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw e;
        }
    }

    private static final class FakeRenderer extends IPreviewRenderer.Stub {
        final List<Integer> pages = new ArrayList<>();
        final List<Long> closedTokens = new ArrayList<>();
        @Override public void open(long token, ParcelFileDescriptor source, String kind, IPreviewCallback callback) { }
        @Override public void renderPage(long token, int pageIndex) { pages.add(pageIndex); }
        @Override public void close(long token) { closedTokens.add(token); }
    }
}
