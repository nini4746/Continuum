package com.continuum.domain;

import jakarta.persistence.*;

/**
 * A runtime-evaluated policy rule (spec §3.5). NOT role-based: {@code condition}
 * matches on user attributes, request data, time, or execution history. Policies
 * live in the DB and are re-read on every evaluation, so changes take effect
 * immediately without a restart (spec §3.5.2).
 */
@Entity
@Table(name = "policies")
public class PolicyEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** Action this policy governs, e.g. "start"; "*" matches any action. */
    @Column(nullable = false)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PolicyEffect effect;

    /** Condition expression evaluated against the flattened policy context (blank = always matches). */
    @Column(length = 2000)
    private String condition;

    @Column(nullable = false)
    private int priority;

    @Column(nullable = false)
    private boolean enabled;

    protected PolicyEntity() {}

    public PolicyEntity(String name, String action, PolicyEffect effect, String condition, int priority, boolean enabled) {
        this.name = name;
        this.action = action;
        this.effect = effect;
        this.condition = condition;
        this.priority = priority;
        this.enabled = enabled;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getAction() { return action; }
    public PolicyEffect getEffect() { return effect; }
    public String getCondition() { return condition; }
    public int getPriority() { return priority; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
