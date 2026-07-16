package com.continuum.engine;

import com.continuum.domain.EventRecord;
import com.continuum.domain.EventType;
import com.continuum.repo.EventRecordRepo;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Internal event bus (spec §3.4.1). Every emission is durably persisted first
 * ("이벤트 손실 방지") and then dispatched to async listeners ("비동기 처리").
 */
@Component
public class EventBus {

    private final EventRecordRepo events;
    private final ApplicationEventPublisher publisher;

    public EventBus(EventRecordRepo events, ApplicationEventPublisher publisher) {
        this.events = events;
        this.publisher = publisher;
    }

    public void stepCompleted(Long executionId, String stepId) {
        emit(executionId, stepId, EventType.STEP_COMPLETED, "step completed");
    }

    public void stepFailed(Long executionId, String stepId, String message) {
        emit(executionId, stepId, EventType.STEP_FAILED, message);
    }

    public void stepTimeout(Long executionId, String stepId, String message) {
        emit(executionId, stepId, EventType.STEP_TIMEOUT, message);
    }

    private void emit(Long executionId, String stepId, EventType type, String message) {
        events.save(new EventRecord(executionId, stepId, type, message)); // durable first
        publisher.publishEvent(new StepEvent(executionId, stepId, type, message)); // then async
    }
}
