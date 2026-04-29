package com.continuum.api;

import com.continuum.domain.Execution;
import com.continuum.domain.WorkflowEntity;
import com.continuum.dto.WorkflowDef;
import com.continuum.engine.WorkflowEngine;
import com.continuum.repo.ExecutionRepo;
import com.continuum.repo.StepRecordRepo;
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

    public WorkflowController(WorkflowEngine engine, ExecutionRepo executions, StepRecordRepo stepRecords) {
        this.engine = engine;
        this.executions = executions;
        this.stepRecords = stepRecords;
    }

    @PostMapping("/workflows")
    public ResponseEntity<Map<String, Object>> register(@RequestBody WorkflowDef def) {
        WorkflowEntity wf = engine.register(def);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", wf.getId(), "name", wf.getName()));
    }

    @PostMapping("/executions")
    public ResponseEntity<Map<String, Object>> start(@RequestBody Map<String, String> body) {
        String name = body.get("workflow");
        if (name == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "workflow required");
        Execution e = engine.start(name);
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
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "async execution failed: " + ee.getCause().getMessage());
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

    @GetMapping("/executions/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        Execution e = executions.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", e.getId());
        body.put("status", e.getStatus().name());
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
