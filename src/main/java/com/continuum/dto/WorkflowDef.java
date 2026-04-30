package com.continuum.dto;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record WorkflowDef(String name, List<StepDef> steps) {
    public WorkflowDef {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("workflow name required");
        if (steps == null || steps.isEmpty()) throw new IllegalArgumentException("workflow needs steps");
        long distinct = steps.stream().map(StepDef::id).distinct().count();
        if (distinct != steps.size()) throw new IllegalArgumentException("step ids must be unique");
        validateDependencies(steps);
    }

    /** Returns true if any step declares a dependency. Sequential-only definitions remain backwards-compatible. */
    public boolean hasDependencies() {
        return steps.stream().anyMatch(s -> !s.dependsOn().isEmpty());
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
