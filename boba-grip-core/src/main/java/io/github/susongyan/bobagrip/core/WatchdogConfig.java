package io.github.susongyan.bobagrip.core;

import java.util.concurrent.TimeUnit;

/** Configuration for the optional lock watchdog. */
public final class WatchdogConfig {
    private static final long DEFAULT_LEASE_MILLIS = 30_000L;
    private static final long DEFAULT_RENEW_INTERVAL_MILLIS = 10_000L;

    private final long leaseMillis;
    private final long renewIntervalMillis;
    private final long maxDurationMillis;

    public WatchdogConfig(long leaseTime, TimeUnit leaseUnit,
                          long renewInterval, TimeUnit renewUnit) {
        this(leaseTime, leaseUnit, renewInterval, renewUnit, 0L, TimeUnit.MILLISECONDS);
    }

    /** maxDuration of zero means no automatic duration limit. */
    public WatchdogConfig(long leaseTime, TimeUnit leaseUnit,
                          long renewInterval, TimeUnit renewUnit,
                          long maxDuration, TimeUnit maxDurationUnit) {
        this.leaseMillis = positiveMillis(leaseTime, leaseUnit, "leaseTime");
        this.renewIntervalMillis = positiveMillis(renewInterval, renewUnit, "renewInterval");
        if (renewIntervalMillis > leaseMillis) {
            throw new IllegalArgumentException("renewInterval must not exceed leaseTime");
        }
        if (maxDuration < 0L) {
            throw new IllegalArgumentException("maxDuration must not be negative");
        }
        if (maxDuration == 0L) {
            this.maxDurationMillis = 0L;
        } else {
            this.maxDurationMillis = positiveMillis(maxDuration, maxDurationUnit, "maxDuration");
        }
    }

    public static WatchdogConfig defaults() {
        return new WatchdogConfig(DEFAULT_LEASE_MILLIS, TimeUnit.MILLISECONDS,
                DEFAULT_RENEW_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    public long getLeaseMillis() {
        return leaseMillis;
    }

    public long getRenewIntervalMillis() {
        return renewIntervalMillis;
    }

    public long getMaxDurationMillis() {
        return maxDurationMillis;
    }

    private static long positiveMillis(long value, TimeUnit unit, String name) {
        if (unit == null) {
            throw new IllegalArgumentException(name + " unit must not be null");
        }
        if (value <= 0L) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        long millis = unit.toMillis(value);
        if (millis <= 0L) {
            throw new IllegalArgumentException(name + " is smaller than one millisecond");
        }
        return millis;
    }
}
