package com.chaoslab.scenario.interfaces.rest;

import com.chaoslab.scenario.application.dto.FaultScenarioDetails;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class FaultScenarioResponseMapper {

    private final ObjectMapper objectMapper;

    FaultScenarioResponseMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    FaultScenarioResponse from(FaultScenarioDetails details) {
        return new FaultScenarioResponse(
                details.id(),
                details.code(),
                details.name(),
                details.description(),
                parseSchema(details),
                details.enabled()
        );
    }

    private JsonNode parseSchema(FaultScenarioDetails details) {
        try {
            return objectMapper.readTree(details.parameterSchema());
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "stored parameter schema is invalid for scenario " + details.id(),
                    exception
            );
        }
    }
}
