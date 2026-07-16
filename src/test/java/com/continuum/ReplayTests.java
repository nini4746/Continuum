package com.continuum;

import com.continuum.domain.Execution;
import com.continuum.dto.*;
import com.continuum.engine.ReplayService;
import com.continuum.engine.WorkflowEngine;
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
 * Replay (spec §3.6.2): reconstruct the chronological flow of a past execution
 * for debugging - transitions + steps + events in time order, read-only.
 */
@SpringBootTest
@DirtiesContext(classMode = ClassMode.BEFORE_EACH_TEST_METHOD)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:cont_${random.uuid};DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ReplayTests {

    @Autowired private WorkflowEngine engine;
    @Autowired private ReplayService replay;

    private StepDef noop(String id) {
        return new StepDef(id, "noop", Map.of(), RetryPolicy.none(), OnFailure.ABORT, null, Map.of());
    }

    @Test
    void replay_reconstructs_transitions_steps_and_events_in_order() {
        engine.register(new WorkflowDef("rp", List.of(noop("a"), noop("b"))));
        Execution e = engine.start("rp");
        engine.run(e.getId());

        ReplayService.Trace trace = replay.replay(e.getId());
        assertEquals("rp", trace.workflowName());
        assertEquals(1, trace.workflowVersion());
        assertFalse(trace.frames().isEmpty());

        // seq is monotonic and frames are time-ordered
        for (int i = 1; i < trace.frames().size(); i++) {
            assertEquals(i + 1, trace.frames().get(i).seq());
            assertFalse(trace.frames().get(i).at().isBefore(trace.frames().get(i - 1).at()));
        }
        // all three kinds are present in a completed run
        var kinds = trace.frames().stream().map(ReplayService.Frame::kind).distinct().toList();
        assertTrue(kinds.contains("TRANSITION"));
        assertTrue(kinds.contains("STEP"));
        assertTrue(kinds.contains("EVENT"));
    }

    @Test
    void replay_of_unknown_execution_is_rejected() {
        assertThrows(IllegalArgumentException.class, () -> replay.replay(9999L));
    }
}
