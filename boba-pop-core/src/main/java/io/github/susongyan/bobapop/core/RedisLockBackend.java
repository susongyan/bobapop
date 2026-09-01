package io.github.susongyan.bobapop.core;

/**
 * Atomic Redis operations needed by the lock algorithm.
 *
 * <p>Client adapters can extend {@link AbstractRedisLockBackend} to reuse the
 * common SET/renew/delete protocol and only implement client-specific command
 * execution.</p>
 */
public interface RedisLockBackend {

    boolean acquire(String key, String token, long leaseMillis);

    boolean renewIfOwner(String key, String token, long leaseMillis);

    boolean deleteIfOwner(String key, String token);
}
