-- Baseline of the teaching module: the schema the 8 migrations of the former Workshopper server
-- produced on an empty database, dumped with pg_dump --schema-only and moved from public into
-- teaching. Generated, not hand-written; later changes go into new migrations.

--
-- Name: courses; Type: TABLE; Schema: teaching; Owner: -
--

CREATE TABLE teaching.courses (
    id character varying(255) NOT NULL,
    owner_id character varying(255) NOT NULL,
    title text,
    display_order integer,
    draft_state_json jsonb,
    status character varying(20),
    current_step character varying(50),
    created_at timestamp without time zone,
    updated_at timestamp without time zone,
    template_id character varying(255)
);

--
-- Name: slide_templates; Type: TABLE; Schema: teaching; Owner: -
--

CREATE TABLE teaching.slide_templates (
    id character varying(255) NOT NULL,
    owner_id character varying(255) NOT NULL,
    file_name character varying(255),
    file_data bytea NOT NULL,
    created_at timestamp without time zone DEFAULT now()
);

--
-- Name: workshop_sessions; Type: TABLE; Schema: teaching; Owner: -
--

CREATE TABLE teaching.workshop_sessions (
    id character varying(255) NOT NULL,
    course_id character varying(36),
    display_order integer,
    title text,
    student_background text,
    session_json jsonb,
    draft_state_json jsonb,
    slides_json jsonb,
    status character varying(20) DEFAULT 'draft'::character varying NOT NULL,
    current_step character varying(50),
    created_at timestamp without time zone,
    updated_at timestamp without time zone,
    owner_id character varying(255),
    duration integer,
    participants integer,
    session_type character varying(50),
    session_type_other character varying(100),
    template_id character varying(255),
    use_default_template boolean DEFAULT false NOT NULL
);

--
-- Name: courses courses_pkey; Type: CONSTRAINT; Schema: teaching; Owner: -
--

ALTER TABLE ONLY teaching.courses
    ADD CONSTRAINT courses_pkey PRIMARY KEY (id);

--
-- Name: slide_templates slide_templates_pkey; Type: CONSTRAINT; Schema: teaching; Owner: -
--

ALTER TABLE ONLY teaching.slide_templates
    ADD CONSTRAINT slide_templates_pkey PRIMARY KEY (id);

--
-- Name: workshop_sessions workshop_sessions_pkey; Type: CONSTRAINT; Schema: teaching; Owner: -
--

ALTER TABLE ONLY teaching.workshop_sessions
    ADD CONSTRAINT workshop_sessions_pkey PRIMARY KEY (id);

--
-- Name: courses fk_courses_template; Type: FK CONSTRAINT; Schema: teaching; Owner: -
--

ALTER TABLE ONLY teaching.courses
    ADD CONSTRAINT fk_courses_template FOREIGN KEY (template_id) REFERENCES teaching.slide_templates(id) ON DELETE SET NULL;

--
-- Name: workshop_sessions fk_workshop_sessions_course; Type: FK CONSTRAINT; Schema: teaching; Owner: -
--

ALTER TABLE ONLY teaching.workshop_sessions
    ADD CONSTRAINT fk_workshop_sessions_course FOREIGN KEY (course_id) REFERENCES teaching.courses(id) ON DELETE CASCADE;

--
-- Name: workshop_sessions fk_workshop_sessions_template; Type: FK CONSTRAINT; Schema: teaching; Owner: -
--

ALTER TABLE ONLY teaching.workshop_sessions
    ADD CONSTRAINT fk_workshop_sessions_template FOREIGN KEY (template_id) REFERENCES teaching.slide_templates(id) ON DELETE SET NULL;
