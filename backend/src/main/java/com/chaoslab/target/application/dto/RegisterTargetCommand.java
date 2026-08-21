package com.chaoslab.target.application.dto;

import com.chaoslab.target.domain.TargetEnvironment;
import com.chaoslab.target.domain.TargetType;

public record RegisterTargetCommand(
        String name,
        TargetType type,
        TargetEnvironment environment
) {
}
