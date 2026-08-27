CREATE TABLE execution_admission_control (
    id SMALLINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_execution_admission_singleton CHECK (id = 1)
);

INSERT INTO execution_admission_control (id) VALUES (1);
