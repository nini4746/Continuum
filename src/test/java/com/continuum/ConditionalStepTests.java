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
 * CONDITIONAL_STEP + transitions branching, driven end to end through the real
 * engine: the taken branch runs, the other branch does NOT (spec §3.1.1).
 */
@SpringBootTest
@DirtiesContext(classMode = ClassMode.BEFORE_EACH_TEST_METHOD)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:cont_${random.uuid};DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ConditionalStepTests {

    @Autowired private WorkflowEngine engine;
    @Autowired private HandlerRegistry handlers;

    @BeforeEach
    void reset() { handlers.resetCounters(); }

    private void registerBranchingWorkflow() {
        StepDef check = new StepDef("check", null, Map.of(), RetryPolicy.none(), null, null, Map.of(),
                List.of(), StepKind.CONDITIONAL, "amount > 100");
        StepDef big = new StepDef("big", "count", Map.of("key", "BIG"), RetryPolicy.none(),
                OnFailure.ABORT, null, Map.of());
        StepDef small = new StepDef("small", "count", Map.of("key", "SMALL"), RetryPolicy.none(),
                OnFailure.ABORT, null, Map.of());
        WorkflowDef def = new WorkflowDef("branch", "branch", 1, List.of(check, big, small),
                List.of(new Transition("check", "big", "true"),
                        new Transition("check", "small", "false")),
                OnFailure.ABORT);
        engine.register(def);
    }

    @Test
    void true_branch_runs_only_the_big_step() {
        registerBranchingWorkflow();
        Execution e = engine.start("branch", Map.of("amount", 150));
        ExecutionStatus s = engine.run(e.getId());
        assertEquals(ExecutionStatus.COMPLETED, s);
        assertEquals(1, handlers.counter("BIG"));
        assertEquals(0, handlers.counter("SMALL"));   // other branch skipped
    }

    @Test
    void false_branch_runs_only_the_small_step() {
        registerBranchingWorkflow();
        Execution e = engine.start("branch", Map.of("amount", 50));
        ExecutionStatus s = engine.run(e.getId());
        assertEquals(ExecutionStatus.COMPLETED, s);
        assertEquals(0, handlers.counter("BIG"));
        assertEquals(1, handlers.counter("SMALL"));
    }
}
