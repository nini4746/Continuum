package com.continuum;

import com.continuum.domain.Execution;
import com.continuum.domain.ExecutionStatus;
import com.continuum.dto.*;
import com.continuum.engine.WorkflowEngine;
import com.continuum.handler.HandlerRegistry;
import com.continuum.repo.AuditLogRepo;
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

/** Admin / operations endpoints (spec §3.7), driven through the real engine + DB. */
@SpringBootTest
@DirtiesContext(classMode = ClassMode.BEFORE_EACH_TEST_METHOD)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:cont_${random.uuid};DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AdminTests {

    @Autowired private WorkflowEngine engine;
    @Autowired private HandlerRegistry handlers;
    @Autowired private AuditLogRepo audit;

    @BeforeEach
    void reset() { handlers.resetCounters(); }

    private StepDef count(String id, String key) {
        return new StepDef(id, "count", Map.of("key", key), RetryPolicy.none(), OnFailure.ABORT, null, Map.of());
    }

    @Test
    void cancel_force_stops_a_waiting_execution() {
        StepDef gate = new StepDef("gate", null, Map.of(), RetryPolicy.none(), null, null, Map.of(),
                List.of(), StepKind.HUMAN_APPROVAL, null);
        engine.register(new WorkflowDef("adm", "adm", 1, List.of(gate), List.of(), OnFailure.ABORT));
        Execution e = engine.start("adm");
        engine.run(e.getId());   // WAITING
        assertEquals(ExecutionStatus.CANCELED, engine.cancel(e.getId(), "ops"));
    }

    @Test
    void cancel_rejects_a_terminal_execution() {
        engine.register(new WorkflowDef("adm2", List.of(count("s", "K"))));
        Execution e = engine.start("adm2");
        engine.run(e.getId());   // COMPLETED
        assertThrows(IllegalStateException.class, () -> engine.cancel(e.getId(), "ops"));
    }

    @Test
    void force_status_bypasses_rules_and_is_audited_with_reason() {
        engine.register(new WorkflowDef("adm3", List.of(count("s", "K"))));
        Execution e = engine.start("adm3");
        engine.run(e.getId());   // COMPLETED (terminal)
        engine.forceStatus(e.getId(), ExecutionStatus.CANCELED, "ops", "manual cleanup");
        assertTrue(audit.findByExecutionIdOrderByCreatedAtAsc(e.getId()).stream()
                .anyMatch(a -> a.getToStatus() == ExecutionStatus.CANCELED
                        && a.getReason() != null && a.getReason().contains("manual cleanup")));
    }

    @Test
    void force_status_requires_a_reason() {
        engine.register(new WorkflowDef("adm4", List.of(count("s", "K"))));
        Execution e = engine.start("adm4");
        assertThrows(IllegalArgumentException.class,
                () -> engine.forceStatus(e.getId(), ExecutionStatus.CANCELED, "ops", "  "));
    }

    @Test
    void rerun_step_re_executes_the_side_effect() {
        engine.register(new WorkflowDef("adm5", List.of(count("s", "K"))));
        Execution e = engine.start("adm5");
        engine.run(e.getId());
        assertEquals(1, handlers.counter("K"));
        engine.rerunStep(e.getId(), "s", "ops");
        assertEquals(2, handlers.counter("K"));   // ran again
    }

    @Test
    void stats_counts_by_status_and_dead_is_empty_for_fresh_runs() {
        engine.register(new WorkflowDef("adm6", List.of(count("s", "K"))));
        engine.run(engine.start("adm6").getId());
        assertTrue(engine.stats().getOrDefault("COMPLETED", 0L) >= 1);
        assertTrue(engine.deadExecutions(30).isEmpty());   // nothing 30min stale
    }
}
