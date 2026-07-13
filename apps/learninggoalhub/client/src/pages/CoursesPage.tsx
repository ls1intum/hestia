import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../api/client.ts";
import type { CourseSummary } from "../api/client.ts";

/** Screen 1 — overview of every course with document/goal counts, status and creation date. */
export default function CoursesPage() {
  const queryClient = useQueryClient();
  const [openMenuId, setOpenMenuId] = useState<number | null>(null);

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
    queryKey: ["courses"],
    queryFn: async () => {
      const { data, error } = await api.GET("/api/courses", {
        params: { query: { size: 100 } },
      });
      if (error || !data) {
        throw new Error("Could not load courses.");
      }
      return data;
    },
  });

  const courses: CourseSummary[] = coursesQuery.data?.content ?? [];

  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-end justify-between gap-4">
        <div>
          <h1 className="text-3xl">Courses</h1>
          <p className="mt-1 text-sm text-hestia-text-muted">
            Each course groups the documents you upload; learning goals are extracted and
            deduplicated across all of them.
          </p>
        </div>
        <Link
          to="/courses/new"
          className="shrink-0 rounded-md bg-hestia-primary px-4 py-2 text-sm font-semibold text-white transition hover:bg-hestia-primary-hover"
        >
          + Add course
        </Link>
      </div>

      {deleteMutation.isError && (
        <p className="rounded-md border border-hestia-danger/40 bg-hestia-danger/10 px-4 py-2 text-sm text-hestia-danger">
          {(deleteMutation.error as Error).message}
        </p>
      )}

      <div className="overflow-hidden rounded-xl border border-hestia-border bg-hestia-surface shadow-sm">
        {coursesQuery.isLoading && (
          <p className="px-5 py-6 text-sm text-hestia-text-muted">Loading courses…</p>
        )}
        {coursesQuery.isError && (
          <p className="px-5 py-6 text-sm text-hestia-danger">
            {(coursesQuery.error as Error).message}
          </p>
        )}
        {!coursesQuery.isLoading && !coursesQuery.isError && courses.length === 0 && (
          <div className="px-5 py-12 text-center">
            <p className="text-sm text-hestia-text-muted">No courses yet.</p>
            <Link
              to="/courses/new"
              className="mt-3 inline-block rounded-md bg-hestia-primary px-4 py-2 text-sm font-semibold text-white transition hover:bg-hestia-primary-hover"
            >
              Create your first course
            </Link>
          </div>
        )}
        {courses.length > 0 && (
          <ul className="divide-y divide-hestia-border">
            <li className="grid grid-cols-[1fr_4.5rem_4.5rem_7rem_7rem_2.5rem] gap-4 px-5 py-3 text-xs font-semibold uppercase tracking-wide text-hestia-text-muted">
              <span>Course</span>
              <span className="text-right">Docs</span>
              <span className="text-right">Goals</span>
              <span>Status</span>
              <span className="text-right">Created</span>
              <span className="sr-only">Actions</span>
            </li>
            {courses.map((course, index) => (
              <li
                key={course.id}
                className={`relative ${openMenuId === course.id ? "z-30" : ""}`}
              >
                <Link
                  to={`/courses/${course.id}`}
                  className="grid grid-cols-[1fr_4.5rem_4.5rem_7rem_7rem_2.5rem] items-center gap-4 px-5 py-4 transition hover:bg-hestia-primary-muted"
                >
                  <span className="font-medium text-hestia-text">{course.name}</span>
                  <span className="text-right tabular-nums text-hestia-text-muted">
                    {course.documentCount ?? 0}
                  </span>
                  <span className="text-right tabular-nums text-hestia-text-muted">
                    {course.goalCount ?? 0}
                  </span>
                  <span>
                    <StatusBadge
                      documentCount={course.documentCount ?? 0}
                      goalCount={course.goalCount ?? 0}
                    />
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
                    onDelete={() => deleteMutation.mutate(course.id as number)}
                    deleting={
                      deleteMutation.isPending &&
                      deleteMutation.variables === course.id
                    }
                    openUp={index === courses.length - 1}
                  />
                )}
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

/**
 * Per-row kebab (⋮) menu. Built to grow into a generic "edit" menu; for now it only offers
 * deletion, gated behind an inline confirm step so a stray click can't drop a course.
 */
function RowMenu({
  open,
  onToggle,
  onClose,
  onDelete,
  deleting,
  openUp,
}: {
  open: boolean;
  onToggle: () => void;
  onClose: () => void;
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
        className="flex h-8 w-8 items-center justify-center rounded-md text-hestia-text-muted transition hover:bg-hestia-bg hover:text-hestia-text"
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
                <button
                  type="button"
                  onClick={onDelete}
                  disabled={deleting}
                  className="rounded-md bg-hestia-danger px-2.5 py-1 text-xs font-medium text-white transition hover:opacity-90 disabled:opacity-50"
                >
                  {deleting ? "Deleting…" : "Delete"}
                </button>
                <button
                  type="button"
                  onClick={onClose}
                  disabled={deleting}
                  className="rounded-md border border-hestia-border px-2.5 py-1 text-xs font-medium text-hestia-text transition hover:bg-hestia-primary-muted disabled:opacity-50"
                >
                  Cancel
                </button>
              </div>
            </div>
          ) : (
            <button
              type="button"
              onClick={() => setConfirm(true)}
              className="block w-full px-3 py-2 text-left text-sm text-hestia-danger transition hover:bg-hestia-primary-muted"
            >
              Delete
            </button>
          )}
        </div>
      )}
    </div>
  );
}

function StatusBadge({
  documentCount,
  goalCount,
}: {
  documentCount: number;
  goalCount: number;
}) {
  let label: string;
  let className: string;
  if (goalCount > 0) {
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
