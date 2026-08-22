CREATE TABLE experiment_executions (
    id CHAR(36) NOT NULL,
    experiment_id CHAR(36) NOT NULL,
    attempt INTEGER NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    engine_experiment_id VARCHAR(128),
    error_message VARCHAR(1000),
    created_at TIMESTAMP(6) NOT NULL,
    started_at TIMESTAMP(6),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_executions_experiment
        FOREIGN KEY (experiment_id) REFERENCES experiments (id),
    CONSTRAINT uk_executions_experiment_attempt
        UNIQUE (experiment_id, attempt),
    CONSTRAINT uk_executions_experiment_idempotency
        UNIQUE (experiment_id, idempotency_key),
    CONSTRAINT ck_executions_attempt
        CHECK (attempt >= 1)
);

CREATE INDEX idx_executions_experiment_status
    ON experiment_executions (experiment_id, status);
