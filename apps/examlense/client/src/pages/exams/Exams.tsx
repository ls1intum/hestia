import { Link, useSearchParams } from "react-router-dom";
import { useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { retryParse } from "@/lib/parsing/retry-parse";
import { retryEvaluation } from "@/lib/exam/retry-evaluation";
import { FileUp, Loader2, PenLine, Search } from "lucide-react";
import { Input } from "@/components/ui/input";
import {
  listExams,
  cancelExam,
  deleteExam,
  duplicateExam as apiDuplicateExam,
  patchExam,
  type ExamListItem,
} from "@/lib/api/api-client";
import { subscribeExamsList } from "@/lib/api/sse";
import { useLghCourses } from "@/hooks/data/use-learning-goals";
import { WarningBanner } from "@/components/shared/exam-content/WarningBanner";
import { useToast } from "@/hooks/ui/use-toast";
import { useParseFailureToasts } from "@/hooks/ui/use-parse-failure-toasts";
import { ThemeToggle } from "@/components/shared/ThemeToggle";
import {
  StartExamDialog,
  type StartExamMode,
} from "@/pages/exams/start-exam/StartExamDialog";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { ExamsTable } from "@/pages/exams/components/ExamsTable";
import type { ExamRowHandlers } from "@/pages/exams/components/ExamTableRow";
import { DuplicateExamDialog } from "@/pages/exams/components/DuplicateExamDialog";
import wordmarkLight from "@/assets/hestia-wordmark-light.svg";
import wordmarkDark from "@/assets/hestia-wordmark-dark.svg";

const Exams = () => {
  const [searchParams, setSearchParams] = useSearchParams();
  const [dialogMode, setDialogMode] = useState<StartExamMode | null>(null);
  // Bumped on each open so the dialog remounts fresh (starts each wizard from a
  // clean slate); left unchanged on close so its exit animation still plays.
  const [dialogSeq, setDialogSeq] = useState(0);
  const openDialog = (mode: StartExamMode) => {
    setDialogSeq((s) => s + 1);
    setDialogMode(mode);
  };
  const [pendingDelete, setPendingDelete] = useState<ExamListItem | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [pendingCancel, setPendingCancel] = useState<ExamListItem | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const [duplicateTarget, setDuplicateTarget] = useState<ExamListItem | null>(null);
  const [search, setSearch] = useState("");
  const queryClient = useQueryClient();
  const { toast } = useToast();

  useEffect(() => {
    if (searchParams.get("new") === "1") {
      setDialogSeq((s) => s + 1);
      setDialogMode("pdf");
      const next = new URLSearchParams(searchParams);
      next.delete("new");
      setSearchParams(next, { replace: true });
    }
  }, [searchParams, setSearchParams]);

  const { data: exams, isLoading } = useQuery({
    queryKey: ["exams-list"],
    queryFn: () => listExams(),
  });

  // Slim "Parsing failed" toast when a background parse fails while on the dashboard.
  useParseFailureToasts(exams);

  // Proactive LGH-availability probe: reuses the course fetch (which also warms
  // the creation wizard's cache) and polls so the banner clears itself once LGH
  // is reachable again.
  const { isError: lghUnavailable } = useLghCourses({ refetchInterval: 60_000 });

  // Live updates so rows reflect parsing → draft/failed transitions and progress
  // advances without a manual refresh.
  useEffect(() => {
    return subscribeExamsList(() => {
      queryClient.invalidateQueries({ queryKey: ["exams-list"] });
    });
  }, [queryClient]);

  // Same `cancelExam` path as the editor's cancel, so the two can't diverge.
  const confirmCancel = async () => {
    if (!pendingCancel) return;
    setCancelling(true);
    try {
      await cancelExam(pendingCancel.id);
    } catch (error) {
      toast({ title: (error as Error).message, variant: "destructive" });
      setCancelling(false);
      return;
    }
    queryClient.invalidateQueries({ queryKey: ["exams-list"] });
    toast({ title: "Processing cancelled." });
    setCancelling(false);
    setPendingCancel(null);
  };

  const confirmDelete = async () => {
    if (!pendingDelete) return;
    setDeleting(true);
    const exam = pendingDelete;
    try {
      // The backend cleans up the stored PDF as part of the delete.
      await deleteExam(exam.id);
      queryClient.invalidateQueries({ queryKey: ["exams-list"] });
      toast({ title: "Exam deleted." });
      setPendingDelete(null);
    } catch (error) {
      toast({ title: (error as Error).message, variant: "destructive" });
    } finally {
      setDeleting(false);
    }
  };

  /**
   * Deep-copy an exam with the title + solver model chosen in the duplicate dialog.
   * The backend clones the exam row plus its sections, blocks, tasks, and figures
   * (remapping section/block ids and copying image files) in one transaction and
   * forces the copy to "draft".
   */
  const confirmDuplicate = async (payload: { title: string; solver_model: string }) => {
    if (!duplicateTarget) return;
    try {
      await apiDuplicateExam(duplicateTarget.id, payload);
      queryClient.invalidateQueries({ queryKey: ["exams-list"] });
      toast({ title: "Exam duplicated." });
      setDuplicateTarget(null);
    } catch (err) {
      console.error("duplicateExam failed", err);
      toast({ title: "Couldn't duplicate this exam.", variant: "destructive" });
    }
  };

  // Writes the new title into the cache before the patch lands, so the cell
  // doesn't flicker back to the old value; a failure rolls back by invalidating.
  const renameExam = async (exam: ExamListItem, title: string) => {
    const next = title.trim();
    if (!next || next === exam.title) return;
    queryClient.setQueryData<ExamListItem[]>(["exams-list"], (old) =>
      old?.map((e) => (e.id === exam.id ? { ...e, title: next } : e)),
    );
    try {
      await patchExam(exam.id, { title: next });
      queryClient.invalidateQueries({ queryKey: ["exams-list"] });
    } catch (err) {
      console.error("renameExam failed", err);
      queryClient.invalidateQueries({ queryKey: ["exams-list"] });
      toast({ title: "Couldn't rename this exam.", variant: "destructive" });
    }
  };

  const rowHandlers: ExamRowHandlers = {
    onRetry: (exam) => retryParse(exam, queryClient),
    onRetryEvaluation: (exam) => retryEvaluation(exam, queryClient),
    onCancel: setPendingCancel,
    onDuplicate: setDuplicateTarget,
    onDelete: setPendingDelete,
    onRename: renameExam,
  };

  return (
    <div className="min-h-dvh bg-hestia-bg text-hestia-text">
      <header className="border-b border-hestia-border bg-hestia-surface">
        <div className="mx-auto flex max-w-5xl items-center justify-between gap-3 px-6 py-3">
          <Link to="/exams" className="flex items-center gap-3" aria-label="ExamLense exams">
            <img src={wordmarkLight} alt="HESTIA" className="h-8 w-auto dark:hidden" />
            <img src={wordmarkDark} alt="HESTIA" className="hidden h-8 w-auto dark:block" />
            <span className="rounded-full bg-hestia-primary-muted px-2 py-0.5 text-xs font-semibold text-hestia-primary">
              ExamLense
            </span>
          </Link>
          <ThemeToggle />
        </div>
      </header>
      <main className="mx-auto w-full max-w-[1120px] px-hestia-5 py-hestia-10">
        {lghUnavailable && (
          <div className="mb-hestia-5">
            <WarningBanner text="LearningGoalHub is currently unavailable — learning-goal insights won't be generated or shown until it's reachable again. You can still create and grade exams." />
          </div>
        )}
        <div className="mb-hestia-6 flex items-end justify-between gap-hestia-3">
          <div>
            <h1 className="font-display text-3xl md:text-4xl font-bold text-hestia-text">
              Your Exams
            </h1>
          </div>
          <div className="flex items-center gap-hestia-2">
            {exams && exams.length > 0 && (
              <div className="relative w-48 md:w-56">
                <Search
                  size={15}
                  className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-hestia-text-muted"
                />
                <Input
                  type="search"
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  placeholder="Search by title…"
                  aria-label="Search exams by title"
                  className="pl-9"
                />
              </div>
            )}
            <button
              type="button"
              onClick={() => openDialog("manual")}
              className="inline-flex items-center gap-1 rounded-hestia-md border border-hestia-border bg-hestia-surface px-hestia-4 py-hestia-2 text-sm font-semibold text-hestia-text shadow-hestia-sm transition-colors hover:bg-hestia-primary-muted/30"
            >
              <PenLine size={14} /> Create From Scratch
            </button>
            <button
              type="button"
              onClick={() => openDialog("pdf")}
              className="inline-flex items-center gap-1 rounded-hestia-md bg-hestia-primary px-hestia-4 py-hestia-2 text-sm font-semibold text-primary-foreground shadow-hestia-sm transition-colors hover:bg-hestia-primary-hover"
            >
              <FileUp size={14} /> Import Exam
            </button>
          </div>
        </div>

        {isLoading ? (
          <p className="text-sm text-hestia-text-muted">…</p>
        ) : !exams || exams.length === 0 ? (
          <div className="hestia-card text-center">
            <p className="text-hestia-text-muted">No exams yet. Start by evaluating one.</p>
            <div className="mt-hestia-4 flex items-center justify-center gap-hestia-3">
              <button
                type="button"
                onClick={() => openDialog("pdf")}
                className="inline-flex items-center gap-1 text-hestia-primary hover:underline underline-offset-4"
              >
                <FileUp size={14} /> Import Exam
              </button>
              <button
                type="button"
                onClick={() => openDialog("manual")}
                className="inline-flex items-center gap-1 text-hestia-primary hover:underline underline-offset-4"
              >
                <PenLine size={14} /> Create From Scratch
              </button>
            </div>
          </div>
        ) : (
          <ExamsTable exams={exams} handlers={rowHandlers} query={search} />
        )}
      </main>
      <StartExamDialog key={dialogSeq} mode={dialogMode} onClose={() => setDialogMode(null)} />
      <DuplicateExamDialog
        key={duplicateTarget?.id}
        exam={duplicateTarget}
        onOpenChange={(open) => !open && setDuplicateTarget(null)}
        onConfirm={confirmDuplicate}
      />
      <AlertDialog open={!!pendingDelete} onOpenChange={(open) => !open && !deleting && setPendingDelete(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete exam?</AlertDialogTitle>
            <AlertDialogDescription>
              {`This will permanently delete "${pendingDelete?.title || "Untitled exam"}" and all its tasks. This action cannot be undone.`}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deleting}>Cancel</AlertDialogCancel>
            <AlertDialogAction
              disabled={deleting}
              onClick={(ev) => {
                ev.preventDefault();
                confirmDelete();
              }}
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
            >
              {deleting ? <Loader2 size={14} className="mr-2 animate-spin" /> : null}
              Delete exam
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
      <AlertDialog open={!!pendingCancel} onOpenChange={(open) => !open && !cancelling && setPendingCancel(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Cancel processing?</AlertDialogTitle>
            <AlertDialogDescription>
              {`This stops processing "${pendingCancel?.title || "Untitled exam"}" and discards any progress so far. You can retry or delete it afterward.`}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel
              disabled={cancelling}
              className="hover:bg-hestia-primary-muted hover:text-hestia-text"
            >
              Keep running
            </AlertDialogCancel>
            <AlertDialogAction
              disabled={cancelling}
              onClick={(ev) => {
                ev.preventDefault();
                confirmCancel();
              }}
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
            >
              {cancelling ? <Loader2 size={14} className="mr-2 animate-spin" /> : null}
              Cancel processing
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
};

export default Exams;
