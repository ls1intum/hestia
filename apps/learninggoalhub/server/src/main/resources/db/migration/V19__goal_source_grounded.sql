-- FALSE means that the snippet was not located verbatim in the document; legacy rows stay FALSE.
ALTER TABLE goal_source ADD COLUMN grounded BOOLEAN NOT NULL DEFAULT FALSE;
