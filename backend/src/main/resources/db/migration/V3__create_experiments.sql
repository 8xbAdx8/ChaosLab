CREATE TABLE experiments (
    id CHAR(36) NOT NULL,
    name VARCHAR(100) NOT NULL,
    hypothesis VARCHAR(500) NOT NULL,
    target_id CHAR(36) NOT NULL,
    scenario_id CHAR(36) NOT NULL,
    duration_seconds INTEGER NOT NULL,
    parameters JSON NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_experiments_target
        FOREIGN KEY (target_id) REFERENCES targets (id),
    CONSTRAINT fk_experiments_scenario
        FOREIGN KEY (scenario_id) REFERENCES fault_scenarios (id),
    CONSTRAINT ck_experiments_duration
        CHECK (duration_seconds BETWEEN 5 AND 60)
);

CREATE INDEX idx_experiments_name_id ON experiments (name, id);
CREATE INDEX idx_experiments_target ON experiments (target_id);
CREATE INDEX idx_experiments_scenario ON experiments (scenario_id);
CREATE INDEX idx_experiments_status ON experiments (status);
