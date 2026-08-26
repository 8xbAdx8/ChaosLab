ALTER TABLE experiment_executions
    ADD COLUMN finished_at TIMESTAMP(6);

CREATE INDEX idx_executions_status_started_at
    ON experiment_executions (status, started_at);