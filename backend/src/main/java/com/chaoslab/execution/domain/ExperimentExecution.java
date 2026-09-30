package com.chaoslab.execution.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class ExperimentExecution {

    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    public static final int MAX_ENGINE_EXPERIMENT_ID_LENGTH = 128;
    public static final int MAX_ERROR_MESSAGE_LENGTH = 1000;

    private final UUID id;
    private final UUID experimentId;
    private final int attempt;
    private final String idempotencyKey;
    private final ExperimentExecutionStatus status;
    private final String engineExperimentId;
    private final String errorMessage;
    private final Instant createdAt;
    private final Instant startedAt;
    private final Instant finishedAt;
    private final long version;

    private ExperimentExecution(
            UUID id,
            UUID experimentId,
            int attempt,
            String idempotencyKey,
            ExperimentExecutionStatus status,
            String engineExperimentId,
            String errorMessage,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            long version
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.experimentId = Objects.requireNonNull(
                experimentId,
                "experimentId must not be null"
        );
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be at least 1");
        }
        this.attempt = attempt;
        this.idempotencyKey = validateText(
                idempotencyKey,
                "idempotencyKey",
                MAX_IDEMPOTENCY_KEY_LENGTH
        );
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.engineExperimentId = normalizeOptionalText(
                engineExperimentId,
                "engineExperimentId",
                MAX_ENGINE_EXPERIMENT_ID_LENGTH
        );
        this.errorMessage = normalizeOptionalText(
                errorMessage,
                "errorMessage",
                MAX_ERROR_MESSAGE_LENGTH
        );
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.startedAt = startedAt;
        if (startedAt != null && startedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("startedAt must not be before createdAt");
        }
        this.finishedAt = finishedAt;
        if (finishedAt != null
                && (startedAt == null || finishedAt.isBefore(startedAt))) {
            throw new IllegalArgumentException("finishedAt must not be before startedAt");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        this.version = version;
        validateState();
    }

    public static ExperimentExecution prepare(
            UUID id,
            UUID experimentId,
            int attempt,
            String idempotencyKey,
            Instant createdAt
    ) {
        return new ExperimentExecution(
                id,
                experimentId,
                attempt,
                idempotencyKey,
                ExperimentExecutionStatus.PREPARING,
                null,
                null,
                createdAt,
                null,
                null,
                0
        );
    }

    public static ExperimentExecution rehydrate(
            UUID id,
            UUID experimentId,
            int attempt,
            String idempotencyKey,
            ExperimentExecutionStatus status,
            String engineExperimentId,
            String errorMessage,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            long version
    ) {
        return new ExperimentExecution(
                id,
                experimentId,
                attempt,
                idempotencyKey,
                status,
                engineExperimentId,
                errorMessage,
                createdAt,
                startedAt,
                finishedAt,
                version
        );
    }

    public ExperimentExecution markRunning(
            String newEngineExperimentId,
            Instant newStartedAt
    ) {
        String normalizedEngineId = validateText(
                newEngineExperimentId,
                "engineExperimentId",
                MAX_ENGINE_EXPERIMENT_ID_LENGTH
        );
        if (status == ExperimentExecutionStatus.RUNNING) {
            if (!engineExperimentId.equals(normalizedEngineId)) {
                throw new IllegalStateException(
                        "running execution cannot change engineExperimentId"
                );
            }
            return this;
        }
        requireStatus(ExperimentExecutionStatus.PREPARING, "mark running");
        return copy(
                ExperimentExecutionStatus.RUNNING,
                normalizedEngineId,
                null,
                Objects.requireNonNull(newStartedAt, "startedAt must not be null"),
                null
        );
    }

    public ExperimentExecution markFailed(String newErrorMessage) {
        String normalizedError = validateText(
                newErrorMessage,
                "errorMessage",
                MAX_ERROR_MESSAGE_LENGTH
        );
        if (status == ExperimentExecutionStatus.FAILED) {
            return this;
        }
        requireStatus(ExperimentExecutionStatus.PREPARING, "mark failed");
        return copy(
                ExperimentExecutionStatus.FAILED,
                null,
                normalizedError,
                null,
                null
        );
    }

    /** No automatic outgoing transition: recovery identity must not be guessed. */
    public ExperimentExecution markCreateUncertain() {
        if (status == ExperimentExecutionStatus.CREATE_UNCERTAIN) return this;
        requireStatus(ExperimentExecutionStatus.PREPARING, "mark create uncertain");
        return copy(ExperimentExecutionStatus.CREATE_UNCERTAIN, null,
                "engine create outcome uncertain; manual intervention required", null, null);
    }

    public ExperimentExecution beginDestroy() {
        if (status == ExperimentExecutionStatus.DESTROYING) {
            return this;
        }
        if (status != ExperimentExecutionStatus.RUNNING
                && status != ExperimentExecutionStatus.ROLLBACK_FAILED) {
            throw new IllegalStateException(
                    "cannot begin destroy execution from status " + status
            );
        }
        return copy(
                ExperimentExecutionStatus.DESTROYING,
                engineExperimentId,
                null,
                startedAt,
                null
        );
    }

    public ExperimentExecution markSuccess(Instant newFinishedAt) {
        if (status == ExperimentExecutionStatus.SUCCESS) {
            return this;
        }
        requireStatus(ExperimentExecutionStatus.DESTROYING, "mark success");
        return copy(
                ExperimentExecutionStatus.SUCCESS,
                engineExperimentId,
                null,
                startedAt,
                Objects.requireNonNull(newFinishedAt, "finishedAt must not be null")
        );
    }

    public ExperimentExecution markRollbackFailed(String newErrorMessage) {
        String normalizedError = validateText(
                newErrorMessage,
                "errorMessage",
                MAX_ERROR_MESSAGE_LENGTH
        );
        if (status == ExperimentExecutionStatus.ROLLBACK_FAILED) {
            return this;
        }
        requireStatus(
                ExperimentExecutionStatus.DESTROYING,
                "mark rollback failed"
        );
        return copy(
                ExperimentExecutionStatus.ROLLBACK_FAILED,
                engineExperimentId,
                normalizedError,
                startedAt,
                null
        );
    }

    public UUID getId() {
        return id;
    }

    public UUID getExperimentId() {
        return experimentId;
    }

    public int getAttempt() {
        return attempt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public ExperimentExecutionStatus getStatus() {
        return status;
    }

    public String getEngineExperimentId() {
        return engineExperimentId;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public long getVersion() {
        return version;
    }

    private ExperimentExecution copy(
            ExperimentExecutionStatus newStatus,
            String newEngineExperimentId,
            String newErrorMessage,
            Instant newStartedAt,
            Instant newFinishedAt
    ) {
        return new ExperimentExecution(
                id,
                experimentId,
                attempt,
                idempotencyKey,
                newStatus,
                newEngineExperimentId,
                newErrorMessage,
                createdAt,
                newStartedAt,
                newFinishedAt,
                version
        );
    }

    private void requireStatus(
            ExperimentExecutionStatus requiredStatus,
            String action
    ) {
        if (status != requiredStatus) {
            throw new IllegalStateException(
                    "cannot " + action + " execution from status " + status
            );
        }
    }

    private void validateState() {
        switch (status) {
            case PREPARING -> requireFields(null, null, null, null);
            case RUNNING -> requireActiveEngineState("running", false);
            case DESTROYING -> requireActiveEngineState("destroying", false);
            case SUCCESS -> requireActiveEngineState("successful", true);
            case FAILED, CREATE_UNCERTAIN -> {
                if (errorMessage == null
                        || engineExperimentId != null
                        || startedAt != null
                        || finishedAt != null) {
                    throw new IllegalArgumentException(
                            "failed or create-uncertain execution requires only an error message"
                    );
                }
            }
            case ROLLBACK_FAILED -> {
                if (engineExperimentId == null
                        || startedAt == null
                        || errorMessage == null
                        || finishedAt != null) {
                    throw new IllegalArgumentException(
                            "rollback failed execution requires engine data and an error"
                    );
                }
            }
        }
    }

    private void requireActiveEngineState(String state, boolean requiresFinishedAt) {
        boolean invalidFinishedAt = requiresFinishedAt
                ? finishedAt == null
                : finishedAt != null;
        if (engineExperimentId == null
                || startedAt == null
                || errorMessage != null
                || invalidFinishedAt) {
            throw new IllegalArgumentException(
                    state + " execution contains inconsistent engine result fields"
            );
        }
    }

    private void requireFields(
            String expectedEngineId,
            String expectedError,
            Instant expectedStartedAt,
            Instant expectedFinishedAt
    ) {
        if (!Objects.equals(engineExperimentId, expectedEngineId)
                || !Objects.equals(errorMessage, expectedError)
                || !Objects.equals(startedAt, expectedStartedAt)
                || !Objects.equals(finishedAt, expectedFinishedAt)) {
            throw new IllegalArgumentException(
                    "preparing execution cannot contain engine result fields"
            );
        }
    }

    private static String validateText(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must not exceed " + maxLength + " characters"
            );
        }
        return normalized;
    }

    private static String normalizeOptionalText(
            String value,
            String field,
            int maxLength
    ) {
        return value == null ? null : validateText(value, field, maxLength);
    }
}
