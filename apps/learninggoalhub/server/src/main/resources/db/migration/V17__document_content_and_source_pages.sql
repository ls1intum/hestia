-- Store the original upload bytes separately so routine document queries do not load large content.
CREATE TABLE document_content (
    document_id BIGINT PRIMARY KEY REFERENCES document (id) ON DELETE CASCADE,
    bytes       BYTEA NOT NULL
);

-- Null means that no reliable per-page character offsets are available for this document.
ALTER TABLE document
    ADD COLUMN page_offsets INTEGER[];

-- The source page is nullable for legacy sources and snippets that cannot be located.
ALTER TABLE goal_source
    ADD COLUMN page INTEGER;
