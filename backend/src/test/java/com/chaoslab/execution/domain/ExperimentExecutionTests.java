package com.chaoslab.execution.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExperimentExecutionTests {

    private static final Instant CREATED_AT = Instant.parse("2026-08-22T00:00:00Z");

    @Test
    void uncertainCreateHasNoInventedRecoveryIdentityOrAutomaticExit() {
        var uncertain = execution().markCreateUncertain();
        assertThat(uncertain.getStatus()).isEqualTo(ExperimentExecutionStatus.CREATE_UNCERTAIN);
        assertThat(uncertain.getEngineExperimentId()).isNull();
        assertThat(uncertain.getStartedAt()).isNull();
        assertThat(uncertain.getFinishedAt()).isNull();
        assertThat(uncertain.markCreateUncertain()).isSameAs(uncertain);
        assertThatThrownBy(() -> uncertain.markRunning("guessed", CREATED_AT)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> uncertain.markFailed("assumed failure")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(uncertain::beginDestroy).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> uncertain.markSuccess(CREATED_AT)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cannotHideAnExistingEngineHandleAsAnUncertainCreate() {
        var running = execution().markRunning("fake-id", CREATED_AT);
        assertThatThrownBy(running::markCreateUncertain).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ExperimentExecution.rehydrate(UUID.randomUUID(), UUID.randomUUID(), 1,
                "key", ExperimentExecutionStatus.CREATE_UNCERTAIN, "guessed", "uncertain",
                CREATED_AT, null, null, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void uncertainCreateMayRetainOnlyItsCommittedJournalReference() {
        var prepared = execution();
        var uncertain = prepared.markCreateUncertain("blade-"+prepared.getId());
        assertThat(uncertain.getStartedAt()).isNull();
        assertThat(uncertain.beginUncertainDestroy(CREATED_AT.plusSeconds(5)).getStatus()).isEqualTo(ExperimentExecutionStatus.DESTROYING);
        assertThatThrownBy(() -> prepared.markCreateUncertain("blade-"+UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> prepared.markCreateUncertain().beginUncertainDestroy(CREATED_AT)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldPrepareFirstExecutionAttempt() {
        ExperimentExecution execution = execution();

        assertThat(execution.getAttempt()).isEqualTo(1);
        assertThat(execution.getIdempotencyKey()).isEqualTo("request-001");
        assertThat(execution.getStatus()).isEqualTo(ExperimentExecutionStatus.PREPARING);
        assertThat(execution.getEngineExperimentId()).isNull();
        assertThat(execution.getVersion()).isZero();
    }

    @Test
    void shouldTransitionFromPreparingToRunning() {
        Instant startedAt = CREATED_AT.plusSeconds(1);

        ExperimentExecution running = execution().markRunning(
                "fake-execution-001",
                startedAt
        );

        assertThat(running.getStatus()).isEqualTo(ExperimentExecutionStatus.RUNNING);
        assertThat(running.getEngineExperimentId()).isEqualTo("fake-execution-001");
        assertThat(running.getStartedAt()).isEqualTo(startedAt);
        assertThat(running.getErrorMessage()).isNull();
    }

    @Test
    void shouldTreatRepeatedRunningResultAsIdempotent() {
        Instant startedAt = CREATED_AT.plusSeconds(1);
        ExperimentExecution running = execution().markRunning(
                "fake-execution-001",
                startedAt
        );

        assertThat(running.markRunning("fake-execution-001", startedAt))
                .isSameAs(running);
    }

    @Test
    void shouldTransitionFromPreparingToFailed() {
        ExperimentExecution failed = execution().markFailed("engine create failed");

        assertThat(failed.getStatus()).isEqualTo(ExperimentExecutionStatus.FAILED);
        assertThat(failed.getErrorMessage()).isEqualTo("engine create failed");
        assertThat(failed.getEngineExperimentId()).isNull();
    }

    @Test
    void shouldRejectRunningAfterFailure() {
        ExperimentExecution failed = execution().markFailed("engine create failed");

        assertThatThrownBy(() -> failed.markRunning(
                "fake-execution-001",
                CREATED_AT.plusSeconds(1)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("cannot mark running execution from status FAILED");
    }

    @Test
    void shouldRejectStartTimeBeforeCreation() {
        assertThatThrownBy(() -> execution().markRunning(
                "fake-execution-001",
                CREATED_AT.minusSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("startedAt must not be before createdAt");
    }

    @Test
    void shouldCompleteDestroyLifecycle() {
        Instant startedAt = CREATED_AT.plusSeconds(1);
        Instant finishedAt = startedAt.plusSeconds(30);
        ExperimentExecution running = execution().markRunning(
                "fake-execution-001",
                startedAt
        );

        ExperimentExecution destroying = running.beginDestroy();
        ExperimentExecution successful = destroying.markSuccess(finishedAt);

        assertThat(destroying.getStatus())
                .isEqualTo(ExperimentExecutionStatus.DESTROYING);
        assertThat(successful.getStatus())
                .isEqualTo(ExperimentExecutionStatus.SUCCESS);
        assertThat(successful.getFinishedAt()).isEqualTo(finishedAt);
        assertThat(successful.getEngineExperimentId())
                .isEqualTo("fake-execution-001");
    }

    @Test
    void shouldRetainEngineDataAndAllowRetryAfterRollbackFailure() {
        ExperimentExecution running = execution().markRunning(
                "fake-execution-001",
                CREATED_AT.plusSeconds(1)
        );

        ExperimentExecution rollbackFailed = running.beginDestroy()
                .markRollbackFailed("engine destroy failed");
        ExperimentExecution retrying = rollbackFailed.beginDestroy();

        assertThat(rollbackFailed.getStatus())
                .isEqualTo(ExperimentExecutionStatus.ROLLBACK_FAILED);
        assertThat(rollbackFailed.getEngineExperimentId())
                .isEqualTo("fake-execution-001");
        assertThat(rollbackFailed.getErrorMessage())
                .isEqualTo("engine destroy failed");
        assertThat(retrying.getStatus())
                .isEqualTo(ExperimentExecutionStatus.DESTROYING);
        assertThat(retrying.getErrorMessage()).isNull();
    }

    @Test
    void shouldRejectFinishedTimeBeforeStartTime() {
        ExperimentExecution destroying = execution().markRunning(
                "fake-execution-001",
                CREATED_AT.plusSeconds(10)
        ).beginDestroy();

        assertThatThrownBy(() -> destroying.markSuccess(
                CREATED_AT.plusSeconds(9)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("finishedAt must not be before startedAt");
    }
    private ExperimentExecution execution() {
        return ExperimentExecution.prepare(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                " request-001 ",
                CREATED_AT
        );
    }
}
