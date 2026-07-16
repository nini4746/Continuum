package com.continuum.repo;

import com.continuum.domain.WorkflowEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowRepo extends JpaRepository<WorkflowEntity, Long> {
    /** Latest registered version of a workflow (spec §3.1.2 "신규 실행만 최신 버전"). */
    Optional<WorkflowEntity> findTopByNameOrderByVersionDesc(String name);

    Optional<WorkflowEntity> findByNameAndVersion(String name, int version);

    /** Full version history, newest first. */
    List<WorkflowEntity> findByNameOrderByVersionDesc(String name);
}
