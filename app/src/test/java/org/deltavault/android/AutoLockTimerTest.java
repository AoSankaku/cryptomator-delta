package org.deltavault.android;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 28)
public class AutoLockTimerTest {
    private Context context;
    private File settingsFile;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        settingsFile = new File(context.getNoBackupFilesDir(), "auto-lock.json");
        settingsFile.delete();
        new File(settingsFile.getPath() + ".bak").delete();
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void defaultsToThirtySecondsAndPersistsOnlySupportedChoices() throws Exception {
        assertEquals(30, new AutoLockSettings(context).seconds());
        assertEquals("すぐに", AutoLockSettings.label(0));
        assertEquals("15秒", AutoLockSettings.label(15));
        assertArrayEquals(new int[]{0, 15, 30, 60, 120, 300}, AutoLockSettings.choices());
        int[] returned = AutoLockSettings.choices();
        returned[0] = 9;
        assertArrayEquals(new int[]{0, 15, 30, 60, 120, 300}, AutoLockSettings.choices());

        AutoLockSettings settings = new AutoLockSettings(context);
        for (int seconds : AutoLockSettings.choices()) {
            settings.setSeconds(seconds);
            assertEquals(seconds, settings.seconds());
            assertEquals(seconds, new AutoLockSettings(context).seconds());
        }
        String contents = new String(Files.readAllBytes(settingsFile.toPath()), StandardCharsets.UTF_8);
        assertEquals("{\"timeout_seconds\":300}", contents);
        assertFalse(contents.contains("password"));
        assertThrows(IllegalArgumentException.class, () -> settings.setSeconds(45));
    }

    @Test public void corruptUnknownAndOversizedSettingsUseSafeDefault() throws Exception {
        Files.write(settingsFile.toPath(), "not-json".getBytes(StandardCharsets.UTF_8));
        assertEquals(30, new AutoLockSettings(context).seconds());
        Files.write(settingsFile.toPath(), "{\"timeout_seconds\":45}".getBytes(StandardCharsets.UTF_8));
        assertEquals(30, new AutoLockSettings(context).seconds());
        Files.write(settingsFile.toPath(), "{\"timeout_seconds\":60,\"other\":true}".getBytes(StandardCharsets.UTF_8));
        assertEquals(30, new AutoLockSettings(context).seconds());
        Files.write(settingsFile.toPath(), new byte[1025]);
        assertEquals(30, new AutoLockSettings(context).seconds());
        settingsFile.delete();
        assertEquals(30, new AutoLockSettings(context).seconds());
    }

    @Test public void failedAtomicWriteLeavesCurrentTimeoutUnchanged() throws Exception {
        File parentThatIsAFile = new File(context.getCacheDir(), "auto-lock-parent-file");
        Files.write(parentThatIsAFile.toPath(), new byte[]{1});
        Context badContext = new ContextWrapper(context) {
            @Override public File getNoBackupFilesDir() { return parentThatIsAFile; }
        };
        AutoLockSettings settings = new AutoLockSettings(badContext);
        assertEquals(30, settings.seconds());
        assertThrows(IOException.class, () -> settings.setSeconds(60));
        assertEquals(30, settings.seconds());
    }

    @Test public void returningBeforeDeadlineCancelsTheBackgroundLock() {
        FakeClock clock = new FakeClock();
        AtomicInteger locks = new AtomicInteger();
        BackgroundAutoLock timer = timer(clock, locks);
        try {
            timer.leaveApp(30_000);
            shadowOf(Looper.getMainLooper()).idleFor(29_999, TimeUnit.MILLISECONDS);
            clock.now = 29_999;
            timer.returnToApp();
            shadowOf(Looper.getMainLooper()).idleFor(10_000, TimeUnit.MILLISECONDS);
            assertEquals(0, locks.get());
        } finally {
            timer.cancel();
        }
    }

    @Test public void scheduledCheckLocksAtTheExactThirtySecondDeadlineOnlyOnce() {
        FakeClock clock = new FakeClock();
        AtomicInteger locks = new AtomicInteger();
        BackgroundAutoLock timer = timer(clock, locks);
        try {
            timer.leaveApp(30_000);
            clock.now = 30_000;
            shadowOf(Looper.getMainLooper()).idleFor(30_000, TimeUnit.MILLISECONDS);
            assertEquals(1, locks.get());
            assertFalse(timer.expireIfDue());
            timer.returnToApp();
            assertEquals(1, locks.get());
        } finally {
            timer.cancel();
        }
    }

    @Test public void leavingAgainRestartsTheEntireDeadline() {
        FakeClock clock = new FakeClock();
        AtomicInteger locks = new AtomicInteger();
        BackgroundAutoLock timer = timer(clock, locks);
        try {
            timer.leaveApp(30_000);
            clock.now = 10_000;
            timer.leaveApp(30_000);
            clock.now = 30_000;
            shadowOf(Looper.getMainLooper()).idleFor(30_000, TimeUnit.MILLISECONDS);
            assertEquals(0, locks.get());
            clock.now = 39_999;
            shadowOf(Looper.getMainLooper()).idleFor(10_000, TimeUnit.MILLISECONDS);
            assertEquals(0, locks.get());
            clock.now = 40_000;
            shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS);
            assertEquals(1, locks.get());
        } finally {
            timer.cancel();
        }
    }

    @Test public void elapsedDeadlineIsEnforcedWhenReturningAfterDeviceSleep() {
        FakeClock clock = new FakeClock();
        AtomicInteger locks = new AtomicInteger();
        BackgroundAutoLock timer = timer(clock, locks);
        try {
            timer.leaveApp(30_000);
            clock.now = 30_000; // elapsedRealtime advanced while the Handler callback was suspended
            timer.returnToApp();
            assertEquals(1, locks.get());
            assertFalse(timer.expireIfDue());
        } finally {
            timer.cancel();
        }
    }

    @Test public void cancelAndImmediateModeAreSafeEvenWhenLockCallbackCancels() {
        FakeClock clock = new FakeClock();
        AtomicInteger locks = new AtomicInteger();
        AtomicReference<BackgroundAutoLock> reference = new AtomicReference<>();
        BackgroundAutoLock timer = new BackgroundAutoLock(new Handler(Looper.getMainLooper()), clock, () -> {
            locks.incrementAndGet();
            reference.get().cancel();
        });
        reference.set(timer);
        try {
            timer.leaveApp(30_000);
            timer.cancel();
            clock.now = 30_000;
            assertFalse(timer.expireIfDue());
            shadowOf(Looper.getMainLooper()).idleFor(30_000, TimeUnit.MILLISECONDS);
            assertEquals(0, locks.get());
            timer.leaveApp(0);
            assertEquals(1, locks.get());
            assertFalse(timer.expireIfDue());
        } finally {
            timer.cancel();
        }
    }

    @Test public void rejectsUnsupportedDeadlines() {
        BackgroundAutoLock timer = timer(new FakeClock(), new AtomicInteger());
        try {
            assertThrows(IllegalArgumentException.class, () -> timer.leaveApp(-1));
            assertThrows(IllegalArgumentException.class, () -> timer.leaveApp(300_001));
        } finally {
            timer.cancel();
        }
    }

    private static BackgroundAutoLock timer(FakeClock clock, AtomicInteger locks) {
        return new BackgroundAutoLock(new Handler(Looper.getMainLooper()), clock, locks::incrementAndGet);
    }

    private static final class FakeClock implements LongSupplier {
        long now;
        @Override public long getAsLong() { return now; }
    }
}
