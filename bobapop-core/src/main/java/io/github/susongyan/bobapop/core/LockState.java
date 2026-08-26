package io.github.susongyan.bobapop.core;

/** Local state of a lock session when watchdog mode is enabled. */
public enum LockState {
    HEALTHY,
    SUSPECT,
    LOST,
    RELEASED
}
