package com.chaoslab.safety.application.model;

import java.util.List;
import java.util.Objects;

public record SafetyDecision(
        List<SafetyCheck> checks,
        DryRunPlan plan
) {

    public SafetyDecision {
        Objects.requireNonNull(checks, "checks must not be null");
        checks = List.copyOf(checks);
        if (checks.isEmpty()) {
            throw new IllegalArgumentException("checks must not be empty");
        }
        if (checks.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("checks must not contain null");
        }
        Objects.requireNonNull(plan, "plan must not be null");
    }

    public boolean accepted() {
        return checks.stream().allMatch(SafetyCheck::passed);
    }
}
