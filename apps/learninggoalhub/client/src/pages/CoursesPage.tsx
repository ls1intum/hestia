import { useEffect, useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../api/client.ts";
import type { CourseSummary, CurrentExtraction } from "../api/client.ts";
import CreateCourseDialog from "../components/CreateCourseDialog.tsx";
import CourseDocuments from "../components/CourseDocuments.tsx";
import RenameCourseDialog from "../components/RenameCourseDialog.tsx";
import ExtractionProgressModal from "../components/ExtractionProgressModal.tsx";
import Button from "../components/Button.tsx";
import { extractionPhaseLabel, extractionPhaseShortLabel } from "../lib/extraction.ts";

/** Courses per page. Small enough that the whole list stays on screen without scrolling. */
const PAGE_SIZE = 8;

/** Screen 1 — overview of every course with document/goal counts, status and creation date. */
export default function CoursesPage() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [openMenuId, setOpenMenuId] = useState<number | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [expandedIds, setExpandedIds] = useState<Set<number>>(new Set());
  const [reviewCourseId, setReviewCourseId] = useState<number | null>(null);
  const [renameCourse, setRenameCourse] = useState<CourseSummary | null>(null);
  const [page, setPage] = useState(0);

  // A row menu belongs to the row it was opened on, so it must not survive that row scrolling
  // out of the page.
  const goToPage = (next: number) => {
    setOpenMenuId(null);
    setPage(next);
  };

  const toggleExpanded = (id?: number) => {
    if (id == null) return;
    setExpandedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const deleteMutation = useMutation({
    mutationFn: async (id: number) => {
      const { error } = await api.DELETE("/api/courses/{id}", {
        params: { path: { id } },
      });
      if (error) throw new Error("Could not delete the course.");
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["courses"] }),
    onSettled: () => setOpenMenuId(null),
  });

  const coursesQuery = useQuery({
    queryKey: ["courses", page],
    queryFn: async () => {
      const { data, error } = await api.GET("/api/courses", {
        params: { query: { page, size: PAGE_SIZE } },
      });
      if (error || !data) {
        throw new Error("Could not load courses.");
      }
      return data;
    },
    // Hold the previous page's rows while the next one loads, so paging doesn't flash the
    // "Loading courses…" placeholder and collapse the card to a single line.
    placeholderData: keepPreviousData,
  });

  const currentExtractionQuery = useQuery<CurrentExtraction | null>({
    queryKey: ["extraction-current"],
    queryFn: async () => {
      const result = await api.GET("/api/extractions/current");
      if (result.response.status === 204) return null;
      if (result.error || !result.data) throw new Error("Could not load extraction progress.");
      return result.data;
    },
    refetchInterval: 1500,
  });

  const currentExtraction = currentExtractionQuery.data ?? null;
  const extractionRunning = currentExtraction?.status === "RUNNING";
  const previousExtraction = useRef<CurrentExtraction | null>(null);

  useEffect(() => {
    const previous = previousExtraction.current;
    if (
      previous != null
      && (currentExtraction == null
        || currentExtraction.status !== "RUNNING"
        || currentExtraction.courseId !== previous.courseId)
    ) {
      queryClient.invalidateQueries({ queryKey: ["courses"] });
    }
    previousExtraction.current = currentExtraction;
  }, [currentExtraction, queryClient]);

  const extractionStatusQuery = useQuery({
    queryKey: ["extract-status", reviewCourseId],
    queryFn: async () => {
      const result = await api.GET("/api/courses/{courseId}/extract/status", {
        params: { path: { courseId: reviewCourseId as number } },
      });
      return result.data ?? null;
    },
    enabled: reviewCourseId != null,
    refetchInterval: (query) => (query.state.data?.status === "RUNNING" ? 1000 : false),
  });

  // Watching a run to the finish hands straight over to the course itself, where the one-time skill
  // review is already due and opens on arrival. The list never shows the review of its own.
  const watchedRunSucceeded = extractionStatusQuery.data?.status === "SUCCEEDED";
  useEffect(() => {
    if (reviewCourseId == null || !watchedRunSucceeded) return;
    const courseId = reviewCourseId;
    setReviewCourseId(null);
    navigate(`/courses/${courseId}`);
  }, [reviewCourseId, watchedRunSucceeded, navigate]);

  const courses: CourseSummary[] = coursesQuery.data?.content ?? [];
  // Left out entirely until the list has loaded, so the heading never shows a placeholder "(0)".
  const courseCount = coursesQuery.data
    ? (coursesQuery.data.page?.totalElements ?? courses.length)
    : null;
  const totalPages = coursesQuery.data?.page?.totalPages ?? 1;

  // Deleting the last course on the final page would otherwise strand the list on a page that
  // no longer exists, so step back whenever the page count shrinks past the current index.
  useEffect(() => {
    if (totalPages > 0 && page > totalPages - 1) setPage(totalPages - 1);
  }, [page, totalPages]);

  // Same column width as the course page, so the two don't reflow against each other.
  return (
    <div className="mx-auto flex w-full max-w-5xl flex-col gap-6">
      <div className="flex items-center justify-between gap-4">
        <h1 className="text-3xl">
          Your Courses{" "}
          {courseCount != null && (
            <span className="text-hestia-text-muted">({courseCount})</span>
          )}
        </h1>
        <div className="flex flex-col items-end gap-1">
          <Button
            size="lg"
            onClick={() => setCreateOpen(true)}
            className="shrink-0"
            disabled={extractionRunning}
            title={extractionRunning ? "Extraction running — one at a time" : undefined}
          >
            + Add course
          </Button>
          {extractionRunning && (
            <span className="text-xs text-hestia-text-muted">Extraction running — one at a time</span>
          )}
        </div>
      </div>

      {deleteMutation.isError && (
        <p className="rounded-md border border-hestia-danger/40 bg-hestia-danger/10 px-4 py-2 text-sm text-hestia-danger">
          {(deleteMutation.error as Error).message}
        </p>
      )}

      <div className="overflow-hidden rounded-xl border border-hestia-border bg-hestia-surface shadow-sm">
        {coursesQuery.isLoading && (
          <p className="px-6 py-6 text-sm text-hestia-text-muted">Loading courses…</p>
        )}
        {coursesQuery.isError && (
          <p className="px-6 py-6 text-sm text-hestia-danger">
            {(coursesQuery.error as Error).message}
          </p>
        )}
        {!coursesQuery.isLoading && !coursesQuery.isError && courses.length === 0 && (
          <div className="px-6 py-12 text-center">
            <p className="text-sm text-hestia-text-muted">No courses yet.</p>
            <Button
              size="lg"
              onClick={() => setCreateOpen(true)}
              className="mt-3"
              disabled={extractionRunning}
              title={extractionRunning ? "Extraction running — one at a time" : undefined}
            >
              Create your first course
            </Button>
            {extractionRunning && (
              <p className="mt-2 text-xs text-hestia-text-muted">
                Extraction running — one at a time
              </p>
            )}
          </div>
        )}
        {courses.length > 0 && (
          <ul className="divide-y divide-hestia-border">
            <li className="flex items-center px-6 py-3 text-xs font-semibold uppercase tracking-wide text-hestia-text-muted">
              <span className="mr-2 w-6 shrink-0" aria-hidden />
              <div className="grid flex-1 grid-cols-[1fr_4.5rem_4.5rem_7rem_7rem_2.5rem] gap-4">
                <span>Course</span>
                <span className="text-right">Docs</span>
                <span className="text-right">Skills</span>
                <span>Status</span>
                <span className="text-right">Created</span>
                <span className="sr-only">Actions</span>
              </div>
            </li>
            {courses.map((course, index) => {
              const expanded = course.id != null && expandedIds.has(course.id);
              const isExtracting = extractionRunning && currentExtraction?.courseId === course.id;
              const extractionProblem = course.extractionStatus === "FAILED"
                || (course.extractionStatus === "RUNNING" && !isExtracting);
              return (
                <li
                  key={course.id}
                  className={`relative ${openMenuId === course.id ? "z-30" : ""}`}
                >
                  {/* The hover tint sits on the whole row, not on the link alone: otherwise it
                      stops short of the caret and the ⋮ and reads as a floating band. Same soft
                      primary wash the competency tree-grid uses for its rows. */}
                  <div className="relative flex items-center px-6 transition-colors hover:bg-[color-mix(in_srgb,var(--hestia-primary)_7%,transparent)]">
                    <button
                      type="button"
                      onClick={() => toggleExpanded(course.id)}
                      aria-expanded={expanded}
                      aria-controls={`course-docs-${course.id}`}
                      aria-label={`${expanded ? "Hide" : "Show"} documents for ${course.name}`}
                      className="mr-2 flex h-8 w-6 shrink-0 items-center justify-center rounded-md text-hestia-text-muted transition hover:bg-hestia-primary-muted hover:text-hestia-text"
                    >
                      <svg
                        viewBox="0 0 20 20"
                        fill="currentColor"
                        className={`h-4 w-4 transition-transform ${expanded ? "rotate-90" : ""}`}
                      >
                        <path d="M7 5l6 5-6 5z" />
                      </svg>
                    </button>
                    <Link
                      to={`/courses/${course.id}`}
                      onClick={(event) => {
                        if ((isExtracting || extractionProblem) && course.id != null) {
                          event.preventDefault();
                          setReviewCourseId(course.id);
                        }
                      }}
                      aria-haspopup={isExtracting || extractionProblem ? "dialog" : undefined}
                      className="grid flex-1 grid-cols-[1fr_4.5rem_4.5rem_7rem_7rem_2.5rem] items-center gap-4 py-4"
                    >
                      <span className="font-medium text-hestia-text">{course.name}</span>
                      <span className="text-right tabular-nums text-hestia-text-muted">
                        {course.documentCount ?? 0}
                      </span>
                      <span className="text-right tabular-nums text-hestia-text-muted">
                        {course.skillCount ?? 0}
                      </span>
                      <span>
                        {isExtracting ? (
                          <span className="flex min-w-0 flex-col gap-1">
                            <span
                              className="flex items-center justify-between gap-1 text-xs font-semibold text-hestia-primary"
                              title={extractionPhaseLabel(currentExtraction?.phase)}
                            >
                              <span className="truncate">
                                {extractionPhaseShortLabel(currentExtraction?.phase)}
                              </span>
                              <span className="shrink-0 tabular-nums">
                                {currentExtraction?.percent ?? 0}%
                              </span>
                            </span>
                            <span
                              className="h-1.5 w-full overflow-hidden rounded-full bg-hestia-primary-muted"
                              role="progressbar"
                              aria-label="Extraction progress"
                              aria-valuemin={0}
                              aria-valuemax={100}
                              aria-valuenow={currentExtraction?.percent ?? 0}
                            >
                              <span
                                className="block h-full rounded-full bg-hestia-primary transition-[width] duration-500"
                                style={{ width: `${currentExtraction?.percent ?? 0}%` }}
                              />
                            </span>
                          </span>
                        ) : (
                          <StatusBadge
                            documentCount={course.documentCount ?? 0}
                            goalCount={course.goalCount ?? 0}
                            extractionStatus={course.extractionStatus}
                          />
                        )}
                      </span>
                      <span className="text-right text-sm text-hestia-text-muted">
                        {formatDate(course.createdAt)}
                      </span>
                      <span aria-hidden />
                    </Link>
                    {course.id != null && (
                      <RowMenu
                        open={openMenuId === course.id}
                        onToggle={() =>
                          setOpenMenuId((id) =>
                            id === course.id ? null : (course.id as number),
                          )
                        }
                        onClose={() => setOpenMenuId(null)}
                        onRename={() => {
                          setOpenMenuId(null);
                          setRenameCourse(course);
                        }}
                        onDelete={() => deleteMutation.mutate(course.id as number)}
                        deleting={
                          deleteMutation.isPending &&
                          deleteMutation.variables === course.id
                        }
                        openUp={index === courses.length - 1}
                      />
                    )}
                  </div>
                  {expanded && course.id != null && (
                    <div id={`course-docs-${course.id}`}>
                      <CourseDocuments courseId={course.id} />
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
        )}
      </div>

      {totalPages > 1 && (
        <nav className="flex items-center justify-between gap-4" aria-label="Course list pages">
          <Button
            variant="neutral"
            size="md"
            onClick={() => goToPage(page - 1)}
            disabled={page === 0}
          >
            ← Previous
          </Button>
          <ul className="flex items-center gap-1">
            {pageItems(page, totalPages).map((item, index) => (
              <li key={item ?? `gap-${index}`}>
                {item == null ? (
                  <span className="px-1 text-sm text-hestia-text-muted" aria-hidden>
                    …
                  </span>
                ) : (
                  <Button
                    variant={item === page ? "primary" : "ghost"}
                    size="sm"
                    className="min-w-8 tabular-nums"
                    aria-label={`Page ${item + 1}`}
                    aria-current={item === page ? "page" : undefined}
                    onClick={() => goToPage(item)}
                  >
                    {item + 1}
                  </Button>
                )}
              </li>
            ))}
          </ul>
          <Button
            variant="neutral"
            size="md"
            onClick={() => goToPage(page + 1)}
            disabled={page >= totalPages - 1}
          >
            Next →
          </Button>
        </nav>
      )}

      {createOpen && <CreateCourseDialog onClose={() => setCreateOpen(false)} />}
      {renameCourse && (
        <RenameCourseDialog course={renameCourse} onClose={() => setRenameCourse(null)} />
      )}
      {reviewCourseId != null && !watchedRunSucceeded && (
        <ExtractionProgressModal
          open
          status={extractionStatusQuery.data ?? undefined}
          error={
            extractionStatusQuery.data?.status === "FAILED"
              ? extractionStatusQuery.data.error ?? "Extraction failed."
              : null
          }
          courseId={reviewCourseId}
          onClose={() => setReviewCourseId(null)}
        />
      )}
    </div>
  );
}

/** Up to this many pages every number is listed; beyond it the middle is elided. */
const MAX_PAGE_BUTTONS = 7;

/**
 * The page numbers to offer: the first, the last, and a window around the current one, with
 * `null` marking each elided stretch. Keeps the pager a fixed width however many pages there are.
 */
function pageItems(current: number, total: number): (number | null)[] {
  if (total <= MAX_PAGE_BUTTONS) {
    return Array.from({ length: total }, (_, index) => index);
  }
  const wanted = [0, current - 1, current, current + 1, total - 1]
    .filter((page) => page >= 0 && page < total)
    .sort((a, b) => a - b);
  const items: (number | null)[] = [];
  for (const page of wanted) {
    const previous = items[items.length - 1];
    if (typeof previous === "number") {
      if (page === previous) continue;
      if (page - previous > 1) items.push(null);
    }
    items.push(page);
  }
  return items;
}

/**
 * Per-row kebab (⋮) menu: renaming the course, and deleting it behind an inline confirm step so a
 * stray click can't drop a course.
 */
function RowMenu({
  open,
  onToggle,
  onClose,
  onRename,
  onDelete,
  deleting,
  openUp,
}: {
  open: boolean;
  onToggle: () => void;
  onClose: () => void;
  onRename: () => void;
  onDelete: () => void;
  deleting: boolean;
  openUp: boolean;
}) {
  const [confirm, setConfirm] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  // Reset the confirm step whenever the menu closes, so it reopens on the safe first step.
  if (!open && confirm) setConfirm(false);

  // Close on any click outside the menu (including the page edge) or on Escape.
  useEffect(() => {
    if (!open) return;
    const onPointerDown = (e: MouseEvent) => {
      if (!ref.current?.contains(e.target as Node)) onClose();
    };
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("mousedown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("mousedown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [open, onClose]);

  return (
    <div ref={ref} className="absolute right-3 top-1/2 -translate-y-1/2">
      <button
        type="button"
        onClick={onToggle}
        aria-label="Course actions"
        aria-expanded={open}
        className="flex h-8 w-8 items-center justify-center rounded-md text-hestia-text-muted transition hover:bg-hestia-primary-muted hover:text-hestia-text"
      >
        <svg viewBox="0 0 20 20" fill="currentColor" className="h-5 w-5">
          <circle cx="10" cy="4" r="1.5" />
          <circle cx="10" cy="10" r="1.5" />
          <circle cx="10" cy="16" r="1.5" />
        </svg>
      </button>
      {open && (
        <div
          className={`absolute right-0 z-20 w-44 overflow-hidden rounded-md border border-hestia-border bg-hestia-surface py-1 shadow-lg ${
            openUp ? "bottom-full mb-1" : "top-full mt-1"
          }`}
        >
          {confirm ? (
            <div className="px-3 py-2">
              <p className="text-xs text-hestia-text-muted">Delete this course?</p>
              <div className="mt-2 flex gap-2">
                <Button
                  variant="danger"
                  size="sm"
                  onClick={onDelete}
                  disabled={deleting}
                >
                  {deleting ? "Deleting…" : "Delete"}
                </Button>
                <Button
                  variant="neutral"
                  size="sm"
                  onClick={onClose}
                  disabled={deleting}
                >
                  Cancel
                </Button>
              </div>
            </div>
          ) : (
            <>
              <button
                type="button"
                onClick={onRename}
                className="block w-full px-3 py-2 text-left text-sm text-hestia-text transition hover:bg-hestia-primary-muted"
              >
                Rename
              </button>
              <button
                type="button"
                onClick={() => setConfirm(true)}
                className="block w-full px-3 py-2 text-left text-sm text-hestia-danger transition hover:bg-hestia-primary-muted"
              >
                Delete
              </button>
            </>
          )}
        </div>
      )}
    </div>
  );
}

function StatusBadge({
  documentCount,
  goalCount,
  extractionStatus,
}: {
  documentCount: number;
  goalCount: number;
  extractionStatus?: CourseSummary["extractionStatus"];
}) {
  let label: string;
  let className: string;
  if (extractionStatus === "FAILED" || extractionStatus === "RUNNING") {
    label = extractionStatus === "FAILED" ? "Failed" : "Interrupted";
    className = "bg-hestia-danger/10 text-hestia-danger";
  } else if (goalCount > 0) {
    label = "Analyzed";
    className = "bg-hestia-primary-muted text-hestia-primary";
  } else if (documentCount > 0) {
    label = "Ready";
    className = "bg-[color-mix(in_srgb,var(--hestia-accent)_15%,transparent)] text-hestia-accent";
  } else {
    label = "Empty";
    className = "bg-hestia-bg text-hestia-text-muted";
  }
  return (
    <span
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-semibold ${className}`}
    >
      {label}
    </span>
  );
}

function formatDate(iso?: string): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleDateString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
  });
}
