package com.continuum.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(
        name = "step_records",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_step_records_exec_step",
                columnNames = {"execution_id", "step_id"}
        )
)
public class StepRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "execution_id", nullable = false)
    private Long executionId;

    @Column(name = "step_id", nullable = false)
    private String stepId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StepStatus status;

    @Column(nullable = false)
    private int attempt;

    @Column(length = 1000)
    private String message;

    @Column(nullable = false)
    private Instant createdAt;

    protected StepRecord() {}

    public StepRecord(Long executionId, String stepId, StepStatus status, int attempt, String message) {
        this.executionId = executionId;
        this.stepId = stepId;
        this.status = status;
        this.attempt = attempt;
        this.message = message;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getExecutionId() { return executionId; }
    public String getStepId() { return stepId; }
    public StepStatus getStatus() { return status; }
    public int getAttempt() { return attempt; }
    public String getMessage() { return message; }
    public Instant getCreatedAt() { return createdAt; }
}
