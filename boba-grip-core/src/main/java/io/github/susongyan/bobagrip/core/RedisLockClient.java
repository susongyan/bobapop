package io.github.susongyan.bobagrip.core;

import java.util.UUID;

/** Thread-safe factory. Every createLock call returns an independent lightweight lock object. */
public final class RedisLockClient implements AutoCloseable {
    public static final String DEFAULT_KEY_PREFIX = "redis-lock:";

    private final RedisLockBackend backend;
    private final String keyPrefix;
    private final WatchdogManager watchdogManager;

    public RedisLockClient(RedisLockBackend backend) {
        this(backend, DEFAULT_KEY_PREFIX, WatchdogConfig.defaults());
    }

    public RedisLockClient(RedisLockBackend backend, String keyPrefix) {
        this(backend, keyPrefix, WatchdogConfig.defaults());
    }

    public RedisLockClient(RedisLockBackend backend, String keyPrefix, WatchdogConfig watchdogConfig) {
        if (backend == null) {
            throw new IllegalArgumentException("backend must not be null");
        }
        if (keyPrefix == null || keyPrefix.trim().isEmpty()) {
            throw new IllegalArgumentException("keyPrefix must not be blank");
        }
        if (watchdogConfig == null) {
            throw new IllegalArgumentException("watchdogConfig must not be null");
        }
        this.backend = backend;
        this.keyPrefix = keyPrefix;
        this.watchdogManager = new WatchdogManager(watchdogConfig);
    }

    /**
     * Creates a new lock session with a new token.
     * Same-key lock objects compete with one another; reuse the returned object for reentrancy.
     */
    public RedisLock createLock(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        return new RedisLock(backend, keyPrefix + key, UUID.randomUUID().toString(), watchdogManager);
    }

    public void close() {
        watchdogManager.close();
    }
}
