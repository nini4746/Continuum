package com.continuum.dto;

/**
 * A declared edge in the workflow graph (spec §3.1.1 required element "transitions").
 * {@code when} is an optional guard used by CONDITIONAL steps: "true"/"false" pick the
 * branch taken after the condition evaluates; a null/blank guard is an unconditional edge.
 */
public record Transition(String from, String to, String when) {
    public Transition {
        if (from == null || from.isBlank()) throw new IllegalArgumentException("transition.from required");
        if (to == null || to.isBlank()) throw new IllegalArgumentException("transition.to required");
    }
}
