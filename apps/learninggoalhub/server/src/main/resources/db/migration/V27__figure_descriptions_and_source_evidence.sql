CREATE TABLE page_description (
    document_id BIGINT NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    page        INT NOT NULL CHECK (page >= 1),
    description TEXT NOT NULL,
    model       VARCHAR(255),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, page)
);

ALTER TABLE document_section
    ADD COLUMN start_page INT,
    ADD COLUMN end_page INT;

ALTER TABLE goal_source
    ADD COLUMN evidence_kind VARCHAR(16);

UPDATE goal_source
SET evidence_kind = CASE WHEN grounded THEN 'TEXT' ELSE 'UNSUPPORTED' END;

ALTER TABLE goal_source
    ALTER COLUMN evidence_kind SET NOT NULL,
    ADD CONSTRAINT goal_source_evidence_kind_check
        CHECK (evidence_kind IN ('TEXT', 'FIGURE', 'UNSUPPORTED'));
