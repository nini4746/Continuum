package com.continuum.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class CrashRecovery {

    private static final Logger log = LoggerFactory.getLogger(CrashRecovery.class);
    private final WorkflowEngine engine;

    public CrashRecovery(WorkflowEngine engine) {
        this.engine = engine;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("crash recovery: scanning RUNNING executions");
        engine.resumeAll();
    }
}
