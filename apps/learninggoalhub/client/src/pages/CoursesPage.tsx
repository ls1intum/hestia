import { useEffect, useLayoutEffect, useRef, useState } from "react";
import type { RefObject } from "react";
import { Link, useNavigate } from "react-router-dom";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../api/client.ts";
import type { CourseSummary, CurrentExtraction } from "../api/client.ts";
import CreateCourseDialog from "../components/CreateCourseDialog.tsx";
import CourseDocuments from "../components/CourseDocuments.tsx";
import RenameCourseDialog from "../components/RenameCourseDialog.tsx";
import ExtractionProgressModal from "../components/ExtractionProgressModal.tsx";
import Button from "../components/Button.tsx";
import ConfirmDialog from "../components/ConfirmDialog.tsx";
import { RowAction } from "../components/GoalInlineEditing.tsx";
import { extractionPhaseLabel, extractionPhaseShortLabel } from "../lib/extraction.ts";

/**
 * Heights the page size is worked out from: a course row and the column-name row (each with its
 * divider), and what sits below the list — the card's edge, the pager and the page's bottom padding.
 */
const ROW_HEIGHT = 49;
const HEADER_ROW_HEIGHT = 33;
const BELOW_LIST = 96;
const MIN_PAGE_SIZE = 5;
const MAX_PAGE_SIZE = 50;

/** Screen 1 — overview of every course with document/goal counts, status and creation date. */
export default function CoursesPage() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [createOpen, setCreateOpen] = useState(false);
  const [expandedIds, setExpandedIds] = useState<Set<number>>(new Set());
  const [reviewCourseId, setReviewCourseId] = useState<number | null>(null);
  const [renameCourse, setRenameCourse] = useState<CourseSummary | null>(null);
  const [courseToDelete, setCourseToDelete] = useState<CourseSummary | null>(null);
  const [page, setPage] = useState(0);
  const listRef = useRef<HTMLDivElement>(null);
  const pageSize = useFittingPageSize(listRef);

  // A new page size keeps the first course on screen in view rather than jumping back to page 1.
  const previousPageSize = useRef(pageSize);
  if (pageSize !== previousPageSize.current) {
    if (previousPageSize.current != null && pageSize != null) {
      setPage(Math.floor((page * previousPageSize.current) / pageSize));
    }
    previousPageSize.current = pageSize;
  }


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
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["courses"] });
      setCourseToDelete(null);
    },
  });

  const coursesQuery = useQuery({
    queryKey: ["courses", page, pageSize],
    queryFn: async () => {
      const { data, error } = await api.GET("/api/courses", {
        params: { query: { page, size: pageSize as number } },
      });
      if (error || !data) {
        throw new Error("Could not load courses.");
      }
      return data;
    },
    // Hold the previous page's rows while the next one loads, so paging doesn't flash the
    // "Loading courses…" placeholder and collapse the card to a single line.
    placeholderData: keepPreviousData,
    // Held back until the window has been measured, so the first request asks for the right size.
    enabled: pageSize != null,
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
        <h1 className="text-2xl">
          Your Courses{" "}
          {courseCount != null && (
            <span className="text-hestia-text-muted">({courseCount})</span>
          )}
        </h1>
        <div className="flex flex-col items-end gap-1">
          <Button
            onClick={() => setCreateOpen(true)}
            className="h-9 shrink-0"
            disabled={extractionRunning}
            title={extractionRunning ? "Extraction running — one at a time" : undefined}
          >
            <PlusIcon />
            Add course
          </Button>
          {extractionRunning && (
            <span className="text-xs text-hestia-text-muted">Extraction running — one at a time</span>
          )}
        </div>
      </div>

      <div ref={listRef} className="overflow-hidden rounded-xl border border-hestia-border bg-hestia-surface shadow-sm">
        {coursesQuery.isLoading && (
          <p className="px-6 py-6 text-sm text-hestia-text-muted">Loading courses…</p>
        )}
        {coursesQuery.isError && (
          <p className="px-6 py-6 text-sm text-hestia-danger">
            {(coursesQuery.error as Error).message}
          </p>
        )}
        {coursesQuery.isSuccess && courses.length === 0 && (
          <div className="px-6 py-12 text-center">
            <p className="text-sm text-hestia-text-muted">No courses yet.</p>
            <Button
              onClick={() => setCreateOpen(true)}
              className="mt-3 h-9"
              disabled={extractionRunning}
              title={extractionRunning ? "Extraction running — one at a time" : undefined}
            >
              <PlusIcon />
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
          <ul>
            <li className="flex items-center border-b border-hestia-border bg-[color-mix(in_srgb,var(--hestia-text)_4%,var(--hestia-surface))] px-4 py-2 text-xs font-semibold text-hestia-text-muted">
              <span className="mr-2 w-5 shrink-0" aria-hidden />
              <div className="grid flex-1 grid-cols-[1fr_4.5rem_4.5rem_7rem_7rem_3rem] gap-4">
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
                  className={`relative border-b border-hestia-border/60 last:border-b-0 ${
                    index % 2 === 1 ? "bg-hestia-text/3" : ""
                  }`}
                >
                  {/* The hover tint sits on the whole row, not on the link alone: otherwise it
                      stops short of the caret and the ⋮ and reads as a floating band. Same soft
                      primary wash the competency tree-grid uses for its rows. */}
                  <div className="group relative flex h-12 items-center px-4 transition-colors hover:bg-[color-mix(in_srgb,var(--hestia-primary)_7%,transparent)]">
                    <button
                      type="button"
                      onClick={() => toggleExpanded(course.id)}
                      aria-expanded={expanded}
                      aria-controls={`course-docs-${course.id}`}
                      aria-label={`${expanded ? "Hide" : "Show"} documents for ${course.name}`}
                      className="mr-2 flex h-5 w-5 shrink-0 items-center justify-center rounded-sm text-hestia-text-muted transition hover:bg-hestia-primary-muted hover:text-hestia-text"
                    >
                      <svg
                        viewBox="0 0 20 20"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="2.5"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        className={`h-3 w-3 transition-transform ${expanded ? "rotate-90" : ""}`}
                      >
                        <path d="M7 5l6 5-6 5" />
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
                      className="grid flex-1 grid-cols-[1fr_4.5rem_4.5rem_7rem_7rem_3rem] items-center gap-4 self-stretch text-sm"
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
                    {/* Revealed on hover or keyboard focus, like the competency table's row actions. */}
                    <span className="absolute right-3 top-1/2 flex -translate-y-1/2 items-center gap-0.5 rounded-md border border-hestia-border bg-hestia-surface p-0.5 opacity-0 shadow-sm transition focus-within:opacity-100 group-hover:opacity-100">
                      <RowAction
                        label="Rename course"
                        onClick={() => setRenameCourse(course)}
                        className="hover:bg-hestia-primary-muted hover:text-hestia-text"
                      >
                        <path d="M13.5 3.5l3 3L7 16l-3.7.7L4 13z" />
                      </RowAction>
                      <RowAction
                        label="Delete course"
                        onClick={() => {
                          deleteMutation.reset();
                          setCourseToDelete(course);
                        }}
                        className="hover:bg-hestia-danger hover:text-hestia-on-danger"
                      >
                        <path d="M4 6h12M8 6V4h4v2M6 6l1 10h6l1-10" />
                      </RowAction>
                    </span>
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
            onClick={() => setPage(page - 1)}
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
                    onClick={() => setPage(item)}
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
            onClick={() => setPage(page + 1)}
            disabled={page >= totalPages - 1}
          >
            Next →
          </Button>
        </nav>
      )}

      {createOpen && <CreateCourseDialog onClose={() => setCreateOpen(false)} />}
      {courseToDelete?.id != null && (
        <ConfirmDialog
          title="Delete course?"
          message={`This permanently removes "${courseToDelete.name}" and all of its learning goals. This cannot be undone.`}
          confirmLabel={deleteMutation.isPending ? "Deleting…" : "Delete course"}
          busy={deleteMutation.isPending}
          error={deleteMutation.isError ? (deleteMutation.error as Error).message : undefined}
          onConfirm={() => deleteMutation.mutate(courseToDelete.id as number)}
          onCancel={() => setCourseToDelete(null)}
        />
      )}
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

/**
 * As many courses per page as fit between the top of the list and the bottom of the window, so the
 * list ends where the window does instead of leaving a gap below it. `null` until measured.
 */
function useFittingPageSize(listRef: RefObject<HTMLDivElement | null>): number | null {
  const [pageSize, setPageSize] = useState<number | null>(null);
  useLayoutEffect(() => {
    const measure = () => {
      const list = listRef.current;
      if (!list) return;
      const top = list.getBoundingClientRect().top + window.scrollY;
      const fitting = Math.floor(
        (window.innerHeight - top - HEADER_ROW_HEIGHT - BELOW_LIST) / ROW_HEIGHT,
      );
      setPageSize(Math.min(MAX_PAGE_SIZE, Math.max(MIN_PAGE_SIZE, fitting)));
    };
    measure();
    window.addEventListener("resize", measure);
    return () => window.removeEventListener("resize", measure);
  }, [listRef]);
  return pageSize;
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
  // Same chip shape and tints as the competency table's tier chips.
  if (extractionStatus === "FAILED" || extractionStatus === "RUNNING") {
    label = extractionStatus === "FAILED" ? "Failed" : "Interrupted";
    className =
      "border-transparent bg-[color-mix(in_srgb,var(--hestia-danger)_12%,var(--hestia-surface))] text-hestia-danger";
  } else if (goalCount > 0) {
    label = "Analyzed";
    className =
      "border-transparent bg-[color-mix(in_srgb,var(--hestia-primary)_14%,var(--hestia-surface))] text-hestia-primary";
  } else if (documentCount > 0) {
    label = "Ready";
    className =
      "border-transparent bg-[color-mix(in_srgb,var(--hestia-accent)_15%,var(--hestia-surface))] text-hestia-accent";
  } else {
    label = "Empty";
    className = "border-hestia-border text-hestia-text-muted";
  }
  return (
    <span
      className={`inline-flex h-[22px] items-center whitespace-nowrap rounded-md border px-2 text-xs font-medium ${className}`}
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

function PlusIcon() {
  return (
    <svg
      viewBox="0 0 20 20"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      aria-hidden="true"
      className="h-4 w-4"
    >
      <path d="M10 4v12M4 10h12" />
    </svg>
  );
}
