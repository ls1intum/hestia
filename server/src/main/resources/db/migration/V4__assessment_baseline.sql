-- Baseline of the assessment module: the schema the 16 migrations of the former ExamLense server
-- produced on an empty database, dumped with pg_dump --schema-only and moved from public into
-- assessment. Generated, not hand-written; later changes go into new migrations.

--
-- Name: set_updated_at(); Type: FUNCTION; Schema: assessment; Owner: -
--

CREATE FUNCTION assessment.set_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
  new.updated_at = now();
  return new;
end;
$$;

--
-- Name: evaluation_runs; Type: TABLE; Schema: assessment; Owner: -
--

CREATE TABLE assessment.evaluation_runs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    exam_id uuid NOT NULL,
    solver_model text NOT NULL,
    thinking_level text,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT evaluation_runs_thinking_level_check CHECK ((thinking_level = ANY (ARRAY['off'::text, 'dynamic'::text, 'minimal'::text, 'low'::text, 'medium'::text, 'high'::text, 'xhigh'::text])))
);

--
-- Name: exams; Type: TABLE; Schema: assessment; Owner: -
--

CREATE TABLE assessment.exams (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    title text DEFAULT ''::text NOT NULL,
    course text,
    source text NOT NULL,
    source_file_url text,
    status text DEFAULT 'draft'::text NOT NULL,
    owner_id uuid NOT NULL,
    parse_error text,
    parse_phase text,
    parser_model text,
    solver_model text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    lgh_course_id bigint,
    page_count integer,
    parse_started_at timestamp with time zone,
    parsed_at timestamp with time zone,
    CONSTRAINT exams_source_check CHECK ((source = ANY (ARRAY['pdf'::text, 'manual'::text]))),
    CONSTRAINT exams_status_check CHECK ((status = ANY (ARRAY['draft'::text, 'parsing'::text, 'failed'::text, 'ready'::text, 'evaluating'::text, 'grading'::text, 'finished'::text])))
);

--
-- Name: llm_usage; Type: TABLE; Schema: assessment; Owner: -
--

CREATE TABLE assessment.llm_usage (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    kind text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT llm_usage_kind_check CHECK ((kind = ANY (ARRAY['parse'::text, 'solve'::text])))
);

--
-- Name: parse_metrics; Type: TABLE; Schema: assessment; Owner: -
--

CREATE TABLE assessment.parse_metrics (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    exam_id uuid,
    owner_id uuid,
    parser_model text NOT NULL,
    pdf_mode text,
    page_count integer,
    duration_ms integer,
    llm_ms integer,
    prompt_tokens integer,
    completion_tokens integer,
    total_tokens integer,
    success boolean NOT NULL,
    error text
);

--
-- Name: section_blocks; Type: TABLE; Schema: assessment; Owner: -
--

CREATE TABLE assessment.section_blocks (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    section_id uuid NOT NULL,
    exam_id uuid NOT NULL,
    "position" integer DEFAULT 0 NOT NULL,
    content text DEFAULT ''::text NOT NULL,
    kind text DEFAULT 'context'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT section_blocks_kind_check CHECK ((kind = ANY (ARRAY['context'::text, 'figure'::text])))
);

--
-- Name: section_figures; Type: TABLE; Schema: assessment; Owner: -
--

CREATE TABLE assessment.section_figures (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    block_id uuid NOT NULL,
    "position" integer DEFAULT 0 NOT NULL,
    storage_path text NOT NULL,
    source text DEFAULT 'upload'::text NOT NULL,
    caption text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT section_figures_source_check CHECK ((source = ANY (ARRAY['pdf'::text, 'upload'::text])))
);

--
-- Name: sections; Type: TABLE; Schema: assessment; Owner: -
--

CREATE TABLE assessment.sections (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    exam_id uuid NOT NULL,
    "position" integer NOT NULL,
    name text DEFAULT ''::text NOT NULL,
    confirmed_at timestamp with time zone,
    solve_started_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    goals_started_at timestamp with time zone
);

--
-- Name: task_answers; Type: TABLE; Schema: assessment; Owner: -
--

CREATE TABLE assessment.task_answers (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    task_id uuid NOT NULL,
    exam_id uuid NOT NULL,
    selected_option_ids uuid[] DEFAULT ARRAY[]::uuid[] NOT NULL,
    answer_text text,
    reasoning text,
    provider text NOT NULL,
    model text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

--
-- Name: task_grades; Type: TABLE; Schema: assessment; Owner: -
--

CREATE TABLE assessment.task_grades (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    score numeric,
    auto_graded boolean DEFAULT false NOT NULL,
    graded_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    answer_id uuid NOT NULL
);

--
-- Name: tasks; Type: TABLE; Schema: assessment; Owner: -
--

CREATE TABLE assessment.tasks (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    exam_id uuid NOT NULL,
    section_id uuid,
    "position" integer NOT NULL,
    section text,
    type text NOT NULL,
    prompt text DEFAULT ''::text NOT NULL,
    options jsonb,
    reference_answer text,
    points numeric,
    parse_confidence text,
    learning_goal_ids jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT tasks_parse_confidence_check CHECK ((parse_confidence = ANY (ARRAY['high'::text, 'medium'::text, 'low'::text]))),
    CONSTRAINT tasks_type_check CHECK ((type = ANY (ARRAY['single_choice'::text, 'multiple_choice'::text, 'text'::text])))
);

--
-- Name: user_tokens; Type: TABLE; Schema: assessment; Owner: -
--

CREATE TABLE assessment.user_tokens (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    token_hash text NOT NULL,
    label text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    last_used_at timestamp with time zone,
    expires_at timestamp with time zone,
    revoked_at timestamp with time zone
);

--
-- Name: users; Type: TABLE; Schema: assessment; Owner: -
--

CREATE TABLE assessment.users (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    external_id text NOT NULL,
    display_name text,
    is_admin boolean DEFAULT false NOT NULL,
    created_ip_hash text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    last_seen_at timestamp with time zone
);

--
-- Name: evaluation_runs evaluation_runs_exam_id_key; Type: CONSTRAINT; Schema: assessment; Owner: -
--

ALTER TABLE ONLY assessment.evaluation_runs
    ADD CONSTRAINT evaluation_runs_exam_id_key UNIQUE (exam_id);

--
-- Name: evaluation_runs evaluation_runs_pkey; Type: CONSTRAINT; Schema: assessment; Owner: -
--

ALTER TABLE ONLY assessment.evaluation_runs
    ADD CONSTRAINT evaluation_runs_pkey PRIMARY KEY (id);

--
-- Name: exams exams_pkey; Type: CONSTRAINT; Schema: assessment; Owner: -
--

ALTER TABLE ONLY assessment.exams
    ADD CONSTRAINT exams_pkey PRIMARY KEY (id);

--
-- Name: llm_usage llm_usage_pkey; Type: CONSTRAINT; Schema: assessment; Owner: -
--

ALTER TABLE ONLY assessment.llm_usage
    ADD CONSTRAINT llm_usage_pkey PRIMARY KEY (id);

--
-- Name: parse_metrics parse_metrics_pkey; Type: CONSTRAINT; Schema: assessment; Owner: -
--

ALTER TABLE ONLY assessment.parse_metrics
    ADD CONSTRAINT parse_metrics_pkey PRIMARY KEY (id);

--
-- Name: section_blocks section_blocks_pkey; Type: CONSTRAINT; Schema: assessment; Owner: -
--

ALTER TABLE ONLY assessment.section_blocks
    ADD CONSTRAINT section_blocks_pkey PRIMARY KEY (id);

--
-- Name: section_figures section_figures_pkey; Type: CONSTRAINT; Schema: assessment; Owner: -
--

ALTER TABLE ONLY assessment.section_figures
    ADD CONSTRAINT section_figures_pkey PRIMARY KEY (id);

--
-- Name: sections sections_pkey; Type: CONSTRAINT; Schema: assessment; Owner: -
--

ALTER TABLE ONLY assessment.sections
    ADD CONSTRAINT sections_pkey PRIMARY KEY (id);

--
-- Name: task_answers task_answers_pkey; Type: CONSTRAINT; Schema: assessment; Owner: -
--

ALTER TABLE ONLY assessment.task_answers
    ADD CONSTRAINT task_answers_pkey PRIMARY KEY (id);

--
-- Name: task_answers task_answers_task_id_key; Type: CONSTRAINT; Schema: assessment; Owner: -
--

ALTER TABLE ONLY assessment.task_answers
    ADD CONSTRAINT task_answers_task_id_key UNIQUE (task_id);

--
-- Name: task_grades task_grades_answer_id_key; Type: CONSTRAINT; Schema: assessment; Owner: -
--

ALTER TABLE ONLY assessment.task_grades
    ADD CONSTRAINT task_grades_answer_id_key UNIQUE (answer_id);

--
-- Name: task_grades task_grades_pkey; Type: CONSTRAINT; Schema: assessment; Owner: -
--

ALTER TABLE ONLY assessment.task_grades
    ADD CONSTRAINT task_grades_pkey PRIMARY KEY (id);

--
-- Name: tasks tasks_pkey; Type: CONSTRAINT; Schema: assessment; Owner: -
--

ALTER TABLE ONLY assessment.tasks
    ADD CONSTRAINT tasks_pkey PRIMARY KEY (id);

--
-- Name: user_tokens user_tokens_pkey; Type: CONSTRAINT; Schema: assessment; Owner: -
--

ALTER TABLE ONLY assessment.user_tokens
    ADD CONSTRAINT user_tokens_pkey PRIMARY KEY (id);

--
-- Name: user_tokens user_tokens_token_hash_key; Type: CONSTRAINT; Schema: assessment; Owner: -
--

ALTER TABLE ONLY assessment.user_tokens
    ADD CONSTRAINT user_tokens_token_hash_key UNIQUE (token_hash);

--
-- Name: users users_external_id_key; Type: CONSTRAINT; Schema: assessment; Owner: -
--

ALTER TABLE ONLY assessment.users
    ADD CONSTRAINT users_external_id_key UNIQUE (external_id);

--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: assessment; Owner: -
--

ALTER TABLE ONLY assessment.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);

--
-- Name: idx_exams_owner_id; Type: INDEX; Schema: assessment; Owner: -
--

CREATE INDEX idx_exams_owner_id ON assessment.exams USING btree (owner_id);

--
-- Name: idx_llm_usage_user_kind_created; Type: INDEX; Schema: assessment; Owner: -
--

CREATE INDEX idx_llm_usage_user_kind_created ON assessment.llm_usage USING btree (user_id, kind, created_at DESC);

--
-- Name: idx_section_blocks_exam_id; Type: INDEX; Schema: assessment; Owner: -
--

CREATE INDEX idx_section_blocks_exam_id ON assessment.section_blocks USING btree (exam_id);

--
-- Name: idx_section_blocks_section_id; Type: INDEX; Schema: assessment; Owner: -
--

CREATE INDEX idx_section_blocks_section_id ON assessment.section_blocks USING btree (section_id);

--
-- Name: idx_task_answers_exam_id; Type: INDEX; Schema: assessment; Owner: -
--

CREATE INDEX idx_task_answers_exam_id ON assessment.task_answers USING btree (exam_id);

--
-- Name: idx_task_answers_task_id; Type: INDEX; Schema: assessment; Owner: -
--

CREATE INDEX idx_task_answers_task_id ON assessment.task_answers USING btree (task_id);

--
-- Name: idx_user_tokens_user_id; Type: INDEX; Schema: assessment; Owner: -
--

CREATE INDEX idx_user_tokens_user_id ON assessment.user_tokens USING btree (user_id);

--
-- Name: idx_users_created_ip_hash; Type: INDEX; Schema: assessment; Owner: -
--

CREATE INDEX idx_users_created_ip_hash ON assessment.users USING btree (created_ip_hash, created_at DESC);

--
-- Name: parse_metrics_created_at_idx; Type: INDEX; Schema: assessment; Owner: -
--

CREATE INDEX parse_metrics_created_at_idx ON assessment.parse_metrics USING btree (created_at);

--
-- Name: parse_metrics_parser_model_idx; Type: INDEX; Schema: assessment; Owner: -
--

CREATE INDEX parse_metrics_parser_model_idx ON assessment.parse_metrics USING btree (parser_model);

--
-- Name: section_figures_block_id_key; Type: INDEX; Schema: assessment; Owner: -
--

CREATE UNIQUE INDEX section_figures_block_id_key ON assessment.section_figures USING btree (block_id);

--
-- Name: sections_exam_id_position_idx; Type: INDEX; Schema: assessment; Owner: -
--

CREATE INDEX sections_exam_id_position_idx ON assessment.sections USING btree (exam_id, "position");

--
-- Name: tasks_exam_position_idx; Type: INDEX; Schema: assessment; Owner: -
--

CREATE INDEX tasks_exam_position_idx ON assessment.tasks USING btree (exam_id, "position");

--
-- Name: tasks_section_id_idx; Type: INDEX; Schema: assessment; Owner: -
--

CREATE INDEX tasks_section_id_idx ON assessment.tasks USING btree (section_id);

--
-- Name: exams exams_set_updated_at; Type: TRIGGER; Schema: assessment; Owner: -
--

CREATE TRIGGER exams_set_updated_at BEFORE UPDATE ON assessment.exams FOR EACH ROW EXECUTE FUNCTION assessment.set_updated_at();

--
-- Name: sections sections_set_updated_at; Type: TRIGGER; Schema: assessment; Owner: -
--

CREATE TRIGGER sections_set_updated_at BEFORE UPDATE ON assessment.sections FOR EACH ROW EXECUTE FUNCTION assessment.set_updated_at();

--
-- Name: section_blocks set_updated_at_section_blocks; Type: TRIGGER; Schema: assessment; Owner: -
--

CREATE TRIGGER set_updated_at_section_blocks BEFORE UPDATE ON assessment.section_blocks FOR EACH ROW EXECUTE FUNCTION assessment.set_updated_at();

--
-- Name: tasks tasks_set_updated_at; Type: TRIGGER; Schema: assessment; Owner: -
--

CREATE TRIGGER tasks_set_updated_at BEFORE UPDATE ON assessment.tasks FOR EACH ROW EXECUTE FUNCTION assessment.set_updated_at();

--
-- Name: task_grades trg_task_grades_updated_at; Type: TRIGGER; Schema: assessment; Owner: -
--

CREATE TRIGGER trg_task_grades_updated_at BEFORE UPDATE ON assessment.task_grades FOR EACH ROW EXECUTE FUNCTION assessment.set_updated_at();

--
-- Name: evaluation_runs evaluation_runs_exam_id_fkey; Type: FK CONSTRAINT; Schema: assessment; Owner: -
--

ALTER TABLE ONLY assessment.evaluation_runs
    ADD CONSTRAINT evaluation_runs_exam_id_fkey FOREIGN KEY (exam_id) REFERENCES assessment.exams(id) ON DELETE CASCADE;

--
-- Name: exams fk_exams_owner_id; Type: FK CONSTRAINT; Schema: assessment; Owner: -
--

ALTER TABLE ONLY assessment.exams
    ADD CONSTRAINT fk_exams_owner_id FOREIGN KEY (owner_id) REFERENCES assessment.users(id);

--
-- Name: llm_usage llm_usage_user_id_fkey; Type: FK CONSTRAINT; Schema: assessment; Owner: -
--

ALTER TABLE ONLY assessment.llm_usage
    ADD CONSTRAINT llm_usage_user_id_fkey FOREIGN KEY (user_id) REFERENCES assessment.users(id) ON DELETE CASCADE;

--
-- Name: parse_metrics parse_metrics_exam_id_fkey; Type: FK CONSTRAINT; Schema: assessment; Owner: -
--

ALTER TABLE ONLY assessment.parse_metrics
    ADD CONSTRAINT parse_metrics_exam_id_fkey FOREIGN KEY (exam_id) REFERENCES assessment.exams(id) ON DELETE SET NULL;

--
-- Name: section_blocks section_blocks_section_id_fkey; Type: FK CONSTRAINT; Schema: assessment; Owner: -
--

ALTER TABLE ONLY assessment.section_blocks
    ADD CONSTRAINT section_blocks_section_id_fkey FOREIGN KEY (section_id) REFERENCES assessment.sections(id) ON DELETE CASCADE;

--
-- Name: section_figures section_figures_block_id_fkey; Type: FK CONSTRAINT; Schema: assessment; Owner: -
--

ALTER TABLE ONLY assessment.section_figures
    ADD CONSTRAINT section_figures_block_id_fkey FOREIGN KEY (block_id) REFERENCES assessment.section_blocks(id) ON DELETE CASCADE;

--
-- Name: sections sections_exam_id_fkey; Type: FK CONSTRAINT; Schema: assessment; Owner: -
--

ALTER TABLE ONLY assessment.sections
    ADD CONSTRAINT sections_exam_id_fkey FOREIGN KEY (exam_id) REFERENCES assessment.exams(id) ON DELETE CASCADE;

--
-- Name: task_answers task_answers_exam_id_fkey; Type: FK CONSTRAINT; Schema: assessment; Owner: -
--

ALTER TABLE ONLY assessment.task_answers
    ADD CONSTRAINT task_answers_exam_id_fkey FOREIGN KEY (exam_id) REFERENCES assessment.exams(id) ON DELETE CASCADE;

--
-- Name: task_answers task_answers_task_id_fkey; Type: FK CONSTRAINT; Schema: assessment; Owner: -
--

ALTER TABLE ONLY assessment.task_answers
    ADD CONSTRAINT task_answers_task_id_fkey FOREIGN KEY (task_id) REFERENCES assessment.tasks(id) ON DELETE CASCADE;

--
-- Name: task_grades task_grades_answer_id_fkey; Type: FK CONSTRAINT; Schema: assessment; Owner: -
--

ALTER TABLE ONLY assessment.task_grades
    ADD CONSTRAINT task_grades_answer_id_fkey FOREIGN KEY (answer_id) REFERENCES assessment.task_answers(id) ON DELETE CASCADE;

--
-- Name: tasks tasks_exam_id_fkey; Type: FK CONSTRAINT; Schema: assessment; Owner: -
--

ALTER TABLE ONLY assessment.tasks
    ADD CONSTRAINT tasks_exam_id_fkey FOREIGN KEY (exam_id) REFERENCES assessment.exams(id) ON DELETE CASCADE;

--
-- Name: tasks tasks_section_id_fkey; Type: FK CONSTRAINT; Schema: assessment; Owner: -
--

ALTER TABLE ONLY assessment.tasks
    ADD CONSTRAINT tasks_section_id_fkey FOREIGN KEY (section_id) REFERENCES assessment.sections(id) ON DELETE SET NULL;

--
-- Name: user_tokens user_tokens_user_id_fkey; Type: FK CONSTRAINT; Schema: assessment; Owner: -
--

ALTER TABLE ONLY assessment.user_tokens
    ADD CONSTRAINT user_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES assessment.users(id) ON DELETE CASCADE;

--
-- Seed rows the old migrations inserted
--

INSERT INTO assessment.users (id, external_id, display_name, is_admin, created_ip_hash, created_at, last_seen_at) VALUES ('00000000-0000-0000-0000-000000000001', 'legacy-default-user', 'Legacy default user', false, NULL, '2026-11-13 14:48:32.330325+00', NULL);
