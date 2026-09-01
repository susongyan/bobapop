package io.github.susongyan.bobapop.core;

/**
 * Template for client adapters that expose Redis primitives and script
 * execution. The lock protocol is kept in one place; adapters only translate
 * it to their client's connection and reply types.
 */
public abstract class AbstractRedisLockBackend implements RedisLockBackend {

    @Override
    public final boolean acquire(String key, String token, long leaseMillis) {
        return setNxPx(key, token, leaseMillis);
    }

    @Override
    public final boolean renewIfOwner(String key, String token, long leaseMillis) {
        return RedisLockScripts.isSuccess(evalScript(
                RedisLockScripts.RENEW_IF_OWNER_SCRIPT,
                key, token, Long.toString(leaseMillis)));
    }

    @Override
    public final boolean deleteIfOwner(String key, String token) {
        return RedisLockScripts.isSuccess(evalScript(
                RedisLockScripts.DELETE_IF_OWNER_SCRIPT,
                key, token));
    }

    /** Executes SET key token NX PX leaseMillis using the client API. */
    protected abstract boolean setNxPx(String key, String token, long leaseMillis);

    /** Executes one of the shared single-key scripts and returns its raw reply. */
    protected abstract Object evalScript(RedisLockScripts.Script script,
                                        String key, String... args);
}
