package com.continuum.engine;

import com.continuum.domain.Execution;
import com.continuum.dto.WorkflowDef;
import com.continuum.repo.AuditLogRepo;
import com.continuum.repo.EventRecordRepo;
import com.continuum.repo.ExecutionRepo;
import com.continuum.repo.StepRecordRepo;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reconstructs the chronological flow of a past execution for debugging
 * (spec §3.6.2 Replay). Read-only: it replays the recorded timeline (state
 * transitions + step records + events) against the pinned workflow version - it
 * does NOT re-execute side effects (that is admin step re-run).
 */
@Component
public class ReplayService {

    private final ExecutionRepo executions;
    private final StepRecordRepo stepRecords;
    private final AuditLogRepo auditLogs;
    private final EventRecordRepo eventRecords;
    private final WorkflowEngine engine;

    public ReplayService(ExecutionRepo executions, StepRecordRepo stepRecords,
                         AuditLogRepo auditLogs, EventRecordRepo eventRecords, WorkflowEngine engine) {
        this.executions = executions;
        this.stepRecords = stepRecords;
        this.auditLogs = auditLogs;
        this.eventRecords = eventRecords;
        this.engine = engine;
    }

    /** One entry on the replayed timeline. */
    public record Frame(int seq, Instant at, String kind, String detail) {}

    public record Trace(Long executionId, int workflowVersion, String workflowName, List<Frame> frames) {}

    public Trace replay(Long executionId) {
        Execution e = executions.findById(executionId)
                .orElseThrow(() -> new IllegalArgumentException("unknown execution: " + executionId));
        WorkflowDef def = engine.defOf(e);

        List<Frame> merged = new ArrayList<>();
        auditLogs.findByExecutionIdOrderByCreatedAtAsc(executionId).forEach(a -> merged.add(new Frame(
                0, a.getCreatedAt(), "TRANSITION",
                (a.getFromStatus() == null ? "∅" : a.getFromStatus().name()) + " -> " + a.getToStatus().name()
                        + " by " + a.getActor() + (a.getReason() == null ? "" : " (" + a.getReason() + ")"))));
        stepRecords.findByExecutionIdOrderByCreatedAtAsc(executionId).forEach(s -> merged.add(new Frame(
                0, s.getCreatedAt(), "STEP",
                s.getStepId() + " " + s.getStatus().name() + " attempt=" + s.getAttempt()
                        + (s.getMessage() == null ? "" : " (" + s.getMessage() + ")"))));
        eventRecords.findByExecutionIdOrderByCreatedAtAsc(executionId).forEach(ev -> merged.add(new Frame(
                0, ev.getCreatedAt(), "EVENT",
                ev.getType().name() + (ev.getStepId() == null ? "" : " " + ev.getStepId()))));

        merged.sort(Comparator.comparing(Frame::at));
        List<Frame> ordered = new ArrayList<>(merged.size());
        int seq = 1;
        for (Frame f : merged) {
            ordered.add(new Frame(seq++, f.at(), f.kind(), f.detail()));
        }
        return new Trace(executionId, e.getWorkflowVersion(), def.name(), ordered);
    }
}
