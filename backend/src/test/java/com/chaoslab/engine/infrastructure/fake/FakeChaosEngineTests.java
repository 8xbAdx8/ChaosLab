package com.chaoslab.engine.infrastructure.fake;

import com.chaoslab.engine.application.EngineExperimentNotFoundException;
import com.chaoslab.engine.application.model.EngineCreateResult;
import com.chaoslab.engine.application.model.EngineExperimentId;
import com.chaoslab.engine.application.model.EngineStatus;
import com.chaoslab.engine.application.model.ReadyExperimentRequest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakeChaosEngineTests {

    private final FakeChaosEngine engine = new FakeChaosEngine();

    @Test
    void shouldCreateDeterministicRunningExperiment() {
        ReadyExperimentRequest request = request();

        EngineCreateResult first = engine.create(request);
        EngineCreateResult repeated = engine.create(request);

        assertThat(first.status()).isEqualTo(EngineStatus.RUNNING);
        assertThat(first.engineExperimentId().value())
                .isEqualTo("fake-" + request.executionId());
        assertThat(repeated).isEqualTo(first);
    }

    @Test
    void shouldReportCurrentStatus() {
        EngineCreateResult created = engine.create(request());

        assertThat(engine.status(created.engineExperimentId()).status())
                .isEqualTo(EngineStatus.RUNNING);
    }

    @Test
    void shouldDestroyIdempotently() {
        EngineCreateResult created = engine.create(request());

        assertThat(engine.destroy(created.engineExperimentId()).status())
                .isEqualTo(EngineStatus.DESTROYED);
        assertThat(engine.destroy(created.engineExperimentId()).status())
                .isEqualTo(EngineStatus.DESTROYED);
        assertThat(engine.status(created.engineExperimentId()).status())
                .isEqualTo(EngineStatus.DESTROYED);
    }

    @Test
    void shouldRejectUnknownEngineExperiment() {
        EngineExperimentId unknown = new EngineExperimentId("fake-unknown");

        assertThatThrownBy(() -> engine.status(unknown))
                .isInstanceOf(EngineExperimentNotFoundException.class)
                .hasMessage("engine experiment not found: fake-unknown");
    }

    private ReadyExperimentRequest request() {
        return new ReadyExperimentRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "CPU_LOAD",
                30,
                "{\"percent\":40}"
        );
    }
}
