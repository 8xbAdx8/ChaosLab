package com.chaoslab.execution.application;

import com.chaoslab.engine.application.EngineCreateUncertainException;
import com.chaoslab.engine.application.port.ChaosEngine;
import com.chaoslab.execution.application.port.ExperimentExecutionRepository;
import com.chaoslab.execution.domain.ExperimentExecution;
import com.chaoslab.execution.domain.ExperimentExecutionStatus;
import com.chaoslab.experiment.application.port.ExperimentRepository;
import com.chaoslab.experiment.domain.Experiment;
import com.chaoslab.target.application.port.TargetRepository;
import com.chaoslab.target.domain.Target;
import com.chaoslab.target.domain.TargetEnvironment;
import com.chaoslab.target.domain.TargetType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** No enclosing test transaction: service commits are read back through new transactions. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:uncertain-create;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "chaoslab.execution.max-active-executions=1"
})
@AutoConfigureMockMvc
class UncertainCreatePersistenceIntegrationTests {
    @Autowired AuditedExperimentExecutionApplicationService executions;
    @Autowired ExperimentExecutionApplicationService delegate;
    @Autowired ExperimentExecutionRepository repository;
    @Autowired ExperimentRepository experiments;
    @Autowired TargetRepository targets;
    @Autowired MockMvc mvc;
    @MockitoBean ChaosEngine engine;

    @Test
    void commitsUncertaintyAndRetainsAdmissionUntilManualReconciliation() throws Exception {
        when(engine.simulated()).thenReturn(true);
        when(engine.create(any())).thenThrow(new EngineCreateUncertainException());
        var experiment = readyExperiment(UUID.randomUUID());
        var first = executions.start(experiment.getId(), "uncertain-request");
        var restored = repository.findById(first.execution().id()).orElseThrow();
        assertThat(restored.getStatus()).isEqualTo(ExperimentExecutionStatus.CREATE_UNCERTAIN);
        assertThat(restored.getEngineExperimentId()).isNull();
        assertThat(restored.getVersion()).isEqualTo(1);
        assertThat(repository.findAllByStatus(ExperimentExecutionStatus.CREATE_UNCERTAIN))
                .extracting(ExperimentExecution::getId).contains(restored.getId());
        var replay = executions.start(experiment.getId(), "uncertain-request");
        assertThat(replay.created()).isFalse();
        assertThat(replay.execution().id()).isEqualTo(restored.getId());
        assertThat(replay.execution().status()).isEqualTo(ExperimentExecutionStatus.CREATE_UNCERTAIN);
        assertThatThrownBy(() -> executions.start(experiment.getId(), "new-key"))
                .isInstanceOf(ExperimentExecutionStartRejectedException.class)
                .extracting("code").isEqualTo("TARGET_EXECUTION_ALREADY_ACTIVE");
        var other = readyExperiment(UUID.randomUUID());
        assertThatThrownBy(() -> executions.start(other.getId(), "other-target"))
                .isInstanceOf(ExperimentExecutionStartRejectedException.class)
                .extracting("code").isEqualTo("GLOBAL_EXECUTION_LIMIT_REACHED");
        assertThatThrownBy(() -> executions.destroy(experiment.getId(), restored.getId()))
                .isInstanceOf(ExperimentExecutionDestroyRejectedException.class);
        assertThat(delegate.findExpired(Instant.now().plusSeconds(3600))).isEmpty();
        mvc.perform(post("/api/v1/emergency-stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateCount").value(1))
                .andExpect(jsonPath("$.manualInterventionCount").value(1))
                .andExpect(jsonPath("$.recoveredCount").value(0))
                .andExpect(jsonPath("$.executions[0].outcome").value("MANUAL_INTERVENTION"));
        assertThat(repository.findById(restored.getId()).orElseThrow().getStatus())
                .isEqualTo(ExperimentExecutionStatus.CREATE_UNCERTAIN);
        verify(engine, times(1)).create(any());
        verify(engine, never()).destroy(any());
        verify(engine, never()).status(any());
    }

    private Experiment readyExperiment(UUID targetId) {
        targets.save(Target.register(targetId, "uncertain-" + targetId,
                TargetType.JAVA_APPLICATION, TargetEnvironment.CHAOS_LAB));
        return experiments.insert(Experiment.create(UUID.randomUUID(), "uncertain test",
                "Service remains available", targetId,
                UUID.fromString("00000000-0000-0000-0000-000000000101"), 30,
                "{\"percent\":40}").validate().ready());
    }
}
