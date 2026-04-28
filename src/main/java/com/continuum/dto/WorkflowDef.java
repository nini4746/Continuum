package com.continuum.dto;

import java.util.List;

public record WorkflowDef(String name, List<StepDef> steps) {
    public WorkflowDef {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("workflow name required");
        if (steps == null || steps.isEmpty()) throw new IllegalArgumentException("workflow needs steps");
        long distinct = steps.stream().map(StepDef::id).distinct().count();
        if (distinct != steps.size()) throw new IllegalArgumentException("step ids must be unique");
    }
}
