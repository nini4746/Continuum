package com.continuum.dto;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Workflow definition DSL (spec §3.1.1). Required elements: workflow_id, version,
 * steps, transitions, failure_policy. All but {@code steps} default so that legacy
 * {@code {"name","steps"}} definitions still deserialize and validate.
 */
public record WorkflowDef(
        String name,
        String workflowId,
        Integer version,
        List<StepDef> steps,
        List<Transition> transitions,
        OnFailure failurePolicy
) {
    public WorkflowDef {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("workflow name required");
        if (steps == null || steps.isEmpty()) throw new IllegalArgumentException("workflow needs steps");
        if (workflowId == null || workflowId.isBlank()) workflowId = name;
        if (version == null) version = 1;
        if (transitions == null) transitions = List.of();
        else transitions = List.copyOf(transitions);
        if (failurePolicy == null) failurePolicy = OnFailure.ABORT;

        long distinct = steps.stream().map(StepDef::id).distinct().count();
        if (distinct != steps.size()) throw new IllegalArgumentException("step ids must be unique");
        validateStepKinds(steps);
        validateDependencies(steps);
        validateTransitions(steps, transitions);
    }

    /** Backwards-compatible constructor for the original {name, steps} shape. */
    public WorkflowDef(String name, List<StepDef> steps) {
        this(name, name, 1, steps, List.of(), OnFailure.ABORT);
    }

    /** Returns true if any step declares a dependency. Sequential-only definitions remain backwards-compatible. */
    public boolean hasDependencies() {
        return steps.stream().anyMatch(s -> !s.dependsOn().isEmpty());
    }

    /** Per-kind required-field validation (spec §3.1.1 "Step 타입별 필수 필드 검증"). */
    private static void validateStepKinds(List<StepDef> steps) {
        for (StepDef s : steps) {
            switch (s.kind()) {
                case SYNC, ASYNC -> {
                    if (s.type() == null || s.type().isBlank())
                        throw new IllegalArgumentException("step " + s.id() + " (" + s.kind() + ") requires a handler type");
                }
                case CONDITIONAL -> {
                    if (s.condition() == null || s.condition().isBlank())
                        throw new IllegalArgumentException("CONDITIONAL step " + s.id() + " requires a condition");
                }
                case HUMAN_APPROVAL, PARALLEL -> { /* no handler/condition required */ }
            }
        }
    }

    private static void validateTransitions(List<StepDef> steps, List<Transition> transitions) {
        Set<String> known = new HashSet<>();
        for (StepDef s : steps) known.add(s.id());
        for (Transition t : transitions) {
            if (!known.contains(t.from()))
                throw new IllegalArgumentException("transition references unknown step: " + t.from());
            if (!known.contains(t.to()))
                throw new IllegalArgumentException("transition references unknown step: " + t.to());
        }
    }

    private static void validateDependencies(List<StepDef> steps) {
        Set<String> known = new HashSet<>();
        for (StepDef s : steps) known.add(s.id());
        for (StepDef s : steps) {
            for (String d : s.dependsOn()) {
                if (!known.contains(d)) {
                    throw new IllegalArgumentException("step " + s.id() + " depends on unknown step: " + d);
                }
                if (d.equals(s.id())) {
                    throw new IllegalArgumentException("step " + s.id() + " cannot depend on itself");
                }
            }
        }
        // detect cycles via topological sort
        java.util.Map<String, java.util.List<String>> adj = new java.util.HashMap<>();
        java.util.Map<String, Integer> indeg = new java.util.HashMap<>();
        for (StepDef s : steps) {
            indeg.putIfAbsent(s.id(), 0);
            for (String d : s.dependsOn()) {
                adj.computeIfAbsent(d, k -> new java.util.ArrayList<>()).add(s.id());
                indeg.merge(s.id(), 1, Integer::sum);
            }
        }
        java.util.Deque<String> queue = new java.util.ArrayDeque<>();
        for (var e : indeg.entrySet()) if (e.getValue() == 0) queue.add(e.getKey());
        int processed = 0;
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            processed++;
            for (String nxt : adj.getOrDefault(cur, List.of())) {
                indeg.merge(nxt, -1, Integer::sum);
                if (indeg.get(nxt) == 0) queue.add(nxt);
            }
        }
        if (processed != steps.size()) {
            throw new IllegalArgumentException("workflow has dependency cycle");
        }
    }
}
