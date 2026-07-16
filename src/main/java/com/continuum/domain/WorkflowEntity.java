package com.continuum.domain;

import jakarta.persistence.*;

/**
 * One immutable version of a workflow definition (spec §3.1.2). A given
 * {@code name} may have many rows, one per {@code version}; history is never
 * mutated - a changed definition becomes a new row with the next version.
 */
@Entity
@Table(
        name = "workflows",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_workflows_name_version",
                columnNames = {"name", "version"}
        )
)
public class WorkflowEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int version;

    @Lob
    @Column(nullable = false)
    private String definitionJson;

    protected WorkflowEntity() {}

    public WorkflowEntity(String name, int version, String definitionJson) {
        this.name = name;
        this.version = version;
        this.definitionJson = definitionJson;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public int getVersion() { return version; }
    public String getDefinitionJson() { return definitionJson; }
}
