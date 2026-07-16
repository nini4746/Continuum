package com.continuum;

import com.continuum.domain.Execution;
import com.continuum.dto.OnFailure;
import com.continuum.dto.RetryPolicy;
import com.continuum.dto.StepDef;
import com.continuum.dto.WorkflowDef;
import com.continuum.engine.WorkflowEngine;
import com.continuum.repo.WorkflowRepo;
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
 * Workflow versioning end to end (spec §3.1.2): re-registering a changed
 * definition creates a new version, prior versions are preserved, and a running
 * execution stays pinned to the version it started on. Drives the real engine + DB.
 */
@SpringBootTest
@DirtiesContext(classMode = ClassMode.BEFORE_EACH_TEST_METHOD)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:cont_${random.uuid};DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class VersioningTests {

    @Autowired private WorkflowEngine engine;
    @Autowired private WorkflowRepo workflows;

    private WorkflowDef defWith(String noopId) {
        return new WorkflowDef("vt", List.of(
                new StepDef(noopId, "noop", Map.of(), RetryPolicy.none(), OnFailure.ABORT, null, Map.of())));
    }

    @Test
    void reregistering_a_change_creates_a_new_version_and_preserves_old() {
        engine.register(defWith("stepA"));                 // v1
        Execution e1 = engine.start("vt");                  // pinned to v1

        engine.register(defWith("stepB"));                 // changed -> v2

        List<?> history = workflows.findByNameOrderByVersionDesc("vt");
        assertEquals(2, history.size(), "both versions preserved");

        Execution e2 = engine.start("vt");                  // pinned to v2
        assertEquals(1, e1.getWorkflowVersion());
        assertEquals(2, e2.getWorkflowVersion());

        // The pinned definitions differ: e1 still sees stepA, e2 sees stepB.
        assertEquals("stepA", engine.defOf(e1).steps().get(0).id());
        assertEquals("stepB", engine.defOf(e2).steps().get(0).id());
        assertEquals(1, engine.defOf(e1).version());
        assertEquals(2, engine.defOf(e2).version());
    }

    @Test
    void identical_reregistration_is_idempotent_no_new_version() {
        engine.register(defWith("stepA"));   // v1
        engine.register(defWith("stepA"));   // identical -> still v1
        assertEquals(1, workflows.findByNameOrderByVersionDesc("vt").size());
    }

    @Test
    void new_execution_uses_latest_version() {
        engine.register(defWith("stepA"));   // v1
        engine.register(defWith("stepB"));   // v2
        assertEquals(2, engine.start("vt").getWorkflowVersion());
    }
}
