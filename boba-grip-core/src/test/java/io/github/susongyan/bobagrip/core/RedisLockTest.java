package io.github.susongyan.bobagrip.core;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class RedisLockTest {

    @Test
    public void sameObjectReentersAndDeletesOnlyAtZero() {
        MemoryBackend backend = new MemoryBackend();
        RedisLock lock = new RedisLockClient(backend).createLock("order:{1}");

        assertTrue(lock.tryLockNow(30, TimeUnit.SECONDS));
        assertTrue(lock.tryLockNow(30, TimeUnit.SECONDS));
        assertEquals(1, backend.acquireCalls);
        assertEquals(1, backend.renewCalls);

        assertTrue(lock.unlock());
        assertTrue(backend.values.containsKey("redis-lock:order:{1}"));
        assertEquals(0, backend.deleteCalls);

        assertTrue(lock.unlock());
        assertFalse(backend.values.containsKey("redis-lock:order:{1}"));
        assertEquals(1, backend.deleteCalls);
    }

    @Test
    public void differentObjectsCompeteWithDifferentTokens() {
        MemoryBackend backend = new MemoryBackend();
        RedisLockClient client = new RedisLockClient(backend);
        RedisLock first = client.createLock("order:{1}");
        RedisLock second = client.createLock("order:{1}");

        assertTrue(first.tryLockNow(30, TimeUnit.SECONDS));
        String firstToken = backend.values.get("redis-lock:order:{1}");
        assertFalse(second.tryLockNow(30, TimeUnit.SECONDS));
        assertNotEquals(firstToken, backend.lastAcquireToken);
    }

    @Test
    public void abandonedObjectLeavesNoThreadLocalStateAndRedisUsesTtl() {
        MemoryBackend backend = new MemoryBackend();
        RedisLockClient client = new RedisLockClient(backend);
        RedisLock abandoned = client.createLock("order:{1}");
        assertTrue(abandoned.tryLockNow(30, TimeUnit.SECONDS));

        backend.values.remove("redis-lock:order:{1}"); // Simulates Redis TTL expiry.
        RedisLock nextRequest = client.createLock("order:{1}");
        assertTrue(nextRequest.tryLockNow(30, TimeUnit.SECONDS));
    }

    @Test
    public void customPrefixIsAppliedByClient() {
        MemoryBackend backend = new MemoryBackend();
        RedisLock lock = new RedisLockClient(backend, "orders:lock:").createLock("{42}");

        assertEquals("orders:lock:{42}", lock.getKey());
        assertTrue(lock.tryLockNow(30, TimeUnit.SECONDS));
    }

    @Test(expected = IllegalArgumentException.class)
    public void zeroWaitIsRejectedByUnitOverload() throws Exception {
        RedisLock lock = new RedisLockClient(new CountingBackend()).createLock("order:{zero-wait}");
        lock.tryLock(0, 30, TimeUnit.MILLISECONDS);
    }

    @Test(expected = IllegalArgumentException.class)
    public void zeroWaitIsRejectedByMillisecondOverload() throws Exception {
        RedisLock lock = new RedisLockClient(new CountingBackend()).createLock("order:{zero-wait-ms}");
        lock.tryLock(0, 30);
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeFineGrainedWaitIsRejectedBeforeConversion() throws Exception {
        RedisLock lock = new RedisLockClient(new CountingBackend()).createLock("order:{negative}");
        lock.tryLock(-1, 30, TimeUnit.NANOSECONDS);
    }

    @Test
    public void waitTimeoutDoesNotStartAnotherAttemptAfterDeadline() throws Exception {
        CountingBackend backend = new CountingBackend(10L);
        RedisLock lock = new RedisLockClient(backend).createLock("order:{deadline}");

        assertFalse(lock.tryLock(1, 30, TimeUnit.MILLISECONDS));
        assertEquals(1, backend.acquireCalls);
    }

    @Test(timeout = 3000)
    public void watchdogRenewsAndReentryDoesNotCreateAnotherTask() throws Exception {
        WatchdogBackend backend = new WatchdogBackend();
        RedisLockClient client = new RedisLockClient(backend, "redis-lock:",
                new WatchdogConfig(200, TimeUnit.MILLISECONDS, 50, TimeUnit.MILLISECONDS));
        try {
            RedisLock lock = client.createLock("watchdog");
            assertTrue(lock.tryLockWithWatchdog(20, TimeUnit.MILLISECONDS));
            Thread.sleep(140L);
            int renewsBeforeReentry = backend.renewCalls.get();
            assertTrue(renewsBeforeReentry > 0);

            assertTrue(lock.tryLockWithWatchdog(20, TimeUnit.MILLISECONDS));
            Thread.sleep(80L);
            assertTrue(backend.renewCalls.get() > renewsBeforeReentry);
            assertTrue(lock.unlock());
            assertTrue(lock.unlock());
        } finally {
            client.close();
        }
    }

    @Test(timeout = 3000)
    public void watchdogFalseRenewMarksLockLostAndNeverReacquires() throws Exception {
        WatchdogBackend backend = new WatchdogBackend();
        backend.returnFalseOnRenew = true;
        RedisLockClient client = new RedisLockClient(backend, "redis-lock:",
                new WatchdogConfig(300, TimeUnit.MILLISECONDS, 40, TimeUnit.MILLISECONDS));
        try {
            RedisLock lock = client.createLock("watchdog-lost");
            assertTrue(lock.tryLockWithWatchdog(20, TimeUnit.MILLISECONDS));
            awaitState(lock, LockState.LOST);
            assertFalse(lock.isLeaseAlive());
            assertEquals(1, backend.acquireCalls.get());
            try {
                lock.unlock();
                throw new AssertionError("unlock should report the lost lock");
            } catch (LockLostException expected) {
                // expected
            }
            assertEquals(1, backend.acquireCalls.get());
        } finally {
            client.close();
        }
    }

    @Test(timeout = 3000)
    public void watchdogRetriesUnknownFailureWithSameToken() throws Exception {
        WatchdogBackend backend = new WatchdogBackend();
        backend.failRenewTimes.set(2);
        RedisLockClient client = new RedisLockClient(backend, "redis-lock:",
                new WatchdogConfig(1000, TimeUnit.MILLISECONDS, 50, TimeUnit.MILLISECONDS));
        try {
            RedisLock lock = client.createLock("watchdog-retry");
            assertTrue(lock.tryLockWithWatchdog(20, TimeUnit.MILLISECONDS));
            long deadline = System.currentTimeMillis() + 2000L;
            while (backend.renewCalls.get() < 3 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L);
            }
            assertTrue(backend.renewCalls.get() >= 3);
            assertEquals(LockState.HEALTHY, lock.getState());
            assertEquals(1, backend.acquireCalls.get());
            assertTrue(lock.unlock());
        } finally {
            client.close();
        }
    }

    @Test(timeout = 3000)
    public void watchdogMaxDurationStopsRenewalConservatively() throws Exception {
        WatchdogBackend backend = new WatchdogBackend();
        RedisLockClient client = new RedisLockClient(backend, "redis-lock:",
                new WatchdogConfig(300, TimeUnit.MILLISECONDS, 40, TimeUnit.MILLISECONDS,
                        130, TimeUnit.MILLISECONDS));
        try {
            RedisLock lock = client.createLock("watchdog-max");
            assertTrue(lock.tryLockWithWatchdog(20, TimeUnit.MILLISECONDS));
            awaitState(lock, LockState.LOST);
            int renewCalls = backend.renewCalls.get();
            Thread.sleep(120L);
            assertEquals(renewCalls, backend.renewCalls.get());
        } finally {
            client.close();
        }
    }

    @Test(timeout = 3000)
    public void closingClientStopsWatchdogAndMarksLeaseLost() throws Exception {
        WatchdogBackend backend = new WatchdogBackend();
        RedisLockClient client = new RedisLockClient(backend, "redis-lock:",
                new WatchdogConfig(500, TimeUnit.MILLISECONDS, 50, TimeUnit.MILLISECONDS));
        RedisLock lock = client.createLock("watchdog-close");
        assertTrue(lock.tryLockWithWatchdog(20, TimeUnit.MILLISECONDS));
        client.close();
        assertEquals(LockState.LOST, lock.getState());
        assertFalse(lock.isLeaseAlive());
    }

    private static void awaitState(RedisLock lock, LockState state) throws Exception {
        long deadline = System.currentTimeMillis() + 2000L;
        while (lock.getState() != state && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertEquals(state, lock.getState());
    }

    private static final class MemoryBackend implements RedisLockBackend {
        private final Map<String, String> values = new HashMap<String, String>();
        private int acquireCalls;
        private int renewCalls;
        private int deleteCalls;
        private String lastAcquireToken;

        public boolean acquire(String key, String token, long leaseMillis) {
            acquireCalls++;
            lastAcquireToken = token;
            if (values.containsKey(key)) return false;
            values.put(key, token);
            return true;
        }

        public boolean renewIfOwner(String key, String token, long leaseMillis) {
            renewCalls++;
            return token.equals(values.get(key));
        }

        public boolean deleteIfOwner(String key, String token) {
            deleteCalls++;
            if (!token.equals(values.get(key))) return false;
            values.remove(key);
            return true;
        }
    }

    private static final class CountingBackend implements RedisLockBackend {
        private final long acquireDelayMillis;
        private int acquireCalls;

        private CountingBackend() {
            this(0L);
        }

        private CountingBackend(long acquireDelayMillis) {
            this.acquireDelayMillis = acquireDelayMillis;
        }

        public boolean acquire(String key, String token, long leaseMillis) {
            acquireCalls++;
            if (acquireDelayMillis > 0L) {
                try {
                    Thread.sleep(acquireDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return false;
        }

        public boolean renewIfOwner(String key, String token, long leaseMillis) {
            return false;
        }

        public boolean deleteIfOwner(String key, String token) {
            return false;
        }
    }

    private static final class WatchdogBackend implements RedisLockBackend {
        private final Map<String, String> values = new HashMap<String, String>();
        private final AtomicInteger acquireCalls = new AtomicInteger();
        private final AtomicInteger renewCalls = new AtomicInteger();
        private final AtomicInteger failRenewTimes = new AtomicInteger();
        private volatile boolean returnFalseOnRenew;

        public synchronized boolean acquire(String key, String token, long leaseMillis) {
            acquireCalls.incrementAndGet();
            if (values.containsKey(key)) {
                return false;
            }
            values.put(key, token);
            return true;
        }

        public synchronized boolean renewIfOwner(String key, String token, long leaseMillis) {
            renewCalls.incrementAndGet();
            if (failRenewTimes.getAndDecrement() > 0) {
                throw new IllegalStateException("temporary redis failure");
            }
            if (returnFalseOnRenew) {
                return false;
            }
            return token.equals(values.get(key));
        }

        public synchronized boolean deleteIfOwner(String key, String token) {
            if (!token.equals(values.get(key))) {
                return false;
            }
            values.remove(key);
            return true;
        }
    }
}
