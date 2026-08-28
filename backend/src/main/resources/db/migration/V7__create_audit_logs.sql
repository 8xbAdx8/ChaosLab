CREATE TABLE audit_logs (
    id CHAR(36) PRIMARY KEY,
    actor VARCHAR(100) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    experiment_id CHAR(36),
    execution_id CHAR(36),
    target_id CHAR(36),
    scenario_code VARCHAR(64),
    parameters JSON,
    source_ip VARCHAR(45),
    result VARCHAR(32) NOT NULL,
    failure_code VARCHAR(100),
    occurred_at TIMESTAMP(6) NOT NULL,
    INDEX idx_audit_logs_occurred_at (occurred_at),
    INDEX idx_audit_logs_experiment_occurred_at (experiment_id, occurred_at)
);
