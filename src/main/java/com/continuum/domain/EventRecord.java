package com.continuum.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Durable record of an emitted event (spec §3.4.1 "이벤트 손실 방지"). Persisted
 * synchronously as the event is published so a crash never loses it; async
 * consumers read from the same stream.
 */
@Entity
@Table(name = "event_records")
public class EventRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "execution_id", nullable = false)
    private Long executionId;

    @Column(name = "step_id")
    private String stepId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventType type;

    @Column(length = 1000)
    private String message;

    @Column(nullable = false)
    private Instant createdAt;

    protected EventRecord() {}

    public EventRecord(Long executionId, String stepId, EventType type, String message) {
        this.executionId = executionId;
        this.stepId = stepId;
        this.type = type;
        this.message = message;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getExecutionId() { return executionId; }
    public String getStepId() { return stepId; }
    public EventType getType() { return type; }
    public String getMessage() { return message; }
    public Instant getCreatedAt() { return createdAt; }
}
