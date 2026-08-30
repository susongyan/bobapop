package io.github.susongyan.bobapop.spring;

import io.github.susongyan.bobapop.core.RedisLockBackend;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** Spring Data Redis adapter compiled against the Boot 1.5 era API. */
public final class RedisTemplateLockBackend implements RedisLockBackend {
    private static final byte[] NX = "NX".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PX = "PX".getBytes(StandardCharsets.UTF_8);

    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<Long>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('pexpire', KEYS[1], ARGV[2]) end return 0",
            Long.class);

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<Long>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) end return 0",
            Long.class);

    private final StringRedisTemplate redis;

    public RedisTemplateLockBackend(StringRedisTemplate redis) {
        if (redis == null) {
            throw new IllegalArgumentException("redis must not be null");
        }
        this.redis = redis;
    }

    public boolean acquire(final String key, final String token, final long leaseMillis) {
        Boolean result = redis.execute(new RedisCallback<Boolean>() {
            @Override
            public Boolean doInRedis(RedisConnection connection) {
                Object reply = connection.execute(
                        "SET",
                        bytes(key),
                        bytes(token),
                        NX,
                        PX,
                        bytes(Long.toString(leaseMillis)));
                return reply != null;
            }
        });
        return Boolean.TRUE.equals(result);
    }

    public boolean renewIfOwner(String key, String token, long leaseMillis) {
        Long result = redis.execute(
                RENEW_SCRIPT,
                Collections.singletonList(key),
                token,
                Long.toString(leaseMillis));
        return Long.valueOf(1L).equals(result);
    }

    public boolean deleteIfOwner(String key, String token) {
        Long result = redis.execute(UNLOCK_SCRIPT, Collections.singletonList(key), token);
        return Long.valueOf(1L).equals(result);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
