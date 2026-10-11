package com.chaoslab.console.fakedemo;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.chaoslab.engine.application.EngineCreateUncertainException;
import com.chaoslab.engine.infrastructure.fake.FakeChaosEngine;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("fake-demo")
class FakeDemoFailureTests {

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry r) {
        FakeDemoApiTests.config(r);
        String dir = FakeDemoApiTests.DIR.replace("m4-api-", "m4-fail-");
        r.add("chaoslab.fake-demo.directory", () -> dir);
        r.add(
            "spring.datasource.url",
            () ->
                "jdbc:h2:file:" +
                dir +
                "/chaoslab_fake_demo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0"
        );
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoSpyBean
    FakeChaosEngine engine;

    @Test
    void uncertainCreateKeepsOccupancyAndSameKeyNeverRedispatches()
        throws Exception {
        var admin = (MockHttpSession) mvc
            .perform(
                post("/api/v1/auth/login")
                    .with(csrf())
                    .param("username", FakeDemoApiTests.ADMIN)
                    .param("password", FakeDemoApiTests.PASSWORD)
            )
            .andExpect(status().isOk())
            .andReturn()
            .getRequest()
            .getSession(false);
        var helpers = new FakeDemoApiTests();
        helpers.mvc = mvc;
        helpers.jdbc = jdbc;
        String id = helpers.ready(admin, 30),
            base = "/api/v1/experiments/" + id + "/executions",
            key = UUID.randomUUID().toString();
        doThrow(new EngineCreateUncertainException())
            .when(engine)
            .create(any());
        var result = helpers.write(base, admin, null, key, 201);
        assertThat(result.path("status").asText()).isEqualTo(
            "CREATE_UNCERTAIN"
        );
        assertThat(
            helpers.write(base, admin, null, key, 200).path("id").asText()
        ).isEqualTo(result.path("id").asText());
        helpers.write(base, admin, null, UUID.randomUUID().toString(), 409);
        verify(engine, times(1)).create(any());
        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM experiment_executions WHERE experiment_id=? AND status='CREATE_UNCERTAIN'",
                Integer.class,
                id
            )
        ).isEqualTo(1);
    }

    @Test
    void backendFailureDoesNotLeakRawDiagnosticOrInventSuccess()
        throws Exception {
        var admin = (MockHttpSession) mvc
            .perform(
                post("/api/v1/auth/login")
                    .with(csrf())
                    .param("username", FakeDemoApiTests.ADMIN)
                    .param("password", FakeDemoApiTests.PASSWORD)
            )
            .andExpect(status().isOk())
            .andReturn()
            .getRequest()
            .getSession(false);
        // Use a different local target so a previous uncertain test's retained occupancy is respected.
        UUID target = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO targets(id,name,target_type,environment,enabled) VALUES(?,?,'LINUX_HOST','LOCAL',TRUE)",
            target.toString(),
            "TEST-ONLY SIMULATED target"
        );
        var helpers = new FakeDemoApiTests();
        helpers.mvc = mvc;
        helpers.jdbc = jdbc;
        String definition = helpers
            .definition(30, 10)
            .replace(
                FakeDemoConfiguration.TARGET_ID.toString(),
                target.toString()
            );
        String id = helpers
            .write("/api/v1/experiments", admin, definition, null, 201)
            .path("id")
            .asText();
        helpers.write(
            "/api/v1/experiments/" + id + "/validation",
            admin,
            null,
            null,
            200
        );
        helpers.write(
            "/api/v1/experiments/" + id + "/dry-run",
            admin,
            null,
            null,
            200
        );
        doThrow(new IllegalStateException("/root/private raw secret"))
            .when(engine)
            .create(any());
        var result = helpers.write(
            "/api/v1/experiments/" + id + "/executions",
            admin,
            null,
            UUID.randomUUID().toString(),
            201
        );
        assertThat(result.path("status").asText()).isEqualTo("FAILED");
        assertThat(result.toString()).doesNotContain(
            "private",
            "secret",
            "/root"
        );
    }
}
