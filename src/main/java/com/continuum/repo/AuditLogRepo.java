package com.continuum.repo;

import com.continuum.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepo extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findByExecutionIdOrderByCreatedAtAsc(Long executionId);
}
