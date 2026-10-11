package com.chaoslab.console.security;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    properties = {
        "chaoslab.console.read-only=true",
        "spring.datasource.url=jdbc:h2:mem:auth-console;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    }
)
@AutoConfigureMockMvc
@ActiveProfiles("auth-test")
class ConsoleAuthenticationTests {

    static final String VIEWER =
        "viewer." + UUID.randomUUID().toString().substring(0, 8);
    static final String ADMIN =
        "admin." + UUID.randomUUID().toString().substring(0, 8);
    static final String PASSWORD = UUID.randomUUID().toString();
    static final String HASH = new BCryptPasswordEncoder(10).encode(PASSWORD);

    @DynamicPropertySource
    static void accounts(DynamicPropertyRegistry r) {
        r.add("chaoslab.security.viewer.username", () -> VIEWER);
        r.add("chaoslab.security.viewer.password-hash", () -> HASH);
        r.add("chaoslab.security.admin.username", () -> ADMIN);
        r.add("chaoslab.security.admin.password-hash", () -> HASH);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    InMemoryUserDetailsManager users;

    private MockHttpSession login(String name) throws Exception {
        return (MockHttpSession) mvc
            .perform(
                post("/api/v1/auth/login")
                    .with(csrf())
                    .param("username", name)
                    .param("role", "ADMIN") // Untrusted login fields cannot select a role.
                    .param("password", PASSWORD)
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(true))
            .andReturn()
            .getRequest()
            .getSession(false);
    }

    @Test
    void anonymousCanGetIdentityCsrfAndHealthButCannotReadAnyConsoleData()
        throws Exception {
        mvc.perform(get("/api/v1/auth/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(false))
            .andExpect(jsonPath("$.mode").value("SESSION"))
            .andExpect(jsonPath("$.roles").isEmpty());
        mvc.perform(get("/api/v1/auth/csrf"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        for (String p : new String[] {
            "/api/v1/console/overview",
            "/api/v1/console/history/m1-core",
            "/api/v1/console/access",
            "/api/v1/targets",
            "/api/v1/scenarios",
        })
            mvc.perform(get(p))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(
            get("/api/v1/console/overview").header(
                "Authorization",
                "Basic " +
                    java.util.Base64.getEncoder().encodeToString(
                        (VIEWER + ":" + PASSWORD).getBytes(
                            java.nio.charset.StandardCharsets.UTF_8
                        )
                    )
            )
        ).andExpect(status().isUnauthorized());
    }

    @Test
    void realLoginSessionPersistsAndRoleIsolationIsServerSide()
        throws Exception {
        var viewer = login(VIEWER);
        mvc.perform(get("/api/v1/auth/me").session(viewer))
            .andExpect(jsonPath("$.username").value(VIEWER))
            .andExpect(jsonPath("$.roles[0]").value("VIEWER"))
            .andExpect(jsonPath("$.realExecutionAllowed").value(false));
        mvc.perform(get("/api/v1/console/overview").session(viewer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticationConfigured").value(true));
        mvc.perform(
            get("/api/v1/console/history/m1-core").session(viewer)
        ).andExpect(status().isOk());
        mvc.perform(get("/api/v1/console/access").session(viewer))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        var admin = login(ADMIN);
        mvc.perform(get("/api/v1/console/access").session(admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.realExecutionAllowed").value(false));
        mvc.perform(
            get("/api/v1/console/overview").with(
                user("outsider").roles("OTHER")
            )
        ).andExpect(status().isForbidden());
        String storedHash = users.loadUserByUsername(VIEWER).getPassword();
        String analysis = "/api/v1/console/reports/" + UUID.randomUUID() + "/analysis";
        mvc.perform(get(analysis)).andExpect(status().isUnauthorized());
        for (var session : new MockHttpSession[]{viewer, admin})
            mvc.perform(get(analysis).session(session)).andExpect(status().isNotFound());
        mvc.perform(get(analysis).with(user("outsider").roles("OTHER"))).andExpect(status().isForbidden());
        assertThat(
            new BCryptPasswordEncoder().matches(PASSWORD, storedHash)
        ).isTrue();
        assertThat(storedHash).isNotEqualTo(PASSWORD);
    }

    @Test
    void csrfProtectsLoginLogoutAndOldTokensDoNotSurviveAuthentication()
        throws Exception {
        mvc.perform(
            post("/api/v1/auth/login")
                .param("username", VIEWER)
                .param("password", PASSWORD)
        ).andExpect(status().isForbidden());
        mvc.perform(
            post("/api/v1/auth/login")
                .with(csrf().useInvalidToken())
                .param("username", VIEWER)
                .param("password", PASSWORD)
        ).andExpect(status().isForbidden());
        var mapper = JsonMapper.builder().build();
        var before = mvc.perform(get("/api/v1/auth/csrf")).andReturn();
        var session = (MockHttpSession) before.getRequest().getSession(false);
        String oldId = session.getId(),
            oldToken = mapper
                .readTree(before.getResponse().getContentAsString())
                .path("token")
                .asText();
        mvc.perform(
            post("/api/v1/auth/login")
                .session(session)
                .header("X-CSRF-TOKEN", oldToken)
                .param("username", VIEWER)
                .param("password", PASSWORD)
        ).andExpect(status().isOk());
        assertThat(session.getId()).isNotEqualTo(oldId);
        mvc.perform(
            post("/api/v1/auth/logout")
                .session(session)
                .header("X-CSRF-TOKEN", oldToken)
        ).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/logout").session(session)).andExpect(
            status().isForbidden()
        );
        var fresh = mvc
            .perform(get("/api/v1/auth/csrf").session(session))
            .andReturn()
            .getResponse()
            .getContentAsString();
        String token = mapper.readTree(fresh).path("token").asText();
        mvc.perform(
            post("/api/v1/auth/logout")
                .session(session)
                .header("X-CSRF-TOKEN", token)
        ).andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(
            get("/api/v1/console/history/m1-core").cookie(
                new jakarta.servlet.http.Cookie(
                    ConsoleSecurityConfiguration.SESSION_COOKIE,
                    oldId
                )
            )
        ).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me")).andExpect(
            jsonPath("$.authenticated").value(false)
        );
    }

    @Test
    void failuresDoNotRevealPasswordsHashesOrUserExistence() throws Exception {
        for (String name : new String[] { VIEWER, "not-existing-user" }) {
            String body = mvc
                .perform(
                    post("/api/v1/auth/login")
                        .with(csrf())
                        .param("username", name)
                        .param("password", UUID.randomUUID().toString())
                )
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();
            assertThat(body)
                .isEqualTo("{\"code\":\"LOGIN_FAILED\"}")
                .doesNotContain(PASSWORD, HASH, VIEWER, "/root/");
        }
    }

    @Test
    void neitherRoleCanWriteOrEscapeTheExactReadonlyWhitelist()
        throws Exception {
        for (String name : new String[] { VIEWER, ADMIN }) {
            var session = login(name);
            for (String p : new String[] {
                "/api/v1/experiments",
                "/api/v1/experiments/" + UUID.randomUUID() + "/destroy",
                "/api/v1/emergency-stop",
                "/api/v1/root",
                "/api/v1/authorization",
                "/api/v1/console/overview",
            })
                for (String method : new String[] {
                    "POST",
                    "PUT",
                    "PATCH",
                    "DELETE",
                })
                    mvc.perform(
                        request(
                            org.springframework.http.HttpMethod.valueOf(method),
                            p
                        )
                            .session(session)
                            .with(csrf())
                            .contentType("application/json")
                            .content("{}")
                    ).andExpect(status().isForbidden());
            for (String p : new String[] {
                "/api/v1/audit-logs",
                "/api/v1/blade/recovery-inventory",
                "/actuator/env",
                "/api/v1/console/history/m1-core/raw",
                "/api/v1/console//overview",
                "/api/v1/console/%6fverview",
                "/api/v1/console/overview;ignored=x",
            })
                mvc.perform(
                    request(
                        org.springframework.http.HttpMethod.GET,
                        URI.create(p)
                    ).session(session)
                ).andExpect(status().is4xxClientError());
            mvc.perform(options("/api/v1/console/overview").session(session))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Allow", "GET, HEAD, OPTIONS"));
            mvc.perform(
                options("/api/v1/console/overview")
                    .session(session)
                    .header("Origin", "https://untrusted.example")
            ).andExpect(status().isForbidden());
        }
    }

    @Test
    void wireIdentityContractContainsNoSecretFields() throws Exception {
        var mapper = JsonMapper.builder().build();
        var bodies = new java.util.LinkedHashMap<String, Object>();
        bodies.put(
            "anonymous",
            mapper.readTree(
                mvc
                    .perform(get("/api/v1/auth/me"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString()
            )
        );
        for (String name : new String[] { VIEWER, ADMIN }) {
            var session = login(name);
            var body = mvc
                .perform(get("/api/v1/auth/me").session(session))
                .andReturn()
                .getResponse()
                .getContentAsString();
            assertThat(body).doesNotContain(
                PASSWORD,
                HASH,
                "token",
                "password"
            );
            bodies.put(
                name.equals(VIEWER) ? "viewer" : "admin",
                mapper.readTree(body)
            );
        }
        Files.createDirectories(Path.of("target"));
        Files.writeString(
            Path.of("target/auth-contract.json"),
            mapper.writeValueAsString(bodies)
        );
    }
}
