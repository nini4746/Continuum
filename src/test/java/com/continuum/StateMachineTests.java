package com.continuum;

import com.continuum.domain.Execution;
import com.continuum.domain.ExecutionStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Directly exercises the execution state machine (spec §3.2.1, §4.3 "상태 전이
 * 단위 테스트 필수"): every allowed edge succeeds, every undeclared edge is rejected.
 */
class StateMachineTests {

    private Execution fresh() {
        return new Execution(1L);
    }

    @Test
    void new_execution_starts_in_created() {
        assertEquals(ExecutionStatus.CREATED, fresh().getStatus());
    }

    @Test
    void happy_path_created_running_completed() {
        Execution e = fresh();
        e.markStatus(ExecutionStatus.RUNNING);
        assertEquals(ExecutionStatus.RUNNING, e.getStatus());
        e.markStatus(ExecutionStatus.COMPLETED);
        assertEquals(ExecutionStatus.COMPLETED, e.getStatus());
    }

    @Test
    void waiting_cycle_running_waiting_running() {
        Execution e = fresh();
        e.markStatus(ExecutionStatus.RUNNING);
        e.markStatus(ExecutionStatus.WAITING);
        assertEquals(ExecutionStatus.WAITING, e.getStatus());
        e.markStatus(ExecutionStatus.RUNNING);   // approval resumes
        assertEquals(ExecutionStatus.RUNNING, e.getStatus());
    }

    @Test
    void cancel_allowed_from_created_running_waiting() {
        for (ExecutionStatus from : new ExecutionStatus[]{ExecutionStatus.CREATED, ExecutionStatus.RUNNING, ExecutionStatus.WAITING}) {
            Execution e = fresh();
            if (from != ExecutionStatus.CREATED) e.markStatus(ExecutionStatus.RUNNING);
            if (from == ExecutionStatus.WAITING) e.markStatus(ExecutionStatus.WAITING);
            e.markStatus(ExecutionStatus.CANCELED);
            assertEquals(ExecutionStatus.CANCELED, e.getStatus());
        }
    }

    @Test
    void failed_can_compensate() {
        Execution e = fresh();
        e.markStatus(ExecutionStatus.RUNNING);
        e.markStatus(ExecutionStatus.FAILED);
        e.markStatus(ExecutionStatus.COMPENSATED);
        assertEquals(ExecutionStatus.COMPENSATED, e.getStatus());
    }

    @Test
    void illegal_edge_created_to_completed_rejected() {
        Execution e = fresh();
        assertThrows(IllegalStateException.class, () -> e.markStatus(ExecutionStatus.COMPLETED));
        assertEquals(ExecutionStatus.CREATED, e.getStatus());  // unchanged
    }

    @Test
    void terminal_states_reject_further_transitions() {
        Execution e = fresh();
        e.markStatus(ExecutionStatus.RUNNING);
        e.markStatus(ExecutionStatus.COMPLETED);
        assertTrue(e.getStatus().isTerminal());
        assertThrows(IllegalStateException.class, () -> e.markStatus(ExecutionStatus.RUNNING));
    }

    @Test
    void same_state_is_idempotent_noop() {
        Execution e = fresh();
        e.markStatus(ExecutionStatus.RUNNING);
        assertDoesNotThrow(() -> e.markStatus(ExecutionStatus.RUNNING));
        assertEquals(ExecutionStatus.RUNNING, e.getStatus());
    }

    @Test
    void force_status_bypasses_rules() {
        Execution e = fresh();
        e.markStatus(ExecutionStatus.RUNNING);
        e.markStatus(ExecutionStatus.COMPLETED);      // terminal
        e.forceStatus(ExecutionStatus.CANCELED);      // admin override
        assertEquals(ExecutionStatus.CANCELED, e.getStatus());
    }
}
