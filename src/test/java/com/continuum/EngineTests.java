package com.continuum;

import com.continuum.domain.Execution;
import com.continuum.domain.ExecutionStatus;
import com.continuum.dto.OnFailure;
import com.continuum.dto.RetryPolicy;
import com.continuum.dto.StepDef;
import com.continuum.dto.WorkflowDef;
import com.continuum.engine.WorkflowEngine;
import com.continuum.handler.HandlerRegistry;
import com.continuum.repo.ExecutionRepo;
import com.continuum.repo.StepRecordRepo;
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

@SpringBootTest
@DirtiesContext(classMode = ClassMode.BEFORE_EACH_TEST_METHOD)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:cont_${random.uuid};DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "continuum.errors.expose-raw-messages=false"
})
class EngineTests {

    @Autowired private WorkflowEngine engine;
    @Autowired private HandlerRegistry handlers;
    @Autowired private ExecutionRepo executions;
    @Autowired private StepRecordRepo stepRecords;

    @BeforeEach
    void resetSideEffects() {
        handlers.resetCounters();
    }

    private StepDef step(String id, String type, Map<String, Object> inputs, RetryPolicy retry, OnFailure onFail) {
        return new StepDef(id, type, inputs, retry, onFail, null, null);
    }

    private StepDef stepWithCompensate(String id, String type, Map<String, Object> in,
                                       String compType, Map<String, Object> compIn) {
        return new StepDef(id, type, in, RetryPolicy.none(), OnFailure.COMPENSATE, compType, compIn);
    }

    @Test
    void happy_path_completes_all_steps() {
        engine.register(new WorkflowDef("hp", List.of(
                step("s1", "count", Map.of("key", "hp"), RetryPolicy.none(), OnFailure.ABORT),
                step("s2", "count", Map.of("key", "hp"), RetryPolicy.none(), OnFailure.ABORT)
        )));
        Execution e = engine.start("hp");
        assertEquals(ExecutionStatus.COMPLETED, engine.run(e.getId()));
        assertEquals(2, handlers.counter("hp"));
    }

    @Test
    void retry_eventually_succeeds() {
        handlers.primeFlaky("rs", 2);
        engine.register(new WorkflowDef("rs", List.of(
                step("s1", "flaky", Map.of("key", "rs"),
                        new RetryPolicy(3, 0, 1.0), OnFailure.ABORT)
        )));
        Execution e = engine.start("rs");
        assertEquals(ExecutionStatus.COMPLETED, engine.run(e.getId()));
        long succeeded = stepRecords.findByExecutionIdOrderByCreatedAtAsc(e.getId()).stream()
                .filter(r -> r.getStepId().equals("s1") && r.getStatus().name().equals("SUCCEEDED")).count();
        assertEquals(1, succeeded);
    }

    @Test
    void retry_exhausted_triggers_compensation_in_reverse() {
        engine.register(new WorkflowDef("rc", List.of(
                stepWithCompensate("s1", "count", Map.of("key", "compA"), "count", Map.of("key", "rc-comp")),
                stepWithCompensate("s2", "count", Map.of("key", "compB"), "count", Map.of("key", "rc-comp")),
                step("s3", "fail", Map.of("reason", "boom"),
                        new RetryPolicy(2, 0, 1.0), OnFailure.COMPENSATE)
        )));
        Execution e = engine.start("rc");
        assertEquals(ExecutionStatus.COMPENSATED, engine.run(e.getId()));
        assertEquals(2, handlers.counter("rc-comp"));

        var compRecords = stepRecords.findByExecutionIdOrderByCreatedAtAsc(e.getId()).stream()
                .filter(r -> r.getStepId().endsWith("#compensate")).toList();
        assertEquals("s2#compensate", compRecords.get(0).getStepId());
        assertEquals("s1#compensate", compRecords.get(1).getStepId());
    }

    @Test
    void crash_mid_execution_resumes_from_last_completed_step() {
        engine.register(new WorkflowDef("cr", List.of(
                step("s1", "count", Map.of("key", "cr"), RetryPolicy.none(), OnFailure.ABORT),
                step("s2", "count", Map.of("key", "cr"), RetryPolicy.none(), OnFailure.ABORT),
                step("s3", "count", Map.of("key", "cr"), RetryPolicy.none(), OnFailure.ABORT)
        )));
        Execution e = engine.start("cr");

        engine.step(e.getId());
        Execution mid = executions.findById(e.getId()).orElseThrow();
        assertEquals(1, mid.getCursor());
        assertEquals(1, handlers.counter("cr"));

        // simulate crash + restart by calling resumeAll with execution still RUNNING
        engine.resumeAll();

        Execution after = executions.findById(e.getId()).orElseThrow();
        assertEquals(ExecutionStatus.COMPLETED, after.getStatus());
        assertEquals(3, handlers.counter("cr"));
    }

    @Test
    void failed_step_records_sanitized_error_class_only() {
        engine.register(new WorkflowDef("err", List.of(
                step("s1", "fail", Map.of("reason", "secret-internal-detail"),
                        new RetryPolicy(1, 0, 1.0), OnFailure.ABORT)
        )));
        Execution e = engine.start("err");
        engine.run(e.getId());
        var failedRecords = stepRecords.findByExecutionIdOrderByCreatedAtAsc(e.getId()).stream()
                .filter(r -> r.getStatus().name().equals("FAILED")).toList();
        assertFalse(failedRecords.isEmpty());
        String msg = failedRecords.get(0).getMessage();
        assertNotNull(msg);
        assertFalse(msg.contains("secret-internal-detail"),
                "sanitized error must not leak internal message: " + msg);
    }

    @Test
    void async_run_completes_independently() throws Exception {
        engine.register(new WorkflowDef("async", List.of(
                step("s1", "count", Map.of("key", "async"), RetryPolicy.none(), OnFailure.ABORT),
                step("s2", "count", Map.of("key", "async"), RetryPolicy.none(), OnFailure.ABORT)
        )));
        Execution e = engine.start("async");
        ExecutionStatus result = engine.runAsync(e.getId()).get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(ExecutionStatus.COMPLETED, result);
        assertEquals(2, handlers.counter("async"));
    }

    @Test
    void duplicate_attempt_record_is_rejected() {
        engine.register(new WorkflowDef("idem", List.of(
                step("only", "count", Map.of("key", "idem"), RetryPolicy.none(), OnFailure.ABORT)
        )));
        Execution e = engine.start("idem");
        engine.run(e.getId());
        boolean rejected = engine.recordDuplicateForReplayCheck(e.getId(), "only", 1);
        assertTrue(rejected, "재실행은 unique 제약으로 거부되어야 한다");
        assertEquals(1, handlers.counter("idem"));
    }
}
