package com.chaoslab.target.interfaces.rest;

import com.chaoslab.shared.interfaces.rest.GlobalExceptionHandler;
import com.chaoslab.target.application.TargetApplicationService;
import com.chaoslab.target.application.TargetNotFoundException;
import com.chaoslab.target.application.dto.RegisterTargetCommand;
import com.chaoslab.target.application.dto.TargetDetails;
import com.chaoslab.target.domain.TargetEnvironment;
import com.chaoslab.target.domain.TargetType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TargetController.class)
@Import(GlobalExceptionHandler.class)
class TargetControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TargetApplicationService targetApplicationService;

    @Test
    void shouldRegisterTarget() throws Exception {
        UUID targetId = UUID.randomUUID();
        TargetDetails details = details(targetId, "payment-service");
        given(targetApplicationService.register(any(RegisterTargetCommand.class)))
                .willReturn(details);

        String request = """
                {
                  "name": "payment-service",
                  "type": "JAVA_APPLICATION",
                  "environment": "CHAOS_LAB"
                }
                """;

        mockMvc.perform(post("/api/v1/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Location",
                        "http://localhost/api/v1/targets/" + targetId
                ))
                .andExpect(jsonPath("$.id").value(targetId.toString()))
                .andExpect(jsonPath("$.name").value("payment-service"))
                .andExpect(jsonPath("$.type").value("JAVA_APPLICATION"))
                .andExpect(jsonPath("$.environment").value("CHAOS_LAB"))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void shouldRejectInvalidRegistrationRequest() throws Exception {
        String request = """
                {
                  "name": " ",
                  "type": null,
                  "environment": null
                }
                """;

        mockMvc.perform(post("/api/v1/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("request validation failed"))
                .andExpect(jsonPath("$.path").value("/api/v1/targets"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.fieldErrors.name").value("name must not be blank"))
                .andExpect(jsonPath("$.fieldErrors.type").value("type must not be null"))
                .andExpect(jsonPath("$.fieldErrors.environment").value("environment must not be null"));
    }

    @Test
    void shouldReturnTargetById() throws Exception {
        UUID targetId = UUID.randomUUID();
        given(targetApplicationService.findById(targetId))
                .willReturn(details(targetId, "order-service"));

        mockMvc.perform(get("/api/v1/targets/{targetId}", targetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(targetId.toString()))
                .andExpect(jsonPath("$.name").value("order-service"));
    }

    @Test
    void shouldReturnNotFoundError() throws Exception {
        UUID targetId = UUID.randomUUID();
        given(targetApplicationService.findById(targetId))
                .willThrow(new TargetNotFoundException(targetId));

        mockMvc.perform(get("/api/v1/targets/{targetId}", targetId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TARGET_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("target not found: " + targetId))
                .andExpect(jsonPath("$.path").value("/api/v1/targets/" + targetId));
    }

    @Test
    void shouldRejectMalformedTargetId() throws Exception {
        mockMvc.perform(get("/api/v1/targets/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void shouldReturnAllTargets() throws Exception {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        given(targetApplicationService.findAll()).willReturn(List.of(
                details(firstId, "order-service"),
                details(secondId, "payment-service")
        ));

        mockMvc.perform(get("/api/v1/targets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(firstId.toString()))
                .andExpect(jsonPath("$[1].id").value(secondId.toString()));
    }

    private TargetDetails details(UUID targetId, String name) {
        return new TargetDetails(
                targetId,
                name,
                TargetType.JAVA_APPLICATION,
                TargetEnvironment.CHAOS_LAB,
                true
        );
    }
}
