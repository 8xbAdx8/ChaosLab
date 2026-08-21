package com.chaoslab.target.interfaces.rest;

import com.chaoslab.target.application.dto.TargetDetails;
import com.chaoslab.target.domain.TargetEnvironment;
import com.chaoslab.target.domain.TargetType;

import java.util.UUID;

public record TargetResponse(
        UUID id,
        String name,
        TargetType type,
        TargetEnvironment environment,
        boolean enabled
) {

    static TargetResponse from(TargetDetails details) {
        return new TargetResponse(
                details.id(),
                details.name(),
                details.type(),
                details.environment(),
                details.enabled()
        );
    }
}
