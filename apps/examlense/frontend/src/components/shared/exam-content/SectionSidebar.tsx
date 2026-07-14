/* eslint-disable react-refresh/only-export-components */
import { useMemo, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { ArrowLeft, Plus, X } from "lucide-react";
import { isSectionReady, totalPoints, type Section, type Task } from "@/lib/exam/exam-helpers";
import { effectiveScore, type TaskAnswer, type TaskGrade } from "@/lib/grading/grading";
import { cn } from "@/lib/utils/utils";

export type EntryStatus =
  | "draft"
  | "ready"
  | "confirmed"
  | "pending"
  | "complete";

export interface SectionEntry {
  id: string;
  slug: string;
  index: number;
  fullLabel: string;
  status: EntryStatus;
  /** Right-aligned score meta (e.g. "12 pt" in edit, "8 / 12 pt" in grading). */
  scoreLabel?: string;
  /** Shown on row hover in place of the name (e.g. "3 / 5 tasks scored"). */
  taskProgressLabel?: string;
}

const STATUS_DOT: Record<EntryStatus, string> = {
  draft: "bg-hestia-danger",
  ready: "bg-hestia-warning",
  confirmed: "bg-hestia-success",
  pending: "bg-hestia-danger",
  complete: "bg-hestia-success",
};

/** Edit hover label: how many tasks in the section have a score set. */
const scoredTasksLabel = (tasks: Task[]): string => {
  const scored = tasks.filter((t) => t.points != null && t.points > 0).length;
  return `${scored} / ${tasks.length} tasks scored`;
};

/** Group tasks by section id (null = unassigned). */
const groupTasksBySection = (tasks: Task[]): Map<string | null, Task[]> => {
  const m = new Map<string | null, Task[]>();
  for (const task of tasks) {
    const k = task.section_id ?? null;
    const arr = m.get(k) ?? [];
    arr.push(task);
    m.set(k, arr);
  }
  return m;
};

/** Build edit-mode entries (status: draft | ready | confirmed; score = max points). */
export const useEditSectionEntries = (
  sections: Section[],
  tasks: Task[],
  confirmedSectionIds: Set<string>,
): SectionEntry[] => {
  return useMemo<SectionEntry[]>(() => {
    const tasksBySection = groupTasksBySection(tasks);
    const list: SectionEntry[] = sections
      .slice()
      .sort((a, b) => a.position - b.position)
      .map((s, i) => {
        const sectionTasks = tasksBySection.get(s.id) ?? [];
        const ready = isSectionReady(sectionTasks);
        const confirmed = ready && confirmedSectionIds.has(s.id);
        const name = s.name?.trim();
        return {
          id: s.id,
          slug: `section-${i + 1}`,
          index: i + 1,
          fullLabel: name || `Section ${i + 1}`,
          status: confirmed ? "confirmed" : ready ? "ready" : "draft",
          scoreLabel: `${totalPoints(sectionTasks)} pt`,
          taskProgressLabel: scoredTasksLabel(sectionTasks),
        };
      });
    const orphan = tasksBySection.get(null) ?? [];
    if (orphan.length > 0) {
      const ready = isSectionReady(orphan);
      list.push({
        id: "_unassigned",
        slug: "section-unassigned",
        index: list.length + 1,
        fullLabel: "Unassigned tasks",
        status: ready ? "ready" : "draft",
        scoreLabel: `${totalPoints(orphan)} pt`,
        taskProgressLabel: scoredTasksLabel(orphan),
      });
    }
    return list;
  }, [sections, tasks, confirmedSectionIds]);
};

/** Build grading-mode entries (status: pending | complete; score = achieved / max). */
export const useGradingSectionEntries = (
  sections: Section[],
  tasks: Task[],
  pendingByTaskId: Map<string, boolean>,
  gradesById: Map<string, TaskGrade>,
  answersById: Map<string, TaskAnswer>,
): SectionEntry[] => {
  return useMemo<SectionEntry[]>(() => {
    const tasksBySection = groupTasksBySection(tasks);
    const scoreLabelFor = (sectionTasks: Task[]): string => {
      const max = totalPoints(sectionTasks);
      const achieved = sectionTasks.reduce((sum, tk) => {
        const eff = effectiveScore(tk, gradesById.get(tk.id), answersById.get(tk.id));
        return sum + (eff.score ?? 0);
      }, 0);
      return `${Number(achieved.toFixed(2))} / ${max} pt`;
    };
    const gradedLabelFor = (sectionTasks: Task[]): string => {
      const graded = sectionTasks.filter((tk) => !pendingByTaskId.get(tk.id)).length;
      return `${graded} / ${sectionTasks.length} graded`;
    };
    const list: SectionEntry[] = sections
      .slice()
      .sort((a, b) => a.position - b.position)
      .map((s, i) => {
        const sectionTasks = tasksBySection.get(s.id) ?? [];
        const pending = sectionTasks.some((tk) => pendingByTaskId.get(tk.id));
        const name = s.name?.trim();
        return {
          id: s.id,
          slug: `section-${i + 1}`,
          index: i + 1,
          fullLabel: name || `Section ${i + 1}`,
          status: pending ? "pending" : "complete",
          scoreLabel: scoreLabelFor(sectionTasks),
          taskProgressLabel: gradedLabelFor(sectionTasks),
        };
      });
    const orphan = tasksBySection.get(null) ?? [];
    if (orphan.length > 0) {
      const pending = orphan.some((tk) => pendingByTaskId.get(tk.id));
      list.push({
        id: "_unassigned",
        slug: "section-unassigned",
        index: list.length + 1,
        fullLabel: "Unassigned tasks",
        status: pending ? "pending" : "complete",
        scoreLabel: scoreLabelFor(orphan),
        taskProgressLabel: gradedLabelFor(orphan),
      });
    }
    return list;
  }, [sections, tasks, pendingByTaskId, gradesById, answersById]);
};

interface Props {
  entries: SectionEntry[];
  /** Slug of the active section. */
  currentSectionId: string;
  onSelectSection: (slug: string) => void;
  /** Edit-only: append a new section. */
  onAddSection?: () => void;
  /** Edit-only: delete a section (never offered for the unassigned bucket). */
  onDeleteSection?: (entry: SectionEntry) => void;
  /** Pinned bottom summary, e.g. "Total: 45 pt" or "12 / 45 pt". Omit to hide the footer. */
  footerScore?: ReactNode;
  /** Exam title node — editable InlineTitle (edit) or StaticTitle (grading). Omit to hide the header (back-link + title). */
  title?: ReactNode;
  /** Optional trailing content beside the title (e.g. SaveIndicator). */
  titleTrailing?: ReactNode;
}

/**
 * Full-height section rail anchored to the left edge. Holds the back link +
 * exam title at the top, a scrollable section list in the middle, and the
 * pinned score total at the bottom. The active row's right border matches the
 * page background so it "flows" into the content column on its right.
 */
export const SectionSidebar = ({
  entries,
  currentSectionId,
  onSelectSection,
  onAddSection,
  onDeleteSection,
  footerScore,
  title,
  titleTrailing,
}: Props) => {
  const canDelete = (e: SectionEntry) =>
    !!onDeleteSection && e.id !== "_unassigned";

  return (
    <nav
      aria-label="Sections"
      className="flex h-full w-64 shrink-0 flex-col border-r border-hestia-border bg-hestia-surface shadow-[4px_0_16px_-6px_hsl(20_14%_17%_/_0.14)]"
    >
      {/* Header: back link + exam title. Omitted when no title is supplied
          (e.g. the Results view already shows both in its ChromeHeader). */}
      {title && (
        <div className="border-b border-hestia-border px-hestia-3 py-hestia-3">
          <Link
            to="/exams"
            className="inline-flex items-center gap-1 font-mono text-xs font-medium uppercase tracking-wide text-hestia-text-muted transition-colors hover:text-hestia-text"
          >
            <ArrowLeft size={12} />
            <span>All exams</span>
          </Link>
          <div className="mt-hestia-2 flex items-center gap-hestia-2">
            <div className="min-w-0 flex-1">{title}</div>
            {titleTrailing}
          </div>
        </div>
      )}

      {/* Section list — extends 1px over the panel border so the active row's
          background can bridge it. */}
      <div className="-mr-px flex-1 space-y-0.5 overflow-y-auto py-hestia-3 pl-hestia-3">
        {entries.map((e) => {
          const isActive = currentSectionId === e.slug;
          const deletable = canDelete(e);
          return (
            <div key={e.id} className="group relative flex items-stretch">
              <button
                type="button"
                onClick={() => onSelectSection(e.slug)}
                title={e.fullLabel}
                aria-current={isActive ? "page" : undefined}
                className={cn(
                  "relative flex w-full items-center gap-1.5 rounded-l-hestia-md border px-hestia-3 py-1.5 text-xs font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-hestia-primary",
                  deletable && "pr-7",
                  isActive
                    ? "border-hestia-border border-r-hestia-bg bg-hestia-bg text-hestia-text"
                    : "border-transparent text-hestia-text-muted hover:bg-hestia-primary-muted/30 hover:text-hestia-text",
                )}
              >
                <span
                  aria-hidden
                  className={cn(
                    "h-1.5 w-1.5 shrink-0 rounded-full",
                    STATUS_DOT[e.status],
                  )}
                />
                <span className="min-w-0 flex-1 truncate text-left">
                  {/* Resting: section name. On hover: task-scored progress. */}
                  <span className={cn(e.taskProgressLabel && "group-hover:hidden")}>
                    {e.fullLabel}
                  </span>
                  {e.taskProgressLabel && (
                    <span className="hidden tabular-nums text-hestia-text-muted group-hover:inline">
                      {e.taskProgressLabel}
                    </span>
                  )}
                </span>
                {e.scoreLabel ? (
                  <span
                    className={cn(
                      "hestia-eyebrow shrink-0 tabular-nums text-hestia-text-muted",
                      e.taskProgressLabel && "group-hover:hidden",
                    )}
                  >
                    {e.scoreLabel}
                  </span>
                ) : null}
              </button>
              {deletable && (
                <button
                  type="button"
                  onClick={(ev) => {
                    ev.stopPropagation();
                    onDeleteSection?.(e);
                  }}
                  aria-label="Delete section"
                  title="Delete section"
                  className={cn(
                    "absolute right-1.5 top-1/2 inline-flex h-4 w-4 -translate-y-1/2 items-center justify-center rounded-hestia-sm bg-inherit text-hestia-text-muted transition-opacity hover:text-hestia-danger focus-visible:opacity-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-hestia-primary group-hover:opacity-100",
                    isActive ? "opacity-100" : "opacity-0",
                  )}
                >
                  <X size={12} />
                </button>
              )}
            </div>
          );
        })}

        {onAddSection && (
          <button
            type="button"
            onClick={onAddSection}
            aria-label="Add a new section"
            className="mt-1 flex w-full items-center gap-1.5 rounded-l-hestia-md px-hestia-3 py-1.5 text-xs font-medium text-hestia-text-muted transition-colors hover:bg-hestia-primary-muted/30 hover:text-hestia-primary"
          >
            <Plus size={14} className="shrink-0" />
            Add section
          </button>
        )}
      </div>

      {/* Pinned score summary. Omitted when no score is supplied. */}
      {footerScore != null && (
        <div className="hestia-eyebrow shrink-0 border-t border-hestia-border px-hestia-3 py-hestia-3 tabular-nums text-hestia-text">
          {footerScore}
        </div>
      )}
    </nav>
  );
};
