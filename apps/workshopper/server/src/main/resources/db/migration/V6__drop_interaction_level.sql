-- Remove interaction_level from workshop_sessions
ALTER TABLE workshop_sessions DROP COLUMN IF EXISTS interaction_level;
