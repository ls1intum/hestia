import { useEffect, useState } from "react";
import { Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { listCourseGoals, errorMessage, handleAuthError } from "@/lib/api";
import type { CourseGoal } from "@/lib/workshop-generator";

interface Props {
  /** The course whose goals are offered; null while the session has not been saved yet. */
  courseId: string | null;
  /** Goals already on the Goals step, by core id; they are not offered again. */
  takenIds: string[];
  /** The picked goals (read from core: text and levels come from there). */
  onAdd: (goals: CourseGoal[]) => void;
  disabled?: boolean;
}

/** Picks goals of the course (core) by id; there is nothing to import. */
export function CoreGoalPicker({ courseId, takenIds, onAdd, disabled }: Props) {
  const [goals, setGoals] = useState<CourseGoal[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());

  useEffect(() => {
    if (!courseId) return;
    let cancelled = false;
    setGoals(null);
    setError(null);
    listCourseGoals(courseId)
      .then(g => { if (!cancelled) setGoals(g); })
      .catch(e => {
        if (cancelled || handleAuthError(e)) return;
        setError(errorMessage(e, "The course's goals could not be loaded."));
      });
    return () => { cancelled = true; };
  }, [courseId]);

  const offered = (goals ?? []).filter(g => !takenIds.includes(g.id));

  const toggle = (id: string) =>
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  const add = () => {
    onAdd(offered.filter(g => selected.has(g.id)));
    setSelected(new Set());
  };

  if (!courseId) {
    return <p className="text-sm text-muted-foreground font-body">Save the session's settings first to pick goals from its course.</p>;
  }
  if (error) return <p className="text-sm text-destructive font-body">{error}</p>;
  if (!goals) {
    return <p className="flex items-center gap-2 text-sm text-muted-foreground font-body"><Loader2 className="h-4 w-4 animate-spin" /> Loading goals…</p>;
  }
  if (offered.length === 0) {
    return <p className="text-sm text-muted-foreground font-body">The course has no further learning goals to pick.</p>;
  }

  return (
    <div className="space-y-3">
      <ul className="space-y-2" aria-label="Learning goals of the course">
        {offered.map(g => (
          <li key={g.id}>
            <label className="flex items-start gap-2 text-sm font-body cursor-pointer">
              <input
                type="checkbox"
                className="mt-1"
                checked={selected.has(g.id)}
                onChange={() => toggle(g.id)}
                disabled={disabled}
              />
              <span>{g.text}</span>
            </label>
          </li>
        ))}
      </ul>
      <Button type="button" variant="outline" size="sm" onClick={add} disabled={disabled || selected.size === 0}>
        Add selected goals
      </Button>
    </div>
  );
}
