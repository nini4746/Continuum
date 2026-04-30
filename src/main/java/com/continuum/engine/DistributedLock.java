package com.continuum.engine;

import java.util.concurrent.TimeUnit;

/**
 * Pluggable mutual-exclusion primitive scoped by a string key.
 *
 * Default implementation is in-process ({@link InProcessDistributedLock}); a
 * Redis/etcd/ZooKeeper-backed implementation can be plugged in by providing a
 * different bean of this type. The interface is intentionally narrow — only the
 * minimum required by the workflow engine — so backing stores stay simple.
 */
public interface DistributedLock {

    /**
     * @return non-null token if acquired, null if timed out. The returned token
     *         must be passed to {@link #unlock(String, Token)} to release.
     */
    Token tryLock(String key, long timeout, TimeUnit unit);

    /** Release a previously acquired lock. No-op if token does not match. */
    void unlock(String key, Token token);

    /** Opaque handle returned by tryLock; required for unlock to prevent stealing. */
    record Token(String value) {}
}
