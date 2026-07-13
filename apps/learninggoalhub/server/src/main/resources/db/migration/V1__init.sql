CREATE TABLE course (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE document (
    id            BIGSERIAL PRIMARY KEY,
    course_id     BIGINT       NOT NULL REFERENCES course (id) ON DELETE CASCADE,
    filename      VARCHAR(512) NOT NULL,
    content_type  VARCHAR(255) NOT NULL,
    raw_text      TEXT,
    uploaded_at   TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_document_course ON document (course_id);
