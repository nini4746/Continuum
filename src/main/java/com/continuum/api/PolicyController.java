package com.continuum.api;

import com.continuum.domain.PolicyEffect;
import com.continuum.domain.PolicyEntity;
import com.continuum.repo.PolicyRepo;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Policy management (spec §3.5). Create/list/toggle/delete policies; changes are
 * picked up on the next evaluation with no restart.
 */
@RestController
@RequestMapping("/policies")
public class PolicyController {

    private final PolicyRepo policies;

    public PolicyController(PolicyRepo policies) {
        this.policies = policies;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String name = str(body.get("name"), null);
        String action = str(body.get("action"), "*");
        String effectStr = str(body.get("effect"), "ALLOW");
        String condition = str(body.get("condition"), "");
        int priority = body.get("priority") instanceof Number n ? n.intValue() : 0;
        boolean enabled = !(body.get("enabled") instanceof Boolean b) || b;
        if (name == null || name.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name required");
        PolicyEffect effect;
        try {
            effect = PolicyEffect.valueOf(effectStr);
        } catch (IllegalArgumentException iae) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid effect: " + effectStr);
        }
        PolicyEntity saved = policies.save(new PolicyEntity(name, action, effect, condition, priority, enabled));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", saved.getId(), "name", saved.getName()));
    }

    @GetMapping
    public Object list() {
        return policies.findAll().stream().map(p -> Map.of(
                "id", p.getId(), "name", p.getName(), "action", p.getAction(),
                "effect", p.getEffect().name(), "condition", p.getCondition() == null ? "" : p.getCondition(),
                "priority", p.getPriority(), "enabled", p.isEnabled())).toList();
    }

    @PatchMapping("/{id}/enabled")
    public Map<String, Object> toggle(@PathVariable Long id, @RequestParam boolean value) {
        PolicyEntity p = policies.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        p.setEnabled(value);
        policies.save(p);
        return Map.of("id", id, "enabled", value);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        policies.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private static String str(Object o, String dflt) {
        return o == null ? dflt : String.valueOf(o);
    }
}
