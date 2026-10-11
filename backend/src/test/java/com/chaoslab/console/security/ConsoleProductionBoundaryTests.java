package com.chaoslab.console.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chaoslab.console.ConsoleReadOnlyFilter;
import com.chaoslab.security.LegacyBusinessTestSecurity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Verify the production chain without relying on the opt-in console servlet filter. */
@SpringBootTest(
    properties = {
        "chaoslab.console.read-only=false",
        "spring.datasource.url=jdbc:h2:mem:auth-production-boundary;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    }
)
@AutoConfigureMockMvc
@ActiveProfiles("auth-test")
class ConsoleProductionBoundaryTests {

    @DynamicPropertySource
    static void accounts(DynamicPropertyRegistry r) {
        ConsoleAuthenticationTests.accounts(r);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Test
    void anonymousCannotUseNonConsoleModeAsAuthenticationBypass()
        throws Exception {
        assertThat(
            context.getBeansOfType(ConsoleReadOnlyFilter.class)
        ).isEmpty();
        assertThat(
            context.getBeansOfType(LegacyBusinessTestSecurity.class)
        ).isEmpty();
        mvc.perform(get("/api/v1/console/overview")).andExpect(
            status().isUnauthorized()
        );
    }

    @Test
    void adminWithValidCsrfStillCannotAccessRawApiOrWrite() throws Exception {
        assertThat(
            context.getBeansOfType(ConsoleReadOnlyFilter.class)
        ).isEmpty();
        var session = (MockHttpSession) mvc
            .perform(
                post("/api/v1/auth/login")
                    .with(csrf())
                    .param("username", ConsoleAuthenticationTests.ADMIN)
                    .param("password", ConsoleAuthenticationTests.PASSWORD)
            )
            .andExpect(status().isOk())
            .andReturn()
            .getRequest()
            .getSession(false);
        mvc.perform(get("/api/v1/console/overview").session(session)).andExpect(
            status().isOk()
        );
        mvc.perform(get("/api/v1/audit-logs").session(session)).andExpect(
            status().isForbidden()
        );
        for (String path : new String[] {
            "/api/v1/targets",
            "/api/v1/experiments",
            "/api/v1/authorization",
            "/api/v1/root",
        }) {
            mvc.perform(
                post(path)
                    .session(session)
                    .with(csrf())
                    .contentType("application/json")
                    .content("{}")
            ).andExpect(status().isForbidden());
        }
    }
}
