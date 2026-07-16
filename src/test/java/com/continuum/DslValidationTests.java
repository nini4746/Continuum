package com.continuum;

import com.continuum.dto.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DSL model + validation for the spec §3.1.1 required elements (workflow_id,
 * version, transitions, failure_policy) and per-kind field checks. Executes the
 * real record constructors (where validation lives).
 */
class DslValidationTests {

    private StepDef sync(String id) {
        return new StepDef(id, "noop", Map.of(), RetryPolicy.none(), OnFailure.ABORT, null, Map.of());
    }

    @Test
    void legacy_name_steps_shape_still_constructs_with_defaults() {
        WorkflowDef d = new WorkflowDef("legacy", List.of(sync("s1")));
        assertEquals("legacy", d.workflowId());   // defaults to name
        assertEquals(1, d.version());
        assertTrue(d.transitions().isEmpty());
        assertEquals(OnFailure.ABORT, d.failurePolicy());
    }

    @Test
    void full_dsl_fields_are_preserved() {
        WorkflowDef d = new WorkflowDef("wf", "wf-id", 3, List.of(sync("a"), sync("b")),
                List.of(new Transition("a", "b", null)), OnFailure.COMPENSATE);
        assertEquals("wf-id", d.workflowId());
        assertEquals(3, d.version());
        assertEquals(1, d.transitions().size());
        assertEquals(OnFailure.COMPENSATE, d.failurePolicy());
    }

    @Test
    void transition_to_unknown_step_rejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new WorkflowDef("wf", "wf", 1, List.of(sync("a")),
                        List.of(new Transition("a", "ghost", null)), OnFailure.ABORT));
    }

    @Test
    void conditional_step_requires_condition() {
        StepDef bad = new StepDef("c", null, Map.of(), RetryPolicy.none(), null, null, Map.of(),
                List.of(), StepKind.CONDITIONAL, null);
        assertThrows(IllegalArgumentException.class, () ->
                new WorkflowDef("wf", "wf", 1, List.of(bad), List.of(), OnFailure.ABORT));
    }

    @Test
    void conditional_step_with_condition_ok_and_needs_no_handler_type() {
        StepDef ok = new StepDef("c", null, Map.of(), RetryPolicy.none(), null, null, Map.of(),
                List.of(), StepKind.CONDITIONAL, "x > 1");
        assertDoesNotThrow(() -> new WorkflowDef("wf", "wf", 1, List.of(ok), List.of(), OnFailure.ABORT));
    }

    @Test
    void human_approval_step_needs_no_handler_type() {
        StepDef appr = new StepDef("gate", null, Map.of(), RetryPolicy.none(), null, null, Map.of(),
                List.of(), StepKind.HUMAN_APPROVAL, null);
        assertDoesNotThrow(() -> new WorkflowDef("wf", "wf", 1, List.of(appr), List.of(), OnFailure.ABORT));
    }

    @Test
    void sync_step_still_requires_handler_type() {
        assertThrows(IllegalArgumentException.class, () ->
                new StepDef("s", null, Map.of(), RetryPolicy.none(), null, null, Map.of(),
                        List.of(), StepKind.SYNC, null));
    }

    @Test
    void step_inherits_workflow_failure_policy_when_unset() {
        StepDef s = new StepDef("s", "noop", Map.of(), RetryPolicy.none(), null, null, Map.of());
        assertEquals(OnFailure.COMPENSATE, s.effectiveOnFailure(OnFailure.COMPENSATE));
        assertEquals(OnFailure.SKIP, new StepDef("t", "noop", Map.of(), RetryPolicy.none(), OnFailure.SKIP, null, Map.of())
                .effectiveOnFailure(OnFailure.COMPENSATE)); // explicit wins
    }
}
