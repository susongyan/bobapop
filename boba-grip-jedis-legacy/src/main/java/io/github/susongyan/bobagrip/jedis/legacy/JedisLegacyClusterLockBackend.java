package io.github.susongyan.bobagrip.jedis.legacy;

import io.github.susongyan.bobagrip.core.RedisLockBackend;
import redis.clients.jedis.JedisCluster;

import java.util.Arrays;
import java.util.Collections;

/**
 * Direct Jedis 2.x/3.x Redis Cluster adapter.
 * The caller owns the cluster client and must close it during application shutdown.
 * All operations use one key, so Jedis routes them to the slot owner.
 */
public final class JedisLegacyClusterLockBackend implements RedisLockBackend {
    private static final String RENEW_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('pexpire', KEYS[1], ARGV[2]) end return 0";
    private static final String UNLOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) end return 0";

    private final JedisCluster cluster;

    public JedisLegacyClusterLockBackend(JedisCluster cluster) {
        if (cluster == null) {
            throw new IllegalArgumentException("cluster must not be null");
        }
        this.cluster = cluster;
    }

    public boolean acquire(String key, String token, long leaseMillis) {
        return "OK".equals(cluster.set(key, token, "NX", "PX", leaseMillis));
    }

    public boolean renewIfOwner(String key, String token, long leaseMillis) {
        Object result = cluster.eval(RENEW_SCRIPT, Collections.singletonList(key),
                Arrays.asList(token, Long.toString(leaseMillis)));
        return result instanceof Number && ((Number) result).longValue() == 1L;
    }

    public boolean deleteIfOwner(String key, String token) {
        Object result = cluster.eval(UNLOCK_SCRIPT, Collections.singletonList(key),
                Collections.singletonList(token));
        return result instanceof Number && ((Number) result).longValue() == 1L;
    }
}
