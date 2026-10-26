import type { ExtractionStatus } from "../../api/client.ts";
import { EXTRACTION_PHASES } from "../../lib/extraction.ts";
import { PhaseTick } from "./PhaseTick.tsx";

/** The run in flight: a progress bar and the pipeline's phases, the current one counting sessions. */
export function RunProgress({
  status,
  statusUnavailable,
}: {
  status?: ExtractionStatus;
  statusUnavailable: boolean;
}) {
  const total = status?.total ?? 0;
  const completed = status?.completed ?? 0;
  const percent = status?.percent ?? 0;
  // Index of the phase the backend currently reports; -1 until the first poll lands ("Starting…").
  const activeIndex = status?.phase
    ? EXTRACTION_PHASES.findIndex((p) => p.key === status.phase)
    : -1;

  return (
    <div className="flex flex-col gap-4 rounded-xl border border-hestia-border bg-hestia-surface p-4 shadow-sm">
      <div className="h-1.5 w-full overflow-hidden rounded-full bg-hestia-primary-muted">
        {total > 0 ? (
          <div
            className="h-full rounded-full bg-hestia-primary transition-[width] duration-500 ease-out"
            style={{ width: `${percent}%` }}
          />
        ) : (
          <div className="progress-sweep h-full rounded-full bg-hestia-primary" />
        )}
      </div>
      <ol className="flex flex-col gap-3">
        {EXTRACTION_PHASES.map((phase, i) => {
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
            <li key={phase.key} className="flex items-center gap-3">
              <PhaseTick state={state} index={i} />
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
            </li>
          );
        })}
      </ol>
      {statusUnavailable && (
        <p className="text-sm text-hestia-danger">
          Cannot reach the server for the progress. Still trying…
        </p>
      )}
    </div>
  );
}
