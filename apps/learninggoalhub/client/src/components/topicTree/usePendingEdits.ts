import { useMemo, useState } from "react";
import type { LearningGoal } from "../../api/client.ts";
import type { GoalChanges } from "./model.ts";

/**
 * Edits made in the grid show at once: they overlay the goals until the next fetch replaces the
 * list, which then carries them for real. A failed save takes its edit back off the overlay.
 */
export function usePendingEdits(
  goals: LearningGoal[],
  onUpdate: (goalId: number, changes: GoalChanges, onFailed: () => void) => void,
) {
  const [pending, setPending] = useState<{
    base: LearningGoal[];
    changes: Map<number, GoalChanges>;
  }>(() => ({ base: goals, changes: new Map() }));
  const effectiveGoals = useMemo(() => {
    if (pending.base !== goals || pending.changes.size === 0) return goals;
    return goals.map((goal) => {
      const changes = pending.changes.get(goal.id!);
      if (!changes) return goal;
      // A new wording invalidates the short label derived from the old one.
      return {
        ...goal,
        ...changes,
        ...(changes.text !== undefined ? { shortLabel: undefined } : {}),
      };
    });
  }, [goals, pending]);
  const updateGoal = (goalId: number, changes: GoalChanges) => {
    setPending((prev) => {
      const next = new Map(prev.base === goals ? prev.changes : undefined);
      next.set(goalId, { ...next.get(goalId), ...changes });
      return { base: goals, changes: next };
    });
    // A failed save leaves the list unchanged, so the edit would otherwise stay on screen as if saved.
    const rollback = () =>
      setPending((prev) => {
        const current = prev.changes.get(goalId);
        if (!current) return prev;
        const kept = Object.fromEntries(
          Object.entries(current).filter(([key]) => !(key in changes)),
        ) as GoalChanges;
        const next = new Map(prev.changes);
        if (Object.keys(kept).length === 0) next.delete(goalId);
        else next.set(goalId, kept);
        return { ...prev, changes: next };
      });
    onUpdate(goalId, changes, rollback);
  };
  return { effectiveGoals, updateGoal };
}
