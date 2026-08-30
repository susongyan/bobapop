package io.github.susongyan.bobapop.core;

public final class LockLostException extends IllegalStateException {
    public LockLostException(String key) {
        super("Redis lock ownership was lost: " + key);
    }
}
