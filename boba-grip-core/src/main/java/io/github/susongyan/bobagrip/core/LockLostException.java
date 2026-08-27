package io.github.susongyan.bobagrip.core;

public final class LockLostException extends IllegalStateException {
    public LockLostException(String key) {
        super("Redis lock ownership was lost: " + key);
    }
}
