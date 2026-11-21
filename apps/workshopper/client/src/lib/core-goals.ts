import type { CourseGoal, LearningGoalPlan } from "./workshop-generator";

/** Learning goals live in core and are identified by UUIDs; anything else is a goal not saved yet. */
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function isCoreGoalId(id: string | undefined | null): id is string {
  return !!id && UUID.test(id);
}

/** What the Goals step sends to the course: core goals by id (changed if the text differs), new ones without id. */
export function goalInputs(plans: Pick<LearningGoalPlan, "id" | "goal">[]): { id?: string; text: string }[] {
  return plans.map(p => ({ id: isCoreGoalId(p.id) ? p.id : undefined, text: p.goal }));
}

/**
 * Puts the saved core goals (same order as the plans) into the plans: their id, text and levels now
 * come from core. `idMap` maps the old (temporary) plan ids to the core ids.
 */
export function applySavedGoals(
  plans: LearningGoalPlan[],
  saved: CourseGoal[],
): { plans: LearningGoalPlan[]; idMap: Record<string, string> } {
  const idMap: Record<string, string> = {};
  const result = plans.map((plan, i) => {
    const goal = saved[i];
    idMap[plan.id] = goal.id;
    return {
      ...plan,
      id: goal.id,
      goal: goal.text,
      originalGoal: goal.originalText ?? goal.text,
      bloomLevel: goal.bloomLevel ?? undefined,
      soloLevel: goal.soloLevel ?? undefined,
    };
  });
  return { plans: result, idMap };
}

/** Points evaluate mappings (which name goals by id) at the core ids after temporary ids were replaced. */
export function remapMappings<T extends { lgIds: string[] }>(mappings: T[] | undefined, idMap: Record<string, string>): T[] | undefined {
  return mappings?.map(m => ({ ...m, lgIds: m.lgIds.map(id => idMap[id] ?? id) }));
}

/**
 * Runs the grammar fix over the goals written in this session only. A goal picked from the course
 * keeps its course wording: rewriting it would change the goal in core and mark every link to it
 * for review. Returns the texts in the order of `goals`; if the fix answers with a different
 * number of texts, the texts stay as they are.
 */
export async function fixGrammarOfOwnGoals(
  goals: { id: string; text: string }[],
  fix: (texts: string[]) => Promise<string[]>,
): Promise<string[]> {
  const texts = goals.map(g => g.text);
  const own = goals.flatMap((g, i) => (isCoreGoalId(g.id) ? [] : [i]));
  if (own.length === 0) return texts;
  const fixed = await fix(own.map(i => texts[i]));
  if (!Array.isArray(fixed) || fixed.length !== own.length) return texts;
  own.forEach((goalIndex, k) => { texts[goalIndex] = fixed[k]; });
  return texts;
}
