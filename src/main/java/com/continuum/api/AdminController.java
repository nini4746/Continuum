package com.continuum.api;

import com.continuum.domain.Execution;
import com.continuum.domain.ExecutionStatus;
import com.continuum.engine.WorkflowEngine;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/** Admin / operations endpoints (spec §3.7). */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final WorkflowEngine engine;

    public AdminController(WorkflowEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/executions/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        String actor = actorOf(body);
        try {
            return Map.of("id", id, "status", engine.cancel(id, actor).name());
        } catch (IllegalStateException ise) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ise.getMessage());
        } catch (IllegalArgumentException iae) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, iae.getMessage());
        }
    }

    @PostMapping("/executions/{id}/steps/{stepId}/rerun")
    public Map<String, Object> rerun(@PathVariable Long id, @PathVariable String stepId,
                                     @RequestBody(required = false) Map<String, Object> body) {
        try {
            return Map.of("id", id, "status", engine.rerunStep(id, stepId, actorOf(body)).name());
        } catch (IllegalStateException ise) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ise.getMessage());
        } catch (IllegalArgumentException iae) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, iae.getMessage());
        }
    }

    @PostMapping("/executions/{id}/force-status")
    public Map<String, Object> forceStatus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Object status = body.get("status");
        Object reason = body.get("reason");
        if (status == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status required");
        if (reason == null || String.valueOf(reason).isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason required");
        ExecutionStatus target;
        try {
            target = ExecutionStatus.valueOf(String.valueOf(status));
        } catch (IllegalArgumentException iae) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid status: " + status);
        }
        try {
            engine.forceStatus(id, target, actorOf(body), String.valueOf(reason));
            return Map.of("id", id, "status", target.name());
        } catch (IllegalArgumentException iae) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, iae.getMessage());
        }
    }

    @GetMapping("/executions/dead")
    public Map<String, Object> dead(@RequestParam(defaultValue = "30") long minutes) {
        List<Execution> dead = engine.deadExecutions(minutes);
        return Map.of("thresholdMinutes", minutes, "count", dead.size(),
                "ids", dead.stream().map(Execution::getId).toList());
    }

    @GetMapping("/stats")
    public Map<String, Long> stats() {
        return engine.stats();
    }

    private static String actorOf(Map<String, Object> body) {
        return body == null ? "admin" : String.valueOf(body.getOrDefault("actor", "admin"));
    }
}
