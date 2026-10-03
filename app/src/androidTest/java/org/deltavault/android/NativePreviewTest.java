package org.deltavault.android;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Process;
import android.os.ProxyFileDescriptorCallback;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.test.InstrumentationTestCase;
import android.view.WindowManager;

import org.cryptomator.cryptolib.api.Cryptor;
import org.cryptomator.cryptolib.api.CryptorProvider;
import org.cryptomator.cryptolib.api.Masterkey;
import org.cryptomator.cryptolib.common.EncryptingReadableByteChannel;
import org.deltavault.core.RemoteStore;
import org.deltavault.core.VaultSession;
import org.deltavault.core.VaultStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real platform PDF / libavif decoding through an authenticated, private RAM descriptor. */
public final class NativePreviewTest extends InstrumentationTestCase {
    private static final int FIXTURE_GREEN = Color.rgb(20, 170, 40);
    private static final int FIXTURE_RED = Color.rgb(230, 26, 26);
    private static final int FIXTURE_BLUE = Color.rgb(26, 26, 230);
    private MainActivity activity;
    private Fixture fixture;

    public void testImagesAndPdfAreIsolatedAndLockRevokesSources() throws Exception {
        start();
        Map<String, String> baseline = snapshot(activity);
        try {
            for (String name : new String[] {"image.png", "image.jpg", "image.webp", "image.avif"}) {
                Handle handle = open(name);
                awaitImage(handle, FIXTURE_GREEN);
                verifyIsolation(handle);
                assertAllZero("parent encoded image was retained: " + name, handle.encoded);
                assertRevoked(handle);
                Bitmap first = currentImage(handle);
                getInstrumentation().runOnMainSync(() -> handle.preview.setForeground(false));
                assertTrue("background image was not recycled", first.isRecycled());
                assertNull(currentImage(handle));
                getInstrumentation().runOnMainSync(() -> handle.preview.setForeground(true));
                awaitImage(handle, FIXTURE_GREEN);
                Bitmap rerendered = currentImage(handle);
                close(handle);
                assertTrue("dismissed image was not recycled", rerendered.isRecycled());
                assertEquals("preview persisted image data", baseline, snapshot(activity));
                assertEquals("unexpected image failure", 0, handle.failures.get());
            }
            Handle pdf = open("pages.pdf");
            awaitImage(pdf, FIXTURE_RED);
            verifyIsolation(pdf);
            AtomicReference<String> pageText = new AtomicReference<>();
            getInstrumentation().runOnMainSync(() -> {
                pageText.set(pdf.preview.pageCount.getText().toString());
                assertFalse(pdf.preview.previous.isEnabled());
                assertTrue(pdf.preview.next.isEnabled());
                pdf.preview.next.performClick();
            });
            assertEquals("1 / 2", pageText.get());
            awaitImage(pdf, FIXTURE_BLUE);
            getInstrumentation().runOnMainSync(() -> {
                assertEquals("2 / 2", pdf.preview.pageCount.getText().toString());
                assertFalse(pdf.preview.next.isEnabled());
                pdf.preview.previous.performClick();
            });
            awaitImage(pdf, FIXTURE_RED);
            Bitmap finalPage = currentImage(pdf);
            lock();
            assertRevoked(pdf);
            assertTrue("lock did not recycle page", finalPage.isRecycled());
            await("session key was not destroyed", () -> fixture.key.isDestroyed());
            assertNull(field(activity, "session"));
            assertEquals(0, pdf.failures.get());
            assertEquals("preview persisted PDF data", baseline, snapshot(activity));
        } finally { finish(); }
    }

    public void testMalformedDocumentsFailAndWipeTheirSources() throws Exception {
        start();
        Map<String, String> baseline = snapshot(activity);
        try {
            for (String name : new String[] {"broken.avif", "broken.pdf"}) {
                Handle handle = open(name);
                await("malformed document was not rejected: " + name, () -> handle.failures.get() != 0);
                assertEquals("failure delivered more than once", 1, handle.failures.get());
                assertNull(currentImage(handle));
                assertRevoked(handle);
                if (handle.encoded != null) assertAllZero("malformed image bytes were retained", handle.encoded);
                close(handle);
                assertFalse("document parser revoked unrelated vault key", fixture.key.isDestroyed());
            }
            assertEquals("malformed preview wrote app data", baseline, snapshot(activity));
        } finally { finish(); }
    }

    private void start() throws Exception {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setClassName(getInstrumentation().getTargetContext(), MainActivity.class.getName());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = (MainActivity) getInstrumentation().startActivitySync(intent);
        fixture = new Fixture(getInstrumentation().getContext());
        setField(activity, "session", fixture.session);
        setField(activity, "remote", fixture.store);
    }

    private Handle open(String name) throws Exception {
        VaultSession.Node node = fixture.nodes.get(name);
        boolean pdf = name.endsWith(".pdf");
        VaultStream stream = pdf ? fixture.session.openStream(node) : null;
        VaultSession.ReadFile read = pdf ? null : fixture.session.read(node);
        AtomicInteger failures = new AtomicInteger();
        AtomicReference<Handle> result = new AtomicReference<>();
        getInstrumentation().runOnMainSync(() -> {
            try {
                SecureViews views = (SecureViews) field(activity, "views");
                DocumentPreview preview = pdf
                        ? new DocumentPreview(activity, views, stream, name, failures::incrementAndGet)
                        : new DocumentPreview(activity, views, read, name, failures::incrementAndGet);
                ProxyFileDescriptorCallback callback = (ProxyFileDescriptorCallback) field(preview.source, "callback");
                AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(name).setView(preview.view)
                        .setPositiveButton("閉じる", null).create();
                dialog.setOnDismissListener(ignored -> preview.close());
                dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                if (android.os.Build.VERSION.SDK_INT >= 31) dialog.getWindow().setHideOverlayWindows(true);
                dialog.show();
                dialog.getWindow().setLayout(-1, -1);
                setField(activity, "document", preview);
                setField(activity, "dialog", dialog);
                result.set(new Handle(preview, dialog, stream, callback, read == null ? null : read.bytes, failures));
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        return result.get();
    }

    private void verifyIsolation(Handle handle) {
        int uid = handle.preview.rendererUid;
        assertTrue("decoder shares the key-owning app UID", uid > 0 && uid != Process.myUid());
        assertTrue("decoder UID is not isolated", uid % 100_000 >= 90_000);
        assertEquals("decoder has INTERNET permission", PackageManager.PERMISSION_DENIED,
                activity.checkPermission(Manifest.permission.INTERNET, -1, uid));
        assertTrue((handle.dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
        getInstrumentation().runOnMainSync(() -> {
            assertTrue("preview has no visible image area", handle.preview.image.getWidth() > 0 && handle.preview.image.getHeight() > 0);
        });
    }

    private void awaitImage(Handle handle, int expected) {
        long deadline = SystemClock.uptimeMillis() + 25_000;
        AtomicReference<String> diagnostic = new AtomicReference<>("no callback");
        while (SystemClock.uptimeMillis() < deadline) {
            AtomicReference<Boolean> correct = new AtomicReference<>(false);
            getInstrumentation().runOnMainSync(() -> {
                Bitmap bitmap = handle.preview.image.currentImage();
                diagnostic.set("uid=" + handle.preview.rendererUid + ", status=" + handle.preview.status.getText()
                        + ", failures=" + handle.failures.get() + ", bitmap=" + bitmap);
                if (bitmap == null || bitmap.isRecycled()) return;
                int actual = bitmap.getPixel(bitmap.getWidth() / 2, bitmap.getHeight() / 2);
                diagnostic.set(diagnostic.get() + ", actual=" + Integer.toHexString(actual)
                        + ", expected=" + Integer.toHexString(expected));
                correct.set(Math.abs(Color.red(actual) - Color.red(expected)) < 30
                        && Math.abs(Color.green(actual) - Color.green(expected)) < 30
                        && Math.abs(Color.blue(actual) - Color.blue(expected)) < 30);
            });
            if (correct.get()) return;
            if (handle.failures.get() != 0) break;
            SystemClock.sleep(50);
        }
        fail("Native preview pixels: " + diagnostic.get());
    }

    private Bitmap currentImage(Handle handle) {
        AtomicReference<Bitmap> result = new AtomicReference<>();
        getInstrumentation().runOnMainSync(() -> result.set(handle.preview.image.currentImage()));
        return result.get();
    }

    private void assertRevoked(Handle handle) throws Exception {
        if (handle.stream != null) {
            try { handle.stream.size(); fail("closed PDF stream remained readable"); }
            catch (IOException expected) { }
        }
        try { handle.callback.onGetSize(); fail("closed preview descriptor retained its size"); }
        catch (ErrnoException expected) { assertEquals(OsConstants.EBADF, expected.errno); }
        byte[] output = new byte[16];
        Arrays.fill(output, (byte) 0x7d);
        try { handle.callback.onRead(0, output.length, output); fail("closed descriptor returned plaintext"); }
        catch (ErrnoException expected) { assertEquals(OsConstants.EBADF, expected.errno); }
        assertAllZero("failed read retained output", output);
    }

    private void close(Handle handle) {
        getInstrumentation().runOnMainSync(() -> {
            handle.dialog.dismiss();
            setField(activity, "document", null);
            setField(activity, "dialog", null);
        });
        getInstrumentation().waitForIdleSync();
    }

    private void lock() {
        getInstrumentation().runOnMainSync(() -> {
            try {
                Method method = MainActivity.class.getDeclaredMethod("lock", boolean.class);
                method.setAccessible(true);
                method.invoke(activity, false);
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    private void finish() {
        if (activity != null) {
            lock();
            getInstrumentation().runOnMainSync(activity::finish);
        }
        if (fixture != null) fixture.session.close();
    }

    private void await(String message, Condition condition) {
        long deadline = SystemClock.uptimeMillis() + 25_000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition.ok()) return;
            SystemClock.sleep(50);
        }
        assertTrue(message, condition.ok());
    }

    private interface Condition { boolean ok(); }

    private static void assertAllZero(String message, byte[] bytes) {
        for (byte value : bytes) if (value != 0) fail(message);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static Map<String, String> snapshot(Context context) {
        Map<String, String> result = new TreeMap<>();
        snapshotTree(context.getFilesDir(), "files", result);
        snapshotTree(context.getCacheDir(), "cache", result);
        snapshotTree(context.getNoBackupFilesDir(), "noBackup", result);
        return result;
    }

    private static void snapshotTree(File file, String path, Map<String, String> result) {
        if (!file.exists()) return;
        result.put(path, file.isDirectory() ? "dir" : Long.toString(file.length()));
        File[] children = file.listFiles();
        if (children != null) for (File child : children) snapshotTree(child, path + "/" + child.getName(), result);
    }

    private record Handle(DocumentPreview preview, AlertDialog dialog, VaultStream stream,
                          ProxyFileDescriptorCallback callback, byte[] encoded, AtomicInteger failures) { }

    private static final class Fixture {
        final Masterkey key = Masterkey.generate(new SecureRandom());
        final Cryptor cryptor = CryptorProvider.forScheme(CryptorProvider.Scheme.SIV_CTRMAC).provide(key, new SecureRandom());
        final FixtureStore store = new FixtureStore();
        final VaultSession session;
        final Map<String, VaultSession.Node> nodes = new LinkedHashMap<>();

        Fixture(Context context) throws Exception {
            for (String name : new String[] {"image.png", "image.jpg", "image.webp", "image.avif", "pages.pdf", "broken.avif", "broken.pdf"}) {
                byte[] clear;
                if (name.startsWith("broken")) clear = new byte[] {1, 2, 3, 4, 5, 6, 7};
                else try (InputStream input = context.getAssets().open(name)) { clear = readSmallAsset(input); }
                try { store.files.put(name, encrypt(clear)); }
                finally { Arrays.fill(clear, (byte) 0); }
            }
            Constructor<VaultSession> constructor = VaultSession.class.getDeclaredConstructor(RemoteStore.class, Masterkey.class, Cryptor.class, int.class);
            constructor.setAccessible(true);
            session = constructor.newInstance(store, key, cryptor, 220);
            Constructor<VaultSession.Node> node = VaultSession.Node.class.getDeclaredConstructor(VaultSession.class, String.class,
                    String.class, boolean.class, String.class, String.class, String.class, String.class);
            node.setAccessible(true);
            for (String name : store.files.keySet()) nodes.put(name, node.newInstance(session, name, name, false, null, name, "", name));
        }

        private static byte[] readSmallAsset(InputStream input) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            try {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 1024 * 1024) throw new IOException("Oversized fixture");
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            } finally { Arrays.fill(buffer, (byte) 0); }
        }

        private byte[] encrypt(byte[] clear) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] scratch = new byte[4096];
            try (ReadableByteChannel channel = new EncryptingReadableByteChannel(Channels.newChannel(new ByteArrayInputStream(clear)), cryptor)) {
                ByteBuffer buffer = ByteBuffer.wrap(scratch);
                while (true) {
                    buffer.clear();
                    int count = channel.read(buffer);
                    if (count < 0) break;
                    if (count > 0) output.write(scratch, 0, count);
                }
                return output.toByteArray();
            } finally { Arrays.fill(scratch, (byte) 0); }
        }
    }

    private static final class FixtureStore implements RemoteStore {
        final Map<String, byte[]> files = new LinkedHashMap<>();
        volatile boolean closed;
        @Override public Blob get(String path, int maxBytes) throws IOException {
            byte[] bytes = files.get(path);
            if (closed || bytes == null || bytes.length > maxBytes) throw new IOException("Fixture unavailable");
            return new Blob(Arrays.copyOf(bytes, bytes.length), "\"fixture\"");
        }
        @Override public Reader openReader(String path) throws IOException {
            byte[] bytes = files.get(path);
            if (closed || bytes == null) throw new IOException("Fixture unavailable");
            return new Reader() {
                boolean released;
                @Override public long size() { return bytes.length; }
                @Override public void readFully(long position, byte[] destination, int offset, int length) throws IOException {
                    if (released || closed) throw new IOException("Closed");
                    RemoteStore.checkRange(bytes.length, position, destination, offset, length);
                    System.arraycopy(bytes, (int) position, destination, offset, length);
                }
                @Override public void close() { released = true; }
            };
        }
        @Override public List<Entry> list(String path) throws IOException { throw new IOException("Unused"); }
        @Override public void put(String path, byte[] bytes, String etag, boolean createOnly) throws IOException { throw new IOException("Unused"); }
        @Override public void mkdir(String path) throws IOException { throw new IOException("Unused"); }
        @Override public void move(String source, String target) throws IOException { throw new IOException("Unused"); }
        @Override public void close() { closed = true; for (byte[] bytes : files.values()) Arrays.fill(bytes, (byte) 0); }
    }
}
