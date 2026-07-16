package com.continuum.engine;

import com.continuum.domain.AuditLog;
import com.continuum.domain.ExecutionStatus;
import com.continuum.repo.AuditLogRepo;
import org.springframework.stereotype.Component;

/** Writes execution-transition audit entries (spec §3.6.1). */
@Component
public class AuditService {

    private final AuditLogRepo repo;

    public AuditService(AuditLogRepo repo) {
        this.repo = repo;
    }

    public void record(Long executionId, ExecutionStatus from, ExecutionStatus to,
                       String actor, String reason) {
        repo.save(new AuditLog(executionId, from, to,
                (actor == null || actor.isBlank()) ? "system" : actor, reason));
    }
}
