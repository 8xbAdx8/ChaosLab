CREATE TABLE experiment_reports (
    id CHAR(36) NOT NULL,
    experiment_id CHAR(36) NOT NULL,
    execution_id CHAR(36) NOT NULL,
    generation_key VARCHAR(128) NOT NULL,
    target_id CHAR(36) NOT NULL,
    scenario_code VARCHAR(64) NOT NULL,
    start_audit_id CHAR(36) NOT NULL,
    recovery_audit_id CHAR(36) NOT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    finished_at TIMESTAMP(6) NOT NULL,
    generated_at TIMESTAMP(6) NOT NULL,
    execution_mode VARCHAR(32) NOT NULL,
    metrics_status VARCHAR(32) NOT NULL,
    conclusion_status VARCHAR(32) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_reports_experiment
        FOREIGN KEY (experiment_id) REFERENCES experiments (id),
    CONSTRAINT fk_reports_execution
        FOREIGN KEY (execution_id) REFERENCES experiment_executions (id),
    CONSTRAINT uk_reports_execution_generation
        UNIQUE (execution_id, generation_key),
    CONSTRAINT ck_reports_time_order
        CHECK (finished_at > started_at)
);

CREATE INDEX idx_reports_execution_generated
    ON experiment_reports (execution_id, generated_at);
