package io.github.susongyan.bobagrip.core;

/** Local state of a lock session when watchdog mode is enabled. */
public enum LockState {
    HEALTHY,
    SUSPECT,
    LOST,
    RELEASED
}
