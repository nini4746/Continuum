package com.continuum.repo;

import com.continuum.domain.StepRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StepRecordRepo extends JpaRepository<StepRecord, Long> {
    List<StepRecord> findByExecutionIdOrderByCreatedAtAsc(Long executionId);

    Optional<StepRecord> findFirstByExecutionIdAndStepIdOrderByAttemptDesc(Long executionId, String stepId);
}
