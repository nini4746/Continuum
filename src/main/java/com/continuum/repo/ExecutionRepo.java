package com.continuum.repo;

import com.continuum.domain.Execution;
import com.continuum.domain.ExecutionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExecutionRepo extends JpaRepository<Execution, Long> {
    List<Execution> findByStatus(ExecutionStatus status);
}
