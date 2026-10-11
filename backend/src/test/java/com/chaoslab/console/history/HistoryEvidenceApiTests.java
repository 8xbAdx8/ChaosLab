package com.chaoslab.console.history;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.chaoslab.historydemo.HistoryDemoApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    classes = HistoryDemoApplication.class,
    properties = "chaoslab.console.read-only=true"
)
@AutoConfigureMockMvc
@ActiveProfiles("history-demo")
class HistoryEvidenceApiTests {

    private static final String API = "/api/v1/console/history/m1-core";

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Test
    void actualRestProjectionNeedsNoDatabaseEngineOrRecoveryScheduler()
        throws Exception {
        assertThat(
            context.getBeansOfType(javax.sql.DataSource.class)
        ).isEmpty();
        assertThat(
            context.getBeansOfType(
                com.chaoslab.engine.application.port.ChaosEngine.class
            )
        ).isEmpty();
        assertThat(
            context.getBeansOfType(
                com.chaoslab.execution.infrastructure.scheduling.AutomaticExperimentRecoveryJob.class
            )
        ).isEmpty();
        var response = mvc
            .perform(get(API))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataMode").value("ARCHIVED_REAL_COMPLETED"))
            .andExpect(
                jsonPath("$.evidenceSource.sha256").value(
                    HistoryEvidenceReader.EVIDENCE_SHA256
                )
            )
            .andExpect(jsonPath("$.cpu[0].percent.value").value(0))
            .andExpect(jsonPath("$.cpu[2].percent.value").value(0))
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertThat(
            mvc.perform(get(API)).andReturn().getResponse().getContentAsString()
        ).isEqualTo(response);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/history-contract.json"), response);
        mvc.perform(head(API)).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void directBackendCannotBroadenHistoryReadOrWriteScope() throws Exception {
        for (String path : new String[] {
            API + "/",
            API + "/raw",
            "/api/v1/console/history",
            "/api/v1/console/history/other",
            "/api/v1/experiments",
            "/api/v1/blade/recovery-inventory",
            "/api/v1/console/%68istory/m1-core",
            "/api/v1/console/history/m1-core;raw=x",
        }) {
            for (var method : new org.springframework.http.HttpMethod[] {
                org.springframework.http.HttpMethod.GET,
                org.springframework.http.HttpMethod.HEAD,
                org.springframework.http.HttpMethod.OPTIONS,
            })
                mvc.perform(request(method, URI.create(path))).andExpect(
                    status().is4xxClientError()
                );
        }
        for (var method : new org.springframework.http.HttpMethod[] {
            org.springframework.http.HttpMethod.POST,
            org.springframework.http.HttpMethod.PUT,
            org.springframework.http.HttpMethod.DELETE,
            org.springframework.http.HttpMethod.PATCH,
        })
            mvc.perform(request(method, API)).andExpect(
                status().is4xxClientError()
            );
        mvc.perform(options(API))
            .andExpect(status().isNoContent())
            .andExpect(header().string("Allow", "GET, HEAD, OPTIONS"));
        mvc.perform(get("/api/v1/auth/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mode").value("LOCAL_HISTORY"))
            .andExpect(jsonPath("$.authenticated").value(false))
            .andExpect(jsonPath("$.roles").isEmpty());
        mvc.perform(
            get(API).with(r -> {
                r.setRemoteAddr("192.0.2.1");
                return r;
            })
        ).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/console/access")).andExpect(
            status().isUnauthorized()
        );
        mvc.perform(get("/api/v1/auth/csrf")).andExpect(
            status().isUnauthorized()
        );
        mvc.perform(
            options(API)
                .header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "GET")
        )
            .andExpect(status().is4xxClientError())
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
