package com.continuum.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "workflows")
public class WorkflowEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Lob
    @Column(nullable = false)
    private String definitionJson;

    protected WorkflowEntity() {}

    public WorkflowEntity(String name, String definitionJson) {
        this.name = name;
        this.definitionJson = definitionJson;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDefinitionJson() { return definitionJson; }
}
