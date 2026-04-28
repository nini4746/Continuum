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
