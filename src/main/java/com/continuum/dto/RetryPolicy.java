package com.continuum.dto;

public record RetryPolicy(int maxAttempts, long initialBackoffMs, double multiplier) {
    public static RetryPolicy none() {
        return new RetryPolicy(1, 0, 1.0);
    }

    public long backoffFor(int attempt) {
        long ms = initialBackoffMs;
        for (int i = 1; i < attempt; i++) ms = (long) (ms * multiplier);
        return ms;
    }
}
