import { Link, useSearchParams } from "react-router-dom";
import { useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { retryParse } from "@/lib/parsing/retry-parse";
import { retryEvaluation } from "@/lib/exam/retry-evaluation";
import { FileUp, Loader2, PenLine, Search } from "lucide-react";
import { Input } from "@/components/ui/input";
import {
  listExams,
  patchExam,
  deleteExam,
  duplicateExam as apiDuplicateExam,
  type ExamListItem,
} from "@/lib/api/api-client";
import { subscribeExamsList } from "@/lib/api/sse";
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
import wordmarkLight from "@/assets/hestia-wordmark-light.svg";
import wordmarkDark from "@/assets/hestia-wordmark-dark.svg";

const Exams = () => {
  const [searchParams, setSearchParams] = useSearchParams();
  const [dialogMode, setDialogMode] = useState<StartExamMode | null>(null);
  const [pendingDelete, setPendingDelete] = useState<ExamListItem | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [pendingCancel, setPendingCancel] = useState<ExamListItem | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const [search, setSearch] = useState("");
  const queryClient = useQueryClient();
  const { toast } = useToast();

  useEffect(() => {
    if (searchParams.get("new") === "1") {
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

  // Live updates so rows reflect parsing → draft/failed transitions and progress
  // advances without a manual refresh.
  useEffect(() => {
    return subscribeExamsList(() => {
      queryClient.invalidateQueries({ queryKey: ["exams-list"] });
    });
  }, [queryClient]);

  /**
   * Reset an exam that's stuck in parsing/evaluating so the user can recover it.
   * Note: this only resets the DB row — it cannot kill a background parse/solve
   * job that's still alive. For genuinely stuck (errored) jobs that's fine; a
   * zombie job that later finishes would simply rewrite the status via realtime.
   */
  const confirmCancel = async () => {
    if (!pendingCancel) return;
    setCancelling(true);
    const exam = pendingCancel;
    const update =
      exam.status === "parsing"
        ? { status: "failed", parse_error: "Parsing cancelled." }
        : { status: "ready" };
    try {
      await patchExam(exam.id, update);
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
    } catch (error) {
      toast({ title: (error as Error).message, variant: "destructive" });
      setDeleting(false);
      return;
    }
    queryClient.invalidateQueries({ queryKey: ["exams-list"] });
    toast({ title: "Exam deleted." });
    setDeleting(false);
    setPendingDelete(null);
  };

  /**
   * Deep-copy an exam. The backend's duplicate endpoint clones the exam row plus
   * its sections, blocks, tasks, and figures (remapping section/block ids and
   * copying image files) in one transaction and forces the copy to "draft".
   */
  const duplicateExam = async (exam: ExamListItem) => {
    try {
      await apiDuplicateExam(exam.id);
      queryClient.invalidateQueries({ queryKey: ["exams-list"] });
      toast({ title: "Exam duplicated." });
    } catch (err) {
      console.error("duplicateExam failed", err);
      toast({ title: "Couldn't duplicate this exam.", variant: "destructive" });
    }
  };

  const rowHandlers: ExamRowHandlers = {
    onRetry: (exam) => retryParse(exam, queryClient),
    onRetryEvaluation: (exam) => retryEvaluation(exam, queryClient),
    onCancel: setPendingCancel,
    onDuplicate: duplicateExam,
    onDelete: setPendingDelete,
  };

  return (
    <div className="min-h-screen bg-hestia-bg text-hestia-text">
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
              onClick={() => setDialogMode("manual")}
              className="inline-flex items-center gap-1 rounded-hestia-md border border-hestia-border bg-hestia-surface px-hestia-4 py-hestia-2 text-sm font-semibold text-hestia-text shadow-hestia-sm transition-colors hover:bg-hestia-primary-muted/30"
            >
              <PenLine size={14} /> Create From Scratch
            </button>
            <button
              type="button"
              onClick={() => setDialogMode("pdf")}
              className="inline-flex items-center gap-1 rounded-hestia-md bg-hestia-primary px-hestia-4 py-hestia-2 text-sm font-semibold text-white shadow-hestia-sm transition-colors hover:bg-hestia-primary-hover"
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
                onClick={() => setDialogMode("pdf")}
                className="inline-flex items-center gap-1 text-hestia-primary hover:underline underline-offset-4"
              >
                <FileUp size={14} /> Import Exam
              </button>
              <button
                type="button"
                onClick={() => setDialogMode("manual")}
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
      <StartExamDialog mode={dialogMode} onClose={() => setDialogMode(null)} />
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
              {`This stops processing "${pendingCancel?.title || "Untitled exam"}" and resets it so you can retry or delete it. Any work in progress will be discarded.`}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={cancelling}>Keep running</AlertDialogCancel>
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
