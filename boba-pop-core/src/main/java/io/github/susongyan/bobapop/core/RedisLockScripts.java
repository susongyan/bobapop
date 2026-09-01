package io.github.susongyan.bobapop.core;

/**
 * Minimal Lua scripts shared by all Redis client adapters.
 *
 * <p>Every script operates on exactly one key, which keeps it routable by
 * Redis Cluster. The owner token is always passed as an argument; scripts
 * never create or replace a token.</p>
 */
public final class RedisLockScripts {

    public static final String RENEW_IF_OWNER =
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('pexpire', KEYS[1], ARGV[2]) end return 0";

    public static final String DELETE_IF_OWNER =
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) end return 0";

    /** Script descriptor used by adapters to share the operation contract. */
    public static final Script RENEW_IF_OWNER_SCRIPT = new Script(RENEW_IF_OWNER);

    /** Script descriptor used by adapters to share the operation contract. */
    public static final Script DELETE_IF_OWNER_SCRIPT = new Script(DELETE_IF_OWNER);

    private RedisLockScripts() {
    }

    /** Returns true only for the integer success reply (1) used by the scripts. */
    public static boolean isSuccess(Object result) {
        return result instanceof Number && ((Number) result).longValue() == 1L;
    }

    public static final class Script {
        private final String source;

        private Script(String source) {
            this.source = source;
        }

        public String source() {
            return source;
        }
    }
}
