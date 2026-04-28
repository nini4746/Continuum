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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExecutionStatus status;

    @Column(nullable = false)
    private int cursor;

    @Column(length = 1000)
    private String lastError;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Execution() {}

    public Execution(Long workflowId) {
        this.workflowId = workflowId;
        this.status = ExecutionStatus.PENDING;
        this.cursor = 0;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() { return id; }
    public Long getWorkflowId() { return workflowId; }
    public ExecutionStatus getStatus() { return status; }
    public int getCursor() { return cursor; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void markStatus(ExecutionStatus s) {
        this.status = s;
        this.updatedAt = Instant.now();
    }

    public void advanceCursor() {
        this.cursor++;
        this.updatedAt = Instant.now();
    }

    public void setLastError(String err) {
        this.lastError = err;
        this.updatedAt = Instant.now();
    }
}
