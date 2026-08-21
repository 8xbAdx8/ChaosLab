package com.chaoslab.target.application.dto;

import com.chaoslab.target.domain.Target;
import com.chaoslab.target.domain.TargetEnvironment;
import com.chaoslab.target.domain.TargetType;

import java.util.UUID;

public record TargetDetails(
        UUID id,
        String name,
        TargetType type,
        TargetEnvironment environment,
        boolean enabled
) {

    public static TargetDetails from(Target target) {
        return new TargetDetails(
                target.getId(),
                target.getName(),
                target.getType(),
                target.getEnvironment(),
                target.isEnabled()
        );
    }
}
