package com.chaoslab.experiment.interfaces.rest;

import java.util.List;

public record ExperimentDryRunResponse(
        ExperimentResponse experiment,
        boolean accepted,
        List<SafetyCheckResponse> checks,
        DryRunPlanResponse plan
) {
}
