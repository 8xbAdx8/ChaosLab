package com.chaoslab.audit.interfaces.rest;

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
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties =
        "spring.datasource.url="
                + "jdbc:h2:mem:chaoslab-audit-test;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
)
@AutoConfigureMockMvc
class AuditLogApiIntegrationTests {

    private static final String CPU_LOAD_ID =
            "00000000-0000-0000-0000-000000000101";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldAppendAndQueryStartAndDestroyAuditSnapshots() throws Exception {
        ReadyExperiment ready = createReadyExperiment("success");
        MvcResult start = mockMvc.perform(post(
                        ready.experimentPath() + "/executions"
                ).header("Idempotency-Key", "audit-success"))
                .andExpect(status().isCreated())
                .andReturn();
        String location = start.getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        mockMvc.perform(post(URI.create(location).getPath() + "/destroy"))
                .andExpect(status().isOk());

        String executionId = idFromPath(URI.create(location).getPath());
        mockMvc.perform(get("/api/v1/audit-logs/by-execution/{experimentId}/{executionId}",
                        ready.experimentId(), executionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].operation").value(hasItems(
                        "START_EXPERIMENT", "DESTROY_EXPERIMENT"
                )))
                .andExpect(jsonPath("$[*].executionId").value(hasItems(executionId)));
        mockMvc.perform(get("/api/v1/audit-logs/by-execution/{experimentId}/{executionId}",
                        ready.experimentId(), UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/v1/audit-logs")
                        .param("experimentId", ready.experimentId())
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].operation").value(hasItems(
                        "START_EXPERIMENT",
                        "DESTROY_EXPERIMENT"
                )))
                .andExpect(jsonPath("$[*].result").value(hasItems(
                        "SUCCESS",
                        "SUCCESS"
                )))
                .andExpect(jsonPath("$[0].actor").value("ANONYMOUS"))
                .andExpect(jsonPath("$[0].targetId").value(ready.targetId()))
                .andExpect(jsonPath("$[0].scenarioCode").value("CPU_LOAD"))
                .andExpect(jsonPath("$[0].parameters.percent").value(40))
                .andExpect(jsonPath("$[0].sourceIp").value("127.0.0.1"));
    }

    @Test
    void shouldKeepRejectedAuditWhenExecutionTransactionRollsBack()
            throws Exception {
        String targetId = registerTarget("rejected");
        String experimentPath = createExperiment(targetId, "rejected");
        String experimentId = idFromPath(experimentPath);
        mockMvc.perform(post(experimentPath + "/validation"))
                .andExpect(status().isOk());

        mockMvc.perform(post(experimentPath + "/executions")
                        .header("Idempotency-Key", "audit-rejected"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXPERIMENT_NOT_READY"));

        mockMvc.perform(get("/api/v1/audit-logs")
                        .param("experimentId", experimentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].operation")
                        .value("START_EXPERIMENT"))
                .andExpect(jsonPath("$[0].result").value("REJECTED"))
                .andExpect(jsonPath("$[0].failureCode")
                        .value("EXPERIMENT_NOT_READY"));
    }

    @Test
    void shouldRejectUnsafeQueryLimit() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs").param("limit", "201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AUDIT_QUERY"));
    }

    private ReadyExperiment createReadyExperiment(String suffix)
            throws Exception {
        String targetId = registerTarget(suffix);
        String experimentPath = createExperiment(targetId, suffix);
        mockMvc.perform(post(experimentPath + "/validation"))
                .andExpect(status().isOk());
        mockMvc.perform(post(experimentPath + "/dry-run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));
        return new ReadyExperiment(
                experimentPath,
                idFromPath(experimentPath),
                targetId
        );
    }

    private String registerTarget(String suffix) throws Exception {
        String request = """
                {
                  "name": "audit-target-%s-%s",
                  "type": "JAVA_APPLICATION",
                  "environment": "CHAOS_LAB"
                }
                """.formatted(suffix, UUID.randomUUID());
        MvcResult result = mockMvc.perform(post("/api/v1/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andReturn();
        String location = result.getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        return idFromPath(URI.create(location).getPath());
    }

    private String createExperiment(String targetId, String suffix)
            throws Exception {
        String request = """
                {
                  "name": "audit experiment %s",
                  "hypothesis": "Service remains available.",
                  "targetId": "%s",
                  "scenarioId": "%s",
                  "durationSeconds": 30,
                  "parameters": {
                    "percent": 40
                  }
                }
                """.formatted(suffix, targetId, CPU_LOAD_ID);
        MvcResult result = mockMvc.perform(post("/api/v1/experiments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andReturn();
        String location = result.getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        return URI.create(location).getPath();
    }

    private String idFromPath(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private record ReadyExperiment(
            String experimentPath,
            String experimentId,
            String targetId
    ) {
    }
}
