CREATE INDEX idx_audit_logs_experiment_execution_occurred_at
    ON audit_logs (experiment_id, execution_id, occurred_at);
