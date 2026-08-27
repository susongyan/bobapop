package io.github.susongyan.bobagrip.jedis.legacy;

import io.github.susongyan.bobagrip.core.RedisLockBackend;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Collections;

/** Direct Jedis 2.x/3.x adapter. Prefer the pool constructor for multi-threaded applications. */
public final class JedisLegacyLockBackend implements RedisLockBackend {
    private static final String RENEW_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('pexpire', KEYS[1], ARGV[2]) end return 0";
    private static final String UNLOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) end return 0";

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

    public boolean acquire(final String key, final String token, final long leaseMillis) {
        return withJedis(new Operation<Boolean>() {
            public Boolean run(Jedis jedis) {
                return "OK".equals(jedis.set(key, token, "NX", "PX", leaseMillis));
            }
        });
    }

    public boolean renewIfOwner(final String key, final String token, final long leaseMillis) {
        return withJedis(new Operation<Boolean>() {
            public Boolean run(Jedis jedis) {
                Object value = jedis.eval(RENEW_SCRIPT, Collections.singletonList(key),
                        java.util.Arrays.asList(token, Long.toString(leaseMillis)));
                return value instanceof Number && ((Number) value).longValue() == 1L;
            }
        });
    }

    public boolean deleteIfOwner(final String key, final String token) {
        return withJedis(new Operation<Boolean>() {
            public Boolean run(Jedis jedis) {
                Object value = jedis.eval(UNLOCK_SCRIPT, Collections.singletonList(key),
                        Collections.singletonList(token));
                return value instanceof Number && ((Number) value).longValue() == 1L;
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
