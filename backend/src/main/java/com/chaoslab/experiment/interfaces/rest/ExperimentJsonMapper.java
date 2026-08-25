package com.chaoslab.experiment.interfaces.rest;

import com.chaoslab.experiment.application.dto.CreateExperimentCommand;
import com.chaoslab.experiment.application.dto.ExperimentDetails;
import com.chaoslab.experiment.application.dto.ExperimentDryRunDetails;
import com.chaoslab.safety.application.model.DryRunPlan;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class ExperimentJsonMapper {

    private final ObjectMapper objectMapper;

    ExperimentJsonMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    CreateExperimentCommand toCommand(CreateExperimentRequest request) {
        try {
            return new CreateExperimentCommand(
                    request.name(),
                    request.hypothesis(),
                    request.targetId(),
                    request.scenarioId(),
                    request.durationSeconds(),
                    objectMapper.writeValueAsString(request.parameters())
            );
        } catch (JacksonException exception) {
            throw new IllegalStateException("validated parameters cannot be serialized", exception);
        }
    }

    ExperimentResponse from(ExperimentDetails details) {
        return new ExperimentResponse(
                details.id(),
                details.name(),
                details.hypothesis(),
                details.targetId(),
                details.scenarioId(),
                details.durationSeconds(),
                parseParameters(details),
                details.status(),
                details.version()
        );
    }

    ExperimentDryRunResponse from(ExperimentDryRunDetails details) {
        DryRunPlan plan = details.decision().plan();
        return new ExperimentDryRunResponse(
                from(details.experiment()),
                details.decision().accepted(),
                details.decision().checks().stream()
                        .map(check -> new SafetyCheckResponse(
                                check.code(),
                                check.passed(),
                                check.message()
                        ))
                        .toList(),
                new DryRunPlanResponse(
                        plan.experimentId(),
                        plan.targetId(),
                        plan.targetName(),
                        plan.environment(),
                        plan.scenarioCode(),
                        plan.targetCount(),
                        plan.durationSeconds(),
                        plan.recoveryWithinSeconds(),
                        parseParameters(details.experiment())
                )
        );
    }

    private JsonNode parseParameters(ExperimentDetails details) {
        try {
            return objectMapper.readTree(details.parameters());
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "stored parameters are invalid for experiment " + details.id(),
                    exception
            );
        }
    }
}
