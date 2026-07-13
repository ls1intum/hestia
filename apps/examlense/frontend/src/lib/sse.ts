/**
 * Server-Sent-Events subscriptions — the replacement for the old Supabase
 * realtime channels. The browser EventSource API can't set headers, so the
 * static token rides along as a `?token=` query param (the backend's auth
 * filter accepts it). EventSource auto-reconnects on transient errors.
 */
import { apiBaseUrl, apiToken } from "@/lib/api-client";

export interface ExamEventHandlers {
  /** exam status/phase changed — refetch exam + children. */
  onExam?: () => void;
  /** a task answer was written — refresh evaluation progress. */
  onProgress?: () => void;
  /** task rows changed server-side (e.g. learning goals generated) — refetch tasks. */
  onTasks?: () => void;
}

function open(path: string): EventSource {
  const sep = path.includes("?") ? "&" : "?";
  return new EventSource(`${apiBaseUrl()}${path}${sep}token=${encodeURIComponent(apiToken())}`);
}

/** Subscribe to one exam's status + progress stream. Returns an unsubscribe fn. */
export function subscribeExam(examId: string, handlers: ExamEventHandlers): () => void {
  const es = open(`/api/exams/${examId}/events`);
  if (handlers.onExam) es.addEventListener("exam", () => handlers.onExam?.());
  if (handlers.onProgress) es.addEventListener("progress", () => handlers.onProgress?.());
  if (handlers.onTasks) es.addEventListener("tasks", () => handlers.onTasks?.());
  return () => es.close();
}

/** Subscribe to the list-level stream (any owned exam changed). Returns an unsubscribe fn. */
export function subscribeExamsList(onChange: () => void): () => void {
  const es = open("/api/exams/events");
  es.addEventListener("exam", () => onChange());
  return () => es.close();
}
