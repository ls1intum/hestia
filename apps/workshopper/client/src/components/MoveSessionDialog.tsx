import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { listCourses, errorMessage, handleAuthError } from "@/lib/api";
import { PERSONAL_COURSE_LABEL, type SessionSummary } from "@/lib/workshop-generator";

/** How a course is called on screen: the personal catch-all course is "Einzelkram". */
export const courseLabel = (c: Pick<SessionSummary, "title" | "personal">) =>
  c.personal ? PERSONAL_COURSE_LABEL : c.title || "Untitled Course";

export const MOVE_CONFIRM_TEXT =
  "Moving the session removes its links to the learning goals of its current course. " +
  "The goals themselves stay in that course.";

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** The course the session is in now (not offered as a target). */
  currentCourseId?: string | null;
  /** A target chosen beforehand (e.g. by dropping the session on a course). */
  initialCourseId?: string | null;
  /** Moves the session; the dialog closes when it resolves and shows the error when it rejects. */
  onMove: (course: { id: string; personal: boolean; title: string }) => Promise<void>;
}

/** Asks for the target course and confirms that the session's links to the old course's goals are removed. */
export function MoveSessionDialog({ open, onOpenChange, currentCourseId, initialCourseId, onMove }: Props) {
  const [courses, setCourses] = useState<SessionSummary[] | null>(null);
  const [target, setTarget] = useState<string>("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    setError(null);
    setTarget(initialCourseId ?? "");
    listCourses()
      .then(all => setCourses(all.filter(c => c.role !== "VIEWER" && c.id !== currentCourseId)))
      .catch(e => { if (!handleAuthError(e)) setError(errorMessage(e, "The courses could not be loaded.")); });
  }, [open, currentCourseId, initialCourseId]);

  const chosen = courses?.find(c => c.id === target);

  const move = async () => {
    if (!chosen) return;
    setBusy(true);
    setError(null);
    try {
      await onMove({ id: chosen.id, personal: !!chosen.personal, title: courseLabel(chosen) });
      onOpenChange(false);
    } catch (e) {
      if (!handleAuthError(e)) setError(errorMessage(e, "The session could not be moved."));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Move to course</DialogTitle>
          <DialogDescription>{MOVE_CONFIRM_TEXT}</DialogDescription>
        </DialogHeader>
        <div className="py-2 space-y-2">
          <label htmlFor="move-target" className="text-sm font-body font-medium">Target course</label>
          <select
            id="move-target"
            className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm font-body"
            value={target}
            onChange={(e) => setTarget(e.target.value)}
            disabled={!courses || busy}
          >
            <option value="">Choose a course…</option>
            {(courses ?? []).map(c => <option key={c.id} value={c.id}>{courseLabel(c)}</option>)}
          </select>
          {error && <p className="text-sm text-destructive font-body">{error}</p>}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>Cancel</Button>
          <Button onClick={move} disabled={!chosen || busy}>Move session</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
