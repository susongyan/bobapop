package io.github.susongyan.bobapop.jedis.modern;

import io.github.susongyan.bobapop.core.RedisLockBackend;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.SetParams;

import java.util.Arrays;
import java.util.Collections;

/** Direct Jedis 4.x+ adapter for JedisPooled, JedisCluster, UnifiedJedis, or JedisPool. */
public final class JedisLockBackend implements RedisLockBackend {
    private static final String RENEW_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('pexpire', KEYS[1], ARGV[2]) end return 0";
    private static final String UNLOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) end return 0";

    private final UnifiedJedis direct;
    private final JedisPool pool;

    /** Accepts JedisPooled, JedisCluster, or another caller-owned UnifiedJedis implementation. */
    public JedisLockBackend(UnifiedJedis direct) {
        if (direct == null) throw new IllegalArgumentException("direct must not be null");
        this.direct = direct;
        this.pool = null;
    }

    public JedisLockBackend(JedisPool pool) {
        if (pool == null) throw new IllegalArgumentException("pool must not be null");
        this.direct = null;
        this.pool = pool;
    }

    public boolean acquire(final String key, final String token, final long leaseMillis) {
        SetParams params = SetParams.setParams().nx().px(leaseMillis);
        if (pool == null) return "OK".equals(direct.set(key, token, params));
        Jedis jedis = pool.getResource();
        try {
            return "OK".equals(jedis.set(key, token, params));
        } finally {
            jedis.close();
        }
    }

    public boolean renewIfOwner(final String key, final String token, final long leaseMillis) {
        Object value;
        if (pool == null) {
            value = direct.eval(RENEW_SCRIPT, Collections.singletonList(key),
                    Arrays.asList(token, Long.toString(leaseMillis)));
        } else {
            Jedis jedis = pool.getResource();
            try {
                value = jedis.eval(RENEW_SCRIPT, Collections.singletonList(key),
                        Arrays.asList(token, Long.toString(leaseMillis)));
            } finally {
                jedis.close();
            }
        }
        return value instanceof Number && ((Number) value).longValue() == 1L;
    }

    public boolean deleteIfOwner(final String key, final String token) {
        Object value;
        if (pool == null) {
            value = direct.eval(UNLOCK_SCRIPT, Collections.singletonList(key),
                    Collections.singletonList(token));
        } else {
            Jedis jedis = pool.getResource();
            try {
                value = jedis.eval(UNLOCK_SCRIPT, Collections.singletonList(key),
                        Collections.singletonList(token));
            } finally {
                jedis.close();
            }
        }
        return value instanceof Number && ((Number) value).longValue() == 1L;
    }
}
