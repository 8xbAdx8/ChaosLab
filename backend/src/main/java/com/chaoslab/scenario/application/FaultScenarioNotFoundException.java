package com.chaoslab.scenario.application;

import java.util.UUID;

public class FaultScenarioNotFoundException extends RuntimeException {

    public FaultScenarioNotFoundException(UUID scenarioId) {
        super("fault scenario not found: " + scenarioId);
    }
}
