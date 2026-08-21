CREATE TABLE fault_scenarios (
    id CHAR(36) NOT NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NOT NULL,
    parameter_schema JSON NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    CONSTRAINT uk_fault_scenarios_code UNIQUE (code)
);

CREATE INDEX idx_fault_scenarios_enabled_code
    ON fault_scenarios (enabled, code);

INSERT INTO fault_scenarios (
    id,
    code,
    name,
    description,
    parameter_schema,
    enabled
) VALUES
(
    '00000000-0000-0000-0000-000000000101',
    'CPU_LOAD',
    'CPU Load',
    'Consumes a bounded percentage of CPU on one isolated target.',
    JSON_OBJECT(
        '$schema', 'https://json-schema.org/draft/2020-12/schema',
        'type', 'object',
        'additionalProperties', FALSE,
        'required', JSON_ARRAY('percent'),
        'properties', JSON_OBJECT(
            'percent', JSON_OBJECT(
                'type', 'integer',
                'minimum', 10,
                'maximum', 80
            )
        )
    ),
    TRUE
),
(
    '00000000-0000-0000-0000-000000000102',
    'NETWORK_DELAY',
    'Network Delay',
    'Adds bounded network latency to one isolated target.',
    JSON_OBJECT(
        '$schema', 'https://json-schema.org/draft/2020-12/schema',
        'type', 'object',
        'additionalProperties', FALSE,
        'required', JSON_ARRAY('delayMs'),
        'properties', JSON_OBJECT(
            'delayMs', JSON_OBJECT(
                'type', 'integer',
                'minimum', 10,
                'maximum', 3000
            )
        )
    ),
    TRUE
);
