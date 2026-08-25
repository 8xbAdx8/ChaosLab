package com.chaoslab.experiment.interfaces.rest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ExperimentApiIntegrationTests {

    private static final String CPU_LOAD_ID =
            "00000000-0000-0000-0000-000000000101";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldCreateAndQueryExperimentWithoutExecutingFault() throws Exception {
        String targetId = registerTarget();
        String request = experimentRequest(targetId, CPU_LOAD_ID, 30);

        MvcResult creation = mockMvc.perform(post("/api/v1/experiments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.parameters.percent").value(40))
                .andReturn();

        String location = creation.getResponse().getHeader("Location");
        assertThat(location).isNotNull();

        mockMvc.perform(get(URI.create(location).getPath()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetId").value(targetId))
                .andExpect(jsonPath("$.scenarioId").value(CPU_LOAD_ID));

        String validationPath = URI.create(location).getPath() + "/validation";
        mockMvc.perform(post(validationPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(post(validationPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.version").value(1));

        String dryRunPath = URI.create(location).getPath() + "/dry-run";
        mockMvc.perform(post(dryRunPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.experiment.status").value("READY"))
                .andExpect(jsonPath("$.experiment.version").value(2))
                .andExpect(jsonPath("$.checks.length()").value(6))
                .andExpect(jsonPath("$.plan.targetId").value(targetId))
                .andExpect(jsonPath("$.plan.targetCount").value(1))
                .andExpect(jsonPath("$.plan.durationSeconds").value(30))
                .andExpect(jsonPath("$.plan.recoveryWithinSeconds").value(30))
                .andExpect(jsonPath("$.plan.parameters.percent").value(40));

        mockMvc.perform(post(dryRunPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.experiment.status").value("READY"))
                .andExpect(jsonPath("$.experiment.version").value(2));

        mockMvc.perform(post(validationPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(get("/api/v1/experiments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("READY"));
    }

    @Test
    void shouldRejectInvalidDuration() throws Exception {
        String request = experimentRequest(
                "00000000-0000-0000-0000-000000000201",
                CPU_LOAD_ID,
                61
        );

        mockMvc.perform(post("/api/v1/experiments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.durationSeconds")
                        .value("durationSeconds must not exceed 60"));
    }

    @Test
    void shouldRejectUnknownTarget() throws Exception {
        String targetId = "00000000-0000-0000-0000-000000000999";

        mockMvc.perform(post("/api/v1/experiments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(experimentRequest(targetId, CPU_LOAD_ID, 30)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TARGET_NOT_FOUND"));
    }

    @Test
    void shouldReturnNotFoundForUnknownExperiment() throws Exception {
        String experimentId = "00000000-0000-0000-0000-000000000999";

        mockMvc.perform(get("/api/v1/experiments/{experimentId}", experimentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXPERIMENT_NOT_FOUND"));
    }

    @Test
    void shouldRejectParametersOutsideScenarioSchemaWithoutChangingStatus()
            throws Exception {
        String targetId = registerTarget();
        MvcResult creation = mockMvc.perform(post("/api/v1/experiments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(experimentRequest(targetId, CPU_LOAD_ID, 30, 81)))
                .andExpect(status().isCreated())
                .andReturn();
        String location = creation.getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        String experimentPath = URI.create(location).getPath();

        mockMvc.perform(post(experimentPath + "/validation"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("EXPERIMENT_PARAMETERS_INVALID"))
                .andExpect(jsonPath("$.violations[0].path").value("/percent"))
                .andExpect(jsonPath("$.violations[0].keyword").value("maximum"));

        mockMvc.perform(get(experimentPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void shouldRejectProductionTargetDuringDryRunWithoutChangingStatus()
            throws Exception {
        String targetId = registerTarget("PRODUCTION");
        MvcResult creation = mockMvc.perform(post("/api/v1/experiments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(experimentRequest(targetId, CPU_LOAD_ID, 30)))
                .andExpect(status().isCreated())
                .andReturn();
        String location = creation.getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        String experimentPath = URI.create(location).getPath();

        mockMvc.perform(post(experimentPath + "/validation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"));

        mockMvc.perform(post(experimentPath + "/dry-run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.experiment.status").value("VALIDATED"))
                .andExpect(jsonPath("$.checks[2].code").value("ENVIRONMENT_ALLOWED"))
                .andExpect(jsonPath("$.checks[2].passed").value(false))
                .andExpect(jsonPath("$.plan.environment").value("PRODUCTION"));

        mockMvc.perform(get(experimentPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void shouldRejectDurationAboveSafetyLimitDuringDryRun()
            throws Exception {
        String targetId = registerTarget();
        MvcResult creation = mockMvc.perform(post("/api/v1/experiments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(experimentRequest(targetId, CPU_LOAD_ID, 60)))
                .andExpect(status().isCreated())
                .andReturn();
        String location = creation.getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        String experimentPath = URI.create(location).getPath();

        mockMvc.perform(post(experimentPath + "/validation"))
                .andExpect(status().isOk());

        mockMvc.perform(post(experimentPath + "/dry-run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.experiment.status").value("VALIDATED"))
                .andExpect(jsonPath("$.checks[4].code").value("DURATION_WITHIN_LIMIT"))
                .andExpect(jsonPath("$.checks[4].passed").value(false))
                .andExpect(jsonPath("$.plan.durationSeconds").value(60));

        mockMvc.perform(get(experimentPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.version").value(1));
    }

    private String registerTarget() throws Exception {
        return registerTarget("CHAOS_LAB");
    }

    private String registerTarget(String environment) throws Exception {
        String request = """
                {
                  "name": "payment-service",
                  "type": "JAVA_APPLICATION",
                  "environment": "%s"
                }
                """.formatted(environment);
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

    private String experimentRequest(
            String targetId,
            String scenarioId,
            int durationSeconds
    ) {
        return experimentRequest(targetId, scenarioId, durationSeconds, 40);
    }

    private String experimentRequest(
            String targetId,
            String scenarioId,
            int durationSeconds,
            int percent
    ) {
        return """
                {
                  "name": "payment CPU experiment",
                  "hypothesis": "Service remains available.",
                  "targetId": "%s",
                  "scenarioId": "%s",
                  "durationSeconds": %d,
                  "parameters": {
                    "percent": %d
                  }
                }
                """.formatted(targetId, scenarioId, durationSeconds, percent);
    }
}
