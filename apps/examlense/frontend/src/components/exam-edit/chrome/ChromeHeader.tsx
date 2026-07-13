import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import { ArrowLeft } from "lucide-react";
import { cn } from "@/lib/utils";

interface Props {
  /** Title slot — typically an InlineTitle or StaticTitle. */
  title: ReactNode;
  /** Optional tabs slot. Section navigation now lives in the sidebar. */
  tabs?: ReactNode;
  /** Optional right-aligned content (e.g. SaveIndicator). */
  trailing?: ReactNode;
  /** Optional content rendered before the title (e.g. mode badge). */
  leading?: ReactNode;
}

/**
 * Generic sticky header chrome shared by exam-edit and grading views.
 * A single fixed-height row (57px total incl. border) so the section sidebar
 * can pin itself directly beneath it with a constant offset.
 */
export const ChromeHeader = ({ title, tabs, trailing, leading }: Props) => {
  return (
    <header className="sticky top-0 z-30 border-b border-hestia-border bg-hestia-surface/95 backdrop-blur">
      <div
        className={cn(
          "mx-auto flex w-full max-w-[1280px] flex-col gap-hestia-2 px-hestia-5 pt-hestia-3",
          tabs ? "" : "pb-hestia-3",
        )}
      >
        <div className="flex h-8 items-center gap-hestia-3">
          <Link
            to="/exams"
            className="inline-flex items-center gap-1 font-mono text-xs font-medium uppercase tracking-wide text-hestia-text-muted transition-colors hover:text-hestia-text"
          >
            <ArrowLeft size={12} />
            <span>All exams</span>
          </Link>
          {leading}
          <div className="min-w-0 flex-1">{title}</div>
          {trailing}
        </div>
        {tabs}
      </div>
    </header>
  );
};