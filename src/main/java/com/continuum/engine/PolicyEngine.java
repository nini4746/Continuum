package com.continuum.engine;

import com.continuum.domain.PolicyEffect;
import com.continuum.domain.PolicyEntity;
import com.continuum.repo.PolicyRepo;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Runtime policy evaluation (spec §3.5). Policies are read from the DB on every
 * call, so edits take effect immediately without a restart. First matching policy
 * by descending priority wins (firewall-style); with no match the default is
 * ALLOW. Conditions are evaluated over the flattened context (user attributes,
 * request data, time, history) - deliberately not role-based.
 */
@Component
public class PolicyEngine {

    private final PolicyRepo policies;
    private final ConditionEvaluator conditions;

    public PolicyEngine(PolicyRepo policies, ConditionEvaluator conditions) {
        this.policies = policies;
        this.conditions = conditions;
    }

    public record Decision(boolean allowed, String policyName, String reason) {}

    public Decision evaluate(String action, Map<String, Object> context) {
        List<PolicyEntity> matching = policies.findByEnabledTrueAndActionInOrderByPriorityDesc(
                List.of(action, "*"));
        for (PolicyEntity p : matching) {
            String cond = p.getCondition();
            boolean matches = (cond == null || cond.isBlank()) || conditions.eval(cond, context);
            if (matches) {
                boolean allowed = p.getEffect() == PolicyEffect.ALLOW;
                return new Decision(allowed, p.getName(),
                        (allowed ? "allowed" : "denied") + " by policy '" + p.getName() + "'");
            }
        }
        return new Decision(true, null, "no matching policy (default allow)");
    }
}
