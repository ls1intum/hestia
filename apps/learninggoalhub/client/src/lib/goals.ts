import type { LearningGoal } from "../api/client.ts";

/** Title-cases an ALL-CAPS enum value (e.g. "EXTENDED_ABSTRACT" → "Extended Abstract"). */
export function titleCase(value: string): string {
  return value
    .toLowerCase()
    .split("_")
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(" ");
}

/** Level → description lookups (keyed by title-cased term), shown in the goal modal's Bloom,
 * SOLO and kind tiles. Insertion order is the taxonomy's level order. */
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

// ────────────────────────────────────────────────────────────────────────────
// Competency tree
//
// The extraction pipeline synthesises a top-down competency tree on top of the
// extracted goals: topics (origin TERMINAL) → generated capabilities → extracted
// skills → grounded knowledge leaves. A skill that forms no capability hangs
// directly under its topic. Older trees are three tiers (topic → skill →
// knowledge) and may carry gap-analysis leaves (origin GAP). The edges are stored
// as CONTRIBUTES_TO and point UPWARD (child → parent), so the client inverts them
// to render the tree top-down.
// ────────────────────────────────────────────────────────────────────────────

/** A node's role in the competency tree, which drives its label and styling. */
export type CompetencyRole =
  | "topic"
  | "capability"
  | "skill"
  | "knowledge"
  | "gap";

/** A node in the rendered competency tree, with its children resolved top-down. */
export type CompetencyNode = {
  goal: LearningGoal;
  role: CompetencyRole;
  children: CompetencyNode[];
};

/** Lecture order first; legacy/unpositioned goals remain stable by database id and then text. */
function compareLectureOrder(a: LearningGoal, b: LearningGoal): number {
  const aOrder = a.lectureOrder ?? Number.MAX_SAFE_INTEGER;
  const bOrder = b.lectureOrder ?? Number.MAX_SAFE_INTEGER;
  if (aOrder !== bOrder) return aOrder - bOrder;
  const aId = a.id ?? Number.MAX_SAFE_INTEGER;
  const bId = b.id ?? Number.MAX_SAFE_INTEGER;
  if (aId !== bId) return aId - bId;
  return (a.text ?? "").localeCompare(b.text ?? "");
}

// Role colours are drawn from the HESTIA styleguide's text-safe palette (primary / accent /
// text-muted); warning is deliberately avoided (never a standalone text colour) and danger is
// reserved for gaps. Topic takes gold (the sparing main accent, few top-level nodes), capability
// the secondary accent, skill a muted blend of it, knowledge the quiet muted tier.
// The labels are what instructors read and deliberately differ from the role keys: a capability is
// shown as "Skill" and a skill as "Sub-skill", so the names alone say which tier sits higher.
export const COMPETENCY_ROLE_META: Record<
  CompetencyRole,
  { label: string; color: string }
> = {
  topic: { label: "Topic", color: "var(--hestia-primary)" },
  capability: { label: "Skill", color: "var(--hestia-accent)" },
  skill: {
    label: "Sub-skill",
    color: "color-mix(in srgb, var(--hestia-accent) 55%, var(--hestia-text-muted))",
  },
  knowledge: { label: "Knowledge", color: "var(--hestia-text-muted)" },
  gap: { label: "Gap", color: "var(--hestia-danger)" },
};

/**
 * Builds the competency forest from a flat goal list: topics are the roots, and each goal's
 * CONTRIBUTES_TO edges (which point child → parent) are inverted into a parent → children map
 * that is walked downward.
 *
 * Tree depth is capped at four tiers (topic → capability → skill → knowledge) and traversal
 * tracks the current path so a stray edge can never produce a cycle or revisit a node within its
 * own branch. Roles are assigned by position: depth 0 = topic; a depth-1 node whose children
 * include skills is a capability; otherwise a depth-1 node is a skill when it has children, is
 * explicitly marked as a skill, or carries a doing/judgement Bloom level for legacy role-null
 * data, and knowledge when not. A depth-2 node under a capability is a skill; every other deeper
 * node is knowledge. Knowledge and gap nodes take no children; any GAP-origin goal renders as a
 * gap leaf. A hand-added depth-1 node is a capability when it was added with the SKILL role, even
 * before it has children, and otherwise a skill whatever its Bloom level: manual goals are
 * deliberately left unclassified, and the instructor added it through an "Add ..." control, which
 * says the tier outright.
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

  const MAX_DEPTH = 3; // depth 0/1/2/3 = topic / capability|skill / skill|knowledge / knowledge

  // Bloom levels that make a goal a skill (mirrors the server's SUB_SKILL_BLOOM split).
  const DOING_BLOOM = new Set(["APPLY", "ANALYZE", "EVALUATE", "CREATE"]);
  const isSkillTier = (goal: LearningGoal): boolean =>
    goal.role != null ? goal.role === "SKILL" : DOING_BLOOM.has(goal.bloomLevel ?? "");

  const build = (
    goal: LearningGoal,
    depth: number,
    onPath: Set<number>,
    parentRole: CompetencyRole | null,
  ): CompetencyNode => {
    const childGoals = (
      depth < MAX_DEPTH && goal.id != null ? (childrenOf.get(goal.id) ?? []) : []
    )
      .filter((c) => c.id != null && !onPath.has(c.id))
      .sort(compareLectureOrder);

    const role: CompetencyRole =
      goal.origin === "GAP"
        ? "gap"
        : depth === 0
          ? "topic"
          : depth === 1 &&
              (childGoals.some((c) => c.role === "SKILL") ||
                (goal.creationProvenance === "USER_CREATED" && goal.role === "SKILL"))
            ? "capability"
            : (depth === 1 &&
                  (childGoals.length > 0 ||
                    isSkillTier(goal) ||
                    goal.creationProvenance === "USER_CREATED")) ||
                (depth === 2 && parentRole === "capability" && isSkillTier(goal))
              ? "skill"
              : "knowledge";

    const nextPath = goal.id != null ? new Set(onPath).add(goal.id) : onPath;
    const children =
      role === "knowledge" || role === "gap"
        ? []
        : childGoals.map((c) => build(c, depth + 1, nextPath, role));
    return { goal, role, children };
  };

  return goals
    .filter((g) => g.origin === "TERMINAL")
    .map((g) => build(g, 0, new Set(), null))
    .sort((a, b) => compareLectureOrder(a.goal, b.goal));
}

/** Finds a node in the competency forest and returns the immediate child goals attached to it. */
export function childGoalsOf(
  forest: CompetencyNode[],
  goalId: number | null | undefined,
): LearningGoal[] {
  if (goalId == null) return [];
  const find = (nodes: CompetencyNode[]): CompetencyNode | undefined => {
    for (const node of nodes) {
      if (node.goal.id === goalId) return node;
      const found = find(node.children);
      if (found) return found;
    }
    return undefined;
  };
  return find(forest)?.children.map((child) => child.goal) ?? [];
}

/** Source-backed lecture outcomes that justify a synthesized sub-skill without becoming tree nodes. */
export function supportingOutcomesOf(
  goals: LearningGoal[],
  goalId: number | null | undefined,
): LearningGoal[] {
  if (goalId == null) return [];
  return goals
    .filter((goal) =>
      goal.relationships?.some(
        (relationship) =>
          relationship.type === "SUPPORTS" && relationship.targetGoalId === goalId,
      ),
    )
    .sort(compareLectureOrder);
}

/**
 * How many AI-generated skills hang under the topic `goalId` — the nodes a regeneration would
 * replace, with everything beneath them. `undefined` means the goal is not a topic at all, which is
 * what tells the goal modal to leave the regeneration action out entirely.
 */
export function generatedChildCount(
  forest: CompetencyNode[],
  goalId: number | null | undefined,
): number | undefined {
  const terminal = forest.find((node) => node.goal.id === goalId);
  return terminal?.children.filter(
    (child) => child.goal.creationProvenance === "WIZARD_AI_SUBTREE",
  ).length;
}
