CREATE TABLE workshop_sessions (
    id VARCHAR(255) PRIMARY KEY,
    type VARCHAR(20) DEFAULT 'SESSION',
    lecture_id VARCHAR(36),
    display_order INTEGER,
    title TEXT,
    learning_goal TEXT,
    student_background TEXT,
    prerequisites TEXT,
    session_json TEXT,
    draft_state_json TEXT,
    template_data BYTEA,
    slides_json TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'draft',
    current_step VARCHAR(50),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);
