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
