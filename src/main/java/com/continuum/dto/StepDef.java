package com.continuum.dto;

import java.util.List;
import java.util.Map;

public record StepDef(
        String id,
        String type,
        Map<String, Object> inputs,
        RetryPolicy retry,
        OnFailure onFailure,
        String compensateType,
        Map<String, Object> compensateInputs,
        List<String> dependsOn,
        StepKind kind,
        String condition
) {
    public StepDef {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("step id required");
        if (kind == null) kind = StepKind.SYNC;
        // A handler type is only mandatory for SYNC/ASYNC steps (checked in WorkflowDef).
        if ((kind == StepKind.SYNC || kind == StepKind.ASYNC)
                && (type == null || type.isBlank())) {
            throw new IllegalArgumentException("step type required");
        }
        if (retry == null) retry = RetryPolicy.none();
        // onFailure intentionally left nullable: null means "inherit workflow failure_policy".
        if (inputs == null) inputs = Map.of();
        if (compensateInputs == null) compensateInputs = Map.of();
        if (dependsOn == null) dependsOn = List.of();
        else dependsOn = List.copyOf(dependsOn);
    }

    /** Effective failure handling: the step's own policy, or the workflow default. */
    public OnFailure effectiveOnFailure(OnFailure workflowDefault) {
        if (onFailure != null) return onFailure;
        return workflowDefault != null ? workflowDefault : OnFailure.ABORT;
    }

    /** Backwards-compatible constructor (no dependsOn, SYNC kind, no condition). */
    public StepDef(String id, String type, Map<String, Object> inputs, RetryPolicy retry,
                   OnFailure onFailure, String compensateType, Map<String, Object> compensateInputs) {
        this(id, type, inputs, retry, onFailure, compensateType, compensateInputs, List.of(), StepKind.SYNC, null);
    }

    /** Backwards-compatible constructor with dependsOn (SYNC kind, no condition). */
    public StepDef(String id, String type, Map<String, Object> inputs, RetryPolicy retry,
                   OnFailure onFailure, String compensateType, Map<String, Object> compensateInputs,
                   List<String> dependsOn) {
        this(id, type, inputs, retry, onFailure, compensateType, compensateInputs, dependsOn, StepKind.SYNC, null);
    }
}
