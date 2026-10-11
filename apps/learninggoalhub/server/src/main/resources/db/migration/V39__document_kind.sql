-- Instructors upload lectures and exercises separately. The kind decides a document's hierarchy
-- level; NULL marks a document uploaded before the choice existed, which keeps the old title-based
-- level. No backfill.
ALTER TABLE document
    ADD COLUMN kind VARCHAR(16)
        CHECK (kind IN ('LECTURE', 'EXERCISE'));
