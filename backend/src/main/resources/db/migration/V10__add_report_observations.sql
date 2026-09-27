ALTER TABLE experiment_reports ADD COLUMN binding_status VARCHAR(32) NOT NULL DEFAULT 'NOT_VERIFIED';
ALTER TABLE experiment_reports ADD COLUMN container_id VARCHAR(64);
ALTER TABLE experiment_reports ADD COLUMN image_id VARCHAR(71);
ALTER TABLE experiment_reports ADD COLUMN observations JSON;
