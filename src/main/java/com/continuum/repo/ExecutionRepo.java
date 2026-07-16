package com.continuum.repo;

import com.continuum.domain.Execution;
import com.continuum.domain.ExecutionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ExecutionRepo extends JpaRepository<Execution, Long> {
    List<Execution> findByStatus(ExecutionStatus status);

    long countByStatus(ExecutionStatus status);

    /** RUNNING executions untouched since {@code before} = candidate dead executions (spec §3.7). */
    List<Execution> findByStatusAndUpdatedAtBefore(ExecutionStatus status, Instant before);
}
