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

// ────────────────────────────────────────────────────────────────────────────
// Topic tree
//
// The extraction pipeline synthesises a top-down Topic tree on top of the
// extracted goals: topics (origin TERMINAL) → generated skills → extracted
// sub-skills → grounded knowledge leaves. A sub-skill that belongs to no skill hangs
// directly under its topic. Older trees are three tiers (topic → sub-skill →
// knowledge). The edges are stored
// as CONTRIBUTES_TO and point UPWARD (child → parent), so the client inverts them
// to render the tree top-down.
// ────────────────────────────────────────────────────────────────────────────

/** A node's role in the Topic tree, which drives its label and styling. */
export type TreeRole =
  | "topic"
  | "skill"
  | "subSkill"
  | "knowledge";

/** A node in the rendered Topic tree, with its children resolved top-down. */
export type TreeNode = {
  goal: LearningGoal;
  role: TreeRole;
  children: TreeNode[];
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
// text-muted); warning is deliberately avoided (never a standalone text colour). Topic takes gold (the sparing main accent, few top-level nodes), skill
// the secondary accent, sub-skill a muted blend of it, knowledge the quiet muted tier.
// The role keys match the labels instructors read. The server marks skills and sub-skills alike
// with role SKILL, so the tree position tells them apart (see buildTopicForest).
export const TREE_ROLE_META: Record<
  TreeRole,
  { label: string; color: string }
> = {
  topic: { label: "Topic", color: "var(--hestia-primary)" },
  skill: { label: "Skill", color: "var(--hestia-accent)" },
  subSkill: {
    label: "Sub-skill",
    color: "color-mix(in srgb, var(--hestia-accent) 55%, var(--hestia-text-muted))",
  },
  knowledge: { label: "Knowledge", color: "var(--hestia-text-muted)" },
};

/** "1 sub-skill", "7 sub-skills", "7 knowledge": the tier's label in lower case, counted. */
export function tierNoun(role: TreeRole, count: number): string {
  const label = TREE_ROLE_META[role].label.toLowerCase();
  return count === 1 || role === "knowledge" ? label : `${label}s`;
}

/** The short label by default; `full` asks for the complete wording instead. */
export function displayedGoalLabel(goal: LearningGoal, full = false): string {
  return (full ? goal.text : goal.shortLabel) ?? goal.shortLabel ?? goal.text ?? "";
}

/**
 * Builds the Topic tree forest from a flat goal list: topics are the roots, and each goal's
 * CONTRIBUTES_TO edges (which point child → parent) are inverted into a parent → children map
 * that is walked downward.
 *
 * Tree depth is capped at four tiers (topic → skill → sub-skill → knowledge) and traversal
 * tracks the current path so a stray edge can never produce a cycle or revisit a node within its
 * own branch. Roles are assigned by position: depth 0 = topic; a depth-1 node whose children
 * include role-SKILL goals is a skill; otherwise a depth-1 node is a sub-skill when it has
 * children, carries the SKILL role, or carries a doing/judgement Bloom level for legacy role-null
 * data, and knowledge when not. A depth-2 node under a skill is a sub-skill when it is
 * skill-tier; every other deeper node is knowledge. Knowledge nodes take no children. A hand-added
 * depth-1 node is a skill when it was added with the SKILL role, even before it has children,
 * and otherwise a sub-skill whatever its Bloom level: manual goals are
 * deliberately left unclassified, and the instructor added it through an "Add ..." control, which
 * says the tier outright.
 */
export function buildTopicForest(goals: LearningGoal[]): TreeNode[] {
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

  const MAX_DEPTH = 3; // depth 0/1/2/3 = topic / skill|sub-skill / sub-skill|knowledge / knowledge

  // Bloom levels that make a goal a sub-skill (mirrors the server's SUB_SKILL_BLOOM split).
  const DOING_BLOOM = new Set(["APPLY", "ANALYZE", "EVALUATE", "CREATE"]);
  const isSkillTier = (goal: LearningGoal): boolean =>
    goal.role != null ? goal.role === "SKILL" : DOING_BLOOM.has(goal.bloomLevel ?? "");

  const build = (
    goal: LearningGoal,
    depth: number,
    onPath: Set<number>,
    parentRole: TreeRole | null,
  ): TreeNode => {
    const childGoals = (
      depth < MAX_DEPTH && goal.id != null ? (childrenOf.get(goal.id) ?? []) : []
    )
      .filter((c) => c.id != null && !onPath.has(c.id))
      .sort(compareLectureOrder);

    const role: TreeRole =
      depth === 0
        ? "topic"
        : depth === 1 &&
            (childGoals.some((c) => c.role === "SKILL") ||
              (goal.creationProvenance === "USER_CREATED" && goal.role === "SKILL"))
          ? "skill"
          : (depth === 1 &&
                (childGoals.length > 0 ||
                  isSkillTier(goal) ||
                  goal.creationProvenance === "USER_CREATED")) ||
              (depth === 2 && parentRole === "skill" && isSkillTier(goal))
            ? "subSkill"
            : "knowledge";

    const nextPath = goal.id != null ? new Set(onPath).add(goal.id) : onPath;
    const children =
      role === "knowledge"
        ? []
        : childGoals.map((c) => build(c, depth + 1, nextPath, role));
    return { goal, role, children };
  };

  return goals
    .filter((g) => g.origin === "TERMINAL")
    .map((g) => build(g, 0, new Set(), null))
    .sort((a, b) => compareLectureOrder(a.goal, b.goal));
}

// ────────────────────────────────────────────────────────────────────────────
// Lecture and exercise coverage
//
// Every source carries the kind of the document it was quoted from, so whether a sub-skill is
// introduced in a lecture, practised in an exercise, or both is read straight off its sources.
// Documents uploaded before kinds existed have none; their sub-skills are "unknown" and show no
// badge. Exam goals have no document source; they come from an exam task and are "exam".
// ────────────────────────────────────────────────────────────────────────────

/** Where a sub-skill's evidence comes from. */
export type Coverage = "lecture" | "exercise" | "both" | "exam" | "unknown";

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
  exam: { label: "Exam", color: "var(--hestia-accent)" },
};

/**
 * A goal's coverage from its sources' document kinds; null when it has no source at all. An exam
 * goal is "exam" whether or not the task it came from was stored.
 */
export function coverageOf(goal: LearningGoal): Coverage | null {
  if (goal.origin === "EXAM") return "exam";
  const sources = goal.sources ?? [];
  if (sources.length === 0) return null;
  const lecture = sources.some((source) => source.documentKind === "LECTURE");
  const exercise = sources.some((source) => source.documentKind === "EXERCISE");
  return lecture && exercise ? "both" : exercise ? "exercise" : lecture ? "lecture" : "unknown";
}

/** How a branch's source-backed sub-skills split by coverage. Exam goals are not counted. */
export type CoverageCounts = Record<Coverage, number> & {
  /** Sub-skills with a source, whatever their coverage. */
  total: number;
  /** Sub-skills an exercise practises: exercise or both. */
  practised: number;
};

function subSkillsOf(node: TreeNode): TreeNode[] {
  const found: TreeNode[] = [];
  const walk = (current: TreeNode) => {
    const coverage = coverageOf(current.goal);
    if (current.role === "subSkill" && coverage != null && coverage !== "exam") found.push(current);
    current.children.forEach(walk);
  };
  node.children.forEach(walk);
  return found;
}

/** Counts the source-backed sub-skills beneath a topic or skill by coverage. */
export function coverageCounts(node: TreeNode): CoverageCounts {
  const counts: CoverageCounts = {
    lecture: 0,
    exercise: 0,
    both: 0,
    exam: 0,
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
export function levelFlags(forest: TreeNode[]): Map<number, string> {
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
function lectureOutcomesOf(topic: TreeNode): LearningGoal[] {
  const found: LearningGoal[] = [];
  const walk = (node: TreeNode) => {
    const coverage = coverageOf(node.goal);
    if (coverage === "lecture" || coverage === "both") found.push(node.goal);
    node.children.forEach(walk);
  };
  topic.children.forEach(walk);
  return found;
}
