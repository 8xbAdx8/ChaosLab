package com.chaoslab.report.interfaces.rest;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties =
        "spring.datasource.url="
                + "jdbc:h2:mem:chaoslab-report-test;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
)
@AutoConfigureMockMvc
class ExperimentReportApiIntegrationTests {

    private static final String CPU_LOAD_ID =
            "00000000-0000-0000-0000-000000000101";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void reportIsImmutableSnapshotWithAuditsAndThreeUncollectedWindows()
            throws Exception {
        String targetId = createTarget();
        String experimentPath = createExperiment(targetId);
        mockMvc.perform(post(experimentPath + "/validation"))
                .andExpect(status().isOk());
        mockMvc.perform(post(experimentPath + "/dry-run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));
        MvcResult start = mockMvc.perform(post(experimentPath + "/executions")
                        .header("Idempotency-Key", "report-execution"))
                .andExpect(status().isCreated())
                .andReturn();
        String executionPath = URI.create(
                start.getResponse().getHeader("Location")
        ).getPath();

        mockMvc.perform(post(executionPath + "/reports")
                        .header("Idempotency-Key", "report-v1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXECUTION_NOT_RECOVERED"));
        mockMvc.perform(post(executionPath + "/destroy"))
                .andExpect(status().isOk());

        MvcResult created = mockMvc.perform(post(executionPath + "/reports")
                        .header("Idempotency-Key", "report-v1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetId").value(targetId))
                .andExpect(jsonPath("$.executionMode").value("SIMULATED"))
                .andExpect(jsonPath("$.metricsStatus").value("NOT_COLLECTED"))
                .andExpect(jsonPath("$.conclusionStatus").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.startAuditId").isNotEmpty())
                .andExpect(jsonPath("$.recoveryAuditId").isNotEmpty())
                .andExpect(jsonPath("$.windows.length()").value(3))
                .andExpect(jsonPath("$.windows[0].phase").value("before"))
                .andExpect(jsonPath("$.windows[1].phase").value("during"))
                .andExpect(jsonPath("$.windows[2].phase").value("after"))
                .andReturn();
        String reportPath = URI.create(created.getResponse().getHeader("Location"))
                .getPath();
        String reportId = reportPath.substring(reportPath.lastIndexOf('/') + 1);

        mockMvc.perform(get(reportPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(reportId));
        mockMvc.perform(post(executionPath + "/reports")
                        .header("Idempotency-Key", "report-v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(reportId));
        mockMvc.perform(post(executionPath + "/reports")
                        .header("Idempotency-Key", "report-v2"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty());
        mockMvc.perform(get(executionPath + "/reports/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXPERIMENT_REPORT_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/experiments/{experimentId}/executions/{executionId}/reports/{reportId}",
                        UUID.randomUUID(), idFromPath(executionPath), reportId))
                .andExpect(status().isNotFound());
        assertThat(reportId).isNotBlank();
    }

    private String createTarget() throws Exception {
        String request = """
                {
                  "name": "report-target-%s",
                  "type": "JAVA_APPLICATION",
                  "environment": "CHAOS_LAB"
                }
                """.formatted(UUID.randomUUID());
        MvcResult result = mockMvc.perform(post("/api/v1/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andReturn();
        return idFromPath(URI.create(result.getResponse().getHeader("Location")).getPath());
    }

    private String createExperiment(String targetId) throws Exception {
        String request = """
                {
                  "name": "report experiment",
                  "hypothesis": "Service remains available.",
                  "targetId": "%s",
                  "scenarioId": "%s",
                  "durationSeconds": 30,
                  "parameters": {"percent": 40}
                }
                """.formatted(targetId, CPU_LOAD_ID);
        MvcResult result = mockMvc.perform(post("/api/v1/experiments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andReturn();
        return URI.create(result.getResponse().getHeader("Location")).getPath();
    }

    private String idFromPath(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
