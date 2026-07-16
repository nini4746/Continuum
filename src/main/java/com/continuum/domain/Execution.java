package com.continuum.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "executions")
public class Execution {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long workflowId;

    /** Workflow version pinned at start time; the execution never sees later edits (spec §3.1.2). */
    @Column(nullable = false)
    private int workflowVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExecutionStatus status;

    @Column(nullable = false)
    private int cursor;

    @Column(length = 1000)
    private String lastError;

    /** JSON map of runtime variables, evaluated by CONDITIONAL steps (spec §3.1.1). */
    @Lob
    @Column
    private String contextJson;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Execution() {}

    public Execution(Long workflowId) {
        this(workflowId, 1);
    }

    public Execution(Long workflowId, int workflowVersion) {
        this.workflowId = workflowId;
        this.workflowVersion = workflowVersion;
        this.status = ExecutionStatus.CREATED;
        this.cursor = 0;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() { return id; }
    public Long getWorkflowId() { return workflowId; }
    public int getWorkflowVersion() { return workflowVersion; }
    public ExecutionStatus getStatus() { return status; }
    public int getCursor() { return cursor; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /**
     * Transition to {@code s} following the explicit state machine (spec §3.2.1).
     * Same-state calls are idempotent no-ops; any other undeclared edge is rejected.
     */
    public void markStatus(ExecutionStatus s) {
        if (s != this.status && !this.status.canTransitionTo(s)) {
            throw new IllegalStateException("illegal execution transition: " + this.status + " -> " + s);
        }
        this.status = s;
        this.updatedAt = Instant.now();
    }

    /**
     * Admin override that bypasses the transition rules (spec §3.7 "강제 상태 변경").
     * Callers MUST record an audit entry; the engine never uses this on the happy path.
     */
    public void forceStatus(ExecutionStatus s) {
        this.status = s;
        this.updatedAt = Instant.now();
    }

    public void advanceCursor() {
        this.cursor++;
        this.updatedAt = Instant.now();
    }

    /** Jump the cursor to an absolute step index (CONDITIONAL branch / admin step re-run). */
    public void setCursor(int c) {
        this.cursor = c;
        this.updatedAt = Instant.now();
    }

    public String getContextJson() { return contextJson; }

    public void setContextJson(String json) {
        this.contextJson = json;
        this.updatedAt = Instant.now();
    }

    public void setLastError(String err) {
        this.lastError = err;
        this.updatedAt = Instant.now();
    }
}
