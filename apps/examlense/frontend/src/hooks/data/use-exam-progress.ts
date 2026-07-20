import { useEffect, useState } from "react";
import { listAnswers, listTasks } from "@/lib/api/api-client";
import { subscribeExam } from "@/lib/api/sse";
import { parseCountdown, formatRemaining } from "@/lib/parsing/parse-estimate";

/**
 * Progress signal for an exam being evaluated.
 * `done` = number of distinct task_ids that have been answered.
 * `total` = total number of tasks for the exam.
 * Updates live via the exam SSE `progress` event (emitted on answer writes).
 */
export const useEvaluationProgress = (examId: string | undefined) => {
  const [done, setDone] = useState(0);
  const [total, setTotal] = useState(0);

  useEffect(() => {
    if (!examId) return;
    let cancelled = false;

    const refresh = async () => {
      try {
        const [tasks, answers] = await Promise.all([
          listTasks(examId),
          listAnswers(examId),
        ]);
        if (cancelled) return;
        setTotal(tasks.length);
        const unique = new Set(answers.map((a) => a.task_id));
        setDone(unique.size);
      } catch {
        /* transient; the next progress event will retry */
      }
    };

    refresh();
    const unsubscribe = subscribeExam(examId, { onProgress: refresh });
    return () => {
      cancelled = true;
      unsubscribe();
    };
  }, [examId]);

  const percent = total > 0 ? Math.round((done / total) * 100) : 0;
  return { done, total, percent };
};

/**
 * Maps the parse_phase string written by the backend to a percentage for the
 * parsing progress bar. Phases are coarse-grained checkpoints; they serve as the
 * floor for the smooth, time-based bar (see {@link useParseCountdown}) and as the
 * sole signal when no page-count estimate is available.
 */
export const parsePhasePercent = (phase: string | null | undefined): number => {
  switch (phase) {
    case "downloading":
      return 10;
    case "rasterizing":
      return 35;
    case "extracting":
      return 75;
    case "persisting":
      return 92;
    default:
      return 5;
  }
};

/** Human labels for each backend parse phase (shown in progress bars). */
export const PARSE_PHASE_LABELS: Record<string, string> = {
  queued: "Queued…",
  downloading: "Downloading PDF…",
  rasterizing: "Rendering PDF pages…",
  extracting: "Identifying tasks with AI…",
  persisting: "Saving parsed tasks…",
};

/** Resolve a parse phase to its label, defaulting to the queued message. */
export const parsePhaseLabel = (phase: string | null | undefined): string =>
  PARSE_PHASE_LABELS[phase ?? "queued"] ?? PARSE_PHASE_LABELS.queued;

export interface ParseCountdown {
  percent: number;
  remainingLabel: string;
}

/**
 * Live, time-driven parsing progress for the loading surfaces (dashboard cell +
 * editor splash). Ticks every 5 seconds while the exam is parsing and its page
 * count is known, deriving a smooth percent + remaining-time label from the
 * page-count estimate anchored at `parseStartedAt` (the start of the *current*
 * attempt — using `created_at` would make a retried exam read as 95%/overrun).
 * The percent is purely time-proportional (floored at 10% so it never starts empty).
 *
 * Returns null when not applicable (not parsing, no page count, or no
 * current-attempt timestamp) so callers fall back to the phase-based bar
 * ({@link parsePhasePercent} / label).
 */
export const useParseCountdown = (params: {
  active: boolean;
  pageCount?: number | null;
  parseStartedAt?: string | null;
  parsePhase?: string | null;
}): ParseCountdown | null => {
  const { active, pageCount, parseStartedAt } = params;
  const running = active && !!pageCount && !!parseStartedAt;
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (!running) return;
    setNow(Date.now());
    // Tick every 5s (not 1s) so the bar advances in bigger, calmer steps.
    const id = setInterval(() => setNow(Date.now()), 5000);
    return () => clearInterval(id);
  }, [running, parseStartedAt]);

  if (!running) return null;
  const elapsed = Math.max(0, (now - Date.parse(parseStartedAt as string)) / 1000);
  const { percent, remainingSeconds, overrun } = parseCountdown(
    pageCount as number,
    elapsed,
  );
  return {
    // Time-proportional: start ~10% and fill smoothly with elapsed time (capped
    // at 95% by `parseCountdown`). We deliberately do NOT floor to the backend
    // phase (`parsePhasePercent`) — that made the bar jump straight to a phase
    // milestone (e.g. 75% the instant "extracting" began) instead of tracking time.
    percent: Math.max(10, percent),
    remainingLabel: formatRemaining(remainingSeconds, overrun),
  };
};