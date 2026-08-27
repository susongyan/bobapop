package io.github.susongyan.bobagrip.lettuce;

import io.github.susongyan.bobagrip.core.RedisLock;
import io.github.susongyan.bobagrip.core.RedisLockClient;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.Assume;
import org.junit.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;

/** Opt-in real Redis test. Use -Dredis.integration=true to enable it. */
public class LettuceLockIntegrationTest {

    @Test
    public void lockReentersAndReleasesAgainstRealRedis() throws Exception {
        Assume.assumeTrue("Set -Dredis.integration=true to run",
                Boolean.getBoolean("redis.integration"));

        RedisClient client = RedisClient.create(
                System.getProperty("redis.integration.uri", "redis://127.0.0.1:6379"));
        StatefulRedisConnection<String, String> connection = client.connect();
        try {
            RedisLock lock = new RedisLockClient(
                    new LettuceLockBackend(connection),
                    "boba-grip-lettuce-it:" + UUID.randomUUID() + ":")
                    .createLock("order:{1}");
            assertTrue(lock.tryLock(1, 10, TimeUnit.SECONDS));
            try {
                assertTrue(lock.tryLockNow(10, TimeUnit.SECONDS));
                assertTrue(lock.unlock());
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        } finally {
            connection.close();
            client.shutdown();
        }
    }
}
