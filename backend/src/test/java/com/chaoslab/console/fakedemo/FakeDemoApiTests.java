package com.chaoslab.console.fakedemo;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("fake-demo")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FakeDemoApiTests {

    static final String PASSWORD = UUID.randomUUID().toString();
    static final String ADMIN =
        "admin." + UUID.randomUUID().toString().substring(0, 8);
    static final String VIEWER =
        "viewer." + UUID.randomUUID().toString().substring(0, 8);
    static final String DIR = Path.of(
        "target",
        "m4-api-" + UUID.randomUUID(),
        "chaoslab-fake-demo"
    )
        .toAbsolutePath()
        .toString()
        .replace('\\', '/');

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry r) {
        r.add("chaoslab.fake-demo.directory", () -> DIR);
        r.add(
            "spring.datasource.url",
            () ->
                "jdbc:h2:file:" +
                DIR +
                "/chaoslab_fake_demo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE"
        );
        String hash = new BCryptPasswordEncoder(10).encode(PASSWORD);
        r.add("chaoslab.security.admin.username", () -> ADMIN);
        r.add("chaoslab.security.admin.password-hash", () -> hash);
        r.add("chaoslab.security.viewer.username", () -> VIEWER);
        r.add("chaoslab.security.viewer.password-hash", () -> hash);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ApplicationContext context;

    private final JsonMapper json = JsonMapper.builder().build();

    MockHttpSession login(String name) throws Exception {
        return (MockHttpSession) mvc
            .perform(
                post("/api/v1/auth/login")
                    .with(csrf())
                    .param("username", name)
                    .param("password", PASSWORD)
            )
            .andExpect(status().isOk())
            .andReturn()
            .getRequest()
            .getSession(false);
    }

    String definition(int duration, int percent) {
        return """
        {"name":"SIMULATED classroom experiment","hypothesis":"Test lifecycle, not physical CPU",
        "targetId":"00000000-0000-0000-0000-000000000401","scenarioId":"00000000-0000-0000-0000-000000000101",
        "durationSeconds":%d,"parameters":{"percent":%d}}
        """.formatted(duration, percent);
    }

    JsonNode write(
        String path,
        MockHttpSession session,
        String body,
        String key,
        int expected
    ) throws Exception {
        var request = post(path).session(session).with(csrf());
        if (body != null) request.contentType("application/json").content(body);
        if (key != null) request.header("Idempotency-Key", key);
        return json.readTree(
            mvc
                .perform(request)
                .andExpect(status().is(expected))
                .andReturn()
                .getResponse()
                .getContentAsString()
        );
    }

    String ready(MockHttpSession session, int duration) throws Exception {
        String id = write(
            "/api/v1/experiments",
            session,
            definition(duration, 10),
            null,
            201
        )
            .path("id")
            .asText();
        write(
            "/api/v1/experiments/" + id + "/validation",
            session,
            null,
            null,
            200
        );
        assertThat(
            write(
                "/api/v1/experiments/" + id + "/dry-run",
                session,
                null,
                null,
                200
            )
                .path("accepted")
                .asBoolean()
        ).isTrue();
        return id;
    }

    @Test
    @Order(1)
    void fullAdminPersistedLifecycleEditIdempotencyAuditsAndHonestReport()
        throws Exception {
        assertThat(
            context.getBeansOfType(
                com.chaoslab.engine.infrastructure.blade.ChaosBladeEngine.class
            )
        ).isEmpty();
        assertThat(
            context.getBeansOfType(
                com.chaoslab.engine.infrastructure.blade.BladeProcessChannel.class
            )
        ).isEmpty();
        var admin = login(ADMIN);
        mvc.perform(get("/api/v1/auth/me").session(admin))
            .andExpect(jsonPath("$.executionMode").value("FAKE_DEMO"))
            .andExpect(jsonPath("$.fakeExecutionAllowed").value(true))
            .andExpect(jsonPath("$.realExecutionAllowed").value(false));
        var created = write(
            "/api/v1/experiments",
            admin,
            definition(30, 10),
            null,
            201
        );
        String id = created.path("id").asText(),
            base = "/api/v1/experiments/" + id;
        String revision =
            "{\"definition\":" + definition(30, 20) + ",\"version\":0}";
        var edited = json.readTree(
            mvc
                .perform(
                    put(base)
                        .session(admin)
                        .with(csrf())
                        .contentType("application/json")
                        .content(revision)
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString()
        );
        mvc.perform(
            put(base)
                .session(admin)
                .with(csrf())
                .contentType("application/json")
                .content(revision)
        ).andExpect(status().isConflict());
        var validated = write(base + "/validation", admin, null, null, 200);
        mvc.perform(
            put(base)
                .session(admin)
                .with(csrf())
                .contentType("application/json")
                .content(
                    "{\"definition\":" +
                        definition(30, 10) +
                        ",\"version\":" +
                        validated.path("version") +
                        "}"
                )
        ).andExpect(status().isConflict());
        var dry = write(base + "/dry-run", admin, null, null, 200);
        assertThat(dry.path("accepted").asBoolean()).isTrue();
        String key = UUID.randomUUID().toString();
        var running = write(base + "/executions", admin, null, key, 201);
        assertThat(running.path("status").asText()).isEqualTo("RUNNING");
        String execution = running.path("id").asText();
        assertThat(
            write(base + "/executions", admin, null, key, 200)
                .path("id")
                .asText()
        ).isEqualTo(execution);
        write(
            base + "/executions",
            admin,
            null,
            UUID.randomUUID().toString(),
            409
        );
        var stopped = write(
            base + "/executions/" + execution + "/destroy",
            admin,
            null,
            null,
            200
        );
        assertThat(stopped.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(
            write(
                base + "/executions/" + execution + "/destroy",
                admin,
                null,
                null,
                200
            )
                .path("version")
                .asLong()
        ).isEqualTo(stopped.path("version").asLong());
        var report = write(
            base + "/executions/" + execution + "/reports",
            admin,
            null,
            UUID.randomUUID().toString(),
            201
        );
        assertThat(report.path("executionMode").asText()).isEqualTo(
            "SIMULATED"
        );
        assertThat(report.path("metricsStatus").asText()).isEqualTo(
            "NOT_COLLECTED"
        );
        assertThat(report.path("conclusionStatus").asText()).isEqualTo(
            "INSUFFICIENT_DATA"
        );
        mvc.perform(
            get(
                "/api/v1/console/executions/" + execution + "/evidence"
            ).session(admin)
        )
            .andExpect(jsonPath("$.physicalRecovery").value("UNKNOWN"))
            .andExpect(jsonPath("$.baselineCpuPercent").isEmpty());
        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM experiment_executions WHERE experiment_id=?",
                Integer.class,
                id
            )
        ).isEqualTo(1);
        assertThat(
            jdbc.queryForObject(
                "SELECT DISTINCT actor FROM audit_logs WHERE execution_id=? AND operation='START_EXPERIMENT'",
                String.class,
                execution
            )
        ).isEqualTo(ADMIN);
        var viewer = login(VIEWER);
        mvc.perform(
            get("/api/v1/console/experiments/" + id).session(viewer)
        ).andExpect(status().isOk());
        var data = new LinkedHashMap<String, Object>();
        data.put("created", created);
        data.put("edited", edited);
        data.put("ready", dry.path("experiment"));
        data.put("running", running);
        data.put("finished", stopped);
        data.put("report", report);
        Files.createDirectories(Path.of("target"));
        Files.writeString(
            Path.of("target/fake-contract.json"),
            json.writeValueAsString(data)
        );
    }

    @Test
    @Order(2)
    void viewerCsrfUnknownInputsAndRealRoutesFailClosed() throws Exception {
        var viewer = login(VIEWER);
        var admin = login(ADMIN);
        String base =
            "/api/v1/experiments/00000000-0000-0000-0000-000000000777";
        mvc.perform(get("/api/v1/auth/me").session(viewer)).andExpect(
            jsonPath("$.fakeExecutionAllowed").value(false)
        );
        for (String path : List.of(
            "/api/v1/experiments",
            base + "/validation",
            base + "/dry-run",
            base + "/executions",
            base + "/executions/00000000-0000-0000-0000-000000000778/destroy"
        ))
            mvc.perform(
                post(path)
                    .session(viewer)
                    .with(csrf())
                    .contentType("application/json")
                    .content(definition(10, 10))
            ).andExpect(status().isForbidden());
        mvc.perform(
            put(base)
                .session(viewer)
                .with(csrf())
                .contentType("application/json")
                .content("{}")
        ).andExpect(status().isForbidden());
        mvc.perform(
            post("/api/v1/experiments")
                .session(admin)
                .contentType("application/json")
                .content(definition(10, 10))
        ).andExpect(status().isForbidden());
        mvc.perform(
            post("/api/v1/experiments")
                .session(admin)
                .with(csrf())
                .contentType("application/json")
                .content(
                    definition(10, 10)
                        .trim()
                        .replaceFirst("\\{", "{\"engine\":\"blade\",")
                )
        ).andExpect(status().isBadRequest());
        for (String path : List.of(
            "/api/v1/authorization",
            "/api/v1/root",
            "/api/v1/blade/recovery-inventory",
            "/api/v1/targets"
        ))
            mvc.perform(post(path).session(admin).with(csrf())).andExpect(
                status().isForbidden()
            );
        String bad = write(
            "/api/v1/experiments",
            admin,
            definition(10, 999),
            null,
            201
        )
            .path("id")
            .asText();
        write(
            "/api/v1/experiments/" + bad + "/validation",
            admin,
            null,
            null,
            422
        );
        write(
            "/api/v1/experiments/" + bad + "/executions",
            admin,
            null,
            UUID.randomUUID().toString(),
            409
        );
    }

    @Test
    @Order(3)
    void concurrentStartSameKeyCreatesOneDurableExecution() throws Exception {
        var admin = login(ADMIN);
        String id = ready(admin, 30),
            path = "/api/v1/experiments/" + id + "/executions",
            key = UUID.randomUUID().toString();
        var token = json.readTree(
            mvc
                .perform(get("/api/v1/auth/csrf").session(admin))
                .andReturn()
                .getResponse()
                .getContentAsString()
        );
        try (var pool = Executors.newFixedThreadPool(3)) {
            var latch = new CountDownLatch(1);
            List<Future<String>> replies = new ArrayList<>();
            for (int i = 0; i < 3; i++) replies.add(
                pool.submit(() -> {
                    latch.await();
                    var result = mvc
                        .perform(
                            post(path)
                                .session(admin)
                                .header("Idempotency-Key", key)
                                .header(
                                    "X-CSRF-TOKEN",
                                    token.path("token").asText()
                                )
                        )
                        .andReturn()
                        .getResponse();
                    assertThat(result.getStatus()).isIn(200, 201);
                    return json
                        .readTree(result.getContentAsString())
                        .path("id")
                        .asText();
                })
            );
            latch.countDown();
            Set<String> ids = new HashSet<>();
            for (var r : replies) ids.add(r.get(10, TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
            assertThat(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM experiment_executions WHERE experiment_id=?",
                    Integer.class,
                    id
                )
            ).isEqualTo(1);
            write(
                path + "/" + ids.iterator().next() + "/destroy",
                admin,
                null,
                null,
                200
            );
        }
    }

    @Test
    @Order(4)
    void originalSchedulerAutomaticallyRecoversSimulatedExecution()
        throws Exception {
        var admin = login(ADMIN);
        String id = ready(admin, 5),
            base = "/api/v1/experiments/" + id + "/executions";
        String execution = write(
            base,
            admin,
            null,
            UUID.randomUUID().toString(),
            201
        )
            .path("id")
            .asText();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        String state;
        do {
            state = jdbc.queryForObject(
                "SELECT status FROM experiment_executions WHERE id=?",
                String.class,
                execution
            );
            if (state.equals("SUCCESS")) break;
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        assertThat(state).isEqualTo("SUCCESS");
        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE execution_id=? AND operation='AUTOMATIC_RECOVERY' AND result='SUCCESS'",
                Integer.class,
                execution
            )
        ).isEqualTo(1);
    }

    @Test
    @Order(5)
    void restartRestoresCommittedRunningIdentityWithoutCreatingAgain()
        throws Exception {
        var admin = login(ADMIN);
        String id = ready(admin, 30),
            base = "/api/v1/experiments/" + id + "/executions";
        var running = write(
            base,
            admin,
            null,
            UUID.randomUUID().toString(),
            201
        );
        var freshEngine =
            new com.chaoslab.engine.infrastructure.fake.FakeChaosEngine();
        var config = new FakeDemoConfiguration(
            freshEngine,
            context.getEnvironment()
        );
        config
            .fakeDemoTarget(
                context.getBean(
                    com.chaoslab.target.application.port.TargetRepository.class
                ),
                context.getBean(
                    com.chaoslab.execution.application.port.ExperimentExecutionRepository.class
                ),
                context.getBean(
                    com.chaoslab.experiment.application.port.ExperimentRepository.class
                )
            )
            .afterSingletonsInstantiated();
        var ref = new com.chaoslab.engine.application.model.EngineExperimentId(
            running.path("engineExperimentId").asText()
        );
        assertThat(freshEngine.status(ref).status()).isEqualTo(
            com.chaoslab.engine.application.model.EngineStatus.RUNNING
        );
        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM experiment_executions WHERE experiment_id=?",
                Integer.class,
                id
            )
        ).isEqualTo(1);
        write(
            base + "/" + running.path("id").asText() + "/destroy",
            admin,
            null,
            null,
            200
        );
    }
}
