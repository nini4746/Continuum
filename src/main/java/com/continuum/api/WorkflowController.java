package com.continuum.api;

import com.continuum.domain.Execution;
import com.continuum.domain.WorkflowEntity;
import com.continuum.dto.WorkflowDef;
import com.continuum.engine.WorkflowEngine;
import com.continuum.repo.EventRecordRepo;
import com.continuum.repo.ExecutionRepo;
import com.continuum.repo.StepRecordRepo;
import com.continuum.repo.WorkflowRepo;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RestController
public class WorkflowController {

    private final WorkflowEngine engine;
    private final ExecutionRepo executions;
    private final StepRecordRepo stepRecords;
    private final WorkflowRepo workflows;
    private final EventRecordRepo eventRecords;

    public WorkflowController(WorkflowEngine engine, ExecutionRepo executions,
                              StepRecordRepo stepRecords, WorkflowRepo workflows,
                              EventRecordRepo eventRecords) {
        this.engine = engine;
        this.executions = executions;
        this.stepRecords = stepRecords;
        this.workflows = workflows;
        this.eventRecords = eventRecords;
    }

    @PostMapping("/workflows")
    public ResponseEntity<Map<String, Object>> register(@RequestBody WorkflowDef def) {
        WorkflowEntity wf = engine.register(def);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", wf.getId(), "name", wf.getName(), "version", wf.getVersion()));
    }

    @GetMapping("/workflows/{name}/versions")
    public Map<String, Object> versions(@PathVariable String name) {
        var history = workflows.findByNameOrderByVersionDesc(name);
        if (history.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return Map.of("name", name, "versions", history.stream()
                .map(w -> Map.of("id", w.getId(), "version", w.getVersion())).toList());
    }

    @PostMapping("/executions")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> start(@RequestBody Map<String, Object> body) {
        Object name = body.get("workflow");
        if (name == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "workflow required");
        Map<String, Object> ctx = body.get("context") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        Execution e = engine.start(String.valueOf(name), ctx);
        engine.run(e.getId());
        Execution after = executions.findById(e.getId()).orElseThrow();
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", after.getId(),
                "status", after.getStatus().name(),
                "cursor", after.getCursor()
        ));
    }

    @PostMapping("/executions/async")
    public ResponseEntity<Map<String, Object>> startAsync(@RequestBody Map<String, String> body) {
        String name = body.get("workflow");
        if (name == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "workflow required");
        Execution e = engine.start(name);
        var future = engine.runAsync(e.getId());
        long waitMs = parseWaitMs(body.get("waitMs"));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", e.getId());
        if (waitMs > 0) {
            try {
                future.get(waitMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                resp.put("status", "RUNNING");
                resp.put("note", "still running after " + waitMs + "ms");
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(resp);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "interrupted");
            } catch (ExecutionException ee) {
                // Do not leak raw exception text to clients; the engine already stores
                // a sanitized error on the execution record.
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "async execution failed; see GET /api/executions/" + e.getId());
            }
        }
        Execution after = executions.findById(e.getId()).orElseThrow();
        resp.put("status", after.getStatus().name());
        resp.put("cursor", after.getCursor());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(resp);
    }

    private static long parseWaitMs(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            long v = Long.parseLong(raw);
            return Math.max(0, Math.min(v, 30_000));
        } catch (NumberFormatException nfe) {
            return 0;
        }
    }

    @PostMapping("/executions/{id}/approve")
    public Map<String, Object> approve(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        String actor = body == null ? null : String.valueOf(body.getOrDefault("actor", "unknown"));
        try {
            var status = engine.approve(id, actor);
            return Map.of("id", id, "status", status.name());
        } catch (IllegalStateException ise) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ise.getMessage());
        } catch (IllegalArgumentException iae) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, iae.getMessage());
        }
    }

    @PostMapping("/executions/{id}/reject")
    public Map<String, Object> reject(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        String actor = body == null ? null : String.valueOf(body.getOrDefault("actor", "unknown"));
        String reason = body == null ? null : (body.get("reason") == null ? null : String.valueOf(body.get("reason")));
        try {
            var status = engine.reject(id, actor, reason);
            return Map.of("id", id, "status", status.name());
        } catch (IllegalStateException ise) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ise.getMessage());
        } catch (IllegalArgumentException iae) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, iae.getMessage());
        }
    }

    @GetMapping("/executions/{id}/events")
    public Map<String, Object> events(@PathVariable Long id) {
        return Map.of("id", id, "events", eventRecords.findByExecutionIdOrderByCreatedAtAsc(id).stream()
                .map(ev -> Map.of(
                        "type", ev.getType().name(),
                        "stepId", ev.getStepId() == null ? "" : ev.getStepId(),
                        "message", ev.getMessage() == null ? "" : ev.getMessage()
                )).toList());
    }

    @GetMapping("/executions/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        Execution e = executions.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", e.getId());
        body.put("status", e.getStatus().name());
        body.put("workflowVersion", e.getWorkflowVersion());
        body.put("cursor", e.getCursor());
        body.put("lastError", e.getLastError());
        body.put("history", stepRecords.findByExecutionIdOrderByCreatedAtAsc(e.getId()).stream()
                .map(r -> Map.of(
                        "stepId", r.getStepId(),
                        "status", r.getStatus().name(),
                        "attempt", r.getAttempt(),
                        "message", r.getMessage() == null ? "" : r.getMessage()
                )).toList());
        return body;
    }
}
