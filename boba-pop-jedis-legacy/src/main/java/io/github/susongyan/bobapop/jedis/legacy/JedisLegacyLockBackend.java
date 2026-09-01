package io.github.susongyan.bobapop.jedis.legacy;

import io.github.susongyan.bobapop.core.AbstractRedisLockBackend;
import io.github.susongyan.bobapop.core.RedisLockScripts;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Collections;

/** Direct Jedis 2.x/3.x adapter. Prefer the pool constructor for multi-threaded applications. */
public final class JedisLegacyLockBackend extends AbstractRedisLockBackend {
    private final Jedis direct;
    private final JedisPool pool;

    public JedisLegacyLockBackend(JedisPool pool) {
        if (pool == null) throw new IllegalArgumentException("pool must not be null");
        this.pool = pool;
        this.direct = null;
    }

    /** The caller owns the connection and must ensure it is not shared concurrently. */
    public JedisLegacyLockBackend(Jedis direct) {
        if (direct == null) throw new IllegalArgumentException("direct must not be null");
        this.direct = direct;
        this.pool = null;
    }

    @Override
    protected boolean setNxPx(final String key, final String token, final long leaseMillis) {
        return withJedis(new Operation<Boolean>() {
            public Boolean run(Jedis jedis) {
                return "OK".equals(jedis.set(key, token, "NX", "PX", leaseMillis));
            }
        });
    }

    @Override
    protected Object evalScript(final RedisLockScripts.Script script,
                                final String key, final String... args) {
        return withJedis(new Operation<Object>() {
            public Object run(Jedis jedis) {
                return jedis.eval(script.source(), Collections.singletonList(key),
                        java.util.Arrays.asList(args));
            }
        });
    }

    private <T> T withJedis(Operation<T> operation) {
        if (pool == null) return operation.run(direct);
        Jedis jedis = pool.getResource();
        try {
            return operation.run(jedis);
        } finally {
            jedis.close();
        }
    }

    private interface Operation<T> {
        T run(Jedis jedis);
    }
}
