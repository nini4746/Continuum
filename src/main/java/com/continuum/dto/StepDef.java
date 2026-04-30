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
        List<String> dependsOn
) {
    public StepDef {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("step id required");
        if (type == null || type.isBlank()) throw new IllegalArgumentException("step type required");
        if (retry == null) retry = RetryPolicy.none();
        if (onFailure == null) onFailure = OnFailure.ABORT;
        if (inputs == null) inputs = Map.of();
        if (compensateInputs == null) compensateInputs = Map.of();
        if (dependsOn == null) dependsOn = List.of();
        else dependsOn = List.copyOf(dependsOn);
    }

    /** Backwards-compatible constructor — defaults dependsOn to empty (sequential). */
    public StepDef(String id, String type, Map<String, Object> inputs, RetryPolicy retry,
                   OnFailure onFailure, String compensateType, Map<String, Object> compensateInputs) {
        this(id, type, inputs, retry, onFailure, compensateType, compensateInputs, List.of());
    }
}
