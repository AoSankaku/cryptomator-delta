package org.deltavault.android;

import android.os.Handler;
import android.os.Looper;
import org.cryptomator.cryptolib.api.Cryptor;
import org.cryptomator.cryptolib.api.CryptorProvider;
import org.cryptomator.cryptolib.api.Masterkey;
import org.deltavault.core.RemoteStore;
import org.deltavault.core.VaultSession;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 28)
public class DocumentLifecycleTest {
    @Test public void returningBeforeDeadlineRestoresViewerAndDeadlineDisposesIt() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get(); FakeViewer viewer = new FakeViewer();
            set(activity, "document", viewer);
            AtomicLong elapsed = new AtomicLong();
            set(activity, "autoLock", new BackgroundAutoLock(new Handler(Looper.getMainLooper()), elapsed::get,
                    () -> lock(activity)));
            controller.pause().stop();
            assertFalse(viewer.foreground); assertEquals(0, viewer.closes);
            elapsed.set(29_000); controller.start().resume();
            assertTrue(viewer.foreground); assertSame(viewer, get(activity, "document"));
            controller.pause().stop(); elapsed.set(59_000);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));
            assertEquals(1, viewer.closes); assertNull(get(activity, "document"));
        }
    }

    @Test public void viewerReleaseFailureStillRevokesVaultKeys() throws Exception { assertRevoked(false); }
    @Test public void backgroundTransitionFailureStillRevokesVaultKeys() throws Exception { assertRevoked(true); }

    private void assertRevoked(boolean transition) throws Exception {
        SecureRandom random = new SecureRandom(); Masterkey key = Masterkey.generate(random);
        Cryptor cryptor = CryptorProvider.forScheme(CryptorProvider.Scheme.SIV_CTRMAC).provide(key, random);
        RemoteStore store = new UnusedStore();
        Constructor<VaultSession> constructor = VaultSession.class.getDeclaredConstructor(
                RemoteStore.class, Masterkey.class, Cryptor.class, int.class);
        constructor.setAccessible(true);
        VaultSession vault = constructor.newInstance(store, key, cryptor, 220);
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get(); set(activity, "session", vault); set(activity, "remote", store);
            set(activity, "document", new DocumentViewer() {
                @Override public void setForeground(boolean foreground) { throw new IllegalStateException("renderer unavailable"); }
                @Override public void close() { throw new IllegalStateException("renderer unavailable"); }
            });
            if (transition) controller.pause().stop(); else lock(activity);
            ((ExecutorService) get(activity, "cleanup")).submit(() -> { }).get(2, TimeUnit.SECONDS);
            assertNull(get(activity, "session")); assertNull(get(activity, "document")); assertTrue(key.isDestroyed());
        } finally { vault.close(); }
    }

    private static final class FakeViewer implements DocumentViewer {
        boolean foreground = true; int closes;
        @Override public void setForeground(boolean value) { foreground = value; }
        @Override public void close() { closes++; }
    }
    private static void lock(MainActivity activity) {
        try {
            Method method = MainActivity.class.getDeclaredMethod("lock", boolean.class);
            method.setAccessible(true); method.invoke(activity, false);
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    private static Object get(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static final class UnusedStore implements RemoteStore {
        @Override public Blob get(String path, int maxBytes) throws IOException { throw new IOException("unused"); }
        @Override public List<Entry> list(String path) throws IOException { throw new IOException("unused"); }
        @Override public void put(String path, byte[] bytes, String etag, boolean createOnly) throws IOException { throw new IOException("unused"); }
        @Override public void mkdir(String path) throws IOException { throw new IOException("unused"); }
        @Override public void move(String source, String destination) throws IOException { throw new IOException("unused"); }
        @Override public void close() { }
    }
}
