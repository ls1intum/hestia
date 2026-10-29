-- V4__extract_slide_templates.sql

CREATE TABLE slide_templates (
    id VARCHAR(255) PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    file_name VARCHAR(255),
    file_data BYTEA NOT NULL,
    created_at TIMESTAMP DEFAULT now()
);

-- One row per session that currently has a template. Use the session's own id
-- as a natural key link for this migration (simplifies the backfill — no need
-- to generate new UUIDs client-side in the migration).
INSERT INTO slide_templates (id, owner_id, file_data, created_at)
SELECT id, owner_id, template_data, COALESCE(updated_at, created_at)
FROM workshop_sessions
WHERE template_data IS NOT NULL;

ALTER TABLE workshop_sessions ADD COLUMN template_id VARCHAR(255);

UPDATE workshop_sessions
SET template_id = id
WHERE template_data IS NOT NULL;

ALTER TABLE workshop_sessions
    ADD CONSTRAINT fk_workshop_sessions_template
    FOREIGN KEY (template_id) REFERENCES slide_templates(id) ON DELETE SET NULL;

ALTER TABLE workshop_sessions DROP COLUMN template_data;
