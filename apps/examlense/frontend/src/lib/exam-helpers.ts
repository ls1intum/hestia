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
  semester: string | null;
  instructor_name: string | null;
  total_points: number | null;
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
  parser_model?: string | null;
  solver_model?: string | null;
  lgh_course_id?: number | null;
  created_at: string;
  updated_at: string;
}


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
export const isSectionReady = (tasks: Task[]): boolean =>
  tasks.length > 0 && tasks.every((t) => t.points != null && t.points > 0);

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
