package com.continuum;

import com.continuum.domain.AuditLog;
import com.continuum.domain.Execution;
import com.continuum.domain.ExecutionStatus;
import com.continuum.dto.*;
import com.continuum.engine.WorkflowEngine;
import com.continuum.repo.AuditLogRepo;
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
 * Audit trail (spec §3.6.1): every state transition is recorded with from/to,
 * actor, reason and time. Drives the real engine + DB.
 */
@SpringBootTest
@DirtiesContext(classMode = ClassMode.BEFORE_EACH_TEST_METHOD)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:cont_${random.uuid};DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AuditLogTests {

    @Autowired private WorkflowEngine engine;
    @Autowired private AuditLogRepo audit;

    private StepDef noop(String id) {
        return new StepDef(id, "noop", Map.of(), RetryPolicy.none(), OnFailure.ABORT, null, Map.of());
    }

    @Test
    void happy_path_records_created_running_completed() {
        engine.register(new WorkflowDef("au", List.of(noop("a"))));
        Execution e = engine.start("au");
        engine.run(e.getId());

        List<AuditLog> trail = audit.findByExecutionIdOrderByCreatedAtAsc(e.getId());
        List<ExecutionStatus> tos = trail.stream().map(AuditLog::getToStatus).toList();
        assertTrue(tos.contains(ExecutionStatus.CREATED));
        assertTrue(tos.contains(ExecutionStatus.RUNNING));
        assertTrue(tos.contains(ExecutionStatus.COMPLETED));
        assertTrue(trail.stream().allMatch(a -> a.getActor() != null && !a.getActor().isBlank()));
        assertTrue(trail.stream().allMatch(a -> a.getCreatedAt() != null));
    }

    @Test
    void approval_records_the_actor_who_decided() {
        StepDef gate = new StepDef("gate", null, Map.of(), RetryPolicy.none(), null, null, Map.of(),
                List.of(), StepKind.HUMAN_APPROVAL, null);
        engine.register(new WorkflowDef("aug", "aug", 1, List.of(gate), List.of(), OnFailure.ABORT));
        Execution e = engine.start("aug");
        engine.run(e.getId());               // -> WAITING
        engine.approve(e.getId(), "carol");  // -> RUNNING -> COMPLETED

        boolean carolRecorded = audit.findByExecutionIdOrderByCreatedAtAsc(e.getId()).stream()
                .anyMatch(a -> "carol".equals(a.getActor()) && a.getReason() != null && a.getReason().contains("approved"));
        assertTrue(carolRecorded, "the approver must appear in the audit trail with a reason");
    }
}
