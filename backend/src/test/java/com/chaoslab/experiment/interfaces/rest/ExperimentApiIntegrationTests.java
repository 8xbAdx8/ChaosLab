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

        mockMvc.perform(get("/api/v1/experiments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
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

    private String experimentRequest(
            String targetId,
            String scenarioId,
            int durationSeconds
    ) {
        return """
                {
                  "name": "payment CPU experiment",
                  "hypothesis": "Service remains available.",
                  "targetId": "%s",
                  "scenarioId": "%s",
                  "durationSeconds": %d,
                  "parameters": {
                    "percent": 40
                  }
                }
                """.formatted(targetId, scenarioId, durationSeconds);
    }
}
