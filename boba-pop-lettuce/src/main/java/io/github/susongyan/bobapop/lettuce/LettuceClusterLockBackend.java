package io.github.susongyan.bobapop.lettuce;

import io.github.susongyan.bobapop.core.AbstractRedisLockBackend;
import io.github.susongyan.bobapop.core.RedisLockScripts;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;

/**
 * Direct Lettuce 5.x+ synchronous Redis Cluster adapter.
 * The connection lifecycle remains caller-owned. Each operation uses one key.
 */
public final class LettuceClusterLockBackend extends AbstractRedisLockBackend {
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

    @Override
    protected boolean setNxPx(String key, String token, long leaseMillis) {
        return "OK".equals(commands.set(key, token, SetArgs.Builder.nx().px(leaseMillis)));
    }

    @Override
    protected Number evalScript(RedisLockScripts.Script script, String key, String... args) {
        return commands.eval(script.source(), ScriptOutputType.INTEGER, new String[]{key}, args);
    }

    private static StatefulRedisClusterConnection<String, String> requireConnection(
            StatefulRedisClusterConnection<String, String> connection) {
        if (connection == null) {
            throw new IllegalArgumentException("connection must not be null");
        }
        return connection;
    }
}
