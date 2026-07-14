import { Link } from "react-router-dom";
import { ArrowLeft, Loader2 } from "lucide-react";

/**
 * Full-page loading state shown while the editor is fetching the exam, its
 * tasks, sections and section blocks. Mirrors the layout of EvaluatingView so
 * the transition into the editor feels consistent.
 */
export const EditorLoadingView = () => {
  return (
    <div className="flex min-h-screen flex-col">
      <header className="border-b border-hestia-border bg-hestia-surface/60 px-hestia-5 py-hestia-3">
        <Link
          to="/exams"
          className="inline-flex items-center gap-1 text-sm text-hestia-text-muted hover:text-hestia-primary"
        >
          <ArrowLeft size={14} />
          Back to exams
        </Link>
      </header>
      <main className="flex flex-1 items-center justify-center px-hestia-5 py-hestia-10">
        <div className="flex max-w-md flex-col items-center text-center">
          <span className="mb-hestia-5 inline-flex h-14 w-14 items-center justify-center rounded-full bg-hestia-primary-muted text-hestia-primary">
            <Loader2 size={28} className="animate-spin" />
          </span>
          <h1 className="font-display text-2xl font-semibold text-hestia-text">
            Opening your exam…
          </h1>
          <p className="mt-hestia-3 text-sm text-hestia-text-muted">
            Loading sections and tasks. This should only take a moment.
          </p>
        </div>
      </main>
    </div>
  );
};