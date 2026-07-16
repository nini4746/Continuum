package com.continuum;

import com.continuum.domain.PolicyEffect;
import com.continuum.domain.PolicyEntity;
import com.continuum.dto.*;
import com.continuum.engine.PolicyDeniedException;
import com.continuum.engine.WorkflowEngine;
import com.continuum.repo.PolicyRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Policy & permission engine (spec §3.5): runtime evaluation of attribute/data
 * conditions (not role-based) at start time, with edits taking effect immediately.
 */
@SpringBootTest
@DirtiesContext(classMode = ClassMode.BEFORE_EACH_TEST_METHOD)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:cont_${random.uuid};DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class PolicyTests {

    @Autowired private WorkflowEngine engine;
    @Autowired private PolicyRepo policies;

    private void registerWf() {
        engine.register(new WorkflowDef("pol", List.of(
                new StepDef("s", "noop", Map.of(), RetryPolicy.none(), OnFailure.ABORT, null, Map.of()))));
    }

    @Test
    void no_policies_defaults_to_allow() {
        registerWf();
        assertNotNull(engine.start("pol", Map.of("amount", 5000)));   // nothing blocks
    }

    @Test
    void deny_policy_blocks_when_condition_matches() {
        registerWf();
        policies.save(new PolicyEntity("big-amount", "start", PolicyEffect.DENY, "amount > 1000", 10, true));

        assertThrows(PolicyDeniedException.class, () -> engine.start("pol", Map.of("amount", 2000)));
        assertNotNull(engine.start("pol", Map.of("amount", 500)));    // condition false -> allowed
    }

    @Test
    void user_attribute_condition_via_dotted_key() {
        registerWf();
        policies.save(new PolicyEntity("block-guest", "start", PolicyEffect.DENY, "user.tier == \"guest\"", 10, true));
        assertThrows(PolicyDeniedException.class,
                () -> engine.start("pol", Map.of("user", Map.of("tier", "guest"))));
        assertNotNull(engine.start("pol", Map.of("user", Map.of("tier", "premium"))));
    }

    @Test
    void disabling_a_policy_takes_effect_immediately() {
        registerWf();
        PolicyEntity p = policies.save(new PolicyEntity("big-amount", "start", PolicyEffect.DENY, "amount > 1000", 10, true));
        assertThrows(PolicyDeniedException.class, () -> engine.start("pol", Map.of("amount", 2000)));

        p.setEnabled(false);
        policies.save(p);   // runtime change, no restart
        assertNotNull(engine.start("pol", Map.of("amount", 2000)));
    }

    @Test
    void higher_priority_policy_wins() {
        registerWf();
        // both match amount>1000; DENY has higher priority so it decides first
        policies.save(new PolicyEntity("allow-all", "start", PolicyEffect.ALLOW, "", 1, true));
        policies.save(new PolicyEntity("deny-big", "start", PolicyEffect.DENY, "amount > 1000", 10, true));
        assertThrows(PolicyDeniedException.class, () -> engine.start("pol", Map.of("amount", 2000)));
    }
}
