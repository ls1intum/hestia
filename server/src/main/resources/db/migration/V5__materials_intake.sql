-- Material intake (package 3a): teachers upload the material of a past course as the first step of
-- the workflow. Each submission records the terms version the submitter accepted, the confirmation
-- and the two research consents.

CREATE TABLE materials.intake_submission (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    submitter_id text NOT NULL,
    course_title text,
    contact_email text,
    note text,
    terms_version text NOT NULL,
    terms_sha256 text NOT NULL,
    -- Terms accepted, rights to the material, no student data: one required confirmation.
    confirmed_terms boolean NOT NULL,
    -- Opt-out, checked by default: the material may be used for the HESTIA research.
    consent_research boolean NOT NULL,
    -- Opt-in: anonymised data may go into a published replication package.
    consent_replication_package boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT intake_submission_pkey PRIMARY KEY (id),
    CONSTRAINT intake_submission_confirmed CHECK (confirmed_terms)
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
