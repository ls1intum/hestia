-- Instructors can rename documents for display. The original filename stays immutable as the
-- provenance/source-attribution label; display_name is NULL until a rename happens and clearing
-- it restores the filename fallback.
ALTER TABLE document
    ADD COLUMN display_name TEXT;
