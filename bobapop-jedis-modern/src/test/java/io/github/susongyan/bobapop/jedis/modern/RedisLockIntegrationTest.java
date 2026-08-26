package io.github.susongyan.bobapop.jedis.modern;

import io.github.susongyan.bobapop.core.RedisLock;
import io.github.susongyan.bobapop.core.RedisLockClient;
import org.junit.Assume;
import org.junit.Test;
import redis.clients.jedis.JedisPooled;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Opt-in tests against a real Redis instance.
 * Run with -Dredis.integration=true -Dredis.integration.uri=redis://127.0.0.1:6379.
 */
public class RedisLockIntegrationTest {

    @Test
    public void standaloneLockReentersAndReleases() throws Exception {
        Assume.assumeTrue("Set -Dredis.integration=true to run", integrationEnabled());

        try (JedisPooled jedis = new JedisPooled(URI.create(integrationUri()))) {
            jedis.ping();
            RedisLockClient client = new RedisLockClient(
                    new JedisLockBackend(jedis),
                    "redis-lock-it:" + UUID.randomUUID() + ":");
            RedisLock lock = client.createLock("order:{1}");

            assertTrue(lock.tryLock(1, 10, TimeUnit.SECONDS));
            try {
                assertTrue(lock.tryLockNow(10, TimeUnit.SECONDS));
                assertTrue(lock.unlock());
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }

            RedisLock next = client.createLock("order:{1}");
            assertTrue(next.tryLockNow(10, TimeUnit.SECONDS));
            assertTrue(next.unlock());
            assertFalse(next.isHeldByCurrentThread());
        }
    }

    private static boolean integrationEnabled() {
        return Boolean.getBoolean("redis.integration");
    }

    private static String integrationUri() {
        return System.getProperty("redis.integration.uri", "redis://127.0.0.1:6379");
    }
}
