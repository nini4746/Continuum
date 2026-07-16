package com.continuum.engine;

import com.continuum.domain.EventType;

/** Application event carried on the internal bus for async consumers (spec §3.4.1). */
public record StepEvent(Long executionId, String stepId, EventType type, String message) {}
