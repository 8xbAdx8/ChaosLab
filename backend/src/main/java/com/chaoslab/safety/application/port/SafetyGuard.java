package com.chaoslab.safety.application.port;

import com.chaoslab.experiment.domain.Experiment;
import com.chaoslab.safety.application.model.SafetyDecision;
import com.chaoslab.scenario.domain.FaultScenario;
import com.chaoslab.target.domain.Target;

public interface SafetyGuard {

    SafetyDecision evaluate(
            Experiment experiment,
            Target target,
            FaultScenario scenario
    );
}
