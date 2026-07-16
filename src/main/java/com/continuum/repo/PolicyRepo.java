package com.continuum.repo;

import com.continuum.domain.PolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PolicyRepo extends JpaRepository<PolicyEntity, Long> {
    /** Enabled policies for an action or the wildcard, highest priority first. */
    List<PolicyEntity> findByEnabledTrueAndActionInOrderByPriorityDesc(List<String> actions);
}
