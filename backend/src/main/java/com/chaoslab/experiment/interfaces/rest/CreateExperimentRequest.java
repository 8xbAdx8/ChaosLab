package com.chaoslab.experiment.interfaces.rest;

import com.chaoslab.experiment.domain.Experiment;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

public record CreateExperimentRequest(
        @NotBlank(message = "name must not be blank")
        @Size(max = Experiment.MAX_NAME_LENGTH, message = "name must not exceed {max} characters")
        String name,

        @NotBlank(message = "hypothesis must not be blank")
        @Size(
                max = Experiment.MAX_HYPOTHESIS_LENGTH,
                message = "hypothesis must not exceed {max} characters"
        )
        String hypothesis,

        @NotNull(message = "targetId must not be null")
        UUID targetId,

        @NotNull(message = "scenarioId must not be null")
        UUID scenarioId,

        @NotNull(message = "durationSeconds must not be null")
        @Min(
                value = Experiment.MIN_DURATION_SECONDS,
                message = "durationSeconds must be at least {value}"
        )
        @Max(
                value = Experiment.MAX_DURATION_SECONDS,
                message = "durationSeconds must not exceed {value}"
        )
        Integer durationSeconds,

        @NotNull(message = "parameters must not be null")
        Map<String, Object> parameters
) {
}
