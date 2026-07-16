package com.continuum.repo;

import com.continuum.domain.EventRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventRecordRepo extends JpaRepository<EventRecord, Long> {
    List<EventRecord> findByExecutionIdOrderByCreatedAtAsc(Long executionId);
}
