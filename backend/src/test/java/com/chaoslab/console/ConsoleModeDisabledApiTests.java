package com.chaoslab.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    properties = {
        "chaoslab.console.read-only=false",
        "spring.datasource.url=jdbc:h2:mem:console-disabled;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    }
)
@AutoConfigureMockMvc
@Transactional
class ConsoleModeDisabledApiTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Test
    void ordinaryModeKeepsExistingReadAndWriteApis() throws Exception {
        assertThat(
            context.getBeansOfType(ConsoleReadOnlyFilter.class)
        ).isEmpty();
        mvc.perform(get("/api/v1/audit-logs")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/experiments")).andExpect(status().isOk());
        // In-memory registration only. Never starts an experiment or invokes an engine.
        mvc.perform(
            post("/api/v1/targets")
                .contentType("application/json")
                .content(
                    """
                    {"name":"non-console-test","type":"JAVA_APPLICATION","environment":"CHAOS_LAB"}
                    """
                )
        )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("non-console-test"));
    }
}
