package com.chaoslab.audit.interfaces.rest;

import com.chaoslab.audit.application.dto.AuditLogDetails;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class AuditLogResponseMapper {

    private final ObjectMapper objectMapper;

    AuditLogResponseMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    AuditLogResponse from(AuditLogDetails details) {
        return new AuditLogResponse(
                details.id(),
                details.actor(),
                details.operation(),
                details.experimentId(),
                details.executionId(),
                details.targetId(),
                details.scenarioCode(),
                parseParameters(details),
                details.sourceIp(),
                details.result(),
                details.failureCode(),
                details.occurredAt()
        );
    }

    private JsonNode parseParameters(AuditLogDetails details) {
        if (details.parameters() == null) {
            return null;
        }
        try {
            return objectMapper.readTree(details.parameters());
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "stored audit parameters are invalid for log " + details.id(),
                    exception
            );
        }
    }
}
