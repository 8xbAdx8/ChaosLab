package com.chaoslab.execution.interfaces.rest;

import com.chaoslab.target.application.port.TargetRepository;
import com.chaoslab.target.domain.Target;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:execution-api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@AutoConfigureMockMvc
class ExperimentExecutionApiIntegrationTests {

    private static final String CPU_LOAD_ID =
            "00000000-0000-0000-0000-000000000101";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TargetRepository targetRepository;

    @Test
    void shouldStartQueryAndReplayExecutionWithoutDuplicateInjection()
            throws Exception {
        ReadyExperiment ready = createReadyExperiment();
        String executionsPath = ready.experimentPath() + "/executions";

        MvcResult start = mockMvc.perform(post(executionsPath)
                        .header("Idempotency-Key", "request-001"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.experimentId")
                        .value(ready.experimentId().toString()))
                .andExpect(jsonPath("$.attempt").value(1))
                .andExpect(jsonPath("$.idempotencyKey").value("request-001"))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.engineExperimentId")
                        .value(org.hamcrest.Matchers.startsWith("fake-")))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn();

        String executionLocation = start.getResponse().getHeader("Location");
        assertThat(executionLocation).isNotNull();
        String executionPath = URI.create(executionLocation).getPath();
        String executionId = executionPath.substring(
                executionPath.lastIndexOf('/') + 1
        );

        mockMvc.perform(get(executionPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(executionId))
                .andExpect(jsonPath("$.status").value("RUNNING"));

        mockMvc.perform(post(executionsPath)
                        .header("Idempotency-Key", "request-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(executionId))
                .andExpect(jsonPath("$.attempt").value(1))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(get(ready.experimentPath()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void shouldDestroyExecutionIdempotentlyAndCompleteExperiment()
            throws Exception {
        ReadyExperiment ready = createReadyExperiment();
        MvcResult start = mockMvc.perform(post(
                        ready.experimentPath() + "/executions"
                ).header("Idempotency-Key", "request-destroy"))
                .andExpect(status().isCreated())
                .andReturn();
        String executionLocation = start.getResponse().getHeader("Location");
        assertThat(executionLocation).isNotNull();
        String destroyPath = URI.create(executionLocation).getPath() + "/destroy";

        mockMvc.perform(post(destroyPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.finishedAt").exists())
                .andExpect(jsonPath("$.errorMessage").doesNotExist())
                .andExpect(jsonPath("$.version").value(3));

        mockMvc.perform(post(destroyPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.version").value(3));

        mockMvc.perform(get(ready.experimentPath()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.version").value(5));
    }
    @Test
    void shouldRejectExecutionBeforeDryRun() throws Exception {
        String targetId = registerTarget();
        String experimentPath = createExperiment(targetId);
        mockMvc.perform(post(experimentPath + "/validation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"));

        mockMvc.perform(post(experimentPath + "/executions")
                        .header("Idempotency-Key", "request-001"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXPERIMENT_NOT_READY"));
    }

    @Test
    void shouldRejectMissingAndBlankIdempotencyKey() throws Exception {
        String executionsPath = "/api/v1/experiments/"
                + UUID.randomUUID()
                + "/executions";

        mockMvc.perform(post(executionsPath))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        mockMvc.perform(post(executionsPath)
                        .header("Idempotency-Key", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
    }

    @Test
    void shouldRejectSafetyDriftBeforeCallingEngine() throws Exception {
        ReadyExperiment ready = createReadyExperiment();
        Target target = targetRepository.findById(ready.targetId()).orElseThrow();
        target.disable();
        targetRepository.save(target);

        mockMvc.perform(post(ready.experimentPath() + "/executions")
                        .header("Idempotency-Key", "request-drift"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SAFETY_CHECK_REJECTED"))
                .andExpect(jsonPath("$.violations[0].keyword")
                        .value("TARGET_ENABLED"));

        mockMvc.perform(get(ready.experimentPath()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.version").value(2));
    }

    private ReadyExperiment createReadyExperiment() throws Exception {
        String targetId = registerTarget();
        String experimentPath = createExperiment(targetId);
        mockMvc.perform(post(experimentPath + "/validation"))
                .andExpect(status().isOk());
        mockMvc.perform(post(experimentPath + "/dry-run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.experiment.status").value("READY"));
        String experimentId = experimentPath.substring(
                experimentPath.lastIndexOf('/') + 1
        );
        return new ReadyExperiment(
                experimentPath,
                UUID.fromString(experimentId),
                UUID.fromString(targetId)
        );
    }

    private String registerTarget() throws Exception {
        String request = """
                {
                  "name": "payment-service",
                  "type": "JAVA_APPLICATION",
                  "environment": "CHAOS_LAB"
                }
                """;
        MvcResult registration = mockMvc.perform(post("/api/v1/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andReturn();
        String location = registration.getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        String path = URI.create(location).getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private String createExperiment(String targetId) throws Exception {
        String request = """
                {
                  "name": "payment CPU experiment",
                  "hypothesis": "Service remains available.",
                  "targetId": "%s",
                  "scenarioId": "%s",
                  "durationSeconds": 30,
                  "parameters": {
                    "percent": 40
                  }
                }
                """.formatted(targetId, CPU_LOAD_ID);
        MvcResult creation = mockMvc.perform(post("/api/v1/experiments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andReturn();
        String location = creation.getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        return URI.create(location).getPath();
    }

    private record ReadyExperiment(
            String experimentPath,
            UUID experimentId,
            UUID targetId
    ) {
    }
}
