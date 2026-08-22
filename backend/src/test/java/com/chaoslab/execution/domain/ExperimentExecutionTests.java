package com.chaoslab.execution.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExperimentExecutionTests {

    private static final Instant CREATED_AT = Instant.parse("2026-08-22T00:00:00Z");

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
