export type TaskType = "single_choice" | "multiple_choice" | "text";

export interface TaskOption {
  id: string;
  text: string;
  is_correct: boolean;
}

export interface Task {
  id: string;
  exam_id: string;
  position: number;
  section: string | null;
  section_id: string | null;
  type: TaskType;
  prompt: string;
  options: TaskOption[] | null;
  reference_answer: string | null;
  points: number | null;
  parse_confidence: "high" | "medium" | "low" | null;
  learning_goal_ids?: number[] | null;
  created_at: string;
  updated_at: string;
}

export interface Section {
  id: string;
  exam_id: string;
  position: number;
  name: string;
  confirmed_at: string | null;
  solve_started_at: string | null;
  created_at: string;
  updated_at: string;
}

export interface SectionFigure {
  id: string;
  block_id: string;
  position: number;
  storage_path: string;
  caption: string | null;
  source: "pdf" | "upload";
  created_at: string;
}

export interface SectionBlock {
  id: string;
  section_id: string;
  exam_id: string;
  position: number;
  content: string;
  kind: "context" | "figure";
  created_at: string;
  updated_at: string;
}

export type BlockItem =
  | { kind: "task"; position: number; created_at: string; task: Task }
  | { kind: "context"; position: number; created_at: string; block: SectionBlock }
  | { kind: "figure"; position: number; created_at: string; block: SectionBlock };

export const mergeSectionItems = (
  tasks: Task[],
  blocks: SectionBlock[],
): BlockItem[] => {
  const items: BlockItem[] = [
    ...tasks.map((task) => ({
      kind: "task" as const,
      position: task.position,
      created_at: task.created_at,
      task,
    })),
    ...blocks.map((block) =>
      block.kind === "figure"
        ? {
            kind: "figure" as const,
            position: block.position,
            created_at: block.created_at,
            block,
          }
        : {
            kind: "context" as const,
            position: block.position,
            created_at: block.created_at,
            block,
          },
    ),
  ];
  items.sort((a, b) => {
    if (a.position !== b.position) return a.position - b.position;
    // non-task blocks before tasks on tie (context/figure typically introduce tasks)
    if (a.kind !== b.kind) {
      if (a.kind === "task") return 1;
      if (b.kind === "task") return -1;
      return 0;
    }
    return a.created_at.localeCompare(b.created_at);
  });
  return items;
};

export interface Exam {
  id: string;
  title: string;
  course: string | null;
  language: "de" | "en" | "other";
  source: "pdf" | "manual";
  source_file_url: string | null;
  status:
    | "parsing"
    | "draft"
    | "ready"
    | "failed"
    | "evaluating"
    | "grading"
    | "finished";
  parse_error?: string | null;
  parse_phase?: string | null;
  /** Start of the current parse attempt (initial or retry); anchors the progress
   *  countdown. Null for exams parsed before this field existed. */
  parse_started_at?: string | null;
  /** Set once when parsing successfully finalized (parsing→draft). Distinguishes a
   *  parse failure (never set) from an evaluation failure (set, then failed while
   *  solving). Null for manual exams and PDFs that never finished parsing. */
  parsed_at?: string | null;
  parser_model?: string | null;
  solver_model?: string | null;
  lgh_course_id?: number | null;
  /** Source document page count (PDF/converted docx); null for manual exams or if unknown. */
  page_count?: number | null;
  created_at: string;
  updated_at: string;
}


/**
 * A `failed` exam is a *parsing* failure when it's a PDF import that never
 * finished parsing — i.e. `parsed_at` was never stamped (the backend sets it
 * only when parsing finalizes to draft). Every other `failed` exam (a manual
 * exam, or a PDF that parsed and later failed to solve / was cancelled mid-solve)
 * is an evaluation failure. Used to gate parse-retry (which re-parses and would
 * overwrite edited structure) vs. re-evaluation.
 *
 * We key off `parsed_at` rather than task count because tasks are committed
 * before the parse finalizes, so a failure/cancel in that window leaves tasks
 * present on an exam that never actually completed parsing — a task-count check
 * would misclassify those as evaluation failures.
 */
export const isParseFailure = (
  exam: Pick<Exam, "status" | "source" | "parsed_at">,
): boolean =>
  exam.status === "failed" && exam.source === "pdf" && !exam.parsed_at;

/**
 * The single canonical mode an exam belongs in, keyed off its status. Routing
 * uses this so that visiting a link for a different mode redirects to the exam's
 * real mode instead of silently transitioning it. `grading` → grade, `finished`
 * → results, everything else (draft/parsing/ready/evaluating/failed) → edit.
 */
export type ExamModeSlug = "edit" | "grade" | "results";

export const examModeSlug = (status: string | null | undefined): ExamModeSlug =>
  status === "finished" ? "results" : status === "grading" ? "grade" : "edit";

export const examModePath = (id: string, status: string | null | undefined): string =>
  `/exams/${id}/${examModeSlug(status)}`;

export const TASK_TYPES: TaskType[] = ["single_choice", "multiple_choice", "text"];

export const newOption = (overrides: Partial<TaskOption> = {}): TaskOption => ({
  id: crypto.randomUUID(),
  text: "",
  is_correct: false,
  ...overrides,
});

export const totalPoints = (tasks: Task[]): number =>
  tasks.reduce((sum, t) => sum + (Number(t.points) || 0), 0);

/**
 * A section is "ready" when it has at least one task and every task has a
 * positive points value. Single source of truth shared by the sidebar and
 * the carousel-based editor.
 */
/** A task still needs a score (no positive point value assigned yet). */
export const taskMissingScore = (task: Task): boolean =>
  task.points == null || task.points <= 0;

export const isSectionReady = (tasks: Task[]): boolean =>
  tasks.length > 0 && tasks.every((t) => !taskMissingScore(t));

export interface MCWarning {
  kind: "noCorrect" | "allCorrect";
}

export const mcWarning = (task: Task): MCWarning | null => {
  if (task.type === "text") return null;
  const opts = task.options ?? [];
  if (opts.length === 0) return null;
  const correctCount = opts.filter((o) => o.is_correct).length;
  if (correctCount === 0) return { kind: "noCorrect" };
  if (task.type === "multiple_choice" && correctCount === opts.length) {
    return { kind: "allCorrect" };
  }
  return null;
};

export const convertTaskType = (
  task: Task,
  toType: TaskType
): Partial<Task> => {
  if (task.type === toType) return {};

  // text → SC/MC
  if (task.type === "text" && toType !== "text") {
    return {
      type: toType,
      options: [newOption(), newOption()],
    };
  }

  // SC/MC → text
  if (toType === "text") {
    const optionsText = (task.options ?? [])
      .map((o) => `• ${o.text}${o.is_correct ? " ✓" : ""}`)
      .join("\n");
    const ref =
      [task.reference_answer, optionsText].filter(Boolean).join("\n\n") || null;
    return {
      type: "text",
      options: null,
      reference_answer: ref,
    };
  }

  // SC ↔ MC
  let options = task.options ?? [];
  if (toType === "single_choice") {
    let kept = false;
    options = options.map((o) => {
      if (o.is_correct && !kept) {
        kept = true;
        return o;
      }
      return { ...o, is_correct: false };
    });
  }
  return { type: toType, options };
};
/** Stable id for a block item (used for keys, dnd, collapse state). */
export const itemId = (item: BlockItem): string =>
  item.kind === "task"
    ? `task:${item.task.id}`
    : item.kind === "figure"
      ? `fig:${item.block.id}`
      : `ctx:${item.block.id}`;

/**
 * DOM id for a block item's scroll target (set on the wrapper in BlockItem,
 * read by the editor's scroll-to helpers). Single source of truth so the two
 * sides can't drift.
 */
export const blockDomId = (item: BlockItem): string =>
  item.kind === "task"
    ? `task-${item.task.id}`
    : item.kind === "figure"
      ? `fig-${item.block.id}`
      : `ctx-${item.block.id}`;

/** True when a string field is blank (empty or whitespace-only). */
export const isTextEmpty = (text: string | null | undefined): boolean =>
  (text ?? "").trim() === "";

/**
 * True when a block item has no authored content: a task with no prompt, a
 * blank context block, or a figure block with no uploaded image (its id present
 * in `emptyFigureBlockIds`). Shared by the confirm-gating hook.
 */
export const isBlockItemEmpty = (
  item: BlockItem,
  emptyFigureBlockIds: ReadonlySet<string>,
): boolean => {
  if (item.kind === "task") return isTextEmpty(item.task.prompt);
  if (item.kind === "context") return isTextEmpty(item.block.content);
  return emptyFigureBlockIds.has(item.block.id);
};

/**
 * Convert a 0-based index into a lowercase letter label: a, b, ..., z, aa, ab, ...
 */
export const letterLabel = (index: number): string => {
  if (index < 0) return "";
  let n = index;
  let out = "";
  while (true) {
    out = String.fromCharCode(97 + (n % 26)) + out;
    n = Math.floor(n / 26) - 1;
    if (n < 0) return out;
  }
};

/**
 * Compute auto-generated display labels for figure blocks across an exam:
 * `Figure {sectionNumber}.{figureIndex}` where sectionNumber is the section's
 * 1-based position in the sorted section list, and figureIndex is the figure
 * block's 1-based order within its section.
 */
export const figureLabelsForBlocks = (
  sections: Section[] | undefined,
  blocks: SectionBlock[] | undefined,
): Map<string, string> => {
  const out = new Map<string, string>();
  if (!sections || !blocks) return out;
  const sorted = sections.slice().sort((a, b) => a.position - b.position);
  sorted.forEach((section, sIdx) => {
    const figs = blocks
      .filter((b) => b.section_id === section.id && b.kind === "figure")
      .sort((a, b) => a.position - b.position);
    figs.forEach((fig, fIdx) => {
      out.set(fig.id, `Figure ${sIdx + 1}.${fIdx + 1}`);
    });
  });
  return out;
};

/** Carousel/hash slug for the orphan ("Unassigned tasks") bucket. */
export const UNASSIGNED_SLUG = "section-unassigned";

/**
 * Index-based carousel/hash slug for a real section: 1-based position in the
 * sorted section list. Shared by the editor and grading views so their
 * deep-link hashes stay in lockstep.
 */
export const sectionIndexSlug = (index: number) => `section-${index + 1}`;

/**
 * Build a URL-hash-safe slug for a section. Used by the editor + grading
 * + results sidebars to deep-link to a specific section.
 */
export const sectionSlug = (key: string | null | undefined) => {
  const base =
    (key ?? "untitled")
      .trim()
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/^-+|-+$/g, "")
      .slice(0, 64) || "untitled";
  return `section-${base}`;
};
