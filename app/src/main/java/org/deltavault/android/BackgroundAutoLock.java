package org.deltavault.android;

import android.os.Handler;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Runs the vault lock after the app has remained in the background for a bounded delay. */
final class BackgroundAutoLock {
    static final long MAX_DELAY_MILLIS = 300_000L;
    private static final long NO_DEADLINE = -1L;

    private final Handler handler;
    private final LongSupplier elapsedClock;
    private final Runnable onLock;
    private final Runnable timerCheck = this::checkTimer;
    private long deadline = NO_DEADLINE;

    BackgroundAutoLock(Handler handler, LongSupplier elapsedClock, Runnable onLock) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.elapsedClock = Objects.requireNonNull(elapsedClock, "elapsedClock");
        this.onLock = Objects.requireNonNull(onLock, "onLock");
    }

    void leaveApp(long timeoutMillis) {
        if (timeoutMillis < 0 || timeoutMillis > MAX_DELAY_MILLIS) {
            throw new IllegalArgumentException("Unsupported background timeout");
        }
        cancel();
        if (timeoutMillis == 0) {
            onLock.run();
            return;
        }

        long now = elapsedClock.getAsLong();
        deadline = now > Long.MAX_VALUE - timeoutMillis ? Long.MAX_VALUE : now + timeoutMillis;
        handler.postDelayed(timerCheck, timeoutMillis);
    }

    /** Cancels the deadline on return, or locks first if the elapsed-time deadline has passed. */
    void returnToApp() {
        if (deadline == NO_DEADLINE) {
            return;
        }
        if (elapsedClock.getAsLong() >= deadline) {
            expireIfDue();
        } else {
            cancel();
        }
    }

    /** Checks elapsed time independently of Handler uptime, which may pause during device sleep. */
    boolean expireIfDue() {
        if (deadline == NO_DEADLINE || elapsedClock.getAsLong() < deadline) {
            return false;
        }
        deadline = NO_DEADLINE;
        handler.removeCallbacks(timerCheck);
        onLock.run();
        return true;
    }

    void cancel() {
        handler.removeCallbacks(timerCheck);
        deadline = NO_DEADLINE;
    }

    private void checkTimer() {
        if (expireIfDue()) {
            return;
        }
        if (deadline != NO_DEADLINE) {
            long remaining = deadline - elapsedClock.getAsLong();
            handler.postDelayed(timerCheck, Math.max(1L, remaining));
        }
    }
}
