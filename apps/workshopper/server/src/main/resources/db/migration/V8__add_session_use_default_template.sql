-- V8__add_session_use_default_template.sql
-- Lets a session in a course opt out of the course's slide template and use the default design.

ALTER TABLE workshop_sessions ADD COLUMN use_default_template BOOLEAN NOT NULL DEFAULT FALSE;
