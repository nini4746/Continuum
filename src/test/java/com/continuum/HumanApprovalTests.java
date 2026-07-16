package com.continuum;

import com.continuum.domain.Execution;
import com.continuum.domain.ExecutionStatus;
import com.continuum.dto.*;
import com.continuum.engine.WorkflowEngine;
import com.continuum.handler.HandlerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HUMAN_APPROVAL_STEP end to end (spec §3.1.1): the execution parks in WAITING at
 * the gate, resumes on approve, and fails on reject. Drives the real engine + DB.
 */
@SpringBootTest
@DirtiesContext(classMode = ClassMode.BEFORE_EACH_TEST_METHOD)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:cont_${random.uuid};DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class HumanApprovalTests {

    @Autowired private WorkflowEngine engine;
    @Autowired private HandlerRegistry handlers;

    @BeforeEach
    void reset() { handlers.resetCounters(); }

    private void registerGated() {
        StepDef before = new StepDef("before", "count", Map.of("key", "BEFORE"), RetryPolicy.none(), OnFailure.ABORT, null, Map.of());
        StepDef gate = new StepDef("gate", null, Map.of(), RetryPolicy.none(), null, null, Map.of(),
                List.of(), StepKind.HUMAN_APPROVAL, null);
        StepDef after = new StepDef("after", "count", Map.of("key", "AFTER"), RetryPolicy.none(), OnFailure.ABORT, null, Map.of());
        engine.register(new WorkflowDef("gated", "gated", 1, List.of(before, gate, after), List.of(), OnFailure.ABORT));
    }

    @Test
    void execution_parks_in_waiting_at_the_gate() {
        registerGated();
        Execution e = engine.start("gated");
        ExecutionStatus s = engine.run(e.getId());
        assertEquals(ExecutionStatus.WAITING, s);
        assertEquals(1, handlers.counter("BEFORE"));
        assertEquals(0, handlers.counter("AFTER"));   // blocked at the gate
    }

    @Test
    void approve_resumes_and_completes() {
        registerGated();
        Execution e = engine.start("gated");
        engine.run(e.getId());
        ExecutionStatus s = engine.approve(e.getId(), "alice");
        assertEquals(ExecutionStatus.COMPLETED, s);
        assertEquals(1, handlers.counter("AFTER"));
    }

    @Test
    void reject_fails_the_execution_and_skips_the_rest() {
        registerGated();
        Execution e = engine.start("gated");
        engine.run(e.getId());
        ExecutionStatus s = engine.reject(e.getId(), "bob", "not allowed");
        assertEquals(ExecutionStatus.FAILED, s);
        assertEquals(0, handlers.counter("AFTER"));
    }

    @Test
    void approving_a_non_waiting_execution_is_rejected() {
        registerGated();
        Execution e = engine.start("gated");   // CREATED, not waiting
        assertThrows(IllegalStateException.class, () -> engine.approve(e.getId(), "alice"));
    }
}
