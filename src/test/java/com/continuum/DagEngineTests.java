package com.continuum;

import com.continuum.domain.Execution;
import com.continuum.domain.ExecutionStatus;
import com.continuum.dto.OnFailure;
import com.continuum.dto.RetryPolicy;
import com.continuum.dto.StepDef;
import com.continuum.dto.WorkflowDef;
import com.continuum.engine.WorkflowEngine;
import com.continuum.handler.HandlerRegistry;
import com.continuum.repo.StepRecordRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:cont_dag_${random.uuid};DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "continuum.errors.expose-raw-messages=false"
})
class DagEngineTests {

    @Autowired private WorkflowEngine engine;
    @Autowired private HandlerRegistry handlers;
    @Autowired private StepRecordRepo stepRecords;

    @BeforeEach
    void reset() {
        handlers.resetCounters();
    }

    private StepDef step(String id, String type, Map<String, Object> in,
                        RetryPolicy retry, OnFailure of, List<String> deps) {
        return new StepDef(id, type, in, retry, of, null, null, deps);
    }

    @Test
    void rejectsCyclicDependencies() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new WorkflowDef("cyc", List.of(
                        step("a", "noop", Map.of(), RetryPolicy.none(), OnFailure.ABORT, List.of("b")),
                        step("b", "noop", Map.of(), RetryPolicy.none(), OnFailure.ABORT, List.of("a"))
                )));
        assertTrue(ex.getMessage().contains("cycle"));
    }

    @Test
    void rejectsUnknownDependency() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new WorkflowDef("uk", List.of(
                        step("a", "noop", Map.of(), RetryPolicy.none(), OnFailure.ABORT, List.of("ghost"))
                )));
        assertTrue(ex.getMessage().contains("ghost"));
    }

    @Test
    void diamondDagCompletesAllSteps() {
        // a -> b, c (parallel) -> d
        engine.register(new WorkflowDef("diamond", List.of(
                step("a", "count", Map.of("key", "diamond"), RetryPolicy.none(), OnFailure.ABORT, List.of()),
                step("b", "count", Map.of("key", "diamond"), RetryPolicy.none(), OnFailure.ABORT, List.of("a")),
                step("c", "count", Map.of("key", "diamond"), RetryPolicy.none(), OnFailure.ABORT, List.of("a")),
                step("d", "count", Map.of("key", "diamond"), RetryPolicy.none(), OnFailure.ABORT, List.of("b", "c"))
        )));
        Execution e = engine.start("diamond");
        ExecutionStatus s = engine.run(e.getId());
        assertEquals(ExecutionStatus.COMPLETED, s);
        assertEquals(4, handlers.counter("diamond"));
    }

    @Test
    void parallelBranchesRunIndependently() {
        // two independent steps with no deps must both run
        engine.register(new WorkflowDef("parallel", List.of(
                step("p1", "count", Map.of("key", "par"), RetryPolicy.none(), OnFailure.ABORT, List.of()),
                step("p2", "count", Map.of("key", "par"), RetryPolicy.none(), OnFailure.ABORT, List.of())
        )));
        Execution e = engine.start("parallel");
        assertEquals(ExecutionStatus.COMPLETED, engine.run(e.getId()));
        assertEquals(2, handlers.counter("par"));
    }

    @Test
    void dagAbortStopsScheduling() {
        engine.register(new WorkflowDef("dabort", List.of(
                step("a", "count", Map.of("key", "dab"), RetryPolicy.none(), OnFailure.ABORT, List.of()),
                step("b", "fail", Map.of("reason", "boom"),
                        new RetryPolicy(1, 0, 1.0), OnFailure.ABORT, List.of("a")),
                step("c", "count", Map.of("key", "dab"), RetryPolicy.none(), OnFailure.ABORT, List.of("b"))
        )));
        Execution e = engine.start("dabort");
        assertEquals(ExecutionStatus.FAILED, engine.run(e.getId()));
        // 'a' ran, 'c' must not have run because 'b' aborted
        assertEquals(1, handlers.counter("dab"));
    }

    @Test
    void dagCompensateUnwindsOnlySucceededSteps() {
        engine.register(new WorkflowDef("dcomp", List.of(
                new StepDef("a", "count", Map.of("key", "fwd"),
                        RetryPolicy.none(), OnFailure.ABORT, "count", Map.of("key", "rev"), List.of()),
                new StepDef("b", "count", Map.of("key", "fwd"),
                        RetryPolicy.none(), OnFailure.ABORT, "count", Map.of("key", "rev"), List.of("a")),
                new StepDef("c", "fail", Map.of("reason", "boom"),
                        new RetryPolicy(1, 0, 1.0), OnFailure.COMPENSATE, null, null, List.of("b"))
        )));
        Execution e = engine.start("dcomp");
        assertEquals(ExecutionStatus.COMPENSATED, engine.run(e.getId()));
        // a, b succeeded forward (count=2), then both compensated (rev count=2)
        assertEquals(2, handlers.counter("fwd"));
        assertEquals(2, handlers.counter("rev"));
        var compRecords = stepRecords.findByExecutionIdOrderByCreatedAtAsc(e.getId()).stream()
                .filter(r -> r.getStepId().endsWith("#compensate")).toList();
        // compensation runs in reverse topological order: b#compensate before a#compensate
        assertEquals("b#compensate", compRecords.get(0).getStepId());
        assertEquals("a#compensate", compRecords.get(1).getStepId());
    }

    @Test
    void sequentialWorkflowStillUsesSequentialPath() {
        // empty dependsOn must keep historical sequential semantics (no DAG branch)
        engine.register(new WorkflowDef("seq", List.of(
                step("s1", "count", Map.of("key", "seq"), RetryPolicy.none(), OnFailure.ABORT, List.of()),
                step("s2", "count", Map.of("key", "seq"), RetryPolicy.none(), OnFailure.ABORT, List.of())
        )));
        // hasDependencies()==false -> sequential engine, cursor increments
        Execution e = engine.start("seq");
        engine.step(e.getId());
        var midCursor = stepRecords.findByExecutionIdOrderByCreatedAtAsc(e.getId()).size();
        assertEquals(1, midCursor);
        engine.run(e.getId());
        assertEquals(2, handlers.counter("seq"));
    }
}
