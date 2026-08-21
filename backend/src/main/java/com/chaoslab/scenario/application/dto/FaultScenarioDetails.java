package com.chaoslab.scenario.application.dto;

import com.chaoslab.scenario.domain.FaultScenario;

import java.util.UUID;

public record FaultScenarioDetails(
        UUID id,
        String code,
        String name,
        String description,
        String parameterSchema,
        boolean enabled
) {

    public static FaultScenarioDetails from(FaultScenario scenario) {
        return new FaultScenarioDetails(
                scenario.getId(),
                scenario.getCode(),
                scenario.getName(),
                scenario.getDescription(),
                scenario.getParameterSchema(),
                scenario.isEnabled()
        );
    }
}
