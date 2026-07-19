import type { ExtractionStatus, ExtractionSummary } from "../api/client.ts";
import { useTheme } from "../theme/context.ts";
import iconLight from "../assets/logos/icon-light.svg";
import iconDark from "../assets/logos/icon-dark.svg";

type Props = {
  open: boolean;
  /** Latest polled snapshot while the run is in flight. */
  status?: ExtractionStatus;
  /** Final summary once the POST resolves (authoritative over the polled snapshot). */
  result?: ExtractionSummary | null;
  /** Set when the run failed. */
  error?: string | null;
  onClose: () => void;
};

type Phase = NonNullable<ExtractionStatus["phase"]>;

/** Ordered list of phases the backend walks through, with their display labels. */
const PHASES: { key: Phase; label: string }[] = [
  { key: "OUTLINING", label: "Outlining documents" },
  { key: "PARSING", label: "Parsing documents" },
  { key: "EXTRACTING", label: "Extracting learning goals" },
  { key: "CLASSIFYING", label: "Classifying (Bloom & SOLO)" },
  { key: "EMBEDDING", label: "Computing embeddings" },
  { key: "PERSISTING", label: "Saving learning goals" },
];

/**
 * Screen 3 — "Analyzing course materials" overlay shown while the (synchronous) extraction POST is
 * in flight. It polls {@code GET /extract/status} for the live phase + per-phase counts and renders
 * a real progress bar, then switches to the summary once the run resolves.
 */
export default function ExtractionProgressModal({ open, status, result, error, onClose }: Props) {
  const { resolved } = useTheme();
  const flame = resolved === "dark" ? iconDark : iconLight;

  if (!open) return null;

  const done = result != null;
  const failed = error != null;
  const running = !done && !failed;

  const total = status?.total ?? 0;
  const completed = status?.completed ?? 0;
  const percent = total > 0 ? Math.min(100, Math.round((completed / total) * 100)) : 0;
  // Index of the phase the backend currently reports; -1 until the first poll lands ("Starting…").
  const activeIndex = status?.phase ? PHASES.findIndex((p) => p.key === status.phase) : -1;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="extraction-progress-title"
    >
      <div className="w-full max-w-md rounded-xl border border-hestia-border bg-hestia-surface p-6 shadow-xl sm:p-8">
        <div className="flex items-center gap-3">
          <img
            src={flame}
            alt=""
            className={`h-9 w-9 ${running ? "animate-pulse" : ""}`}
          />
          <h2 id="extraction-progress-title" className="text-xl">
            {done
              ? "Analysis complete"
              : failed
                ? "Analysis failed"
                : "Analyzing course materials"}
          </h2>
        </div>

        {running && (
          <div className="mt-6">
            <p className="text-sm font-medium text-hestia-text-muted">
              Step {Math.max(activeIndex, 0) + 1} of {PHASES.length}
            </p>
            <ol className="mt-3 flex flex-col gap-2.5">
              {PHASES.map((phase, i) => {
                const state =
                  activeIndex < 0
                    ? i === 0
                      ? "active"
                      : "pending"
                    : i < activeIndex
                      ? "done"
                      : i === activeIndex
                        ? "active"
                        : "pending";
                return (
                  <li key={phase.key} className="flex flex-col gap-1.5">
                    <div className="flex items-center gap-3">
                      <StepIcon state={state} />
                      <span
                        className={`text-sm ${
                          state === "active"
                            ? "font-medium text-hestia-text"
                            : state === "done"
                              ? "text-hestia-text-muted"
                              : "text-hestia-text-muted/60"
                        }`}
                      >
                        {phase.label}
                      </span>
                      {state === "active" && total > 0 && (
                        <span className="ml-auto tabular-nums text-xs text-hestia-text-muted">
                          {completed}/{total}
                        </span>
                      )}
                    </div>
                    {state === "active" && (
                      <div className="ml-8 h-1.5 w-full overflow-hidden rounded-full bg-hestia-primary-muted">
                        {total > 0 ? (
                          <div
                            className="h-full rounded-full bg-hestia-primary transition-[width] duration-500 ease-out"
                            style={{ width: `${percent}%` }}
                          />
                        ) : (
                          <div className="h-full w-1/3 animate-pulse rounded-full bg-hestia-primary" />
                        )}
                      </div>
                    )}
                  </li>
                );
              })}
            </ol>
            <p className="mt-4 text-sm text-hestia-text-muted">
              This can take a while — large courses run many LLM calls. You can keep this open.
            </p>
          </div>
        )}

        {done && (
          <>
            <dl className="mt-6 grid grid-cols-2 gap-3 sm:grid-cols-3">
              <Stat label="Documents" value={result?.documentsProcessed} />
              <Stat label="Goals created" value={result?.goalsCreated} />
              <Stat label="Terminal competencies" value={result?.terminalCompetencies} />
            </dl>
            <div className="mt-6 flex justify-end">
              <button
                onClick={onClose}
                className="rounded-md bg-hestia-primary px-4 py-2 text-sm font-semibold text-white transition hover:bg-hestia-primary-hover"
              >
                View goals →
              </button>
            </div>
          </>
        )}

        {failed && (
          <>
            <p className="mt-6 rounded-md border border-hestia-danger/40 bg-hestia-danger/10 px-3 py-2 text-sm text-hestia-danger">
              {error}
            </p>
            <div className="mt-6 flex justify-end">
              <button
                onClick={onClose}
                className="rounded-md px-4 py-2 text-sm font-semibold text-hestia-text-muted transition hover:text-hestia-text"
              >
                Close
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

function StepIcon({ state }: { state: "done" | "active" | "pending" }) {
  if (state === "done") {
    return (
      <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-hestia-primary text-white">
        <svg viewBox="0 0 20 20" fill="currentColor" className="h-3 w-3">
          <path
            fillRule="evenodd"
            d="M16.7 5.3a1 1 0 010 1.4l-7.5 7.5a1 1 0 01-1.4 0l-3.5-3.5a1 1 0 011.4-1.4l2.8 2.8 6.8-6.8a1 1 0 011.4 0z"
            clipRule="evenodd"
          />
        </svg>
      </span>
    );
  }
  if (state === "active") {
    return (
      <span className="flex h-5 w-5 shrink-0 items-center justify-center">
        <span className="h-4 w-4 animate-spin rounded-full border-2 border-hestia-primary border-t-transparent" />
      </span>
    );
  }
  return (
    <span className="flex h-5 w-5 shrink-0 items-center justify-center">
      <span className="h-2 w-2 rounded-full bg-hestia-border" />
    </span>
  );
}

function Stat({ label, value }: { label: string; value?: number }) {
  return (
    <div className="rounded-lg bg-hestia-bg px-3 py-2">
      <dt className="text-xs font-medium uppercase tracking-wide text-hestia-text-muted">
        {label}
      </dt>
      <dd className="mt-0.5 text-lg font-semibold tabular-nums text-hestia-text">
        {value ?? 0}
      </dd>
    </div>
  );
}
