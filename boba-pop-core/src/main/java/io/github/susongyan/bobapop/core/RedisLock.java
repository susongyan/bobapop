package io.github.susongyan.bobapop.core;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * A lightweight per-use Redis lock object.
 * Reentrancy is supported only by reusing this same object on the owning thread.
 */
public final class RedisLock {
    private final RedisLockBackend backend;
    private final String key;
    private final String token;
    private final WatchdogManager watchdogManager;

    private volatile int holdCount;
    private volatile long ownerThreadId = -1L;
    private volatile LockState state = LockState.RELEASED;
    private volatile WatchdogManager.WatchdogRegistration watchdogRegistration;

    RedisLock(RedisLockBackend backend, String key, String token, WatchdogManager watchdogManager) {
        this.backend = backend;
        this.key = key;
        this.token = token;
        this.watchdogManager = watchdogManager;
    }

    /**
     * Immediately tries once and never waits for another owner.
     *
     * <p>The Redis client's synchronous command timeout still applies; this method
     * does not cancel an in-flight network operation.</p>
     */
    public boolean tryLockNow(long leaseTime, TimeUnit unit) {
        if (unit == null) {
            throw new IllegalArgumentException("unit must not be null");
        }
        try {
            return doTryLock(0L, unit.toMillis(leaseTime));
        } catch (InterruptedException impossible) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Immediate tryLockNow must not block", impossible);
        }
    }

    /** Millisecond convenience API for an immediate attempt. */
    public boolean tryLockNow(long leaseMillis) {
        try {
            return doTryLock(0L, leaseMillis);
        } catch (InterruptedException impossible) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Immediate tryLockNow must not block", impossible);
        }
    }

    /** Immediately tries once and starts the client's watchdog after acquisition. */
    public boolean tryLockWithWatchdog(long waitTime, TimeUnit unit) throws InterruptedException {
        if (unit == null) {
            throw new IllegalArgumentException("unit must not be null");
        }
        validatePositiveWait(waitTime);
        return doTryLock(unit.toNanos(waitTime), watchdogManagerConfigLeaseMillis(), true);
    }

    /** Millisecond convenience API for watchdog mode. */
    public boolean tryLockWithWatchdog(long waitMillis) throws InterruptedException {
        validatePositiveWait(waitMillis);
        return doTryLock(TimeUnit.MILLISECONDS.toNanos(waitMillis),
                watchdogManagerConfigLeaseMillis(), true);
    }

    /**
     * Tries until acquired, interrupted, or waitTime expires. One unit applies to both values.
     * waitTime must be positive; use tryLockNow for an immediate attempt.
     *
     * <p>The deadline governs when another attempt may start. A Redis command that
     * is already in flight may finish after the deadline, so configure the client's
     * socket/command timeout to match the application's latency budget.</p>
     */
    public boolean tryLock(
            long waitTime,
            long leaseTime,
            TimeUnit unit) throws InterruptedException {
        if (unit == null) {
            throw new IllegalArgumentException("unit must not be null");
        }
        validatePositiveWait(waitTime);
        validatePositiveLease(leaseTime);
        return doTryLock(unit.toNanos(waitTime), unit.toMillis(leaseTime));
    }

    /**
     * Millisecond convenience API. The wait must be positive; use tryLockNow for
     * an immediate attempt.
     * An in-flight Redis command is still bounded by the client's own timeout.
     */
    public boolean tryLock(long waitMillis, long leaseMillis) throws InterruptedException {
        validatePositiveWait(waitMillis);
        validatePositiveLease(leaseMillis);
        return doTryLock(TimeUnit.MILLISECONDS.toNanos(waitMillis), leaseMillis);
    }

    private boolean doTryLock(long waitNanos, long leaseMillis) throws InterruptedException {
        return doTryLock(waitNanos, leaseMillis, false);
    }

    private boolean doTryLock(long waitNanos, long leaseMillis, boolean watchdog) throws InterruptedException {
        validate(waitNanos, leaseMillis);

        if (holdCount > 0) {
            checkOwnerThread();
            if (state == LockState.LOST) {
                throw new LockLostException(key);
            }
            if (!backend.renewIfOwner(key, token, leaseMillis)) {
                markLost(watchdogRegistration);
                clearLocalOwnership();
                throw new LockLostException(key);
            }
            state = LockState.HEALTHY;
            if (watchdog && watchdogRegistration == null) {
                startWatchdog();
            }
            holdCount++;
            return true;
        }

        long startNanos = System.nanoTime();
        boolean firstAttempt = true;
        while (firstAttempt || remainingNanos(startNanos, waitNanos) > 0L) {
            firstAttempt = false;
            if (backend.acquire(key, token, leaseMillis)) {
                ownerThreadId = Thread.currentThread().getId();
                holdCount = 1;
                state = LockState.HEALTHY;
                if (watchdog) {
                    startWatchdog();
                }
                return true;
            }
            long remainingNanos = remainingNanos(startNanos, waitNanos);
            if (remainingNanos <= 0L) {
                return false;
            }
            long jitterNanos = TimeUnit.MILLISECONDS.toNanos(
                    ThreadLocalRandom.current().nextLong(25L, 76L));
            TimeUnit.NANOSECONDS.sleep(Math.min(remainingNanos, jitterNanos));
        }
        return false;
    }

    private static long remainingNanos(long startNanos, long waitNanos) {
        return waitNanos - (System.nanoTime() - startNanos);
    }

    /** Releases one reentrant level; Redis is touched only when the count reaches zero. */
    public boolean unlock() {
        if (holdCount == 0) {
            throw new IllegalMonitorStateException("Lock is not held: " + key);
        }
        checkOwnerThread();

        if (holdCount > 1) {
            holdCount--;
            return true;
        }

        boolean wasLost = state == LockState.LOST;
        cancelWatchdog();
        boolean released;
        try {
            released = backend.deleteIfOwner(key, token);
        } finally {
            clearLocalOwnership();
        }
        if (!released || wasLost) {
            throw new LockLostException(key);
        }
        return true;
    }

    public boolean isHeldByCurrentThread() {
        return holdCount > 0 && ownerThreadId == Thread.currentThread().getId();
    }

    /** Returns the local watchdog state. A healthy state is not proof of a distributed lease. */
    public LockState getState() {
        return state;
    }

    /** Returns true only while the local watchdog state is confirmed healthy. */
    public boolean isLeaseAlive() {
        return holdCount > 0 && state == LockState.HEALTHY;
    }

    public String getKey() {
        return key;
    }

    private void checkOwnerThread() {
        if (ownerThreadId != Thread.currentThread().getId()) {
            throw new IllegalMonitorStateException("Lock cannot be used by a non-owner thread: " + key);
        }
    }

    private void clearLocalOwnership() {
        holdCount = 0;
        ownerThreadId = -1L;
        state = LockState.RELEASED;
        watchdogRegistration = null;
    }

    private long watchdogManagerConfigLeaseMillis() {
        return watchdogManager.leaseMillis();
    }

    private void startWatchdog() {
        if (watchdogRegistration == null) {
            watchdogRegistration = watchdogManager.register(this, backend, key, token);
        }
    }

    private void cancelWatchdog() {
        WatchdogManager.WatchdogRegistration registration = watchdogRegistration;
        watchdogRegistration = null;
        if (registration != null) {
            registration.cancel();
        }
    }

    private void markLost(WatchdogManager.WatchdogRegistration registration) {
        state = LockState.LOST;
        if (registration != null) {
            if (watchdogRegistration == registration) {
                watchdogRegistration = null;
            }
            registration.cancel();
        }
    }

    void onWatchdogTick(WatchdogManager.WatchdogRegistration registration) {
        if (watchdogRegistration != registration || holdCount == 0 || state == LockState.LOST) {
            registration.cancel();
            return;
        }
        if (registration.maxDurationReached()) {
            markLost(registration);
            return;
        }
        try {
            if (!registration.backend().renewIfOwner(
                    registration.key(), registration.token(), registration.leaseMillis())) {
                markLost(registration);
                return;
            }
            registration.renewed();
            state = LockState.HEALTHY;
            registration.schedule(registration.renewIntervalMillis());
        } catch (RuntimeException failure) {
            state = LockState.SUSPECT;
            int retryAttempt = registration.nextRetryAttempt();
            if (retryAttempt > 3 || !registration.canRetryBeforeLeaseDeadline()) {
                markLost(registration);
                return;
            }
            registration.schedule(WatchdogManager.retryDelayMillis(retryAttempt));
        }
    }

    void onWatchdogClosed(WatchdogManager.WatchdogRegistration registration) {
        if (watchdogRegistration == registration && holdCount > 0) {
            markLost(registration);
        }
    }

    private static void validate(long waitNanos, long leaseMillis) {
        if (waitNanos < 0L) {
            throw new IllegalArgumentException("waitTime must not be negative");
        }
        if (leaseMillis <= 0L) {
            throw new IllegalArgumentException("leaseMillis must be positive");
        }
    }

    private static void validatePositiveWait(long waitTime) {
        if (waitTime <= 0L) {
            throw new IllegalArgumentException("waitTime must be positive; use tryLockNow for an immediate attempt");
        }
    }

    private static void validatePositiveLease(long leaseTime) {
        if (leaseTime <= 0L) {
            throw new IllegalArgumentException("leaseTime must be positive");
        }
    }
}
