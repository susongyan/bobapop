package io.github.susongyan.bobapop.jedis.legacy;

import io.github.susongyan.bobapop.core.AbstractRedisLockBackend;
import io.github.susongyan.bobapop.core.RedisLockScripts;
import redis.clients.jedis.JedisCluster;

import java.util.Arrays;
import java.util.Collections;

/**
 * Direct Jedis 2.x/3.x Redis Cluster adapter.
 * The caller owns the cluster client and must close it during application shutdown.
 * All operations use one key, so Jedis routes them to the slot owner.
 */
public final class JedisLegacyClusterLockBackend extends AbstractRedisLockBackend {
    private final JedisCluster cluster;

    public JedisLegacyClusterLockBackend(JedisCluster cluster) {
        if (cluster == null) {
            throw new IllegalArgumentException("cluster must not be null");
        }
        this.cluster = cluster;
    }

    @Override
    protected boolean setNxPx(String key, String token, long leaseMillis) {
        return "OK".equals(cluster.set(key, token, "NX", "PX", leaseMillis));
    }

    @Override
    protected Object evalScript(RedisLockScripts.Script script, String key, String... args) {
        return cluster.eval(script.source(), Collections.singletonList(key), Arrays.asList(args));
    }
}
