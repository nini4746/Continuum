package com.continuum;

import com.continuum.domain.EventType;
import com.continuum.domain.Execution;
import com.continuum.dto.*;
import com.continuum.engine.AsyncEventConsumer;
import com.continuum.engine.WorkflowEngine;
import com.continuum.repo.EventRecordRepo;
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
 * Internal event bus (spec §3.4.1): step completed/failed events are durably
 * persisted (loss prevention) and delivered to an async consumer. Real engine + DB.
 */
@SpringBootTest
@DirtiesContext(classMode = ClassMode.BEFORE_EACH_TEST_METHOD)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:cont_${random.uuid};DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class EventBusTests {

    @Autowired private WorkflowEngine engine;
    @Autowired private EventRecordRepo events;
    @Autowired private AsyncEventConsumer consumer;

    private StepDef noop(String id) {
        return new StepDef(id, "noop", Map.of(), RetryPolicy.none(), OnFailure.ABORT, null, Map.of());
    }

    @Test
    void completed_steps_emit_durable_events() {
        engine.register(new WorkflowDef("ev", List.of(noop("a"), noop("b"))));
        Execution e = engine.start("ev");
        engine.run(e.getId());

        var recs = events.findByExecutionIdOrderByCreatedAtAsc(e.getId());
        assertEquals(2, recs.size(), "one durable event per completed step");
        assertTrue(recs.stream().allMatch(r -> r.getType() == EventType.STEP_COMPLETED));
    }

    @Test
    void async_consumer_eventually_receives_events() {
        int before = consumer.deliveredCount();
        engine.register(new WorkflowDef("ev2", List.of(noop("a"), noop("b"))));
        Execution e = engine.start("ev2");
        engine.run(e.getId());
        // async delivery: poll until the consumer has seen both events (or time out).
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (consumer.deliveredCount() < before + 2 && System.nanoTime() < deadline) {
            try { Thread.sleep(25); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        assertTrue(consumer.deliveredCount() >= before + 2,
                "async consumer should eventually receive both step events");
    }

    @Test
    void failed_step_emits_failure_event() {
        engine.register(new WorkflowDef("evf", List.of(
                new StepDef("boom", "fail", Map.of(), RetryPolicy.none(), OnFailure.ABORT, null, Map.of()))));
        Execution e = engine.start("evf");
        engine.run(e.getId());
        assertTrue(events.findByExecutionIdOrderByCreatedAtAsc(e.getId()).stream()
                .anyMatch(r -> r.getType() == EventType.STEP_FAILED));
    }
}
