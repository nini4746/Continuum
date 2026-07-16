package com.continuum.engine;

/** Thrown when a policy denies a requested action (spec §3.5). */
public class PolicyDeniedException extends RuntimeException {
    public PolicyDeniedException(String message) {
        super(message);
    }
}
