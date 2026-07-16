import { useEffect, useRef } from "react";
import { subscribeExam, type ExamEventHandlers } from "@/lib/api/sse";

/**
 * Subscribe to one exam's SSE stream for the lifetime of the component,
 * invoking the latest handlers without re-subscribing on every render. Wraps
 * the subscribe + cache-invalidation effect that ExamEdit and GradingView
 * otherwise duplicate. (The evaluation-progress channel keeps its own wrapper
 * in `use-exam-progress`.)
 */
export function useExamRealtime(
  id: string | undefined,
  handlers: ExamEventHandlers,
) {
  const handlersRef = useRef(handlers);
  handlersRef.current = handlers;

  // Re-subscribe only when the exam or the set of active channels changes —
  // handler identity is read through the ref, so inline closures are fine.
  const hasExam = !!handlers.onExam;
  const hasProgress = !!handlers.onProgress;
  const hasTasks = !!handlers.onTasks;

  useEffect(() => {
    if (!id) return;
    return subscribeExam(id, {
      onExam: hasExam ? () => handlersRef.current.onExam?.() : undefined,
      onProgress: hasProgress ? () => handlersRef.current.onProgress?.() : undefined,
      onTasks: hasTasks ? () => handlersRef.current.onTasks?.() : undefined,
    });
  }, [id, hasExam, hasProgress, hasTasks]);
}
