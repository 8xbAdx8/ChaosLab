package com.chaoslab.safety.interfaces.rest;

import com.chaoslab.safety.application.dto.EmergencyStopOutcome;
import com.chaoslab.safety.application.dto.EmergencyStopResult;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class EmergencyStopResponseMapper {

    EmergencyStopResponse from(EmergencyStopResult result) {
        List<EmergencyStopExecutionResponse> executions =
                result.executions().stream()
                        .map(execution -> new EmergencyStopExecutionResponse(
                                execution.experimentId(),
                                execution.executionId(),
                                execution.outcome()
                        ))
                        .toList();
        return new EmergencyStopResponse(
                result.requestedAt(),
                executions.size(),
                count(executions, EmergencyStopOutcome.RECOVERED),
                count(executions, EmergencyStopOutcome.ROLLBACK_FAILED),
                count(executions, EmergencyStopOutcome.PROCESSING_FAILED),
                count(executions, EmergencyStopOutcome.MANUAL_INTERVENTION),
                executions
        );
    }

    private int count(
            List<EmergencyStopExecutionResponse> executions,
            EmergencyStopOutcome outcome
    ) {
        return Math.toIntExact(executions.stream()
                .filter(execution -> execution.outcome() == outcome)
                .count());
    }
}
