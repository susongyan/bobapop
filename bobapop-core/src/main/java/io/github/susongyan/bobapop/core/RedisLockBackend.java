package io.github.susongyan.bobapop.core;

/** Atomic Redis operations needed by the lock algorithm. */
public interface RedisLockBackend {

    boolean acquire(String key, String token, long leaseMillis);

    boolean renewIfOwner(String key, String token, long leaseMillis);

    boolean deleteIfOwner(String key, String token);
}
