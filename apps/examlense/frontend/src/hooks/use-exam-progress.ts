import { useEffect, useState } from "react";
import { listAnswers, listTasks } from "@/lib/api-client";
import { subscribeExam } from "@/lib/sse";

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
 * Maps the parse_phase string written by the parse-exam-pdf edge function
 * to a percentage for the parsing progress bar. Phases are coarse-grained
 * checkpoints, not real progress, so we interpolate between them visually.
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