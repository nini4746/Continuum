package com.continuum.domain;

/** Internal event bus event kinds (spec §3.4.1). */
public enum EventType {
    STEP_COMPLETED, STEP_FAILED, STEP_TIMEOUT
}
