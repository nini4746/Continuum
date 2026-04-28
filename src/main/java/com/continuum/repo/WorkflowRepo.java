package com.continuum.repo;

import com.continuum.domain.WorkflowEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WorkflowRepo extends JpaRepository<WorkflowEntity, Long> {
    Optional<WorkflowEntity> findByName(String name);
}
