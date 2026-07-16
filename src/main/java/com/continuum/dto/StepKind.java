package com.continuum.dto;

/**
 * Structural step category (spec §3.1.1 "Step 타입"), orthogonal to the handler
 * {@code type} that names the business action. Defaults to SYNC for backward
 * compatibility with definitions that only set a handler type.
 *
 * - SYNC / ASYNC : run the handler named by {@link StepDef#type()}
 * - CONDITIONAL  : evaluate {@link StepDef#condition()} to branch via transitions
 * - PARALLEL     : structural marker; concurrency is expressed via dependsOn/DAG
 * - HUMAN_APPROVAL : park the execution in WAITING until an approval decision
 */
public enum StepKind {
    SYNC, ASYNC, CONDITIONAL, PARALLEL, HUMAN_APPROVAL
}
