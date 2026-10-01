package com.chaoslab.safety.interfaces.rest;

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
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:emergency-api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@AutoConfigureMockMvc
class EmergencyStopApiIntegrationTests {

    private static final String CPU_LOAD_ID =
            "00000000-0000-0000-0000-000000000101";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldRecoverAllRunningExecutionsAndBeIdempotent() throws Exception {
        RunningExperiment first = createRunningExperiment("first");
        RunningExperiment second = createRunningExperiment("second");

        mockMvc.perform(post("/api/v1/emergency-stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedAt").exists())
                .andExpect(jsonPath("$.candidateCount").value(2))
                .andExpect(jsonPath("$.recoveredCount").value(2))
                .andExpect(jsonPath("$.rollbackFailedCount").value(0))
                .andExpect(jsonPath("$.processingFailedCount").value(0))
                .andExpect(jsonPath("$.executions.length()").value(2))
                .andExpect(jsonPath("$.executions[*].outcome")
                        .value(everyItem(is("RECOVERED"))));

        assertSuccessful(first);
        assertSuccessful(second);

        mockMvc.perform(post("/api/v1/emergency-stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateCount").value(0))
                .andExpect(jsonPath("$.recoveredCount").value(0))
                .andExpect(jsonPath("$.executions.length()").value(0));
    }

    private void assertSuccessful(RunningExperiment running) throws Exception {
        mockMvc.perform(get(running.executionPath()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.finishedAt").exists());
        mockMvc.perform(get(running.experimentPath()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    private RunningExperiment createRunningExperiment(String suffix)
            throws Exception {
        String targetId = registerTarget(suffix);
        String experimentPath = createExperiment(targetId, suffix);
        mockMvc.perform(post(experimentPath + "/validation"))
                .andExpect(status().isOk());
        mockMvc.perform(post(experimentPath + "/dry-run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));
        MvcResult start = mockMvc.perform(post(experimentPath + "/executions")
                        .header("Idempotency-Key", "emergency-" + suffix))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andReturn();
        String location = start.getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        return new RunningExperiment(
                experimentPath,
                URI.create(location).getPath()
        );
    }

    private String registerTarget(String suffix) throws Exception {
        String request = """
                {
                  "name": "emergency-target-%s-%s",
                  "type": "JAVA_APPLICATION",
                  "environment": "CHAOS_LAB"
                }
                """.formatted(suffix, UUID.randomUUID());
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

    private String createExperiment(String targetId, String suffix)
            throws Exception {
        String request = """
                {
                  "name": "emergency experiment %s",
                  "hypothesis": "Service remains available.",
                  "targetId": "%s",
                  "scenarioId": "%s",
                  "durationSeconds": 30,
                  "parameters": {
                    "percent": 40
                  }
                }
                """.formatted(suffix, targetId, CPU_LOAD_ID);
        MvcResult creation = mockMvc.perform(post("/api/v1/experiments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andReturn();
        String location = creation.getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        return URI.create(location).getPath();
    }

    private record RunningExperiment(
            String experimentPath,
            String executionPath
    ) {
    }
}
