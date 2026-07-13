-- Document sections are the structural learning units detected at upload time (deterministically,
-- from PDF bookmarks) rather than by an LLM outline pass. Each section is a half-open character
-- range [start_offset, end_offset) into the document's raw_text; the extraction step turns each
-- section into one SESSION hierarchy node and routes that range's chunks to it. A document with no
-- detectable structure has no rows here and is treated as a single session.
CREATE TABLE document_section (
    id           BIGSERIAL PRIMARY KEY,
    document_id  BIGINT      NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    ordinal      INTEGER     NOT NULL,
    title        TEXT        NOT NULL,
    start_offset INTEGER     NOT NULL,
    end_offset   INTEGER     NOT NULL
);

CREATE INDEX idx_document_section_document ON document_section (document_id);
