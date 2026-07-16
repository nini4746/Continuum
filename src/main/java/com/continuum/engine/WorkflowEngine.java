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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
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
    private final RetryStrategy retryStrategy;
    private final DistributedLock distributedLock;
    private final ConditionEvaluator conditions;
    private final long lockTimeoutMs;
    private final int workerThreads;
    private final boolean exposeRawErrors;
    private ExecutorService asyncWorker;
    private ExecutorService dagStepWorker;

    public WorkflowEngine(WorkflowRepo workflows, ExecutionRepo executions,
                          StepRecordRepo stepRecords, HandlerRegistry handlers, ObjectMapper om,
                          RetryStrategy retryStrategy,
                          DistributedLock distributedLock,
                          ConditionEvaluator conditions,
                          @Value("${continuum.async.threads:4}") int workerThreads,
                          @Value("${continuum.lock.timeout-ms:50}") long lockTimeoutMs,
                          @Value("${continuum.errors.expose-raw-messages:false}") boolean exposeRawErrors) {
        this.workflows = workflows;
        this.executions = executions;
        this.stepRecords = stepRecords;
        this.handlers = handlers;
        this.om = om;
        this.retryStrategy = retryStrategy;
        this.distributedLock = distributedLock;
        this.conditions = conditions;
        this.workerThreads = Math.max(1, workerThreads);
        this.lockTimeoutMs = Math.max(0, lockTimeoutMs);
        this.exposeRawErrors = exposeRawErrors;
    }

    private static String executionLockKey(Long executionId) {
        return "execution:" + executionId;
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
        AtomicInteger dagSeq = new AtomicInteger();
        ThreadFactory dagFactory = r -> {
            Thread t = new Thread(r, "continuum-dag-" + dagSeq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        // separate pool so DAG step parallelism never deadlocks against an asyncWorker
        // thread that's blocked waiting for child steps to complete.
        this.dagStepWorker = Executors.newFixedThreadPool(workerThreads, dagFactory);
    }

    @PreDestroy
    void shutdownWorker() {
        for (ExecutorService es : new ExecutorService[]{asyncWorker, dagStepWorker}) {
            if (es == null) continue;
            es.shutdown();
            try {
                if (!es.awaitTermination(5, TimeUnit.SECONDS)) es.shutdownNow();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                es.shutdownNow();
            }
        }
    }

    public Future<ExecutionStatus> runAsync(Long executionId) {
        return asyncWorker.submit(() -> run(executionId));
    }

    /**
     * Register a workflow version (spec §3.1.2). An identical re-registration is
     * idempotent (returns the latest version); any change creates a new version and
     * preserves prior versions untouched.
     */
    @Transactional
    public WorkflowEntity register(WorkflowDef def) {
        var latest = workflows.findTopByNameOrderByVersionDesc(def.name());
        int nextVersion = latest.map(w -> w.getVersion() + 1).orElse(1);
        // Stamp the assigned version into the stored definition so DSL and store agree.
        WorkflowDef stamped = new WorkflowDef(def.name(), def.workflowId(), nextVersion,
                def.steps(), def.transitions(), def.failurePolicy());
        try {
            String json = om.writeValueAsString(stamped);
            if (latest.isPresent() && latest.get().getDefinitionJson().equals(
                    om.writeValueAsString(new WorkflowDef(def.name(), def.workflowId(),
                            latest.get().getVersion(), def.steps(), def.transitions(), def.failurePolicy())))) {
                return latest.get(); // no change -> no new version
            }
            return workflows.save(new WorkflowEntity(def.name(), nextVersion, json));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialize workflow def", e);
        }
    }

    @Transactional
    public Execution start(String workflowName) {
        return start(workflowName, null);
    }

    @Transactional
    public Execution start(String workflowName, Map<String, Object> context) {
        WorkflowEntity wf = workflows.findTopByNameOrderByVersionDesc(workflowName)
                .orElseThrow(() -> new IllegalArgumentException("unknown workflow: " + workflowName));
        Execution e = new Execution(wf.getId(), wf.getVersion());
        if (context != null && !context.isEmpty()) {
            try {
                e.setContextJson(om.writeValueAsString(context));
            } catch (JsonProcessingException ex) {
                throw new IllegalArgumentException("invalid execution context", ex);
            }
        }
        return executions.save(e);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> contextOf(Execution e) {
        if (e.getContextJson() == null || e.getContextJson().isBlank()) return new HashMap<>();
        try {
            return om.readValue(e.getContextJson(), Map.class);
        } catch (JsonProcessingException ex) {
            return new HashMap<>();
        }
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
        String lockKey = executionLockKey(executionId);
        DistributedLock.Token token = distributedLock.tryLock(lockKey, lockTimeoutMs, TimeUnit.MILLISECONDS);
        if (token == null) {
            log.info("execution {} is already running, skipping concurrent run", executionId);
            return executions.findById(executionId).map(Execution::getStatus).orElse(ExecutionStatus.FAILED);
        }
        try {
            // Route to DAG mode if any step declares deps; otherwise stay sequential.
            Execution e = executions.findById(executionId).orElseThrow();
            WorkflowDef def = defOf(e);
            if (def.hasDependencies()) {
                return runDag(executionId, def);
            }
            for (int i = 0; i < maxSteps; i++) {
                ExecutionStatus s = step(executionId);
                if (s != ExecutionStatus.RUNNING) return s;
            }
            log.warn("execution {} exceeded max-steps={} — treating as FAILED", executionId, maxSteps);
            Execution exec = executions.findById(executionId).orElseThrow();
            exec.markStatus(ExecutionStatus.FAILED);
            exec.setLastError("max-steps exceeded");
            executions.save(exec);
            return ExecutionStatus.FAILED;
        } finally {
            distributedLock.unlock(lockKey, token);
        }
    }

    /**
     * DAG execution: schedule steps whose {@code dependsOn} are all satisfied. Independent
     * branches run concurrently on the async worker pool. Failures still honour
     * {@link OnFailure} semantics — ABORT halts scheduling, COMPENSATE triggers reverse
     * compensation across already-succeeded steps.
     */
    private ExecutionStatus runDag(Long executionId, WorkflowDef def) {
        Execution e = executions.findById(executionId).orElseThrow();
        if (e.getStatus() == ExecutionStatus.CREATED) {
            e.markStatus(ExecutionStatus.RUNNING);
            executions.save(e);
        }

        Map<String, StepDef> stepById = new HashMap<>();
        Map<String, Set<String>> remainingDeps = new HashMap<>();
        for (StepDef s : def.steps()) {
            stepById.put(s.id(), s);
            remainingDeps.put(s.id(), new HashSet<>(s.dependsOn()));
        }
        // honour any prior completed records (idempotent re-run after restart)
        Set<String> done = new HashSet<>();
        for (StepRecord r : stepRecords.findByExecutionIdOrderByCreatedAtAsc(executionId)) {
            if (r.getStatus() == StepStatus.SUCCEEDED) done.add(r.getStepId());
        }
        // remove satisfied deps from in-flight tracker
        for (String d : done) {
            for (Set<String> deps : remainingDeps.values()) deps.remove(d);
            remainingDeps.remove(d);
        }

        CompletionService<DagResult> cs = new ExecutorCompletionService<>(dagStepWorker);
        Set<String> running = new HashSet<>();
        boolean abort = false;
        boolean compensate = false;
        String terminalError = null;

        try {
            while (!remainingDeps.isEmpty() || !running.isEmpty()) {
                // schedule all currently ready steps
                if (!abort) {
                    List<String> ready = new ArrayList<>();
                    for (var entry : remainingDeps.entrySet()) {
                        if (entry.getValue().isEmpty() && !running.contains(entry.getKey())) {
                            ready.add(entry.getKey());
                        }
                    }
                    for (String id : ready) {
                        running.add(id);
                        StepDef s = stepById.get(id);
                        cs.submit(() -> attemptStepDag(executionId, s));
                    }
                    if (running.isEmpty() && !ready.isEmpty()) break; // safety
                    if (running.isEmpty()) break; // nothing scheduled and nothing running -> done
                }
                if (running.isEmpty()) break;

                Future<DagResult> finished = cs.take();
                DagResult r = finished.get();
                running.remove(r.stepId);
                if (r.success) {
                    done.add(r.stepId);
                    remainingDeps.remove(r.stepId);
                    for (Set<String> deps : remainingDeps.values()) deps.remove(r.stepId);
                } else {
                    terminalError = r.errorMessage;
                    StepDef failedStep = stepById.get(r.stepId);
                    switch (failedStep.effectiveOnFailure(def.failurePolicy())) {
                        case SKIP -> {
                            done.add(r.stepId);
                            remainingDeps.remove(r.stepId);
                            for (Set<String> deps : remainingDeps.values()) deps.remove(r.stepId);
                        }
                        case ABORT -> abort = true;
                        case COMPENSATE -> { abort = true; compensate = true; }
                    }
                }
            }
        } catch (Exception ex) {
            log.error("DAG execution {} failed unexpectedly: {}", executionId, ex.toString());
            abort = true;
            terminalError = sanitizeError(ex);
        }

        Execution latest = executions.findById(executionId).orElseThrow();
        latest.setLastError(terminalError);
        if (abort) {
            latest.markStatus(ExecutionStatus.FAILED);
            executions.save(latest);
            if (compensate) {
                compensateDag(latest, def, done);
            }
            return latest.getStatus();
        }
        latest.markStatus(ExecutionStatus.COMPLETED);
        executions.save(latest);
        return ExecutionStatus.COMPLETED;
    }

    private record DagResult(String stepId, boolean success, String errorMessage) {}

    /**
     * Run a single DAG step with retry semantics. Mirrors the sequential path but does
     * not advance the execution cursor (cursor is meaningless under DAG ordering).
     */
    private DagResult attemptStepDag(Long executionId, StepDef stepDef) {
        var handler = handlers.require(stepDef.type());
        int max = Math.max(1, stepDef.retry().maxAttempts());
        String lastError = null;
        Throwable lastThrown = null;
        for (int attempt = 1; attempt <= max; attempt++) {
            try {
                handler.execute(stepDef.inputs());
                stepRecords.saveAndFlush(
                        new StepRecord(executionId, stepDef.id(), StepStatus.SUCCEEDED, attempt, null));
                return new DagResult(stepDef.id(), true, null);
            } catch (DataIntegrityViolationException dup) {
                log.warn("duplicate dag step record exec={} step={} attempt={}", executionId, stepDef.id(), attempt);
                return new DagResult(stepDef.id(), true, null);
            } catch (Exception ex) {
                lastError = sanitizeError(ex);
                lastThrown = ex;
                try {
                    stepRecords.saveAndFlush(
                            new StepRecord(executionId, stepDef.id(), StepStatus.FAILED, attempt, lastError));
                } catch (DataIntegrityViolationException ignore) { /* duplicate, ok */ }
                if (!retryStrategy.shouldRetry(stepDef.retry(), attempt, lastThrown)) break;
                long backoff = retryStrategy.backoffMillis(stepDef.retry(), attempt + 1);
                if (backoff > 0) sleepQuiet(backoff);
            }
        }
        return new DagResult(stepDef.id(), false, lastError == null ? "unknown" : lastError);
    }

    private void compensateDag(Execution e, WorkflowDef def, Set<String> succeededIds) {
        // walk the topological order in reverse and compensate succeeded steps
        List<StepDef> order = topologicalOrder(def);
        for (int i = order.size() - 1; i >= 0; i--) {
            StepDef s = order.get(i);
            if (!succeededIds.contains(s.id())) continue;
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
        executions.save(e);
    }

    private static List<StepDef> topologicalOrder(WorkflowDef def) {
        Map<String, Integer> indeg = new HashMap<>();
        Map<String, List<String>> adj = new HashMap<>();
        Map<String, StepDef> byId = new HashMap<>();
        for (StepDef s : def.steps()) {
            byId.put(s.id(), s);
            indeg.putIfAbsent(s.id(), 0);
            for (String d : s.dependsOn()) {
                adj.computeIfAbsent(d, k -> new ArrayList<>()).add(s.id());
                indeg.merge(s.id(), 1, Integer::sum);
            }
        }
        java.util.Deque<String> queue = new java.util.ArrayDeque<>();
        for (var e : indeg.entrySet()) if (e.getValue() == 0) queue.add(e.getKey());
        List<StepDef> out = new ArrayList<>();
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            out.add(byId.get(cur));
            for (String nxt : adj.getOrDefault(cur, List.of())) {
                indeg.merge(nxt, -1, Integer::sum);
                if (indeg.get(nxt) == 0) queue.add(nxt);
            }
        }
        return out;
    }

    @Transactional
    public ExecutionStatus step(Long executionId) {
        Execution e = executions.findById(executionId)
                .orElseThrow(() -> new IllegalArgumentException("unknown execution: " + executionId));
        if (e.getStatus() == ExecutionStatus.COMPLETED
                || e.getStatus() == ExecutionStatus.FAILED
                || e.getStatus() == ExecutionStatus.CANCELED
                || e.getStatus() == ExecutionStatus.COMPENSATED
                || e.getStatus() == ExecutionStatus.WAITING) {
            return e.getStatus();
        }
        if (e.getStatus() == ExecutionStatus.CREATED) {
            e.markStatus(ExecutionStatus.RUNNING);
        }
        WorkflowDef def = defOf(e);
        if (e.getCursor() >= def.steps().size()) {
            e.markStatus(ExecutionStatus.COMPLETED);
            executions.save(e);
            return ExecutionStatus.COMPLETED;
        }
        StepDef stepDef = def.steps().get(e.getCursor());
        switch (stepDef.kind()) {
            case CONDITIONAL -> handleConditional(e, def, stepDef);
            case HUMAN_APPROVAL -> parkForApproval(e, stepDef);
            default -> runOneStep(e, def, stepDef);
        }
        executions.save(e);
        return e.getStatus();
    }

    /** Park the execution in WAITING until an approve/reject decision arrives (spec §3.1.1). */
    private void parkForApproval(Execution e, StepDef stepDef) {
        try {
            stepRecords.saveAndFlush(
                    new StepRecord(e.getId(), stepDef.id(), StepStatus.STARTED, 0, "awaiting approval"));
        } catch (DataIntegrityViolationException ignore) { /* already recorded */ }
        e.markStatus(ExecutionStatus.WAITING);
    }

    /**
     * Move the cursor to the next step. When the workflow declares transitions the
     * next step is the unconditional edge from {@code current} (no outgoing edge =
     * leaf = complete); otherwise fall back to the linear cursor+1.
     */
    private void advanceTo(Execution e, WorkflowDef def, StepDef current) {
        if (def.transitions().isEmpty()) {
            e.advanceCursor();
            return;
        }
        String target = def.transitions().stream()
                .filter(t -> t.from().equals(current.id()) && (t.when() == null || t.when().isBlank()))
                .map(com.continuum.dto.Transition::to)
                .findFirst().orElse(null);
        int idx = target == null ? def.steps().size() : indexOfStep(def, target);
        e.setCursor(idx >= 0 ? idx : def.steps().size());
    }

    /**
     * Evaluate a CONDITIONAL step against the execution context, record the outcome,
     * and branch: follow the transition from this step whose {@code when} matches the
     * boolean result ("true"/"false") by jumping the cursor to the target step; with
     * no matching transition, fall through to the next step (cursor+1).
     */
    private void handleConditional(Execution e, WorkflowDef def, StepDef stepDef) {
        boolean result = conditions.eval(stepDef.condition(), contextOf(e));
        stepRecords.save(new StepRecord(e.getId(), stepDef.id(), StepStatus.SUCCEEDED, 1,
                "condition '" + stepDef.condition() + "' = " + result));
        String want = Boolean.toString(result);
        String target = def.transitions().stream()
                .filter(t -> t.from().equals(stepDef.id())
                        && t.when() != null && t.when().equalsIgnoreCase(want))
                .map(com.continuum.dto.Transition::to)
                .findFirst().orElse(null);
        if (target == null) {
            e.advanceCursor();
            return;
        }
        int idx = indexOfStep(def, target);
        if (idx < 0) { // defensive: validated at register time, should not happen
            e.advanceCursor();
            return;
        }
        e.setCursor(idx);
    }

    private static int indexOfStep(WorkflowDef def, String stepId) {
        for (int i = 0; i < def.steps().size(); i++) {
            if (def.steps().get(i).id().equals(stepId)) return i;
        }
        return -1;
    }

    private void runOneStep(Execution e, WorkflowDef def, StepDef stepDef) {
        var handler = handlers.require(stepDef.type());
        int max = Math.max(1, stepDef.retry().maxAttempts());
        String lastError = null;
        Throwable lastThrown = null;
        for (int attempt = 1; attempt <= max; attempt++) {
            try {
                handler.execute(stepDef.inputs());
                stepRecords.saveAndFlush(
                        new StepRecord(e.getId(), stepDef.id(), StepStatus.SUCCEEDED, attempt, null));
                advanceTo(e, def, stepDef);
                e.setLastError(null);
                return;
            } catch (DataIntegrityViolationException dup) {
                log.warn("duplicate step record rejected exec={} step={} attempt={}",
                        e.getId(), stepDef.id(), attempt);
                advanceTo(e, def, stepDef);
                return;
            } catch (Exception ex) {
                lastError = sanitizeError(ex);
                lastThrown = ex;
                log.warn("step failed exec={} step={} attempt={} cause={}",
                        e.getId(), stepDef.id(), attempt, ex.getClass().getSimpleName());
                try {
                    stepRecords.saveAndFlush(
                            new StepRecord(e.getId(), stepDef.id(), StepStatus.FAILED, attempt, lastError));
                } catch (DataIntegrityViolationException ignore) {
                    // already recorded; safe to continue
                }
                if (!retryStrategy.shouldRetry(stepDef.retry(), attempt, lastThrown)) break;
                long backoff = retryStrategy.backoffMillis(stepDef.retry(), attempt + 1);
                if (backoff > 0) sleepQuiet(backoff);
            }
        }
        handleTerminalFailure(e, def, stepDef, lastError == null ? "unknown" : lastError);
    }

    private void handleTerminalFailure(Execution e, WorkflowDef def, StepDef stepDef, String message) {
        e.setLastError(stepDef.id() + ": " + message);
        switch (stepDef.effectiveOnFailure(def.failurePolicy())) {
            case SKIP -> advanceTo(e, def, stepDef);
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

    /**
     * Approve a HUMAN_APPROVAL step (spec §3.1.1): record the approval, move past the
     * gate, and resume the workflow. Rejects if the execution is not WAITING on an
     * approval step.
     */
    public ExecutionStatus approve(Long executionId, String actor) {
        return decide(executionId, actor, true, null);
    }

    public ExecutionStatus reject(Long executionId, String actor, String reason) {
        return decide(executionId, actor, false, reason);
    }

    private ExecutionStatus decide(Long executionId, String actor, boolean approved, String reason) {
        String lockKey = executionLockKey(executionId);
        DistributedLock.Token token = distributedLock.tryLock(lockKey, lockTimeoutMs, TimeUnit.MILLISECONDS);
        if (token == null) throw new IllegalStateException("execution " + executionId + " is busy");
        try {
            Execution e = executions.findById(executionId)
                    .orElseThrow(() -> new IllegalArgumentException("unknown execution: " + executionId));
            if (e.getStatus() != ExecutionStatus.WAITING) {
                throw new IllegalStateException("execution " + executionId + " is not awaiting approval");
            }
            WorkflowDef def = defOf(e);
            StepDef gate = def.steps().get(e.getCursor());
            if (gate.kind() != com.continuum.dto.StepKind.HUMAN_APPROVAL) {
                throw new IllegalStateException("current step is not a human-approval gate");
            }
            String who = (actor == null || actor.isBlank()) ? "unknown" : actor;
            e.markStatus(ExecutionStatus.RUNNING);
            if (approved) {
                stepRecords.save(new StepRecord(e.getId(), gate.id(), StepStatus.SUCCEEDED, 1,
                        "approved by " + who));
                advanceTo(e, def, gate);
                executions.save(e);
            } else {
                stepRecords.save(new StepRecord(e.getId(), gate.id(), StepStatus.FAILED, 1,
                        "rejected by " + who + (reason == null ? "" : ": " + reason)));
                handleTerminalFailure(e, def, gate, "rejected by " + who);
                executions.save(e);
            }
        } finally {
            distributedLock.unlock(lockKey, token);
        }
        // continue outside the lock (run() re-acquires) if still runnable
        Execution after = executions.findById(executionId).orElseThrow();
        if (after.getStatus() == ExecutionStatus.RUNNING) {
            return run(executionId);
        }
        return after.getStatus();
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
