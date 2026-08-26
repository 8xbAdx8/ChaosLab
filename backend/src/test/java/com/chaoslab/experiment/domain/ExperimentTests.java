package com.chaoslab.experiment.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExperimentTests {

    @Test
    void shouldCreateExperimentInCreatedStatus() {
        Experiment experiment = createExperiment(30);

        assertThat(experiment.getStatus()).isEqualTo(ExperimentStatus.CREATED);
        assertThat(experiment.getVersion()).isZero();
        assertThat(experiment.getParameters()).isEqualTo("{\"percent\":40}");
    }

    @Test
    void shouldNormalizeNameAndHypothesis() {
        Experiment experiment = Experiment.create(
                UUID.randomUUID(),
                "  payment CPU experiment  ",
                "  Service remains available.  ",
                UUID.randomUUID(),
                UUID.randomUUID(),
                30,
                "{\"percent\":40}"
        );

        assertThat(experiment.getName()).isEqualTo("payment CPU experiment");
        assertThat(experiment.getHypothesis()).isEqualTo("Service remains available.");
    }

    @Test
    void shouldRejectDurationBelowMinimum() {
        assertThatThrownBy(() -> createExperiment(4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("durationSeconds must be between 5 and 60");
    }

    @Test
    void shouldRejectDurationAboveMaximum() {
        assertThatThrownBy(() -> createExperiment(61))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("durationSeconds must be between 5 and 60");
    }

    @Test
    void shouldTransitionFromCreatedToValidated() {
        Experiment created = createExperiment(30);

        Experiment validated = created.validate();

        assertThat(validated.getStatus()).isEqualTo(ExperimentStatus.VALIDATED);
        assertThat(validated.getId()).isEqualTo(created.getId());
        assertThat(validated.getVersion()).isEqualTo(created.getVersion());
    }

    @Test
    void shouldTreatRepeatedValidationAsIdempotent() {
        Experiment validated = createExperiment(30).validate();

        assertThat(validated.validate()).isSameAs(validated);
    }

    @Test
    void shouldTransitionFromValidatedToReady() {
        Experiment validated = createExperiment(30).validate();

        Experiment ready = validated.ready();

        assertThat(ready.getStatus()).isEqualTo(ExperimentStatus.READY);
        assertThat(ready.getId()).isEqualTo(validated.getId());
        assertThat(ready.getVersion()).isEqualTo(validated.getVersion());
    }

    @Test
    void shouldTreatRepeatedReadyTransitionAsIdempotent() {
        Experiment ready = createExperiment(30).validate().ready();

        assertThat(ready.ready()).isSameAs(ready);
        assertThat(ready.validate()).isSameAs(ready);
    }

    @Test
    void shouldRejectReadyTransitionBeforeValidation() {
        Experiment created = createExperiment(30);

        assertThatThrownBy(created::ready)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("experiment cannot be made ready from status CREATED");
    }

    @Test
    void shouldTransitionFromReadyToRunning() {
        Experiment ready = createExperiment(30).validate().ready();

        Experiment running = ready.start();

        assertThat(running.getStatus()).isEqualTo(ExperimentStatus.RUNNING);
        assertThat(running.getId()).isEqualTo(ready.getId());
        assertThat(running.getVersion()).isEqualTo(ready.getVersion());
    }

    @Test
    void shouldTreatRepeatedStartAsIdempotent() {
        Experiment running = createExperiment(30).validate().ready().start();

        assertThat(running.start()).isSameAs(running);
        assertThat(running.validate()).isSameAs(running);
    }

    @Test
    void shouldRejectStartBeforeReady() {
        Experiment validated = createExperiment(30).validate();

        assertThatThrownBy(validated::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("experiment cannot be started from status VALIDATED");
    }
    private Experiment createExperiment(int durationSeconds) {
        return Experiment.create(
                UUID.randomUUID(),
                "payment CPU experiment",
                "Service remains available.",
                UUID.randomUUID(),
                UUID.randomUUID(),
                durationSeconds,
                "{\"percent\":40}"
        );
    }
}
