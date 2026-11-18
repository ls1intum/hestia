-- Material intake (package 3a): teachers submit slides, exams and module descriptions for research.
-- Each submission records the terms version the submitter accepted and their confirmations.

CREATE TABLE materials.intake_submission (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    submitter_id text NOT NULL,
    contact_email text,
    note text,
    terms_version text NOT NULL,
    terms_sha256 text NOT NULL,
    confirmed_rights boolean NOT NULL,
    confirmed_no_student_data boolean NOT NULL,
    consent_publication boolean DEFAULT false NOT NULL,
    consent_external_ai boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT intake_submission_pkey PRIMARY KEY (id),
    CONSTRAINT intake_submission_confirmed CHECK (confirmed_rights AND confirmed_no_student_data)
);

CREATE INDEX intake_submission_submitter_idx ON materials.intake_submission (submitter_id, created_at);

CREATE TABLE materials.intake_file (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    submission_id uuid NOT NULL,
    original_name text NOT NULL,
    content_type text,
    size_bytes bigint NOT NULL,
    sha256 text NOT NULL,
    bytes bytea NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT intake_file_pkey PRIMARY KEY (id),
    CONSTRAINT intake_file_submission_fkey FOREIGN KEY (submission_id)
        REFERENCES materials.intake_submission (id) ON DELETE CASCADE
);

CREATE INDEX intake_file_submission_idx ON materials.intake_file (submission_id);
