package com.chaoslab.target.interfaces.rest;

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
class TargetPersistenceApiIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldRegisterAndLoadTargetThroughRealPersistenceStack() throws Exception {
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
                .andExpect(header().exists("Location"))
                .andReturn();

        String location = registration.getResponse().getHeader("Location");
        assertThat(location).isNotNull();

        mockMvc.perform(get(URI.create(location).getPath()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("payment-service"))
                .andExpect(jsonPath("$.type").value("JAVA_APPLICATION"))
                .andExpect(jsonPath("$.environment").value("CHAOS_LAB"))
                .andExpect(jsonPath("$.enabled").value(true));
    }
}
