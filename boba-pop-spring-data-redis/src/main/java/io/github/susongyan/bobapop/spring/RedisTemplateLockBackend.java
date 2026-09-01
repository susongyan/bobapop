package io.github.susongyan.bobapop.spring;

import io.github.susongyan.bobapop.core.AbstractRedisLockBackend;
import io.github.susongyan.bobapop.core.RedisLockScripts;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** Spring Data Redis adapter compiled against the Boot 1.5 era API. */
public final class RedisTemplateLockBackend extends AbstractRedisLockBackend {
    private static final byte[] NX = "NX".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PX = "PX".getBytes(StandardCharsets.UTF_8);

    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<Long>(
            RedisLockScripts.RENEW_IF_OWNER,
            Long.class);

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<Long>(
            RedisLockScripts.DELETE_IF_OWNER,
            Long.class);

    private final StringRedisTemplate redis;

    public RedisTemplateLockBackend(StringRedisTemplate redis) {
        if (redis == null) {
            throw new IllegalArgumentException("redis must not be null");
        }
        this.redis = redis;
    }

    @Override
    protected boolean setNxPx(final String key, final String token, final long leaseMillis) {
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

    @Override
    protected Object evalScript(RedisLockScripts.Script script, String key, String... args) {
        DefaultRedisScript<Long> redisScript = script == RedisLockScripts.RENEW_IF_OWNER_SCRIPT
                ? RENEW_SCRIPT : UNLOCK_SCRIPT;
        return redis.execute(redisScript, Collections.singletonList(key), (Object[]) args);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
