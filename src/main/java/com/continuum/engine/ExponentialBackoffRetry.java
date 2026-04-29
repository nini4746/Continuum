package com.continuum.engine;

import com.continuum.dto.RetryPolicy;
import org.springframework.stereotype.Component;

@Component
public class ExponentialBackoffRetry implements RetryStrategy {

    @Override public String name() { return "exponential-backoff"; }

    @Override
    public boolean shouldRetry(RetryPolicy policy, int attemptNumber, Throwable lastError) {
        if (attemptNumber >= Math.max(1, policy.maxAttempts())) return false;
        // policy holds maxAttempts; lastError is exposed for custom strategies that
        // may want to distinguish transient (retry) from permanent (give up) failures.
        return true;
    }

    @Override
    public long backoffMillis(RetryPolicy policy, int nextAttemptNumber) {
        return Math.max(0, policy.backoffFor(nextAttemptNumber));
    }
}
