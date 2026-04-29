package com.continuum.engine;

import com.continuum.domain.*;
import com.continuum.dto.StepDef;
import com.continuum.dto.WorkflowDef;
import com.continuum.handler.HandlerRegistry;
import com.continuum.repo.ExecutionRepo;
import com.continuum.repo.StepRecordRepo;
import com.continuum.repo.WorkflowRepo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    private final WorkflowRepo workflows;
    private final ExecutionRepo executions;
    private final StepRecordRepo stepRecords;
    private final HandlerRegistry handlers;
    private final ObjectMapper om;
    private final int workerThreads;
    private final boolean exposeRawErrors;
    private ExecutorService asyncWorker;

    public WorkflowEngine(WorkflowRepo workflows, ExecutionRepo executions,
                          StepRecordRepo stepRecords, HandlerRegistry handlers, ObjectMapper om,
                          @Value("${continuum.async.threads:4}") int workerThreads,
                          @Value("${continuum.errors.expose-raw-messages:false}") boolean exposeRawErrors) {
        this.workflows = workflows;
        this.executions = executions;
        this.stepRecords = stepRecords;
        this.handlers = handlers;
        this.om = om;
        this.workerThreads = Math.max(1, workerThreads);
        this.exposeRawErrors = exposeRawErrors;
    }

    private String sanitizeError(Exception ex) {
        if (exposeRawErrors) return ex.getMessage();
        return ex.getClass().getSimpleName();
    }

    @PostConstruct
    void initWorker() {
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "continuum-worker-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.asyncWorker = Executors.newFixedThreadPool(workerThreads, factory);
    }

    @PreDestroy
    void shutdownWorker() {
        if (asyncWorker == null) return;
        asyncWorker.shutdown();
        try {
            if (!asyncWorker.awaitTermination(5, TimeUnit.SECONDS)) asyncWorker.shutdownNow();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            asyncWorker.shutdownNow();
        }
    }

    public Future<ExecutionStatus> runAsync(Long executionId) {
        return asyncWorker.submit(() -> run(executionId));
    }

    @Transactional
    public WorkflowEntity register(WorkflowDef def) {
        return workflows.findByName(def.name()).orElseGet(() -> {
            try {
                return workflows.save(new WorkflowEntity(def.name(), om.writeValueAsString(def)));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("could not serialize workflow def", e);
            }
        });
    }

    @Transactional
    public Execution start(String workflowName) {
        WorkflowEntity wf = workflows.findByName(workflowName)
                .orElseThrow(() -> new IllegalArgumentException("unknown workflow: " + workflowName));
        return executions.save(new Execution(wf.getId()));
    }

    public WorkflowDef defOf(Execution e) {
        WorkflowEntity wf = workflows.findById(e.getWorkflowId())
                .orElseThrow(() -> new IllegalStateException("workflow missing"));
        try {
            return om.readValue(wf.getDefinitionJson(), WorkflowDef.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("could not parse workflow def", ex);
        }
    }

    public ExecutionStatus run(Long executionId) {
        // bounded iteration to prevent runaway workflows; configurable via continuum.run.max-steps
        int maxSteps = 10_000;
        for (int i = 0; i < maxSteps; i++) {
            ExecutionStatus s = step(executionId);
            if (s != ExecutionStatus.RUNNING) return s;
        }
        log.warn("execution {} exceeded max-steps={} — treating as FAILED", executionId, maxSteps);
        Execution e = executions.findById(executionId).orElseThrow();
        e.markStatus(ExecutionStatus.FAILED);
        e.setLastError("max-steps exceeded");
        executions.save(e);
        return ExecutionStatus.FAILED;
    }

    @Transactional
    public ExecutionStatus step(Long executionId) {
        Execution e = executions.findById(executionId)
                .orElseThrow(() -> new IllegalArgumentException("unknown execution: " + executionId));
        if (e.getStatus() == ExecutionStatus.COMPLETED
                || e.getStatus() == ExecutionStatus.FAILED
                || e.getStatus() == ExecutionStatus.COMPENSATED) {
            return e.getStatus();
        }
        if (e.getStatus() == ExecutionStatus.PENDING) {
            e.markStatus(ExecutionStatus.RUNNING);
        }
        WorkflowDef def = defOf(e);
        if (e.getCursor() >= def.steps().size()) {
            e.markStatus(ExecutionStatus.COMPLETED);
            executions.save(e);
            return ExecutionStatus.COMPLETED;
        }
        StepDef stepDef = def.steps().get(e.getCursor());
        runOneStep(e, stepDef);
        executions.save(e);
        return e.getStatus();
    }

    private void runOneStep(Execution e, StepDef stepDef) {
        var handler = handlers.require(stepDef.type());
        int max = Math.max(1, stepDef.retry().maxAttempts());
        String lastError = null;
        for (int attempt = 1; attempt <= max; attempt++) {
            try {
                handler.execute(stepDef.inputs());
                stepRecords.saveAndFlush(
                        new StepRecord(e.getId(), stepDef.id(), StepStatus.SUCCEEDED, attempt, null));
                e.advanceCursor();
                e.setLastError(null);
                return;
            } catch (DataIntegrityViolationException dup) {
                log.warn("duplicate step record rejected exec={} step={} attempt={}",
                        e.getId(), stepDef.id(), attempt);
                e.advanceCursor();
                return;
            } catch (Exception ex) {
                lastError = sanitizeError(ex);
                log.warn("step failed exec={} step={} attempt={} cause={}",
                        e.getId(), stepDef.id(), attempt, ex.getClass().getSimpleName());
                try {
                    stepRecords.saveAndFlush(
                            new StepRecord(e.getId(), stepDef.id(), StepStatus.FAILED, attempt, lastError));
                } catch (DataIntegrityViolationException ignore) {
                    // already recorded; safe to continue
                }
                if (attempt < max) {
                    long backoff = stepDef.retry().backoffFor(attempt + 1);
                    if (backoff > 0) sleepQuiet(backoff);
                }
            }
        }
        handleTerminalFailure(e, stepDef, lastError == null ? "unknown" : lastError);
    }

    private void handleTerminalFailure(Execution e, StepDef stepDef, String message) {
        e.setLastError(stepDef.id() + ": " + message);
        switch (stepDef.onFailure()) {
            case SKIP -> e.advanceCursor();
            case ABORT -> e.markStatus(ExecutionStatus.FAILED);
            case COMPENSATE -> {
                e.markStatus(ExecutionStatus.FAILED);
                compensate(e);
            }
        }
    }

    private void compensate(Execution e) {
        WorkflowDef def = defOf(e);
        for (int i = e.getCursor() - 1; i >= 0; i--) {
            StepDef s = def.steps().get(i);
            if (s.compensateType() == null || s.compensateType().isBlank()) continue;
            var h = handlers.require(s.compensateType());
            try {
                h.execute(s.compensateInputs());
                stepRecords.save(new StepRecord(e.getId(), s.id() + "#compensate",
                        StepStatus.COMPENSATED, 0, "compensated"));
            } catch (Exception ex) {
                stepRecords.save(new StepRecord(e.getId(), s.id() + "#compensate",
                        StepStatus.FAILED, 0, "compensate failed: " + sanitizeError(ex)));
            }
        }
        e.markStatus(ExecutionStatus.COMPENSATED);
    }

    private void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    public boolean recordDuplicateForReplayCheck(Long executionId, String stepId, int attempt) {
        return stepRecords.existsByExecutionIdAndStepIdAndAttempt(executionId, stepId, attempt);
    }

    public void resumeAll() {
        for (Execution e : executions.findByStatus(ExecutionStatus.RUNNING)) {
            log.info("resuming execution {} from cursor {}", e.getId(), e.getCursor());
            run(e.getId());
        }
    }
}
