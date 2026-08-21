package com.chaoslab.scenario.interfaces.rest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class FaultScenarioApiIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldReturnSeededScenarioCatalog() throws Exception {
        mockMvc.perform(get("/api/v1/scenarios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].code").value("CPU_LOAD"))
                .andExpect(jsonPath("$[0].parameterSchema.type").value("object"))
                .andExpect(jsonPath(
                        "$[0].parameterSchema.properties.percent.maximum"
                ).value(80))
                .andExpect(jsonPath("$[1].code").value("NETWORK_DELAY"));
    }

    @Test
    void shouldReturnScenarioById() throws Exception {
        String scenarioId = "00000000-0000-0000-0000-000000000102";

        mockMvc.perform(get("/api/v1/scenarios/{scenarioId}", scenarioId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("NETWORK_DELAY"))
                .andExpect(jsonPath(
                        "$.parameterSchema.properties.delayMs.maximum"
                ).value(3000));
    }

    @Test
    void shouldReturnNotFoundForUnknownScenario() throws Exception {
        String scenarioId = "00000000-0000-0000-0000-000000000999";

        mockMvc.perform(get("/api/v1/scenarios/{scenarioId}", scenarioId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SCENARIO_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/v1/scenarios/" + scenarioId));
    }
}
