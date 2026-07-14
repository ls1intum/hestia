import type { LearningGoal } from "../api/client.ts";

/** Coarse hierarchy level of a goal — the deepest path segment it actually carries. */
export type GoalLevel = "MODULE" | "SESSION" | "EXERCISE" | "NONE";

export function levelOf(goal: LearningGoal): GoalLevel {
  if (goal.hierarchy?.exercise) return "EXERCISE";
  if (goal.hierarchy?.session) return "SESSION";
  if (goal.hierarchy?.module) return "MODULE";
  return "NONE";
}

/** Label + HESTIA colour token per level. Colours are CSS vars so they track the theme. */
export const LEVEL_META: Record<GoalLevel, { label: string; color: string }> = {
  MODULE: { label: "Module", color: "var(--hestia-accent)" },
  SESSION: { label: "Session", color: "var(--hestia-primary)" },
  EXERCISE: { label: "Exercise", color: "var(--hestia-warning)" },
  NONE: { label: "Ungrouped", color: "var(--hestia-text-muted)" },
};

/** The levels present in a set of goals, in module→exercise order (drops empty buckets). */
export function presentLevels(goals: LearningGoal[]): GoalLevel[] {
  const order: GoalLevel[] = ["MODULE", "SESSION", "EXERCISE", "NONE"];
  return order.filter((l) => goals.some((g) => levelOf(g) === l));
}

const LEVEL_ORDER: GoalLevel[] = ["MODULE", "SESSION", "EXERCISE", "NONE"];

/** The lecture/exercise title a goal belongs to — the deepest path segment it carries. */
export function unitTitleOf(goal: LearningGoal): string {
  const level = levelOf(goal);
  return (
    goal.hierarchy?.exercise ??
    goal.hierarchy?.session ??
    (level === "MODULE" ? "Course-wide" : "Ungrouped")
  );
}

export type GoalGroup = {
  key: string;
  title: string;
  level: GoalLevel;
  goals: LearningGoal[];
};

/** Table-of-contents label for a group: the course-wide module bucket reads "Module goals". */
export function tocLabel(group: GoalGroup): string {
  return group.level === "MODULE" ? "Module goals" : group.title;
}

/**
 * Buckets goals by the unit (lecture/exercise) they belong to so the list can show a section per
 * lecture/exercise. Groups are ordered module → session → exercise → ungrouped, then by title;
 * goals keep their original order within a group.
 */
export function groupGoalsByUnit(goals: LearningGoal[]): GoalGroup[] {
  const groups = new Map<string, GoalGroup>();
  for (const goal of goals) {
    const level = levelOf(goal);
    const title = unitTitleOf(goal);
    const key = `${level}:${title}`;
    const group = groups.get(key) ?? { key, title, level, goals: [] };
    group.goals.push(goal);
    groups.set(key, group);
  }
  return [...groups.values()].sort(
    (a, b) =>
      LEVEL_ORDER.indexOf(a.level) - LEVEL_ORDER.indexOf(b.level) ||
      a.title.localeCompare(b.title),
  );
}

/** Title-cases an ALL-CAPS enum value (e.g. "EXTENDED_ABSTRACT" → "Extended Abstract"). */
export function titleCase(value: string): string {
  return value
    .toLowerCase()
    .split("_")
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(" ");
}

/** Level → description lookups (keyed by title-cased term), shared by the list view's badge
 * tooltips and filter infos and the competency map's hover card. Insertion order is the
 * taxonomy's level order. */
export const BLOOM_DESC: Record<string, string> = {
  Remember: "Recall facts and basic concepts.",
  Understand: "Explain ideas or concepts.",
  Apply: "Use knowledge in new situations.",
  Analyze: "Break ideas apart and draw connections.",
  Evaluate: "Justify a stance or judgement.",
  Create: "Produce new or original work.",
};

export const SOLO_DESC: Record<string, string> = {
  Prestructural: "Misses the point; no real grasp.",
  Unistructural: "Grasps one relevant aspect.",
  Multistructural: "Several aspects, but in isolation.",
  Relational: "Integrates aspects into a coherent whole.",
  "Extended Abstract": "Generalises beyond to new contexts.",
};

export const KIND_DESC: Record<string, string> = {
  Explicit: "Stated directly in the source material.",
  Implicit: "Inferred by the model from the content.",
};

export const RELATIONSHIP_LABELS: Record<string, string> = {
  CONTRIBUTES_TO: "contributes to",
  PREREQUISITE_OF: "prerequisite of",
  OVERLAPS_WITH: "overlaps with",
};

const RELATIONSHIP_ORDER = [
  "CONTRIBUTES_TO",
  "PREREQUISITE_OF",
  "OVERLAPS_WITH",
];

/** Per-type relationship counts for a goal, in a stable order, dropping types with none. */
export function relationshipCounts(
  goal: LearningGoal,
): { type: string; label: string; count: number }[] {
  const byType = new Map<string, number>();
  for (const rel of goal.relationships ?? []) {
    if (rel.type) byType.set(rel.type, (byType.get(rel.type) ?? 0) + 1);
  }
  return RELATIONSHIP_ORDER.filter((t) => byType.has(t)).map((t) => ({
    type: t,
    label: RELATIONSHIP_LABELS[t] ?? t,
    count: byType.get(t)!,
  }));
}

/** Subject-first phrasing per relationship type, e.g. "Prerequisite for 2 goals". */
export const RELATIONSHIP_PHRASES: Record<string, string> = {
  PREREQUISITE_OF: "Prerequisite for",
  OVERLAPS_WITH: "Overlaps with",
  CONTRIBUTES_TO: "Contributes to",
};

export type RelationshipGroup = {
  type: string;
  phrase: string;
  count: number;
  targets: string[];
};

/**
 * Groups a goal's relationships by type for the card's single-line summary: each entry carries the
 * count and the target goals' texts so a hover can reveal exactly which goals are linked.
 */
export function groupRelationships(goal: LearningGoal): RelationshipGroup[] {
  const counts = new Map<string, number>();
  const targets = new Map<string, string[]>();
  for (const rel of goal.relationships ?? []) {
    if (!rel.type) continue;
    counts.set(rel.type, (counts.get(rel.type) ?? 0) + 1);
    if (rel.targetText) {
      const list = targets.get(rel.type) ?? [];
      list.push(rel.targetText);
      targets.set(rel.type, list);
    }
  }
  return RELATIONSHIP_ORDER.filter((t) => counts.has(t)).map((t) => ({
    type: t,
    phrase: RELATIONSHIP_PHRASES[t] ?? t,
    count: counts.get(t)!,
    targets: targets.get(t) ?? [],
  }));
}

// ────────────────────────────────────────────────────────────────────────────
// Competency tree
//
// The extraction pipeline synthesises a top-down competency tree on top of the
// extracted goals: terminal competencies (origin TERMINAL) → sub-skills → grounded
// knowledge leaves, plus gap-analysis leaves (origin GAP) for knowledge the course
// does NOT yet cover. The edges are stored as CONTRIBUTES_TO and point UPWARD
// (child → parent), so the client inverts them to render the tree top-down.
// ────────────────────────────────────────────────────────────────────────────

/** A node's role in the competency tree, which drives its label and styling. */
export type CompetencyRole = "competency" | "sub-skill" | "knowledge" | "gap";

/** A node in the rendered competency tree, with its children resolved top-down. */
export type CompetencyNode = {
  goal: LearningGoal;
  role: CompetencyRole;
  children: CompetencyNode[];
};

// Role colours are drawn from the HESTIA styleguide's text-safe palette (primary / accent /
// text-muted); warning is deliberately avoided (never a standalone text colour) and danger is
// reserved for gaps. Skill takes gold (the sparing main accent, few top-level nodes), sub-skill
// the secondary accent, knowledge the quiet muted tier.
export const COMPETENCY_ROLE_META: Record<
  CompetencyRole,
  { label: string; color: string }
> = {
  competency: { label: "Skill", color: "var(--hestia-primary)" },
  "sub-skill": { label: "Sub-skill", color: "var(--hestia-accent)" },
  knowledge: { label: "Knowledge", color: "var(--hestia-text-muted)" },
  gap: { label: "Gap", color: "var(--hestia-danger)" },
};

/**
 * Builds the competency forest from a flat goal list: terminal competencies are the roots,
 * and each goal's CONTRIBUTES_TO edges (which point child → parent) are inverted into a
 * parent → children map that is walked downward.
 *
 * Tree depth is capped at three tiers (terminal → sub-skill → knowledge/gap) and traversal
 * tracks the current path so a stray edge can never produce a cycle or revisit a node within
 * its own branch. Roles are assigned by position: depth 0 = competency; a depth-1 node is a
 * sub-skill when it has children OR carries a doing/judgement Bloom level (a childless
 * apply/analyze/evaluate/create goal is still a capability, not knowledge), otherwise it is
 * leftover knowledge attached directly to the terminal; deeper nodes are knowledge; any
 * GAP-origin goal renders as a gap leaf.
 */
export function buildCompetencyForest(goals: LearningGoal[]): CompetencyNode[] {
  const byId = new Map<number, LearningGoal>();
  for (const g of goals) if (g.id != null) byId.set(g.id, g);

  const childrenOf = new Map<number, LearningGoal[]>();
  for (const g of goals) {
    if (g.id == null) continue;
    for (const rel of g.relationships ?? []) {
      if (rel.type !== "CONTRIBUTES_TO" || rel.targetGoalId == null) continue;
      if (!byId.has(rel.targetGoalId)) continue; // edge to a goal outside this set
      const list = childrenOf.get(rel.targetGoalId) ?? [];
      list.push(g);
      childrenOf.set(rel.targetGoalId, list);
    }
  }

  const MAX_DEPTH = 2; // depth 0/1/2 = competency / sub-skill / knowledge|gap

  // Bloom levels that make a goal a capability (mirrors the server's SUB_SKILL_BLOOM split).
  const DOING_BLOOM = new Set(["APPLY", "ANALYZE", "EVALUATE", "CREATE"]);

  const build = (
    goal: LearningGoal,
    depth: number,
    onPath: Set<number>,
  ): CompetencyNode => {
    const rawChildren =
      depth < MAX_DEPTH && goal.id != null
        ? (childrenOf.get(goal.id) ?? [])
        : [];
    const nextPath = goal.id != null ? new Set(onPath).add(goal.id) : onPath;
    const children = rawChildren
      .filter((c) => c.id != null && !onPath.has(c.id))
      .map((c) => build(c, depth + 1, nextPath));

    const role: CompetencyRole =
      goal.origin === "GAP"
        ? "gap"
        : depth === 0
          ? "competency"
          : depth === 1 &&
              (children.length > 0 || DOING_BLOOM.has(goal.bloomLevel ?? ""))
            ? "sub-skill"
            : "knowledge";
    return { goal, role, children };
  };

  return goals
    .filter((g) => g.origin === "TERMINAL")
    .map((g) => build(g, 0, new Set()))
    .sort((a, b) => (a.goal.text ?? "").localeCompare(b.goal.text ?? ""));
}

/** Whether a course has a synthesised competency tree to show (≥1 terminal competency). */
export function hasCompetencyTree(goals: LearningGoal[]): boolean {
  return goals.some((g) => g.origin === "TERMINAL");
}

/**
 * Number of not-yet-approved goals in a node's subtree, excluding the node itself — i.e. how much
 * review work is still hidden beneath a (collapsed) branch box in the competency map.
 */
export function countPendingDescendants(node: CompetencyNode): number {
  let pending = 0;
  for (const child of node.children) {
    if (child.goal.status !== "APPROVED") pending += 1;
    pending += countPendingDescendants(child);
  }
  return pending;
}

/** Distinct source filenames backing a goal, preserving first-seen order. */
export function sourceFilenames(goal: LearningGoal): string[] {
  const seen = new Set<string>();
  for (const source of goal.sources ?? []) {
    if (source.filename) seen.add(source.filename);
  }
  return [...seen];
}
