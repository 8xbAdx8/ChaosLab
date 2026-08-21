package com.chaoslab.target.interfaces.rest;

import com.chaoslab.target.application.dto.RegisterTargetCommand;
import com.chaoslab.target.domain.Target;
import com.chaoslab.target.domain.TargetEnvironment;
import com.chaoslab.target.domain.TargetType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterTargetRequest(
        @NotBlank(message = "name must not be blank")
        @Size(max = Target.MAX_NAME_LENGTH, message = "name must not exceed {max} characters")
        String name,

        @NotNull(message = "type must not be null")
        TargetType type,

        @NotNull(message = "environment must not be null")
        TargetEnvironment environment
) {

    RegisterTargetCommand toCommand() {
        return new RegisterTargetCommand(name, type, environment);
    }
}
