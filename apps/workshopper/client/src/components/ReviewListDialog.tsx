import { useEffect, useState } from "react";
import { Loader2, Check } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from "@/components/ui/dialog";
import { ApiError, confirmReview, listReviews, errorMessage } from "@/lib/api";
import { reviewReasonLabel, reviewTargetName } from "@/lib/reviews";
import type { AlignmentReview } from "@/lib/workshop-generator";
import { useToast } from "@/hooks/use-toast";

interface Props {
  courseId: string | null;
  courseTitle?: string;
  onOpenChange: (open: boolean) => void;
  /** Called after the list changed (a link was confirmed), so counts can be reloaded. */
  onChanged: () => void;
}

/** The links of a course that need a look because their learning goal changed or was removed. */
export function ReviewListDialog({ courseId, courseTitle, onOpenChange, onChanged }: Props) {
  const { toast } = useToast();
  const [reviews, setReviews] = useState<AlignmentReview[] | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const load = (id: string) =>
    listReviews(id)
      .then(setReviews)
      .catch(err => {
        setReviews([]);
        toast({ title: "Could not load the review list", description: errorMessage(err, "Please try again."), variant: "destructive" });
      });

  useEffect(() => {
    if (!courseId) return;
    setReviews(null);
    load(courseId);
  }, [courseId]); // eslint-disable-line react-hooks/exhaustive-deps

  const confirm = async (review: AlignmentReview) => {
    if (!courseId) return;
    setBusy(review.linkId);
    try {
      await confirmReview(courseId, review.linkId, review.goalVersion);
      setReviews(prev => (prev ?? []).filter(r => r.linkId !== review.linkId));
      onChanged();
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        // The goal changed again while the list was open: show its current wording first
        toast({ title: "The learning goal changed again", description: "The list was reloaded. Check the new wording." });
        await load(courseId);
        return;
      }
      toast({ title: "Could not confirm", description: errorMessage(err, "Please try again."), variant: "destructive" });
    } finally {
      setBusy(null);
    }
  };

  return (
    <Dialog open={courseId !== null} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl max-h-[85vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>To review{courseTitle ? ` - ${courseTitle}` : ""}</DialogTitle>
          <DialogDescription>
            A learning goal changed or was removed after it was linked. Check the linked item and confirm it.
          </DialogDescription>
        </DialogHeader>
        {reviews === null ? (
          <div className="flex items-center gap-2 py-6 text-muted-foreground"><Loader2 className="h-4 w-4 animate-spin" /> Loading…</div>
        ) : reviews.length === 0 ? (
          <p className="py-6 text-sm text-muted-foreground">Nothing to review.</p>
        ) : (
          <ul className="space-y-3">
            {reviews.map(r => (
              <li key={r.linkId} className="rounded-lg border border-border/60 p-3 space-y-2">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="font-display font-semibold text-foreground truncate">{reviewTargetName(r)}</p>
                    <p className="text-xs text-amber-700 dark:text-amber-400 font-medium">{reviewReasonLabel(r.reason)}</p>
                  </div>
                  <Button size="sm" variant="outline" className="gap-1.5 shrink-0" disabled={busy === r.linkId} onClick={() => confirm(r)}>
                    {busy === r.linkId ? <Loader2 className="h-3 w-3 animate-spin" /> : <Check className="h-3 w-3" />}
                    Reviewed
                  </Button>
                </div>
                <div className="text-sm font-body space-y-1">
                  {r.goalRemoved || r.reason === "GOAL_REMOVED" ? (
                    <p className="text-muted-foreground"><span className="font-medium">Goal was:</span> {r.seenText || r.currentText}</p>
                  ) : (
                    <>
                      {r.seenText && r.seenText !== r.currentText && (
                        <p className="text-muted-foreground line-through decoration-muted-foreground/40">{r.seenText}</p>
                      )}
                      <p className="text-foreground">{r.currentText}</p>
                    </>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </DialogContent>
    </Dialog>
  );
}
