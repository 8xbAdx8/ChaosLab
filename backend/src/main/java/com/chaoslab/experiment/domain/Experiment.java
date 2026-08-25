package com.chaoslab.experiment.domain;

import java.util.Objects;
import java.util.UUID;

public final class Experiment {

    public static final int MAX_NAME_LENGTH = 100;
    public static final int MAX_HYPOTHESIS_LENGTH = 500;
    public static final int MIN_DURATION_SECONDS = 5;
    public static final int MAX_DURATION_SECONDS = 60;

    private final UUID id;
    private final String name;
    private final String hypothesis;
    private final UUID targetId;
    private final UUID scenarioId;
    private final int durationSeconds;
    private final String parameters;
    private final ExperimentStatus status;
    private final long version;

    private Experiment(
            UUID id,
            String name,
            String hypothesis,
            UUID targetId,
            UUID scenarioId,
            int durationSeconds,
            String parameters,
            ExperimentStatus status,
            long version
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.name = validateText(name, "name", MAX_NAME_LENGTH);
        this.hypothesis = validateText(
                hypothesis,
                "hypothesis",
                MAX_HYPOTHESIS_LENGTH
        );
        this.targetId = Objects.requireNonNull(targetId, "targetId must not be null");
        this.scenarioId = Objects.requireNonNull(scenarioId, "scenarioId must not be null");
        this.durationSeconds = validateDuration(durationSeconds);
        this.parameters = validateRequiredText(parameters, "parameters");
        this.status = Objects.requireNonNull(status, "status must not be null");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        this.version = version;
    }

    public static Experiment create(
            UUID id,
            String name,
            String hypothesis,
            UUID targetId,
            UUID scenarioId,
            int durationSeconds,
            String parameters
    ) {
        return new Experiment(
                id,
                name,
                hypothesis,
                targetId,
                scenarioId,
                durationSeconds,
                parameters,
                ExperimentStatus.CREATED,
                0
        );
    }

    public static Experiment rehydrate(
            UUID id,
            String name,
            String hypothesis,
            UUID targetId,
            UUID scenarioId,
            int durationSeconds,
            String parameters,
            ExperimentStatus status,
            long version
    ) {
        return new Experiment(
                id,
                name,
                hypothesis,
                targetId,
                scenarioId,
                durationSeconds,
                parameters,
                status,
                version
        );
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getHypothesis() {
        return hypothesis;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public UUID getScenarioId() {
        return scenarioId;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public String getParameters() {
        return parameters;
    }

    public ExperimentStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public Experiment validate() {
        if (status == ExperimentStatus.VALIDATED || status == ExperimentStatus.READY) {
            return this;
        }
        if (status != ExperimentStatus.CREATED) {
            throw new IllegalStateException(
                    "experiment cannot be validated from status " + status
            );
        }
        return new Experiment(
                id,
                name,
                hypothesis,
                targetId,
                scenarioId,
                durationSeconds,
                parameters,
                ExperimentStatus.VALIDATED,
                version
        );
    }

    public Experiment ready() {
        if (status == ExperimentStatus.READY) {
            return this;
        }
        if (status != ExperimentStatus.VALIDATED) {
            throw new IllegalStateException(
                    "experiment cannot be made ready from status " + status
            );
        }
        return new Experiment(
                id,
                name,
                hypothesis,
                targetId,
                scenarioId,
                durationSeconds,
                parameters,
                ExperimentStatus.READY,
                version
        );
    }

    private static int validateDuration(int durationSeconds) {
        if (durationSeconds < MIN_DURATION_SECONDS
                || durationSeconds > MAX_DURATION_SECONDS) {
            throw new IllegalArgumentException(
                    "durationSeconds must be between "
                            + MIN_DURATION_SECONDS
                            + " and "
                            + MAX_DURATION_SECONDS
            );
        }
        return durationSeconds;
    }

    private static String validateText(String value, String field, int maxLength) {
        String normalizedValue = validateRequiredText(value, field);
        if (normalizedValue.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must not exceed " + maxLength + " characters"
            );
        }
        return normalizedValue;
    }

    private static String validateRequiredText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalizedValue = value.trim();
        if (normalizedValue.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalizedValue;
    }
}
