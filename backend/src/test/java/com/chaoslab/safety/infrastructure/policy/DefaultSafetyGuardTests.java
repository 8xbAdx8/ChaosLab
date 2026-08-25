package com.chaoslab.safety.infrastructure.policy;

import com.chaoslab.experiment.domain.Experiment;
import com.chaoslab.safety.application.model.SafetyCheck;
import com.chaoslab.safety.application.model.SafetyDecision;
import com.chaoslab.scenario.domain.FaultScenario;
import com.chaoslab.target.domain.Target;
import com.chaoslab.target.domain.TargetEnvironment;
import com.chaoslab.target.domain.TargetType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultSafetyGuardTests {

    private final DefaultSafetyGuard guard = new DefaultSafetyGuard();

    @Test
    void shouldAcceptValidatedSingleTargetPlanInAllowedEnvironment() {
        Target target = target(TargetEnvironment.CHAOS_LAB, true);
        FaultScenario scenario = scenario(true);
        Experiment experiment = experiment(target.getId(), scenario.getId(), 30).validate();

        SafetyDecision decision = guard.evaluate(experiment, target, scenario);

        assertThat(decision.accepted()).isTrue();
        assertThat(decision.checks()).allMatch(SafetyCheck::passed);
        assertThat(decision.plan().targetId()).isEqualTo(target.getId());
        assertThat(decision.plan().targetCount()).isEqualTo(1);
        assertThat(decision.plan().recoveryWithinSeconds()).isEqualTo(30);
    }

    @Test
    void shouldRejectExperimentThatWasNotValidated() {
        Target target = target(TargetEnvironment.TEST, true);
        FaultScenario scenario = scenario(true);

        SafetyDecision decision = guard.evaluate(
                experiment(target.getId(), scenario.getId(), 30),
                target,
                scenario
        );

        assertFailed(decision, "EXPERIMENT_VALIDATED");
    }

    @Test
    void shouldRejectDisabledTarget() {
        Target target = target(TargetEnvironment.DEV, false);
        FaultScenario scenario = scenario(true);

        SafetyDecision decision = guard.evaluate(
                experiment(target.getId(), scenario.getId(), 30).validate(),
                target,
                scenario
        );

        assertFailed(decision, "TARGET_ENABLED");
    }

    @Test
    void shouldRejectProductionTarget() {
        Target target = target(TargetEnvironment.PRODUCTION, true);
        FaultScenario scenario = scenario(true);

        SafetyDecision decision = guard.evaluate(
                experiment(target.getId(), scenario.getId(), 30).validate(),
                target,
                scenario
        );

        assertFailed(decision, "ENVIRONMENT_ALLOWED");
    }

    @Test
    void shouldRejectDisabledScenario() {
        Target target = target(TargetEnvironment.LOCAL, true);
        FaultScenario scenario = scenario(false);

        SafetyDecision decision = guard.evaluate(
                experiment(target.getId(), scenario.getId(), 30).validate(),
                target,
                scenario
        );

        assertFailed(decision, "SCENARIO_ENABLED");
    }

    @Test
    void shouldRejectDurationAbovePlatformSafetyLimit() {
        Target target = target(TargetEnvironment.TEST, true);
        FaultScenario scenario = scenario(true);

        SafetyDecision decision = guard.evaluate(
                experiment(target.getId(), scenario.getId(), 60).validate(),
                target,
                scenario
        );

        assertFailed(decision, "DURATION_WITHIN_LIMIT");
        assertThat(decision.plan().durationSeconds()).isEqualTo(60);
    }

    @Test
    void shouldRejectTargetOutsideExperimentScope() {
        Target registeredTarget = target(TargetEnvironment.TEST, true);
        Target differentTarget = target(TargetEnvironment.TEST, true);
        FaultScenario scenario = scenario(true);

        SafetyDecision decision = guard.evaluate(
                experiment(registeredTarget.getId(), scenario.getId(), 30).validate(),
                differentTarget,
                scenario
        );

        assertFailed(decision, "SINGLE_TARGET_SCOPE");
    }

    private void assertFailed(SafetyDecision decision, String code) {
        assertThat(decision.accepted()).isFalse();
        assertThat(decision.checks())
                .filteredOn(check -> check.code().equals(code))
                .singleElement()
                .extracting(SafetyCheck::passed)
                .isEqualTo(false);
    }

    private Target target(TargetEnvironment environment, boolean enabled) {
        Target target = Target.register(
                UUID.randomUUID(),
                "payment-service",
                TargetType.JAVA_APPLICATION,
                environment
        );
        if (!enabled) {
            target.disable();
        }
        return target;
    }

    private FaultScenario scenario(boolean enabled) {
        return FaultScenario.rehydrate(
                UUID.randomUUID(),
                "CPU_LOAD",
                "CPU Load",
                "Consumes bounded CPU.",
                "{\"type\":\"object\"}",
                enabled
        );
    }

    private Experiment experiment(
            UUID targetId,
            UUID scenarioId,
            int durationSeconds
    ) {
        return Experiment.create(
                UUID.randomUUID(),
                "payment CPU experiment",
                "Service remains available.",
                targetId,
                scenarioId,
                durationSeconds,
                "{\"percent\":40}"
        );
    }
}
