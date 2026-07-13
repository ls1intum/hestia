import type { LucideIcon } from "lucide-react";
import { ArrowLeft } from "lucide-react";
import { Link } from "react-router-dom";
import type { ReactNode } from "react";
import { cn } from "@/lib/utils";

export interface ResultsViewItem {
  key: string;
  label: string;
  icon: LucideIcon;
}

interface Props {
  views: ResultsViewItem[];
  /** Key of the active view. */
  currentView: string;
  onSelectView: (key: string) => void;
  /** Exam title node (StaticTitle). */
  title: ReactNode;
  /** Optional pinned bottom content (e.g. a "Back to Grading" button). */
  footer?: ReactNode;
}

/**
 * Primary navigation rail for the Results page. Mirrors SectionSidebar's visual
 * language (full-height panel, back-link + title header, and the active row that
 * "flows" into the content column via a background-colored right border), but its
 * rows are plain view links rather than section entries.
 */
export const ResultsSidebar = ({
  views,
  currentView,
  onSelectView,
  title,
  footer,
}: Props) => {
  return (
    <nav
      aria-label="Results views"
      className="flex h-full w-56 shrink-0 flex-col border-r border-hestia-border bg-hestia-surface shadow-[4px_0_16px_-6px_hsl(20_14%_17%_/_0.14)]"
    >
      {/* Header: back link + exam title. */}
      <div className="border-b border-hestia-border px-hestia-3 py-hestia-3">
        <Link
          to="/exams"
          className="inline-flex items-center gap-1 font-mono text-xs font-medium uppercase tracking-wide text-hestia-text-muted transition-colors hover:text-hestia-text"
        >
          <ArrowLeft size={12} />
          <span>All exams</span>
        </Link>
        <div className="mt-hestia-2 min-w-0">{title}</div>
      </div>

      {/* View list — extends 1px over the panel border so the active row's
          background can bridge it into the content column. */}
      <div className="-mr-px flex-1 space-y-0.5 overflow-y-auto py-hestia-3 pl-hestia-3">
        {views.map((v) => {
          const isActive = currentView === v.key;
          const Icon = v.icon;
          return (
            <button
              key={v.key}
              type="button"
              onClick={() => onSelectView(v.key)}
              title={v.label}
              aria-current={isActive ? "page" : undefined}
              className={cn(
                "relative flex w-full items-center gap-hestia-2 rounded-l-hestia-md border px-hestia-3 py-1.5 text-xs font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-hestia-primary",
                isActive
                  ? "border-hestia-border border-r-hestia-bg bg-hestia-bg text-hestia-text"
                  : "border-transparent text-hestia-text-muted hover:bg-hestia-primary-muted/30 hover:text-hestia-text",
              )}
            >
              <Icon size={14} className="shrink-0" />
              <span className="min-w-0 flex-1 truncate text-left">{v.label}</span>
            </button>
          );
        })}
      </div>

      {/* Pinned bottom action (e.g. Back to Grading). */}
      {footer && (
        <div className="shrink-0 border-t border-hestia-border px-hestia-3 py-hestia-3">
          {footer}
        </div>
      )}
    </nav>
  );
};
