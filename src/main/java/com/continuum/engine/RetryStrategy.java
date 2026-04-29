package com.continuum.engine;

import com.continuum.dto.RetryPolicy;

/**
 * Pluggable retry strategy. Decides how long to wait between attempts and whether
 * to attempt again. The default {@link ExponentialBackoffRetry} reads from RetryPolicy
 * (maxAttempts, initialBackoffMs, multiplier) but custom strategies can implement:
 *  - jitter (full/equal jitter to avoid thundering herd)
 *  - circuit-breaker integration
 *  - per-step-id policy lookup
 *
 * Invariants:
 *  - shouldRetry(policy, attempt, lastError) is pure (no side effects)
 *  - backoffMillis returns >= 0
 *  - implementations must be thread-safe (called from async workers)
 */
public interface RetryStrategy {

    String name();

    boolean shouldRetry(RetryPolicy policy, int attemptNumber, Throwable lastError);

    long backoffMillis(RetryPolicy policy, int nextAttemptNumber);
}
