-- Baseline of the materials module: the schema the 42 migrations of the former LearningGoalHub server
-- produced on an empty database, dumped with pg_dump --schema-only and moved from public into
-- materials. Generated, not hand-written; later changes go into new migrations.

--
-- Name: course; Type: TABLE; Schema: materials; Owner: -
--

CREATE TABLE materials.course (
    id bigint NOT NULL,
    name character varying(255) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    output_language character varying(16),
    figures_enabled boolean DEFAULT false NOT NULL,
    skills_reviewed_at timestamp with time zone
);

--
-- Name: course_id_seq; Type: SEQUENCE; Schema: materials; Owner: -
--

CREATE SEQUENCE materials.course_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: course_id_seq; Type: SEQUENCE OWNED BY; Schema: materials; Owner: -
--

ALTER SEQUENCE materials.course_id_seq OWNED BY materials.course.id;

--
-- Name: document; Type: TABLE; Schema: materials; Owner: -
--

CREATE TABLE materials.document (
    id bigint NOT NULL,
    course_id bigint NOT NULL,
    filename character varying(512) NOT NULL,
    content_type character varying(255) NOT NULL,
    raw_text text,
    uploaded_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    display_name text,
    page_offsets integer[],
    language character varying(16),
    kind character varying(16),
    CONSTRAINT document_kind_check CHECK (((kind)::text = ANY ((ARRAY['LECTURE'::character varying, 'EXERCISE'::character varying])::text[])))
);

--
-- Name: document_content; Type: TABLE; Schema: materials; Owner: -
--

CREATE TABLE materials.document_content (
    document_id bigint NOT NULL,
    bytes bytea NOT NULL
);

--
-- Name: document_id_seq; Type: SEQUENCE; Schema: materials; Owner: -
--

CREATE SEQUENCE materials.document_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: document_id_seq; Type: SEQUENCE OWNED BY; Schema: materials; Owner: -
--

ALTER SEQUENCE materials.document_id_seq OWNED BY materials.document.id;

--
-- Name: document_section; Type: TABLE; Schema: materials; Owner: -
--

CREATE TABLE materials.document_section (
    id bigint NOT NULL,
    document_id bigint NOT NULL,
    ordinal integer NOT NULL,
    title text NOT NULL,
    start_offset integer NOT NULL,
    end_offset integer NOT NULL,
    start_page integer,
    end_page integer
);

--
-- Name: document_section_id_seq; Type: SEQUENCE; Schema: materials; Owner: -
--

CREATE SEQUENCE materials.document_section_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: document_section_id_seq; Type: SEQUENCE OWNED BY; Schema: materials; Owner: -
--

ALTER SEQUENCE materials.document_section_id_seq OWNED BY materials.document_section.id;

--
-- Name: exam_block; Type: TABLE; Schema: materials; Owner: -
--

CREATE TABLE materials.exam_block (
    id bigint NOT NULL,
    submission_id bigint NOT NULL,
    "position" integer NOT NULL,
    block_id text,
    block_type character varying(16) NOT NULL,
    task_type text,
    task_number integer,
    description text,
    context text,
    CONSTRAINT exam_block_block_type_check CHECK (((block_type)::text = ANY ((ARRAY['CONTEXT'::character varying, 'TASK'::character varying])::text[])))
);

--
-- Name: exam_block_id_seq; Type: SEQUENCE; Schema: materials; Owner: -
--

CREATE SEQUENCE materials.exam_block_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: exam_block_id_seq; Type: SEQUENCE OWNED BY; Schema: materials; Owner: -
--

ALTER SEQUENCE materials.exam_block_id_seq OWNED BY materials.exam_block.id;

--
-- Name: exam_submission; Type: TABLE; Schema: materials; Owner: -
--

CREATE TABLE materials.exam_submission (
    id bigint NOT NULL,
    course_id bigint NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

--
-- Name: exam_submission_id_seq; Type: SEQUENCE; Schema: materials; Owner: -
--

CREATE SEQUENCE materials.exam_submission_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: exam_submission_id_seq; Type: SEQUENCE OWNED BY; Schema: materials; Owner: -
--

ALTER SEQUENCE materials.exam_submission_id_seq OWNED BY materials.exam_submission.id;

--
-- Name: extraction_run; Type: TABLE; Schema: materials; Owner: -
--

CREATE TABLE materials.extraction_run (
    id bigint NOT NULL,
    course_id bigint NOT NULL,
    model character varying(255),
    prompt_version character varying(32) NOT NULL,
    params text NOT NULL,
    status character varying(16) NOT NULL,
    error text,
    started_at timestamp with time zone NOT NULL,
    finished_at timestamp with time zone,
    goals_created integer,
    failed_sessions integer,
    dropped_outcomes integer,
    CONSTRAINT extraction_run_status_check CHECK (((status)::text = ANY ((ARRAY['RUNNING'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::character varying])::text[])))
);

--
-- Name: extraction_run_id_seq; Type: SEQUENCE; Schema: materials; Owner: -
--

CREATE SEQUENCE materials.extraction_run_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: extraction_run_id_seq; Type: SEQUENCE OWNED BY; Schema: materials; Owner: -
--

ALTER SEQUENCE materials.extraction_run_id_seq OWNED BY materials.extraction_run.id;

--
-- Name: goal_relationship; Type: TABLE; Schema: materials; Owner: -
--

CREATE TABLE materials.goal_relationship (
    id bigint NOT NULL,
    source_goal_id bigint NOT NULL,
    target_goal_id bigint NOT NULL,
    type character varying(32) NOT NULL,
    confidence double precision NOT NULL,
    origin character varying(32) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT goal_relationship_no_self_loop CHECK ((source_goal_id <> target_goal_id)),
    CONSTRAINT goal_relationship_origin_check CHECK (((origin)::text = ANY ((ARRAY['HIERARCHY'::character varying, 'EMBEDDING'::character varying, 'LLM'::character varying, 'SYNTHESIS'::character varying])::text[]))),
    CONSTRAINT goal_relationship_type_check CHECK (((type)::text = ANY ((ARRAY['CONTRIBUTES_TO'::character varying, 'SUPPORTS'::character varying, 'PREREQUISITE_OF'::character varying, 'OVERLAPS_WITH'::character varying])::text[])))
);

--
-- Name: goal_relationship_id_seq; Type: SEQUENCE; Schema: materials; Owner: -
--

CREATE SEQUENCE materials.goal_relationship_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: goal_relationship_id_seq; Type: SEQUENCE OWNED BY; Schema: materials; Owner: -
--

ALTER SEQUENCE materials.goal_relationship_id_seq OWNED BY materials.goal_relationship.id;

--
-- Name: goal_source; Type: TABLE; Schema: materials; Owner: -
--

CREATE TABLE materials.goal_source (
    goal_id bigint NOT NULL,
    document_id bigint NOT NULL,
    snippet text NOT NULL,
    page integer,
    grounded boolean DEFAULT false NOT NULL,
    grounding_quality character varying(32),
    unverified_snippet text,
    highlight_rects jsonb,
    evidence_kind character varying(16) NOT NULL,
    CONSTRAINT goal_source_evidence_kind_check CHECK (((evidence_kind)::text = ANY ((ARRAY['TEXT'::character varying, 'FIGURE'::character varying, 'UNSUPPORTED'::character varying])::text[]))),
    CONSTRAINT goal_source_grounding_quality_check CHECK (((grounding_quality)::text = ANY ((ARRAY['EXACT_IN_SESSION'::character varying, 'EXACT_IN_DOCUMENT'::character varying, 'NORMALIZED'::character varying, 'FRAGMENT'::character varying, 'NONE'::character varying])::text[])))
);

--
-- Name: hierarchy_node; Type: TABLE; Schema: materials; Owner: -
--

CREATE TABLE materials.hierarchy_node (
    id bigint NOT NULL,
    parent_id bigint,
    course_id bigint NOT NULL,
    level character varying(16) NOT NULL,
    label text NOT NULL,
    document_id bigint,
    CONSTRAINT hierarchy_node_level_check CHECK (((level)::text = ANY ((ARRAY['MODULE'::character varying, 'SESSION'::character varying, 'EXERCISE'::character varying, 'EXAM'::character varying, 'COMPETENCY'::character varying])::text[])))
);

--
-- Name: hierarchy_node_id_seq; Type: SEQUENCE; Schema: materials; Owner: -
--

CREATE SEQUENCE materials.hierarchy_node_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: hierarchy_node_id_seq; Type: SEQUENCE OWNED BY; Schema: materials; Owner: -
--

ALTER SEQUENCE materials.hierarchy_node_id_seq OWNED BY materials.hierarchy_node.id;

--
-- Name: learning_goal; Type: TABLE; Schema: materials; Owner: -
--

CREATE TABLE materials.learning_goal (
    id bigint NOT NULL,
    course_id bigint NOT NULL,
    text text NOT NULL,
    kind character varying(16) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    hierarchy_node_id bigint,
    bloom_level character varying(32),
    solo_level character varying(32),
    origin character varying(16) DEFAULT 'EXTRACTED'::character varying NOT NULL,
    status character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    short_label text,
    creation_provenance character varying(32),
    role character varying(16),
    lecture_order integer,
    original_text text,
    exam_block_id bigint,
    CONSTRAINT learning_goal_bloom_level_check CHECK (((bloom_level)::text = ANY ((ARRAY['REMEMBER'::character varying, 'UNDERSTAND'::character varying, 'APPLY'::character varying, 'ANALYZE'::character varying, 'EVALUATE'::character varying, 'CREATE'::character varying])::text[]))),
    CONSTRAINT learning_goal_creation_provenance_check CHECK (((creation_provenance)::text = ANY ((ARRAY['USER_CREATED'::character varying, 'WIZARD_AI_SUBTREE'::character varying])::text[]))),
    CONSTRAINT learning_goal_kind_check CHECK (((kind)::text = ANY ((ARRAY['EXPLICIT'::character varying, 'IMPLICIT'::character varying])::text[]))),
    CONSTRAINT learning_goal_origin_check CHECK (((origin)::text = ANY ((ARRAY['EXTRACTED'::character varying, 'SYNTHESIZED'::character varying, 'EXAM'::character varying, 'TERMINAL'::character varying, 'GAP'::character varying])::text[]))),
    CONSTRAINT learning_goal_role_check CHECK (((role)::text = ANY ((ARRAY['SKILL'::character varying, 'KNOWLEDGE'::character varying])::text[]))),
    CONSTRAINT learning_goal_solo_level_check CHECK (((solo_level)::text = ANY ((ARRAY['PRESTRUCTURAL'::character varying, 'UNISTRUCTURAL'::character varying, 'MULTISTRUCTURAL'::character varying, 'RELATIONAL'::character varying, 'EXTENDED_ABSTRACT'::character varying])::text[]))),
    CONSTRAINT learning_goal_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'APPROVED'::character varying])::text[])))
);

--
-- Name: learning_goal_id_seq; Type: SEQUENCE; Schema: materials; Owner: -
--

CREATE SEQUENCE materials.learning_goal_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: learning_goal_id_seq; Type: SEQUENCE OWNED BY; Schema: materials; Owner: -
--

ALTER SEQUENCE materials.learning_goal_id_seq OWNED BY materials.learning_goal.id;

--
-- Name: page_description; Type: TABLE; Schema: materials; Owner: -
--

CREATE TABLE materials.page_description (
    document_id bigint NOT NULL,
    page integer NOT NULL,
    description text NOT NULL,
    model character varying(255),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    teaches_content boolean DEFAULT true NOT NULL,
    language character varying(16),
    prompt_version character varying(32),
    CONSTRAINT page_description_page_check CHECK ((page >= 1))
);

--
-- Name: course id; Type: DEFAULT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.course ALTER COLUMN id SET DEFAULT nextval('materials.course_id_seq'::regclass);

--
-- Name: document id; Type: DEFAULT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.document ALTER COLUMN id SET DEFAULT nextval('materials.document_id_seq'::regclass);

--
-- Name: document_section id; Type: DEFAULT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.document_section ALTER COLUMN id SET DEFAULT nextval('materials.document_section_id_seq'::regclass);

--
-- Name: exam_block id; Type: DEFAULT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.exam_block ALTER COLUMN id SET DEFAULT nextval('materials.exam_block_id_seq'::regclass);

--
-- Name: exam_submission id; Type: DEFAULT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.exam_submission ALTER COLUMN id SET DEFAULT nextval('materials.exam_submission_id_seq'::regclass);

--
-- Name: extraction_run id; Type: DEFAULT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.extraction_run ALTER COLUMN id SET DEFAULT nextval('materials.extraction_run_id_seq'::regclass);

--
-- Name: goal_relationship id; Type: DEFAULT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.goal_relationship ALTER COLUMN id SET DEFAULT nextval('materials.goal_relationship_id_seq'::regclass);

--
-- Name: hierarchy_node id; Type: DEFAULT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.hierarchy_node ALTER COLUMN id SET DEFAULT nextval('materials.hierarchy_node_id_seq'::regclass);

--
-- Name: learning_goal id; Type: DEFAULT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.learning_goal ALTER COLUMN id SET DEFAULT nextval('materials.learning_goal_id_seq'::regclass);

--
-- Name: course course_pkey; Type: CONSTRAINT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.course
    ADD CONSTRAINT course_pkey PRIMARY KEY (id);

--
-- Name: document_content document_content_pkey; Type: CONSTRAINT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.document_content
    ADD CONSTRAINT document_content_pkey PRIMARY KEY (document_id);

--
-- Name: document document_pkey; Type: CONSTRAINT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.document
    ADD CONSTRAINT document_pkey PRIMARY KEY (id);

--
-- Name: document_section document_section_pkey; Type: CONSTRAINT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.document_section
    ADD CONSTRAINT document_section_pkey PRIMARY KEY (id);

--
-- Name: exam_block exam_block_pkey; Type: CONSTRAINT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.exam_block
    ADD CONSTRAINT exam_block_pkey PRIMARY KEY (id);

--
-- Name: exam_block exam_block_position_unique; Type: CONSTRAINT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.exam_block
    ADD CONSTRAINT exam_block_position_unique UNIQUE (submission_id, "position");

--
-- Name: exam_submission exam_submission_pkey; Type: CONSTRAINT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.exam_submission
    ADD CONSTRAINT exam_submission_pkey PRIMARY KEY (id);

--
-- Name: extraction_run extraction_run_pkey; Type: CONSTRAINT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.extraction_run
    ADD CONSTRAINT extraction_run_pkey PRIMARY KEY (id);

--
-- Name: goal_relationship goal_relationship_pkey; Type: CONSTRAINT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.goal_relationship
    ADD CONSTRAINT goal_relationship_pkey PRIMARY KEY (id);

--
-- Name: goal_relationship goal_relationship_unique; Type: CONSTRAINT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.goal_relationship
    ADD CONSTRAINT goal_relationship_unique UNIQUE (source_goal_id, target_goal_id, type);

--
-- Name: goal_source goal_source_pkey; Type: CONSTRAINT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.goal_source
    ADD CONSTRAINT goal_source_pkey PRIMARY KEY (goal_id, document_id);

--
-- Name: hierarchy_node hierarchy_node_pkey; Type: CONSTRAINT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.hierarchy_node
    ADD CONSTRAINT hierarchy_node_pkey PRIMARY KEY (id);

--
-- Name: learning_goal learning_goal_pkey; Type: CONSTRAINT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.learning_goal
    ADD CONSTRAINT learning_goal_pkey PRIMARY KEY (id);

--
-- Name: page_description page_description_pkey; Type: CONSTRAINT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.page_description
    ADD CONSTRAINT page_description_pkey PRIMARY KEY (document_id, page);

--
-- Name: idx_document_course; Type: INDEX; Schema: materials; Owner: -
--

CREATE INDEX idx_document_course ON materials.document USING btree (course_id);

--
-- Name: idx_document_section_document; Type: INDEX; Schema: materials; Owner: -
--

CREATE INDEX idx_document_section_document ON materials.document_section USING btree (document_id);

--
-- Name: idx_exam_submission_course; Type: INDEX; Schema: materials; Owner: -
--

CREATE INDEX idx_exam_submission_course ON materials.exam_submission USING btree (course_id);

--
-- Name: idx_extraction_run_course; Type: INDEX; Schema: materials; Owner: -
--

CREATE INDEX idx_extraction_run_course ON materials.extraction_run USING btree (course_id);

--
-- Name: idx_goal_relationship_source; Type: INDEX; Schema: materials; Owner: -
--

CREATE INDEX idx_goal_relationship_source ON materials.goal_relationship USING btree (source_goal_id);

--
-- Name: idx_goal_relationship_target; Type: INDEX; Schema: materials; Owner: -
--

CREATE INDEX idx_goal_relationship_target ON materials.goal_relationship USING btree (target_goal_id);

--
-- Name: idx_goal_source_document; Type: INDEX; Schema: materials; Owner: -
--

CREATE INDEX idx_goal_source_document ON materials.goal_source USING btree (document_id);

--
-- Name: idx_hierarchy_node_course; Type: INDEX; Schema: materials; Owner: -
--

CREATE INDEX idx_hierarchy_node_course ON materials.hierarchy_node USING btree (course_id);

--
-- Name: idx_hierarchy_node_document; Type: INDEX; Schema: materials; Owner: -
--

CREATE INDEX idx_hierarchy_node_document ON materials.hierarchy_node USING btree (document_id);

--
-- Name: idx_hierarchy_node_parent; Type: INDEX; Schema: materials; Owner: -
--

CREATE INDEX idx_hierarchy_node_parent ON materials.hierarchy_node USING btree (parent_id);

--
-- Name: idx_learning_goal_course; Type: INDEX; Schema: materials; Owner: -
--

CREATE INDEX idx_learning_goal_course ON materials.learning_goal USING btree (course_id);

--
-- Name: idx_learning_goal_course_lecture_order; Type: INDEX; Schema: materials; Owner: -
--

CREATE INDEX idx_learning_goal_course_lecture_order ON materials.learning_goal USING btree (course_id, lecture_order);

--
-- Name: idx_learning_goal_exam_block; Type: INDEX; Schema: materials; Owner: -
--

CREATE INDEX idx_learning_goal_exam_block ON materials.learning_goal USING btree (exam_block_id);

--
-- Name: idx_learning_goal_hierarchy; Type: INDEX; Schema: materials; Owner: -
--

CREATE INDEX idx_learning_goal_hierarchy ON materials.learning_goal USING btree (hierarchy_node_id);

--
-- Name: document_content document_content_document_id_fkey; Type: FK CONSTRAINT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.document_content
    ADD CONSTRAINT document_content_document_id_fkey FOREIGN KEY (document_id) REFERENCES materials.document(id) ON DELETE CASCADE;

--
-- Name: document document_course_id_fkey; Type: FK CONSTRAINT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.document
    ADD CONSTRAINT document_course_id_fkey FOREIGN KEY (course_id) REFERENCES materials.course(id) ON DELETE CASCADE;

--
-- Name: document_section document_section_document_id_fkey; Type: FK CONSTRAINT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.document_section
    ADD CONSTRAINT document_section_document_id_fkey FOREIGN KEY (document_id) REFERENCES materials.document(id) ON DELETE CASCADE;

--
-- Name: exam_block exam_block_submission_id_fkey; Type: FK CONSTRAINT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.exam_block
    ADD CONSTRAINT exam_block_submission_id_fkey FOREIGN KEY (submission_id) REFERENCES materials.exam_submission(id) ON DELETE CASCADE;

--
-- Name: exam_submission exam_submission_course_id_fkey; Type: FK CONSTRAINT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.exam_submission
    ADD CONSTRAINT exam_submission_course_id_fkey FOREIGN KEY (course_id) REFERENCES materials.course(id) ON DELETE CASCADE;

--
-- Name: extraction_run extraction_run_course_id_fkey; Type: FK CONSTRAINT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.extraction_run
    ADD CONSTRAINT extraction_run_course_id_fkey FOREIGN KEY (course_id) REFERENCES materials.course(id) ON DELETE CASCADE;

--
-- Name: goal_relationship goal_relationship_source_goal_id_fkey; Type: FK CONSTRAINT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.goal_relationship
    ADD CONSTRAINT goal_relationship_source_goal_id_fkey FOREIGN KEY (source_goal_id) REFERENCES materials.learning_goal(id) ON DELETE CASCADE;

--
-- Name: goal_relationship goal_relationship_target_goal_id_fkey; Type: FK CONSTRAINT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.goal_relationship
    ADD CONSTRAINT goal_relationship_target_goal_id_fkey FOREIGN KEY (target_goal_id) REFERENCES materials.learning_goal(id) ON DELETE CASCADE;

--
-- Name: goal_source goal_source_document_id_fkey; Type: FK CONSTRAINT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.goal_source
    ADD CONSTRAINT goal_source_document_id_fkey FOREIGN KEY (document_id) REFERENCES materials.document(id) ON DELETE CASCADE;

--
-- Name: goal_source goal_source_goal_id_fkey; Type: FK CONSTRAINT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.goal_source
    ADD CONSTRAINT goal_source_goal_id_fkey FOREIGN KEY (goal_id) REFERENCES materials.learning_goal(id) ON DELETE CASCADE;

--
-- Name: hierarchy_node hierarchy_node_course_id_fkey; Type: FK CONSTRAINT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.hierarchy_node
    ADD CONSTRAINT hierarchy_node_course_id_fkey FOREIGN KEY (course_id) REFERENCES materials.course(id) ON DELETE CASCADE;

--
-- Name: hierarchy_node hierarchy_node_document_id_fkey; Type: FK CONSTRAINT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.hierarchy_node
    ADD CONSTRAINT hierarchy_node_document_id_fkey FOREIGN KEY (document_id) REFERENCES materials.document(id) ON DELETE SET NULL;

--
-- Name: hierarchy_node hierarchy_node_parent_id_fkey; Type: FK CONSTRAINT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.hierarchy_node
    ADD CONSTRAINT hierarchy_node_parent_id_fkey FOREIGN KEY (parent_id) REFERENCES materials.hierarchy_node(id) ON DELETE CASCADE;

--
-- Name: learning_goal learning_goal_course_id_fkey; Type: FK CONSTRAINT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.learning_goal
    ADD CONSTRAINT learning_goal_course_id_fkey FOREIGN KEY (course_id) REFERENCES materials.course(id) ON DELETE CASCADE;

--
-- Name: learning_goal learning_goal_exam_block_id_fkey; Type: FK CONSTRAINT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.learning_goal
    ADD CONSTRAINT learning_goal_exam_block_id_fkey FOREIGN KEY (exam_block_id) REFERENCES materials.exam_block(id) ON DELETE SET NULL;

--
-- Name: learning_goal learning_goal_hierarchy_node_id_fkey; Type: FK CONSTRAINT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.learning_goal
    ADD CONSTRAINT learning_goal_hierarchy_node_id_fkey FOREIGN KEY (hierarchy_node_id) REFERENCES materials.hierarchy_node(id) ON DELETE SET NULL;

--
-- Name: page_description page_description_document_id_fkey; Type: FK CONSTRAINT; Schema: materials; Owner: -
--

ALTER TABLE ONLY materials.page_description
    ADD CONSTRAINT page_description_document_id_fkey FOREIGN KEY (document_id) REFERENCES materials.document(id) ON DELETE CASCADE;
