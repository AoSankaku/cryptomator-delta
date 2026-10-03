package org.deltavault.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import org.cryptomator.cryptolib.api.Cryptor;
import org.cryptomator.cryptolib.api.CryptorProvider;
import org.cryptomator.cryptolib.api.Masterkey;
import org.deltavault.core.RemoteStore;
import org.deltavault.core.VaultSession;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowContentResolver;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 28)
public class AutoLockLifecycleTest {
    private Context context;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        Files.deleteIfExists(new File(context.getNoBackupFilesDir(), "auto-lock.json").toPath());
    }

    @Test public void earlyReturnRetainsSessionAndUnsavedEditorWithoutForegroundIdleLock() throws Exception {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity screen = activity.get(); Fixture fixture = unlocked(screen);
            invoke(screen, "edit", new Class<?>[]{VaultSession.Node.class, VaultSession.ReadFile.class}, null, null);
            AlertDialog editor = ShadowAlertDialog.getLatestAlertDialog();
            EditText body = inputs(editor.getWindow().getDecorView()).get(1); body.setText("unsaved text");
            activity.pause().stop(); advance(29);
            assertSame(fixture.vault, get(screen, "session")); assertFalse(fixture.store.closed);
            activity.start().resume(); advance(180);
            assertSame(fixture.vault, get(screen, "session")); assertEquals("unsaved text", body.getText().toString());
            activity.pause().stop(); advance(30); awaitCleanup(screen);
            assertNull(get(screen, "session")); assertTrue(fixture.store.closed); assertTrue(fixture.key.isDestroyed());
            assertEquals("", body.getText().toString());
        }
    }

    @Test public void elapsedDeadlineIsCheckedBeforeReturnEvenWhenHandlerDidNotRun() throws Exception {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity screen = activity.get(); Fixture fixture = unlocked(screen);
            AtomicLong elapsed = new AtomicLong(1000);
            set(screen, "autoLock", new BackgroundAutoLock(new Handler(Looper.getMainLooper()), elapsed::get,
                    () -> invokeUnchecked(screen, "lock", new Class<?>[]{boolean.class}, false)));
            activity.pause().stop(); elapsed.addAndGet(30_000);
            activity.start().resume(); awaitCleanup(screen);
            assertNull(get(screen, "session")); assertTrue(fixture.key.isDestroyed());
        }
    }

    @Test public void backgroundCompletionWaitsForReturnAndTimeoutDisposesItsPlaintext() throws Exception {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity screen = activity.get();
            AtomicInteger applied = new AtomicInteger(), discarded = new AtomicInteger();
            activity.pause().stop();
            completion(screen, () -> applied.incrementAndGet(), () -> discarded.incrementAndGet());
            assertEquals(0, applied.get()); assertEquals(0, discarded.get());
            advance(10); activity.start().resume(); assertEquals(1, applied.get());
            activity.pause().stop();
            byte[] plain = {4, 5, 6};
            Constructor<VaultSession.ReadFile> constructor = VaultSession.ReadFile.class.getDeclaredConstructor(byte[].class, String.class);
            constructor.setAccessible(true); VaultSession.ReadFile read = constructor.newInstance(plain, "\"test\"");
            completion(screen, () -> applied.incrementAndGet(), () -> {
                invokeUnchecked(screen, "dispose", new Class<?>[]{Object.class}, read); discarded.incrementAndGet();
            });
            advance(30); assertArrayEquals(new byte[3], plain); assertEquals(1, discarded.get());
            activity.start().resume(); assertEquals(1, applied.get());
        }
    }

    @Test public void lateUnlockResultIsDestroyedAfterTimeoutAndCannotReviveSession() throws Exception {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity screen = activity.get(); Fixture fixture = unlocked(screen);
            set(screen, "session", null); // The connection exists, but unlock has not delivered its result yet.
            AtomicInteger applied = new AtomicInteger();
            Object result = newCompletion(screen, () -> applied.incrementAndGet(), fixture.vault::close);
            activity.pause().stop(); advance(30);
            invoke(screen, "deliver", new Class<?>[]{result.getClass()}, result);
            activity.start().resume();
            assertEquals(0, applied.get()); assertNull(get(screen, "session"));
            assertTrue(fixture.key.isDestroyed()); assertTrue(fixture.store.closed);
        }
    }

    @Test public void manualLockAndDestroyCancelGraceAndRejectStaleResults() throws Exception {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity screen = activity.get(); Fixture fixture = unlocked(screen);
            AtomicInteger applied = new AtomicInteger(), discarded = new AtomicInteger();
            Object old = newCompletion(screen, () -> applied.incrementAndGet(), () -> discarded.incrementAndGet());
            activity.pause().stop(); invoke(screen, "lock", new Class<?>[]{boolean.class}, false);
            invoke(screen, "deliver", new Class<?>[]{old.getClass()}, old); awaitCleanup(screen);
            assertTrue(fixture.key.isDestroyed()); assertEquals(0, applied.get()); assertEquals(1, discarded.get());
            activity.start().resume(); assertNull(get(screen, "session"));
        }
        ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity screen = activity.get(); Fixture fixture = unlocked(screen);
        activity.pause().stop().destroy();
        ((ExecutorService) get(screen, "cleanup")).awaitTermination(3, TimeUnit.SECONDS);
        assertTrue(fixture.key.isDestroyed()); assertTrue(fixture.store.closed);
    }

    @Test public void settingsUiPersistsSixtySecondsAndUsesItForNextBackgroundPeriod() throws Exception {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity screen = activity.get();
            findText(screen.getWindow().getDecorView(), "自動ロック設定（30秒）").performClick();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog(); shadowOf(Looper.getMainLooper()).idle();
            assertEquals(2, dialog.getListView().getCheckedItemPosition());
            dialog.getListView().performItemClick(null, 3, 3); shadowOf(Looper.getMainLooper()).idle();
            assertEquals(60, new AutoLockSettings(context).seconds());
            Fixture fixture = unlocked(screen);
            activity.pause().stop(); advance(59); assertFalse(fixture.store.closed);
            advance(1); awaitCleanup(screen); assertTrue(fixture.key.isDestroyed());
        }
    }

    @Test public void importWithinGraceUsesCurrentVaultAndAfterExpiryRequiresPassword() throws Exception {
        File source = File.createTempFile("import-", ".txt", context.getCacheDir());
        Files.write(source.toPath(), "test import".getBytes(StandardCharsets.UTF_8));
        ImportProvider provider = new ImportProvider(); provider.source = source;
        ProviderInfo info = new ProviderInfo(); info.authority = "org.deltavault.test.import";
        provider.attachInfo(context, info); ShadowContentResolver.registerProviderInternal(info.authority, provider);
        Uri uri = Uri.parse("content://" + info.authority + "/file");
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity screen = activity.get(); Fixture fixture = unlocked(screen);
            armImport(screen); activity.pause().stop(); advance(10); activity.start();
            screen.onActivityResult(100, Activity.RESULT_OK, new Intent().setData(uri));
            activity.resume(); shadowOf(Looper.getMainLooper()).idle();
            ((ExecutorService) get(screen, "io")).submit(() -> { }).get(3, TimeUnit.SECONDS);
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(fixture.store.putCount.get() > 0); assertSame(fixture.vault, get(screen, "session"));
            assertNull(get(screen, "dialog"));
            // Drain the refresh queued by successful import before opening the picker again.
            ((ExecutorService) get(screen, "io")).submit(() -> { }).get(3, TimeUnit.SECONDS);
            shadowOf(Looper.getMainLooper()).idle();
            armImport(screen); activity.pause().stop(); advance(30); activity.start();
            screen.onActivityResult(100, Activity.RESULT_OK, new Intent().setData(uri));
            activity.resume(); shadowOf(Looper.getMainLooper()).idle();
            assertNull(get(screen, "session"));
            AlertDialog unlock = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("保管庫パスワード", inputs(unlock.getWindow().getDecorView()).get(0).getHint().toString());
        } finally { source.delete(); }
    }

    private Fixture unlocked(MainActivity screen) throws Exception {
        TrackingStore store = new TrackingStore(); Masterkey key = Masterkey.generate(new SecureRandom());
        Cryptor cryptor = CryptorProvider.forScheme(CryptorProvider.Scheme.SIV_GCM).provide(key, new SecureRandom());
        Constructor<VaultSession> constructor = VaultSession.class.getDeclaredConstructor(RemoteStore.class, Masterkey.class, Cryptor.class, int.class);
        constructor.setAccessible(true); VaultSession vault = constructor.newInstance(store, key, cryptor, 220);
        set(screen, "session", vault); set(screen, "remote", store);
        set(screen, "activeProfile", new VaultProfiles.Profile("test", "Test", "content://test/tree/root", "", "local"));
        return new Fixture(vault, store, key);
    }
    private void armImport(MainActivity screen) throws Exception {
        set(screen, "importPicker", true); set(screen, "importProfile", get(screen, "activeProfile")); set(screen, "importDirectory", "");
    }
    private void completion(MainActivity screen, Runnable apply, Runnable discard) throws Exception {
        Object completion = newCompletion(screen, apply, discard);
        invoke(screen, "deliver", new Class<?>[]{completion.getClass()}, completion);
    }
    private Object newCompletion(MainActivity screen, Runnable apply, Runnable discard) throws Exception {
        Class<?> type = Class.forName(MainActivity.class.getName() + "$Completion");
        Constructor<?> constructor = type.getDeclaredConstructor(int.class, Runnable.class, Runnable.class); constructor.setAccessible(true);
        return constructor.newInstance(((AtomicInteger) get(screen, "epoch")).get(), apply, discard);
    }
    private void awaitCleanup(MainActivity screen) throws Exception { ((ExecutorService) get(screen, "cleanup")).submit(() -> { }).get(3, TimeUnit.SECONDS); }
    private void advance(long seconds) { shadowOf(Looper.getMainLooper()).idleFor(seconds, TimeUnit.SECONDS); }
    private static Object get(Object target, String name) throws Exception { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
    private static void set(Object target, String name, Object value) throws Exception { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
    private static void invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception { Method method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(target, args); }
    private static void invokeUnchecked(Object target, String name, Class<?>[] types, Object... args) {
        try { invoke(target, name, types, args); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static List<EditText> inputs(View root) {
        java.util.ArrayList<EditText> result = new java.util.ArrayList<>(); collectInputs(root, result); return result;
    }
    private static void collectInputs(View view, List<EditText> result) {
        if (view instanceof EditText) result.add((EditText) view);
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) collectInputs(group.getChildAt(i), result);
    }
    private static TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            TextView found = findText(group.getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }
    private record Fixture(VaultSession vault, TrackingStore store, Masterkey key) { }
    private static final class TrackingStore implements RemoteStore {
        volatile boolean closed; final AtomicInteger putCount = new AtomicInteger();
        @Override public Blob get(String path, int limit) throws IOException { throw new IOException("No file"); }
        @Override public List<Entry> list(String path) throws IOException { if (closed) throw new IOException("Closed"); return List.of(); }
        @Override public void put(String path, byte[] bytes, String etag, boolean createOnly) throws IOException { if (closed) throw new IOException("Closed"); putCount.incrementAndGet(); }
        @Override public void mkdir(String path) { }
        @Override public void move(String source, String destination) { }
        @Override public void close() { closed = true; }
    }
    public static final class ImportProvider extends ContentProvider {
        File source;
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
            MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME}); cursor.addRow(new Object[]{"import.txt"}); return cursor;
        }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException { return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY); }
        @Override public String getType(Uri uri) { return "text/plain"; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }
}
