CREATE TABLE targets (
    id CHAR(36) NOT NULL,
    name VARCHAR(100) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    environment VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id)
);

CREATE INDEX idx_targets_name_id ON targets (name, id);
