package com.chaoslab.experiment.interfaces.rest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/** Draft revision uses the existing create definition, plus its optimistic version. */
public record UpdateExperimentRequest(
    @NotNull @Valid CreateExperimentRequest definition,
    @NotNull @Min(0) Long version
) {}
