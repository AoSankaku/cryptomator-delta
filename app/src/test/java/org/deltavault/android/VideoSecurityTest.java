package org.deltavault.android;

import android.os.Handler;
import android.os.Looper;
import android.system.ErrnoException;
import org.cryptomator.cryptolib.api.Cryptor;
import org.cryptomator.cryptolib.api.CryptorProvider;
import org.cryptomator.cryptolib.api.Masterkey;
import org.cryptomator.cryptolib.common.EncryptingReadableByteChannel;
import org.deltavault.core.RemoteStore;
import org.deltavault.core.VaultSession;
import org.deltavault.core.VaultStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 28)
public class VideoSecurityTest {
    @Test public void callbackSupportsSeekEofAndWipesTransferredBuffers() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicInteger failed = new AtomicInteger();
            VideoReadCallback callback = new VideoReadCallback(fixture.stream, failed::incrementAndGet);
            byte[] first = new byte[40];
            assertEquals(35, callback.onRead(32760, 35, first));
            assertArrayEquals(Arrays.copyOfRange(fixture.plain, 32760, 32795), Arrays.copyOf(first, 35));
            assertArrayEquals(new byte[5], Arrays.copyOfRange(first, 35, 40));
            byte[] second = new byte[10];
            assertEquals(10, callback.onRead(4, 10, second)); assertArrayEquals(new byte[40], first);
            assertArrayEquals(Arrays.copyOfRange(fixture.plain, 4, 14), second);
            assertEquals(0, callback.onRead(Long.MAX_VALUE, 10, first)); assertArrayEquals(new byte[10], second);
            callback.onRead(0, 10, first); callback.close(); callback.close();
            assertArrayEquals(new byte[40], first); assertEquals(0, failed.get());
            assertThrows(ErrnoException.class, callback::onGetSize);
            assertThrows(ErrnoException.class, () -> callback.onRead(0, 10, second));
        }
    }
    @Test public void authenticationFailureRevokesFdAndNeverReturnsPlaintext() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicInteger failed = new AtomicInteger();
            VideoReadCallback callback = new VideoReadCallback(fixture.stream, failed::incrementAndGet);
            fixture.cipher[fixture.cryptor.fileHeaderCryptor().headerSize() + 19] ^= 1;
            byte[] output = new byte[50]; Arrays.fill(output, (byte) 4);
            assertThrows(ErrnoException.class, () -> callback.onRead(0, 50, output));
            assertArrayEquals(new byte[50], output); assertEquals(1, failed.get());
            assertThrows(ErrnoException.class, () -> callback.onRead(0, 50, output)); assertEquals(1, failed.get());
        }
    }
    @Test public void activityPausesOnDepartureAndReleasesPlayerAtTimerDeadline() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get(); FakePlayback playback = new FakePlayback();
            Field video = MainActivity.class.getDeclaredField("video"); video.setAccessible(true); video.set(activity, playback);
            AtomicLong elapsed = new AtomicLong();
            Field timer = MainActivity.class.getDeclaredField("autoLock"); timer.setAccessible(true);
            timer.set(activity, new BackgroundAutoLock(new Handler(Looper.getMainLooper()), elapsed::get, () -> {
                try { Method lock = MainActivity.class.getDeclaredMethod("lock", boolean.class); lock.setAccessible(true); lock.invoke(activity, false); }
                catch (Exception e) { throw new AssertionError(e); }
            }));
            controller.pause().stop(); assertEquals(1, playback.pauses); assertEquals(0, playback.closes);
            elapsed.set(29000); controller.start().resume(); assertSame(playback, video.get(activity));
            controller.pause().stop(); elapsed.set(59000);
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(30));
            assertEquals(2, playback.pauses); assertEquals(1, playback.closes); assertNull(video.get(activity));
        }
    }
    @Test public void videoRoutingRejectsPlaylistAndNonContainerExtensions() {
        assertEquals("mp4", VideoPreview.demux("VIDEO.MP4")); assertEquals("mkv", VideoPreview.demux("v.webm"));
        for (String name : List.of("p.m3u", "p.m3u8", "p.xspf", "p.url", "p.mp4.txt", "p.srt")) assertNull(VideoPreview.demux(name));
    }
    @Test public void playerCleanupFailureDoesNotPreventKeyRevocation() throws Exception {
        assertKeysRevokedByFault(false);
    }
    @Test public void playerPauseFailureRevokesKeysAndCompletesLifecycle() throws Exception {
        assertKeysRevokedByFault(true);
    }
    private void assertKeysRevokedByFault(boolean duringPause) throws Exception {
        try (Fixture fixture = new Fixture(); ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            Field session = MainActivity.class.getDeclaredField("session"); session.setAccessible(true); session.set(activity, fixture.vault);
            Field video = MainActivity.class.getDeclaredField("video"); video.setAccessible(true);
            video.set(activity, new Playback() {
                @Override public void pause() { throw new IllegalStateException("native pause failed"); }
                @Override public void close() { throw new IllegalStateException("native release failed"); }
            });
            if (duringPause) controller.pause();
            else { Method lock = MainActivity.class.getDeclaredMethod("lock", boolean.class); lock.setAccessible(true); lock.invoke(activity, false); }
            Field cleanup = MainActivity.class.getDeclaredField("cleanup"); cleanup.setAccessible(true);
            ((java.util.concurrent.ExecutorService) cleanup.get(activity)).submit(() -> { }).get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertNull(session.get(activity)); assertNull(video.get(activity));
            assertThrows(IOException.class, fixture.stream::size);
            Field key = VaultSession.class.getDeclaredField("masterkey"); key.setAccessible(true);
            assertTrue(((Masterkey) key.get(fixture.vault)).isDestroyed());
        }
    }
    private static final class FakePlayback implements Playback {
        int pauses, closes;
        @Override public void pause() { pauses++; }
        @Override public void close() { closes++; }
    }
    private static final class Fixture implements AutoCloseable {
        final byte[] plain = new byte[32768 + 35];
        final byte[] cipher;
        final Cryptor cryptor;
        final VaultSession vault;
        final VaultStream stream;
        Fixture() throws Exception {
            for (int i = 0; i < plain.length; i++) plain[i] = (byte) (i * 13);
            SecureRandom random = new SecureRandom(); Masterkey key = Masterkey.generate(random);
            cryptor = CryptorProvider.forScheme(CryptorProvider.Scheme.SIV_CTRMAC).provide(key, random);
            ByteArrayOutputStream encrypted = new ByteArrayOutputStream(); byte[] scratch = new byte[8192];
            try (EncryptingReadableByteChannel channel = new EncryptingReadableByteChannel(Channels.newChannel(new ByteArrayInputStream(plain)), cryptor)) {
                ByteBuffer buffer = ByteBuffer.wrap(scratch); int count;
                while (true) { buffer.clear(); count = channel.read(buffer); if (count < 0) break; encrypted.write(scratch, 0, count); }
            }
            cipher = encrypted.toByteArray();
            RemoteStore store = new RemoteStore() {
                @Override public Reader openReader(String path) {
                    return new Reader() {
                        boolean closed;
                        @Override public long size() { return cipher.length; }
                        @Override public void readFully(long position, byte[] output, int offset, int length) throws IOException {
                            if (closed) throw new IOException("closed"); RemoteStore.checkRange(size(), position, output, offset, length);
                            System.arraycopy(cipher, (int) position, output, offset, length);
                        }
                        @Override public void close() { closed = true; }
                    };
                }
                @Override public Blob get(String path, int limit) { throw new UnsupportedOperationException(); }
                @Override public List<Entry> list(String path) { throw new UnsupportedOperationException(); }
                @Override public void put(String p, byte[] b, String e, boolean c) { throw new UnsupportedOperationException(); }
                @Override public void mkdir(String p) { throw new UnsupportedOperationException(); }
                @Override public void move(String a, String b) { throw new UnsupportedOperationException(); }
                @Override public void close() { }
            };
            Constructor<VaultSession> vc = VaultSession.class.getDeclaredConstructor(RemoteStore.class, Masterkey.class, Cryptor.class, int.class);
            vc.setAccessible(true); vault = vc.newInstance(store, key, cryptor, 220);
            Constructor<VaultSession.Node> nc = VaultSession.Node.class.getDeclaredConstructor(VaultSession.class, String.class, String.class, boolean.class, String.class, String.class, String.class, String.class);
            nc.setAccessible(true); VaultSession.Node node = nc.newInstance(vault, "test.avi", "cipher.c9r", false, null, "cipher.c9r", "", "cipher");
            stream = vault.openStream(node);
        }
        @Override public void close() { vault.close(); Arrays.fill(plain, (byte) 0); Arrays.fill(cipher, (byte) 0); }
    }
}
