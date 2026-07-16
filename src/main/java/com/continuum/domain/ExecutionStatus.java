package com.continuum.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Execution lifecycle states (spec §3.2.1). Transitions are explicit: only the
 * edges declared in {@link #ALLOWED} are permitted via {@link Execution#markStatus}.
 *
 * COMPENSATED is a SAGA extension (not in the spec's six-state list) reached only
 * from FAILED when a step requested compensation.
 */
public enum ExecutionStatus {
    CREATED, RUNNING, WAITING, COMPLETED, FAILED, CANCELED, COMPENSATED;

    private static final Map<ExecutionStatus, Set<ExecutionStatus>> ALLOWED = Map.of(
            CREATED,     EnumSet.of(RUNNING, CANCELED),
            RUNNING,     EnumSet.of(WAITING, COMPLETED, FAILED, CANCELED),
            WAITING,     EnumSet.of(RUNNING, CANCELED),
            FAILED,      EnumSet.of(COMPENSATED, CANCELED),
            COMPLETED,   EnumSet.noneOf(ExecutionStatus.class),
            CANCELED,    EnumSet.noneOf(ExecutionStatus.class),
            COMPENSATED, EnumSet.noneOf(ExecutionStatus.class)
    );

    /** A terminal state has no outgoing transitions. */
    public boolean isTerminal() {
        return ALLOWED.getOrDefault(this, Set.of()).isEmpty();
    }

    public boolean canTransitionTo(ExecutionStatus next) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(next);
    }
}
