-- Set when the instructor dismisses the one-time skill review shown on first opening a course
-- after its extraction finished. NULL means the review is still outstanding.
ALTER TABLE course
    ADD COLUMN skills_reviewed_at TIMESTAMPTZ;
