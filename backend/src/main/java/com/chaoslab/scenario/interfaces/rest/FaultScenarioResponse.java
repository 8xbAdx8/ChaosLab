package com.chaoslab.scenario.interfaces.rest;

import tools.jackson.databind.JsonNode;

import java.util.UUID;

public record FaultScenarioResponse(
        UUID id,
        String code,
        String name,
        String description,
        JsonNode parameterSchema,
        boolean enabled
) {
}
