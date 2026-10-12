import { LearningGoalPlan } from "@/lib/workshop-generator";

/**
 * Best-fit activity for a learning goal from its Bloom's and SOLO levels.
 * Keep in sync with server/src/main/java/com/workshopper/usecase/ActivityRecommender.java.
 */

/** Bloom's levels from lowest to highest, each with its best-fit activities in order of preference. */
const BLOOM: { name: string; activities: string[] }[] = [
  { name: "remember", activities: ["Quiz / Polls"] },
  { name: "understand", activities: ["Q&A Session", "Think-Pair-Share"] },
  { name: "apply", activities: ["Worked Problem", "Hands-on Practice"] },
  { name: "analyze", activities: ["Case Study", "Group Discussion", "Concept Mapping"] },
  { name: "evaluate", activities: ["Debate", "Peer Review"] },
  { name: "create", activities: ["Design Sprint / Prototype Challenge", "Role Play"] },
];

/** SOLO levels from lowest to highest. */
const SOLO: { name: string; activities: string[] }[] = [
  { name: "unistructural", activities: ["Quiz / Polls"] },
  { name: "multistructural", activities: ["Q&A Session", "Brainstorming", "Worked Problem"] },
  { name: "relational", activities: ["Concept Mapping", "Think-Pair-Share", "Group Discussion", "Case Study"] },
  { name: "extendedabstract", activities: ["Design Sprint / Prototype Challenge", "Debate", "Role Play", "Peer Review"] },
];

const normalise = (s: string) => s.toLowerCase().replace(/[^a-z]/g, "");

/** Maps "EXTENDED_ABSTRACT", "Extended abstract", "analyse" etc. to the level index, or -1. */
function levelIndex(level: string | undefined, levels: { name: string }[]): number {
  if (!level) return -1;
  const key = normalise(level).replace("analyse", "analyze");
  return levels.findIndex(l => key.includes(l.name));
}

function candidates(bloom: number, solo: number): string[] {
  return Array.from(new Set([
    ...(bloom >= 0 ? BLOOM[bloom].activities : []),
    ...(solo >= 0 ? SOLO[solo].activities : []),
  ]));
}

/**
 * Returns the activity for a goal, or null if its level is unknown or none of the user's
 * activities fits at or below its level. With no selected activities, the first best fit is used;
 * otherwise only selected activities count, moving down one level at a time in both taxonomies.
 */
export function recommendActivity(goal: Pick<LearningGoalPlan, "bloomLevel" | "soloLevel">, selectedActivities: string[]): string | null {
  const bloom = levelIndex(goal.bloomLevel, BLOOM);
  const solo = levelIndex(goal.soloLevel, SOLO);
  if (bloom < 0 && solo < 0) return null;

  if (selectedActivities.length === 0) return candidates(bloom, solo)[0];

  for (let step = 0; bloom - step >= 0 || solo - step >= 0; step++) {
    for (const candidate of candidates(bloom - step, solo - step)) {
      const match = selectedActivities.find(s => normalise(s) === normalise(candidate));
      if (match) return match;
    }
  }
  return null;
}

/**
 * Default activity-to-goal mapping for the Check Understanding block: each goal gets its
 * best-fit activity (falling back to the user's activities in turn), and goals that share
 * an activity are assessed together in one box.
 */
export function defaultEvaluateMappings(goals: LearningGoalPlan[], selectedActivities: string[]): { method: string; lgIds: string[] }[] {
  const byMethod = new Map<string, string[]>();
  goals.forEach((g, i) => {
    const method = recommendActivity(g, selectedActivities)
      ?? (selectedActivities.length > 0 ? selectedActivities[i % selectedActivities.length] : "Quiz / Polls");
    byMethod.set(method, [...(byMethod.get(method) || []), g.id]);
  });
  return Array.from(byMethod, ([method, lgIds]) => ({ method, lgIds }));
}
