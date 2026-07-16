package com.continuum.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Immutable record of a single execution state transition (spec §3.6.1):
 * who changed it, when, why, and from/to which state.
 */
@Entity
@Table(name = "audit_logs")
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "execution_id", nullable = false)
    private Long executionId;

    @Enumerated(EnumType.STRING)
    private ExecutionStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExecutionStatus toStatus;

    @Column(nullable = false)
    private String actor;

    @Column(length = 1000)
    private String reason;

    @Column(nullable = false)
    private Instant createdAt;

    protected AuditLog() {}

    public AuditLog(Long executionId, ExecutionStatus fromStatus, ExecutionStatus toStatus,
                    String actor, String reason) {
        this.executionId = executionId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actor = actor;
        this.reason = reason;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getExecutionId() { return executionId; }
    public ExecutionStatus getFromStatus() { return fromStatus; }
    public ExecutionStatus getToStatus() { return toStatus; }
    public String getActor() { return actor; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
