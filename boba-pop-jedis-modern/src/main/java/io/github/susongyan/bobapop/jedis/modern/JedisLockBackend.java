package io.github.susongyan.bobapop.jedis.modern;

import io.github.susongyan.bobapop.core.AbstractRedisLockBackend;
import io.github.susongyan.bobapop.core.RedisLockScripts;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.SetParams;

import java.util.Arrays;
import java.util.Collections;

/** Direct Jedis 4.x+ adapter for JedisPooled, JedisCluster, UnifiedJedis, or JedisPool. */
public final class JedisLockBackend extends AbstractRedisLockBackend {
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

    @Override
    protected boolean setNxPx(final String key, final String token, final long leaseMillis) {
        SetParams params = SetParams.setParams().nx().px(leaseMillis);
        if (pool == null) return "OK".equals(direct.set(key, token, params));
        Jedis jedis = pool.getResource();
        try {
            return "OK".equals(jedis.set(key, token, params));
        } finally {
            jedis.close();
        }
    }

    @Override
    protected Object evalScript(final RedisLockScripts.Script script,
                                final String key, final String... args) {
        if (pool == null) {
            return direct.eval(script.source(), Collections.singletonList(key), Arrays.asList(args));
        }
        Jedis jedis = pool.getResource();
        try {
            return jedis.eval(script.source(), Collections.singletonList(key), Arrays.asList(args));
        } finally {
            jedis.close();
        }
    }
}
