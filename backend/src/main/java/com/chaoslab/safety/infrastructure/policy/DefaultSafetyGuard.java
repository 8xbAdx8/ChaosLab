package com.chaoslab.safety.infrastructure.policy;

import com.chaoslab.experiment.domain.Experiment;
import com.chaoslab.experiment.domain.ExperimentStatus;
import com.chaoslab.engine.application.port.ChaosEngine;
import com.chaoslab.safety.application.model.DryRunPlan;
import com.chaoslab.safety.application.model.SafetyCheck;
import com.chaoslab.safety.application.model.SafetyDecision;
import com.chaoslab.safety.application.model.TargetIdentityVerification;
import com.chaoslab.safety.application.model.VerifiedDockerTarget;
import com.chaoslab.safety.application.port.SafetyGuard;
import com.chaoslab.safety.application.port.TargetIdentityVerifier;
import com.chaoslab.scenario.domain.FaultScenario;
import com.chaoslab.target.domain.Target;
import com.chaoslab.target.domain.TargetEnvironment;
import com.chaoslab.target.domain.TargetType;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Component
public class DefaultSafetyGuard implements SafetyGuard {

    public static final int PLATFORM_MAX_DURATION_SECONDS = 30;

    private static final Set<TargetEnvironment> ALLOWED_ENVIRONMENTS = EnumSet.of(
            TargetEnvironment.LOCAL,
            TargetEnvironment.DEV,
            TargetEnvironment.TEST,
            TargetEnvironment.CHAOS_LAB
    );

    private final TargetIdentityVerifier identityVerifier;
    private final ChaosEngine chaosEngine;

    public DefaultSafetyGuard(
            TargetIdentityVerifier identityVerifier,
            ChaosEngine chaosEngine
    ) {
        this.identityVerifier = Objects.requireNonNull(identityVerifier);
        this.chaosEngine = Objects.requireNonNull(chaosEngine);
    }

    @Override
    public SafetyDecision evaluate(
            Experiment experiment,
            Target target,
            FaultScenario scenario
    ) {
        Objects.requireNonNull(experiment, "experiment must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(scenario, "scenario must not be null");

        boolean validated = experiment.getStatus() == ExperimentStatus.VALIDATED
                || experiment.getStatus() == ExperimentStatus.READY;
        boolean singleTarget = experiment.getTargetId().equals(target.getId());
        boolean matchingScenario = experiment.getScenarioId().equals(scenario.getId());
        boolean environmentAllowed = ALLOWED_ENVIRONMENTS.contains(
                target.getEnvironment()
        );
        boolean durationAllowed = experiment.getDurationSeconds()
                <= PLATFORM_MAX_DURATION_SECONDS;
        boolean scenarioAllowed = scenario.isEnabled() && matchingScenario;
        boolean engineTargetAllowed = chaosEngine.simulated()
                || target.getType() == TargetType.DOCKER_CONTAINER;
        TargetIdentityVerification identityVerification = null;
        if (target.getType() == TargetType.DOCKER_CONTAINER
                && target.isEnabled() && environmentAllowed && singleTarget) {
            try {
                identityVerification = identityVerifier.verify(target);
            } catch (RuntimeException exception) {
                identityVerification = TargetIdentityVerification.rejected(
                        "target identity verification is unavailable"
                );
            }
        }
        VerifiedDockerTarget verifiedTarget = identityVerification != null
                && identityVerification.verified()
                && identityVerification.identity().targetId().equals(target.getId())
                ? identityVerification.identity() : null;
        boolean identityAllowed = target.getType() != TargetType.DOCKER_CONTAINER
                || verifiedTarget != null;

        List<SafetyCheck> checks = List.of(
                check(
                        "EXPERIMENT_VALIDATED",
                        validated,
                        validated
                                ? "experiment definition is validated"
                                : "experiment must be validated before dry run"
                ),
                check(
                        "TARGET_ENABLED",
                        target.isEnabled(),
                        target.isEnabled()
                                ? "target is enabled"
                                : "target is disabled"
                ),
                check(
                        "ENVIRONMENT_ALLOWED",
                        environmentAllowed,
                        environmentAllowed
                                ? "target environment is allowed"
                                : "production targets are not allowed"
                ),
                check(
                        "SCENARIO_ENABLED",
                        scenarioAllowed,
                        scenarioAllowed
                                ? "fault scenario is enabled"
                                : "fault scenario is disabled or does not match"
                ),
                check(
                        "DURATION_WITHIN_LIMIT",
                        durationAllowed,
                        durationAllowed
                                ? "duration is within the platform safety limit"
                                : "duration exceeds the platform safety limit of "
                                        + PLATFORM_MAX_DURATION_SECONDS
                                        + " seconds"
                ),
                check(
                        "SINGLE_TARGET_SCOPE",
                        singleTarget,
                        singleTarget
                                ? "blast radius is limited to one explicit target"
                                : "dry run target does not match the experiment target"
                ),
                check(
                        "ENGINE_TARGET_TYPE_ALLOWED",
                        engineTargetAllowed,
                        engineTargetAllowed
                                ? "engine target type is allowed"
                                : "real engine requires a verified Docker target"
                ),
                check(
                        "TARGET_IDENTITY_VERIFIED",
                        identityAllowed,
                        identityAllowed
                                ? target.getType() == TargetType.DOCKER_CONTAINER
                                        ? "Docker target identity is verified"
                                        : "target identity is not required for simulation"
                                : identityVerification == null
                                        ? "Docker identity was not checked"
                                        : identityVerification.reason()
                )
        );

        DryRunPlan plan = new DryRunPlan(
                experiment.getId(),
                target.getId(),
                target.getName(),
                target.getEnvironment(),
                scenario.getCode(),
                1,
                experiment.getDurationSeconds(),
                experiment.getDurationSeconds(),
                experiment.getParameters()
        );
        return new SafetyDecision(checks, plan, verifiedTarget);
    }

    private SafetyCheck check(String code, boolean passed, String message) {
        return new SafetyCheck(code, passed, message);
    }
}
