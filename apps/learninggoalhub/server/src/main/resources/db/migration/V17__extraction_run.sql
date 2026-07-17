CREATE TABLE extraction_run (
    id                   BIGSERIAL PRIMARY KEY,
    course_id            BIGINT       NOT NULL REFERENCES course (id) ON DELETE CASCADE,
    model                VARCHAR(255),
    prompt_version       VARCHAR(32)  NOT NULL,
    params               TEXT         NOT NULL,
    status               VARCHAR(16)  NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    error                TEXT,
    started_at           TIMESTAMPTZ  NOT NULL,
    finished_at          TIMESTAMPTZ,
    goals_created        INTEGER
);

CREATE INDEX idx_extraction_run_course ON extraction_run (course_id);
