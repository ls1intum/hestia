import { saveAs } from "file-saver";
import type {
  WorkshopInput,
  LearningGoalPlan,
  WorkshopSession,
  SessionSkeleton,
  SessionSummary,
  SessionDetail,
  DraftState,
} from "./workshop-generator";

const API_BASE = import.meta.env.VITE_API_URL ?? import.meta.env.BASE_URL + "api";

async function post<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `HTTP ${res.status}`);
  }
  return res.json() as Promise<T>;
}

async function put<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `HTTP ${res.status}`);
  }
  return res.json() as Promise<T>;
}

async function del<T>(path: string): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    method: "DELETE",
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  if (res.status === 204) return {} as T;
  return res.json() as Promise<T>;
}

async function get<T>(path: string): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json() as Promise<T>;
}

/** Step 1: Generate initial learning goal drafts from user input */
export function generatePlan(input: WorkshopInput): Promise<LearningGoalPlan[]> {
  return post<LearningGoalPlan[]>("/workshop/plan", input);
}

/** Step 3: Generate the full session timetable from goals + activities */
export function generateSession(
  goals: LearningGoalPlan[],
  meta: WorkshopInput,
  skeleton: SessionSkeleton
): Promise<WorkshopSession> {
  return post<WorkshopSession>("/workshop/session", { goals, meta, skeleton });
}

/** List all sessions (lightweight summaries for dashboard) */
export function listSessions(): Promise<SessionSummary[]> {
  return get<SessionSummary[]>("/workshop/sessions");
}

/** Fetch full session detail (draft state + generated session if complete) */
export function getSessionDetail(id: string): Promise<SessionDetail> {
  return get<SessionDetail>(`/workshop/sessions/${id}`);
}

/**
 * Save or update the in-progress draft state.
 * Pass sessionId to update an existing draft; omit to create a new one.
 * Returns the session id (either existing or newly created).
 */
export async function saveDraft(
  draft: DraftState,
  sessionId: string | null,
  currentStep: string,
  type?: "SESSION" | "LECTURE",
  lectureId?: string
): Promise<string> {
  const title = draft.session?.title ?? draft.workshopInput?.title ?? (draft.workshopInput?.sessionType
    ? `${draft.workshopInput.sessionType.charAt(0).toUpperCase() + draft.workshopInput.sessionType.slice(1)} Session`
    : "Workshop Session");
  const learningGoal =
    draft.refinedGoals?.[0]?.goal ??
    draft.workshopInput?.learningGoals?.[0] ??
    null;

  const body = {
    sessionId: sessionId ?? undefined,
    currentStep,
    title,
    learningGoal,
    draftStateJson: JSON.stringify(draft),
    type,
    lectureId
  };

  const res = await post<{ id: string }>("/workshop/sessions/draft", body);
  return res.id;
}

/** Mark a session as fully finished (Finish & Save clicked) */
export function finishSession(id: string): Promise<void> {
  return put<void>(`/workshop/sessions/${id}/finish`, {});
}

/** Delete a session */
export function deleteSession(id: string): Promise<void> {
  return del<void>(`/workshop/sessions/${id}`);
}

/** Rename a session */
export function renameSession(id: string, title: string): Promise<void> {
  return put<void>(`/workshop/sessions/${id}/rename`, { title });
}

/** Move a session to a lecture */
export function moveSession(id: string, lectureId: string): Promise<void> {
  return put<void>(`/workshop/sessions/${id}/move`, { lectureId });
}

/** Reorder sessions */
export function reorderSessions(sessionIds: string[]): Promise<void> {
  return put<void>('/workshop/sessions/reorder', sessionIds);
}

/** Download PDF */
export async function downloadPdf(id: string): Promise<void> {
  const detail = await getSessionDetail(id);
  if (!detail.session) throw new Error("No session generated yet");
  
  // Ensure the embedded session matches the top-level entity title
  if (detail.title) {
    detail.session.title = detail.title;
  }

  const res = await fetch(`${API_BASE}/workshop/export/pdf`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      session: detail.session,
      meta: detail.draftStateJson ? JSON.parse(detail.draftStateJson).workshopInput : undefined
    }),
  });
  if (!res.ok) throw new Error("PDF export failed");
  const blob = await res.blob();
  saveAs(blob, `session-${id}.pdf`);
}

/** Download PPTX */
export async function downloadPptx(id: string): Promise<void> {
  const detail = await getSessionDetail(id);
  if (!detail.session) throw new Error("No session generated yet");
  
  // Ensure the embedded session matches the top-level entity title
  if (detail.title) {
    detail.session.title = detail.title;
  }

  const res = await fetch(`${API_BASE}/workshop/export/pptx`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      session: detail.session,
      meta: detail.draftStateJson ? JSON.parse(detail.draftStateJson).workshopInput : undefined
    }),
  });
  if (!res.ok) throw new Error("PPTX export failed");
  const blob = await res.blob();
  saveAs(blob, `slides-${id}.pptx`);
}

/** Download ZIP of all Lecture Sessions */
export async function downloadLectureZip(id: string): Promise<void> {
  const res = await fetch(`${API_BASE}/workshop/export/lecture/${id}/zip`, {
    method: "GET",
  });
  if (!res.ok) throw new Error("ZIP export failed");
  const blob = await res.blob();
  saveAs(blob, `lecture-${id}.zip`);
}
