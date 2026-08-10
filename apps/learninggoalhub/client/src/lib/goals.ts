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
 * sub-skill when it has children, is explicitly marked as a skill, or carries a doing/judgement
 * Bloom level for legacy role-null data; otherwise it is knowledge attached directly to the
 * terminal. Deeper nodes are knowledge; any GAP-origin goal renders as a gap leaf. A hand-added
 * depth-1 node is a sub-skill whatever its Bloom level: manual goals are deliberately left
 * unclassified, and the instructor added it through the "Add sub-skill" knob, which says the tier
 * outright.
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
  const isSkillTier = (goal: LearningGoal): boolean =>
    goal.role != null ? goal.role === "SKILL" : DOING_BLOOM.has(goal.bloomLevel ?? "");

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
              (children.length > 0 ||
                isSkillTier(goal) ||
                goal.creationProvenance === "USER_CREATED")
            ? "sub-skill"
            : "knowledge";
    return { goal, role, children };
  };

  return goals
    .filter((g) => g.origin === "TERMINAL")
    .map((g) => build(g, 0, new Set()))
    .sort((a, b) => (a.goal.text ?? "").localeCompare(b.goal.text ?? ""));
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

/**
 * How many wizard-generated sub-skills hang under the terminal skill `goalId` — the nodes a
 * regeneration would replace. `undefined` means the goal is not a terminal skill at all, which is
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
