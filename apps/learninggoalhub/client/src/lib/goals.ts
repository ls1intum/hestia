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

// ────────────────────────────────────────────────────────────────────────────
// Lecture and exercise coverage
//
// Every source carries the kind of the document it was quoted from, so whether a sub-skill is
// introduced in a lecture, practised in an exercise, or both is read straight off its sources.
// Documents uploaded before kinds existed have none; their sub-skills are "unknown" and show no
// badge.
// ────────────────────────────────────────────────────────────────────────────

/** Where a sub-skill's evidence comes from. */
export type Coverage = "lecture" | "exercise" | "both" | "unknown";

export const COVERAGE_META: Record<
  Exclude<Coverage, "unknown">,
  { label: string; color: string }
> = {
  lecture: { label: "Lecture", color: "var(--hestia-text-muted)" },
  exercise: { label: "Exercise", color: "var(--hestia-primary)" },
  both: {
    label: "Both",
    color: "color-mix(in srgb, var(--hestia-primary) 50%, var(--hestia-accent))",
  },
};

/** A goal's coverage from its sources' document kinds; null when it has no source at all. */
export function coverageOf(goal: LearningGoal): Coverage | null {
  const sources = goal.sources ?? [];
  if (sources.length === 0) return null;
  const lecture = sources.some((source) => source.documentKind === "LECTURE");
  const exercise = sources.some((source) => source.documentKind === "EXERCISE");
  return lecture && exercise ? "both" : exercise ? "exercise" : lecture ? "lecture" : "unknown";
}

/** How a branch's source-backed sub-skills split by coverage. */
export type CoverageCounts = Record<Coverage, number> & {
  /** Sub-skills with a source, whatever their coverage. */
  total: number;
  /** Sub-skills an exercise practises: exercise or both. */
  practised: number;
};

function subSkillsOf(node: CompetencyNode): CompetencyNode[] {
  const found: CompetencyNode[] = [];
  const walk = (current: CompetencyNode) => {
    if (current.role === "skill" && coverageOf(current.goal) != null) found.push(current);
    current.children.forEach(walk);
  };
  node.children.forEach(walk);
  return found;
}

/** Counts the source-backed sub-skills beneath a topic or skill by coverage. */
export function coverageCounts(node: CompetencyNode): CoverageCounts {
  const counts: CoverageCounts = {
    lecture: 0,
    exercise: 0,
    both: 0,
    unknown: 0,
    total: 0,
    practised: 0,
  };
  for (const subSkill of subSkillsOf(node)) {
    counts[coverageOf(subSkill.goal)!] += 1;
    counts.total += 1;
  }
  counts.practised = counts.exercise + counts.both;
  return counts;
}

const BLOOM_RANK = ["REMEMBER", "UNDERSTAND", "APPLY", "ANALYZE", "EVALUATE", "CREATE"];
const bloomRank = (goal: LearningGoal) => BLOOM_RANK.indexOf(goal.bloomLevel ?? "");

/**
 * Sub-skills whose exercise level sits abnormally against what the lectures of the same topic
 * claim, keyed by goal id with the reason to show. Only the abnormal direction is flagged:
 *
 * - an exercise sub-skill below UNDERSTAND under a topic whose lecture sub-skills reach APPLY or
 *   above — the exercise practises less than the lecture claims;
 * - an exercise sub-skill at EVALUATE or above under a topic whose lecture outcomes never pass
 *   UNDERSTAND — the exercise asks for more than the lecture prepares.
 *
 * Exercise outcomes hang beside the lecture sub-skills of their topic rather than inside them, so
 * the topic is the scope both sides are compared in. A lecture at UNDERSTAND with an exercise at
 * APPLY is how teaching is meant to work and is never flagged. A goal without a Bloom level takes
 * part on neither side.
 */
export function levelFlags(forest: CompetencyNode[]): Map<number, string> {
  const flags = new Map<number, string>();
  for (const topic of forest) {
    const subSkills = subSkillsOf(topic);
    const lectureSide = (coverage: Coverage | null) => coverage === "lecture" || coverage === "both";
    const lectureSkillRank = Math.max(
      -1,
      ...subSkills.filter((node) => lectureSide(coverageOf(node.goal))).map((node) => bloomRank(node.goal)),
    );
    const lectureOutcomeRank = Math.max(-1, ...lectureOutcomesOf(topic).map(bloomRank));
    for (const node of subSkills) {
      const rank = bloomRank(node.goal);
      if (node.goal.id == null || coverageOf(node.goal) !== "exercise" || rank < 0) continue;
      if (rank < BLOOM_RANK.indexOf("UNDERSTAND") && lectureSkillRank >= BLOOM_RANK.indexOf("APPLY")) {
        flags.set(
          node.goal.id,
          `The exercise practises this at ${titleCase(BLOOM_RANK[rank])}, while the lectures of this topic claim ${titleCase(BLOOM_RANK[lectureSkillRank])}.`,
        );
      } else if (
        rank >= BLOOM_RANK.indexOf("EVALUATE") &&
        lectureOutcomeRank >= 0 &&
        lectureOutcomeRank <= BLOOM_RANK.indexOf("UNDERSTAND")
      ) {
        flags.set(
          node.goal.id,
          `The exercise asks for ${titleCase(BLOOM_RANK[rank])}, while the lectures of this topic never go beyond ${titleCase(BLOOM_RANK[lectureOutcomeRank])}.`,
        );
      }
    }
  }
  return flags;
}

/** Every goal beneath a topic whose sources include a lecture. */
function lectureOutcomesOf(topic: CompetencyNode): LearningGoal[] {
  const found: LearningGoal[] = [];
  const walk = (node: CompetencyNode) => {
    const coverage = coverageOf(node.goal);
    if (coverage === "lecture" || coverage === "both") found.push(node.goal);
    node.children.forEach(walk);
  };
  topic.children.forEach(walk);
  return found;
}
