package io.github.susongyan.bobagrip.core;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class WatchdogManager implements AutoCloseable {
    private static final long[] RETRY_DELAYS_MILLIS = {100L, 300L, 500L};

    private final WatchdogConfig config;
    private final Set<WatchdogRegistration> registrations =
            Collections.newSetFromMap(new ConcurrentHashMap<WatchdogRegistration, Boolean>());
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile ScheduledThreadPoolExecutor executor;

    WatchdogManager(WatchdogConfig config) {
        this.config = config;
    }

    synchronized WatchdogRegistration register(RedisLock lock, RedisLockBackend backend,
                                                String key, String token) {
        if (closed.get()) {
            throw new IllegalStateException("RedisLockClient is closed");
        }
        WatchdogRegistration registration = new WatchdogRegistration(
                this, lock, backend, key, token, config);
        registrations.add(registration);
        registration.schedule(config.getRenewIntervalMillis());
        return registration;
    }

    private synchronized ScheduledThreadPoolExecutor executor() {
        if (closed.get()) {
            throw new IllegalStateException("RedisLockClient is closed");
        }
        if (executor == null) {
            executor = new ScheduledThreadPoolExecutor(2, new ThreadFactory() {
                private final AtomicLong sequence = new AtomicLong();

                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable,
                            "redis-lock-watchdog-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }
            });
            executor.setRemoveOnCancelPolicy(true);
        }
        return executor;
    }

    void remove(WatchdogRegistration registration) {
        registrations.remove(registration);
    }

    long leaseMillis() {
        return config.getLeaseMillis();
    }

    static long retryDelayMillis(int retryAttempt) {
        return RETRY_DELAYS_MILLIS[Math.min(retryAttempt - 1, RETRY_DELAYS_MILLIS.length - 1)];
    }

    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (WatchdogRegistration registration : registrations) {
            registration.clientClosed();
            registration.cancel();
        }
        registrations.clear();
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    static final class WatchdogRegistration implements Runnable {
        private final WatchdogManager manager;
        private final WeakReference<RedisLock> lockReference;
        private final RedisLockBackend backend;
        private final String key;
        private final String token;
        private final WatchdogConfig config;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicInteger retryAttempts = new AtomicInteger();
        private final long startedNanos = System.nanoTime();
        private volatile long leaseDeadlineNanos;
        private volatile ScheduledFuture<?> future;

        WatchdogRegistration(WatchdogManager manager, RedisLock lock,
                             RedisLockBackend backend, String key, String token,
                             WatchdogConfig config) {
            this.manager = manager;
            this.lockReference = new WeakReference<RedisLock>(lock);
            this.backend = backend;
            this.key = key;
            this.token = token;
            this.config = config;
            this.leaseDeadlineNanos = deadline(startedNanos, configLeaseNanos(config));
        }

        public void run() {
            if (cancelled.get()) {
                return;
            }
            RedisLock lock = lockReference.get();
            if (lock == null) {
                cancel();
                return;
            }
            lock.onWatchdogTick(this);
        }

        void schedule(long delayMillis) {
            if (cancelled.get()) {
                return;
            }
            future = manager.executor().schedule(this, Math.max(1L, delayMillis),
                    TimeUnit.MILLISECONDS);
        }

        void renewed() {
            retryAttempts.set(0);
            leaseDeadlineNanos = deadline(System.nanoTime(), configLeaseNanos(config));
        }

        int nextRetryAttempt() {
            return retryAttempts.incrementAndGet();
        }

        boolean canRetryBeforeLeaseDeadline() {
            long safetyNanos = configRenewNanos(config);
            return System.nanoTime() < leaseDeadlineNanos - safetyNanos;
        }

        boolean maxDurationReached() {
            long maxDuration = config.getMaxDurationMillis();
            return maxDuration > 0L
                    && System.nanoTime() - startedNanos >= TimeUnit.MILLISECONDS.toNanos(maxDuration);
        }

        RedisLockBackend backend() {
            return backend;
        }

        String key() {
            return key;
        }

        String token() {
            return token;
        }

        long leaseMillis() {
            return config.getLeaseMillis();
        }

        long renewIntervalMillis() {
            return config.getRenewIntervalMillis();
        }

        void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                ScheduledFuture<?> scheduled = future;
                if (scheduled != null) {
                    scheduled.cancel(false);
                }
                manager.remove(this);
            }
        }

        void clientClosed() {
            RedisLock lock = lockReference.get();
            if (lock != null) {
                lock.onWatchdogClosed(this);
            }
        }

        private static long configLeaseNanos(WatchdogConfig config) {
            return TimeUnit.MILLISECONDS.toNanos(config.getLeaseMillis());
        }

        private static long configRenewNanos(WatchdogConfig config) {
            return TimeUnit.MILLISECONDS.toNanos(config.getRenewIntervalMillis());
        }

        private static long deadline(long start, long duration) {
            long result = start + duration;
            return result < start ? Long.MAX_VALUE : result;
        }
    }
}
