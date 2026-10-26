import type { LearningGoal } from "../../api/client.ts";
import {
  BLOOM_DESC,
  COMPETENCY_ROLE_META,
  SOLO_DESC,
  coverageOf,
  titleCase,
  type CompetencyNode,
  type CompetencyRole,
  type Coverage,
} from "../../lib/goals.ts";

/** One goal flattened out of the forest, with the tree structure kept via parent ids. */
export type Row = {
  id: number;
  parent: number | null;
  number: string;
  goal: LearningGoal;
  role: CompetencyRole;
  /** The session the goal's own source sits in; empty for synthesised and hand-added goals. */
  session: string;
};

export type FilterKey = "role" | "kind" | "bloom" | "solo" | "document" | "session" | "coverage";
export type LevelScale = "bloom" | "solo";
export type GoalChanges = {
  text?: string;
  bloomLevel?: LearningGoal["bloomLevel"];
  soloLevel?: LearningGoal["soloLevel"];
};
export type Layout = "table" | "diagram";
export type CreationTier = 1 | 2 | 3 | 4;
export type CreationState = {
  key: string;
  tier: CreationTier;
  parentGoalId: number | null;
  text: string;
  /** A topic created together with AI-written skills beneath it, instead of on its own. */
  generate?: boolean;
};

export type AttributeKey = "role" | "coverage" | "kind" | "bloom" | "solo" | "source";
export type ColumnKey = "text" | AttributeKey;
/**
 * The reader's column layout: the order of the attribute columns, which of them are hidden and the
 * widths they were dragged to. The learning-goal column always comes first and can't be hidden,
 * because the tree's carets live in it. Kept in the browser, so the layout survives reloads.
 */
export type ColumnPrefs = {
  order: AttributeKey[];
  hidden: AttributeKey[];
  widths: Partial<Record<ColumnKey, number>>;
};

/** Maps a title-cased ladder term back to its API enum value ("Extended Abstract" → "EXTENDED_ABSTRACT"). */
export const toEnum = (term: string) => term.toUpperCase().replace(/ /g, "_");

// Each taxonomy's ladder is the insertion order of its description map, the same order the goal
// modal's dot scales use.
export const LEVEL_SCALES = {
  bloom: { label: "Bloom", desc: BLOOM_DESC },
  solo: { label: "SOLO", desc: SOLO_DESC },
} as const;
export const BLOOM_ORDER = Object.keys(BLOOM_DESC).map(toEnum);

/** What a row's "+" adds beneath it. Knowledge and gaps take no children. */
export const CHILD_APPEND: Partial<
  Record<
    CompetencyRole,
    { tier: 2 | 3 | 4; label: string; placeholder: string; color: string }
  >
> = {
  topic: {
    tier: 2,
    label: "Add skill",
    placeholder: "Describe a skill…",
    color: COMPETENCY_ROLE_META.skill.color,
  },
  skill: {
    tier: 3,
    label: "Add sub-skill",
    placeholder: "Describe a sub-skill…",
    color: COMPETENCY_ROLE_META.subSkill.color,
  },
  subSkill: {
    tier: 4,
    label: "Add knowledge",
    placeholder: "Describe what students should know…",
    color: COMPETENCY_ROLE_META.knowledge.color,
  },
};
export const SOLO_ORDER = Object.keys(SOLO_DESC).map(toEnum);

// "AI_INFERRED" is a synthetic kind value derived from a goal's WIZARD_AI_SUBTREE provenance, so the
// Kind column and its filter surface AI-generated goals without a separate GoalKind enum on the server.
export const NO_IDS: ReadonlySet<number> = new Set();
export const AI_INFERRED_KIND = "AI_INFERRED";
export const MANUAL_KIND = "MANUAL";
export const KIND_ORDER = ["EXPLICIT", "IMPLICIT", AI_INFERRED_KIND, MANUAL_KIND];
// Coverage filter values for goals with a source: sourced only from lectures, only from exercises,
// or from both; exam goals are their own value. Goals without a source, or whose documents have no
// kind, match none.
export const LECTURE_ONLY = "LECTURE_ONLY";
export const EXERCISE_ONLY = "EXERCISE_ONLY";
export const BOTH = "BOTH";
export const EXAM = "EXAM";
export const COVERAGE_ORDER = [LECTURE_ONLY, EXERCISE_ONLY, BOTH, EXAM];
export const COVERAGE_VALUE: Partial<Record<Coverage, string>> = {
  lecture: LECTURE_ONLY,
  exercise: EXERCISE_ONLY,
  both: BOTH,
  exam: EXAM,
};
export const ROLE_ORDER: CompetencyRole[] = [
  "topic",
  "skill",
  "subSkill",
  "knowledge",
];

export const prefersReducedMotion = () =>
  window.matchMedia("(prefers-reduced-motion: reduce)").matches;

/**
 * Topics and skills are generated to group what extraction found, so explicit vs implicit
 * says nothing about them: every one is stored as IMPLICIT.
 */
export const isGrouping = (role: CompetencyRole) => role === "topic" || role === "skill";

/** The filterable values of a row in one column. */
export function valuesOf(row: Row, key: FilterKey): string[] {
  switch (key) {
    case "role":
      return [row.role];
    case "kind":
      if (row.goal.creationProvenance === "WIZARD_AI_SUBTREE")
        return [AI_INFERRED_KIND];
      if (row.goal.creationProvenance === "USER_CREATED") return [MANUAL_KIND];
      return [isGrouping(row.role) ? "" : (row.goal.kind ?? "")];
    case "bloom":
      return [row.goal.bloomLevel ?? ""];
    case "solo":
      return [row.goal.soloLevel ?? ""];
    case "document":
      return [documentOf(row)];
    // Only real sessions: an exercise's title is its file name, which the document filter already lists.
    case "session":
      return [row.goal.hierarchy?.session ?? ""];
    case "coverage": {
      const coverage = coverageOf(row.goal);
      return [(coverage && COVERAGE_VALUE[coverage]) ?? ""];
    }
  }
}

/** The name of the document a row's own source comes from; empty when it has no source. */
export function documentOf(row: Row): string {
  const source = row.goal.sources?.[0];
  return source?.displayName || source?.filename || "";
}

/** Session is preferred; exercise titles preserve useful context for exercise-only goals. */
export function sessionTitleOf(goal: LearningGoal): string {
  return goal.hierarchy?.session ?? goal.hierarchy?.exercise ?? "";
}

/** The tier's colour, faded for knowledge; the rail beside a row and the preview dots share it. */
export function tierRailColor(role: CompetencyRole): string {
  const color = COMPETENCY_ROLE_META[role].color;
  return role === "knowledge" ? `color-mix(in srgb, ${color} 55%, transparent)` : color;
}

/** Human label for a raw column value (role names, title-cased enums). */
export function displayValue(key: FilterKey, value: string): string {
  if (key === "role")
    return COMPETENCY_ROLE_META[value as CompetencyRole].label;
  if (key === "session" || key === "document") return value || "—";
  if (value === LECTURE_ONLY) return "Lecture only";
  if (value === EXERCISE_ONLY) return "Exercise only";
  if (value === BOTH) return "Both";
  if (value === EXAM) return "Exam";
  if (value === AI_INFERRED_KIND) return "AI-inferred";
  if (value === MANUAL_KIND) return "Manual";
  return value ? titleCase(value) : "—";
}

/** What each filter is called in the active-filter chips and in a multi-group popover. */
export const FILTER_LABELS: Record<FilterKey, string> = {
  role: "Tier",
  kind: "Kind",
  bloom: "Bloom",
  solo: "SOLO",
  document: "Document",
  session: "Session",
  coverage: "Coverage",
};

/** Flattens the forest depth-first into rows that keep the structure via parent ids. */
export function flattenForest(forest: CompetencyNode[]): Row[] {
  const rows: Row[] = [];
  const walk = (node: CompetencyNode, parent: number | null, number: string) => {
    if (node.goal.id == null) return;
    rows.push({
      id: node.goal.id,
      parent,
      number,
      goal: node.goal,
      role: node.role,
      session: sessionTitleOf(node.goal),
    });
    // Under a topic the skills come first and the sub-skills in no skill after them, so the
    // numbering follows the order the table shows them in: skills, then the "Sub-skills without a
    // skill" group.
    const children =
      node.role === "topic"
        ? [
            ...node.children.filter((child) => child.role === "skill"),
            ...node.children.filter((child) => child.role !== "skill"),
          ]
        : node.children;
    for (let index = 0; index < children.length; index++) {
      walk(children[index], node.goal.id, `${number}.${index + 1}`);
    }
  };
  for (let index = 0; index < forest.length; index++) {
    walk(forest[index], null, `${index + 1}`);
  }
  return rows;
}

/** A goal's kind, or how it came about when a person or the AI added it rather than extraction. */
export function kindLabel(row: Row): string | null {
  if (row.goal.creationProvenance === "WIZARD_AI_SUBTREE") return "AI-inferred";
  if (row.goal.creationProvenance === "USER_CREATED") return "Manual";
  return row.goal.kind && !isGrouping(row.role) ? titleCase(row.goal.kind) : null;
}
