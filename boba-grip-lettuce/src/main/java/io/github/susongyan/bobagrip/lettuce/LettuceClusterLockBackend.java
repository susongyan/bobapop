package io.github.susongyan.bobagrip.lettuce;

import io.github.susongyan.bobagrip.core.RedisLockBackend;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;

/**
 * Direct Lettuce 5.x+ synchronous Redis Cluster adapter.
 * The connection lifecycle remains caller-owned. Each operation uses one key.
 */
public final class LettuceClusterLockBackend implements RedisLockBackend {
    private static final String RENEW_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('pexpire', KEYS[1], ARGV[2]) end return 0";
    private static final String UNLOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) end return 0";

    private final RedisAdvancedClusterCommands<String, String> commands;

    public LettuceClusterLockBackend(StatefulRedisClusterConnection<String, String> connection) {
        this(requireConnection(connection).sync());
    }

    public LettuceClusterLockBackend(
            RedisAdvancedClusterCommands<String, String> commands) {
        if (commands == null) {
            throw new IllegalArgumentException("commands must not be null");
        }
        this.commands = commands;
    }

    public boolean acquire(String key, String token, long leaseMillis) {
        return "OK".equals(commands.set(key, token, SetArgs.Builder.nx().px(leaseMillis)));
    }

    public boolean renewIfOwner(String key, String token, long leaseMillis) {
        Number result = commands.eval(RENEW_SCRIPT, ScriptOutputType.INTEGER,
                new String[]{key}, token, Long.toString(leaseMillis));
        return result != null && result.longValue() == 1L;
    }

    public boolean deleteIfOwner(String key, String token) {
        Number result = commands.eval(UNLOCK_SCRIPT, ScriptOutputType.INTEGER,
                new String[]{key}, token);
        return result != null && result.longValue() == 1L;
    }

    private static StatefulRedisClusterConnection<String, String> requireConnection(
            StatefulRedisClusterConnection<String, String> connection) {
        if (connection == null) {
            throw new IllegalArgumentException("connection must not be null");
        }
        return connection;
    }
}
