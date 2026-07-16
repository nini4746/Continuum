package com.continuum.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Async consumer of {@link StepEvent}s (spec §3.4.1 "비동기 처리"). Runs off the
 * publishing thread; keeps a delivered counter so tests can await eventual
 * delivery without leaking the async plumbing elsewhere.
 */
@Component
public class AsyncEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AsyncEventConsumer.class);
    private final AtomicInteger delivered = new AtomicInteger();

    @Async
    @EventListener
    public void onStepEvent(StepEvent event) {
        delivered.incrementAndGet();
        log.debug("async event exec={} step={} type={}", event.executionId(), event.stepId(), event.type());
    }

    public int deliveredCount() {
        return delivered.get();
    }
}
