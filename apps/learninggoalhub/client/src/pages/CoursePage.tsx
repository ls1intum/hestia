import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, API_PREFIX } from "../api/client.ts";
import type { ExtractionStatus, LearningGoal } from "../api/client.ts";
import CompetencyTree from "../components/CompetencyTree.tsx";
import ConfirmDialog from "../components/ConfirmDialog.tsx";
import ExtractionProgressModal from "../components/ExtractionProgressModal.tsx";
import Button from "../components/Button.tsx";
import { fetchAllGoals } from "../lib/fetchGoals.ts";

export default function CoursePage() {
  const { courseId: courseIdParam } = useParams();
  const courseId = Number(courseIdParam);
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [confirmDelete, setConfirmDelete] = useState(false);
  const [goalToDelete, setGoalToDelete] = useState<LearningGoal | null>(null);
  const [extractionModalOpen, setExtractionModalOpen] = useState(false);

  const courseQuery = useQuery({
    queryKey: ["course", courseId],
    queryFn: async () => {
      const { data, error } = await api.GET("/api/courses/{id}", {
        params: { path: { id: courseId } },
      });
      if (error || !data) throw new Error("Could not load the course.");
      return data;
    },
  });

  const goalsQuery = useQuery({
    queryKey: ["goals", courseId],
    queryFn: () => fetchAllGoals(courseId),
  });

  // One-time skill review: shown the first time a course is opened after its extraction produced
  // skills, then recorded server-side so it never reappears. Dismissing it is what marks it done,
  // so closing the tab instead leaves it due — the instructor cannot silently miss it.
  const course = courseQuery.data;
  const extractionProblem = course?.extractionStatus === "FAILED"
    || course?.extractionStatus === "RUNNING";
  const extractionStatusQuery = useQuery<ExtractionStatus | null>({
    queryKey: ["extract-status", courseId],
    queryFn: async () => {
      const result = await api.GET("/api/courses/{courseId}/extract/status", {
        params: { path: { courseId } },
      });
      return result.data ?? null;
    },
    enabled: extractionModalOpen,
    refetchInterval: (query) => (query.state.data?.status === "RUNNING" ? 1000 : false),
  });
  useEffect(() => {
    if (extractionStatusQuery.data?.status !== "SUCCEEDED") return;
    queryClient.invalidateQueries({ queryKey: ["course", courseId] });
    queryClient.invalidateQueries({ queryKey: ["courses"] });
    queryClient.invalidateQueries({ queryKey: ["goals", courseId] });
  }, [courseId, extractionStatusQuery.data?.status, queryClient]);
  const reviewDue = course != null
    && (course.skillCount ?? 0) > 0
    && course.skillsReviewedAt == null;
  const [reviewDismissed, setReviewDismissed] = useState(false);

  const markReviewed = useMutation({
    mutationFn: async () => {
      await api.POST("/api/courses/{id}/skills-reviewed", {
        params: { path: { id: courseId } },
      });
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: ["course", courseId] }),
  });

  const deleteMutation = useMutation({
    mutationFn: async () => {
      const { error } = await api.DELETE("/api/courses/{id}", {
        params: { path: { id: courseId } },
      });
      if (error) throw new Error("Could not delete the course.");
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["courses"] });
      navigate("/");
    },
  });


  const updateGoalMutation = useMutation({
    mutationFn: async (vars: {
      goalId: number;
      text?: string;
      status?: NonNullable<LearningGoal["status"]>;
      bloomLevel?: LearningGoal["bloomLevel"];
      soloLevel?: LearningGoal["soloLevel"];
    }) => {
      const { goalId, ...body } = vars;
      const { error } = await api.PATCH(
        "/api/courses/{courseId}/learning-goals/{goalId}",
        {
          params: { path: { courseId, goalId } },
          body,
        },
      );
      if (error) throw new Error("Could not update the learning goal.");
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["goals", courseId] });
    },
  });

  const deleteGoalMutation = useMutation({
    mutationFn: async (goalId: number) => {
      const { error } = await api.DELETE(
        "/api/courses/{courseId}/learning-goals/{goalId}",
        { params: { path: { courseId, goalId } } },
      );
      if (error) throw new Error("Could not delete the learning goal.");
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["goals", courseId] });
      // goalCount on the course header and the course list comes from the server.
      await queryClient.invalidateQueries({ queryKey: ["course", courseId] });
      await queryClient.invalidateQueries({ queryKey: ["courses"] });
      setGoalToDelete(null);
    },
  });

  // In-modal edits (goal text, Bloom/SOLO dot clicks) go straight through the update mutation.
  const updateGoal = (
    goalId: number,
    changes: {
      text?: string;
      bloomLevel?: LearningGoal["bloomLevel"];
      soloLevel?: LearningGoal["soloLevel"];
    },
  ) => updateGoalMutation.mutate({ goalId, ...changes });

  // Gap-analysis goals are hidden for now (backlog: dedicated gap review);
  // the pipeline still synthesises and stores them, only the client filters.
  const goals: LearningGoal[] = useMemo(
    () => (goalsQuery.data ?? []).filter((g) => g.origin !== "GAP"),
    [goalsQuery.data],
  );

  const courseName = courseQuery.data?.name ?? `Course #${courseId}`;
  return (
    <div className="flex flex-col gap-6">
      <div className="mx-auto flex w-full max-w-5xl flex-col gap-6">
        {/* Header — course identity stays separate from the view switch below. */}
        <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
          <h1 className="text-2xl">{courseName}</h1>
          <CourseMenu
            exportHref={`${API_PREFIX}/api/courses/${courseId}/learning-goals/export.csv`}
            onDelete={() => setConfirmDelete(true)}
          />
        </div>
        {deleteMutation.isError && (
          <p className="text-sm text-hestia-danger">
            {(deleteMutation.error as Error).message}
          </p>
        )}
        {extractionProblem && (
          <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-hestia-danger/40 bg-hestia-danger/10 px-4 py-3 text-sm text-hestia-text">
            <span>
              The latest extraction did not finish, so this course may not contain a complete skill tree.
            </span>
            <Button size="sm" onClick={() => setExtractionModalOpen(true)}>
              View error and retry
            </Button>
          </div>
        )}

        {extractionModalOpen && (
          <ExtractionProgressModal
            open
            status={extractionStatusQuery.data ?? undefined}
            error={
              extractionStatusQuery.data?.status === "FAILED"
                ? extractionStatusQuery.data.error ?? "Extraction failed."
                : null
            }
            courseId={courseId}
            onClose={() => setExtractionModalOpen(false)}
          />
        )}
        {/* In-modal edits (text, Bloom/SOLO dots) save without a dialog of their own, so their
            failures surface here. */}
        {updateGoalMutation.isError && (
          <p className="text-sm text-hestia-danger">
            {(updateGoalMutation.error as Error).message}
          </p>
        )}

        {confirmDelete && (
          <ConfirmDialog
            title="Delete course?"
            message={`This permanently removes "${courseName}" and all of its learning goals. This cannot be undone.`}
            confirmLabel={
              deleteMutation.isPending ? "Deleting…" : "Delete course"
            }
            busy={deleteMutation.isPending}
            onConfirm={() => deleteMutation.mutate()}
            onCancel={() => setConfirmDelete(false)}
          />
        )}

        {goalToDelete && (
          <ConfirmDialog
            title="Delete learning goal?"
            message={`This permanently removes "${goalToDelete.text}" and everything beneath it in the competency tree, together with their sources and relationships. This cannot be undone.`}
            confirmLabel={
              deleteGoalMutation.isPending ? "Deleting…" : "Delete goal"
            }
            busy={deleteGoalMutation.isPending}
            error={
              deleteGoalMutation.isError
                ? (deleteGoalMutation.error as Error).message
                : undefined
            }
            onConfirm={() => deleteGoalMutation.mutate(goalToDelete.id!)}
            onCancel={() => {
              deleteGoalMutation.reset();
              setGoalToDelete(null);
            }}
          />
        )}

        {/* States */}
        {goalsQuery.isLoading && (
          <p className="text-sm text-hestia-text-muted">Loading…</p>
        )}
        {goalsQuery.isError && (
          <p className="text-sm text-hestia-danger">
            {(goalsQuery.error as Error).message}
          </p>
        )}
        {!goalsQuery.isLoading && goals.length === 0 && (
          <p className="rounded-xl border border-dashed border-hestia-border p-8 text-center text-sm text-hestia-text-muted">
            No learning goals yet for this course.
          </p>
        )}
      </div>

      {/* The competency grid: topics as a filterable Excel-style list, each opening its map
          inline beneath its row. */}
      {goals.length > 0 && (
        <div className="w-full">
          <CompetencyTree
            courseId={courseId}
            goals={goals}
            onUpdate={updateGoal}
            onDelete={setGoalToDelete}
          />
        </div>
      )}

      {reviewDue && !reviewDismissed && (
        <ExtractionProgressModal
          open
          reviewOnly
          courseId={courseId}
          onClose={() => {
            setReviewDismissed(true);
            markReviewed.mutate();
          }}
        />
      )}
    </div>
  );
}

/** Kebab (⋮) overflow menu holding the course's Documents, Export and Delete actions. */
function CourseMenu({
  exportHref,
  onDelete,
}: {
  exportHref: string;
  onDelete: () => void;
}) {
  const [open, setOpen] = useState(false);
  const ref = useDismissable<HTMLDivElement>(open, () => setOpen(false));

  return (
    <div ref={ref} className="relative">
      <Button
        variant="neutral"
        size="icon-md"
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label="More actions"
      >
        <svg viewBox="0 0 20 20" fill="currentColor" className="h-5 w-5">
          <circle cx="10" cy="4" r="1.6" />
          <circle cx="10" cy="10" r="1.6" />
          <circle cx="10" cy="16" r="1.6" />
        </svg>
      </Button>
      {open && (
        <div
          role="menu"
          className="absolute right-0 top-full z-20 mt-1 w-44 overflow-hidden rounded-md border border-hestia-border bg-hestia-surface py-1 shadow-lg"
        >
          <a
            href={exportHref}
            role="menuitem"
            onClick={() => setOpen(false)}
            className="flex items-center gap-2 px-3 py-2 text-sm text-hestia-text transition hover:bg-hestia-bg"
          >
            <svg
              viewBox="0 0 20 20"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
              className="h-4 w-4 text-hestia-text-muted"
            >
              <path d="M10 3v9m0 0l-3-3m3 3l3-3" />
              <path d="M4 15v2h12v-2" />
            </svg>
            Export CSV
          </a>
          <button
            type="button"
            role="menuitem"
            onClick={() => {
              setOpen(false);
              onDelete();
            }}
            className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-hestia-danger transition hover:bg-hestia-danger hover:text-hestia-on-danger"
          >
            <svg
              viewBox="0 0 20 20"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
              className="h-4 w-4"
            >
              <path d="M4 6h12M8 6V4h4v2M6 6l1 10h6l1-10" />
            </svg>
            Delete course
          </button>
        </div>
      )}
    </div>
  );
}

/**
 * Closes a popover when the user clicks outside the returned ref's element or presses Escape.
 * Returns a ref to attach to the popover's root.
 */
function useDismissable<T extends HTMLElement>(
  open: boolean,
  onClose: () => void,
) {
  const ref = useRef<T>(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;
  useEffect(() => {
    if (!open) return;
    const onPointer = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node))
        onCloseRef.current();
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onCloseRef.current();
    };
    window.addEventListener("mousedown", onPointer);
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("mousedown", onPointer);
      window.removeEventListener("keydown", onKey);
    };
  }, [open]);
  return ref;
}
