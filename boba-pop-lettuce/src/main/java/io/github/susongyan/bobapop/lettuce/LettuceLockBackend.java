package io.github.susongyan.bobapop.lettuce;

import io.github.susongyan.bobapop.core.AbstractRedisLockBackend;
import io.github.susongyan.bobapop.core.RedisLockScripts;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

/** Direct Lettuce 5.x+ synchronous-command adapter. The connection lifecycle remains caller-owned. */
public final class LettuceLockBackend extends AbstractRedisLockBackend {
    private final RedisCommands<String, String> commands;

    public LettuceLockBackend(StatefulRedisConnection<String, String> connection) {
        this(requireConnection(connection).sync());
    }

    public LettuceLockBackend(RedisCommands<String, String> commands) {
        if (commands == null) throw new IllegalArgumentException("commands must not be null");
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

    private static StatefulRedisConnection<String, String> requireConnection(
            StatefulRedisConnection<String, String> connection) {
        if (connection == null) throw new IllegalArgumentException("connection must not be null");
        return connection;
    }
}
