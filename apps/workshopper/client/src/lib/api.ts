import { saveAs } from "file-saver";
import { toast } from "@/hooks/use-toast";
import type {
  WorkshopInput,
  LearningGoalPlan,
  WorkshopSession,
  ActivityBlock,
  SlideData,
  GoalSuggestion,
  SessionSkeleton,
  SessionSummary,
  SessionDetail,
  DraftState,
  CourseGoal,
  AlignmentReview,
} from "./workshop-generator";

const API_BASE = import.meta.env.VITE_API_URL ?? import.meta.env.BASE_URL + "api";
/** Core's API (alignment review list); mapped like `api`: Vite proxy in dev, nginx of the client in production. */
const CORE_API_BASE = import.meta.env.BASE_URL + "core/api";

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

/**
 * Handles common auth errors globally: 401 redirects to the SAML login (unless autoRedirect is
 * false), 403 shows an "Access denied" toast. Returns true if the error was auth-related.
 */
export function handleAuthError(err: unknown, autoRedirect = true): boolean {
  if (err && typeof err === 'object' && 'status' in err) {
    if (err.status === 401) {
      if (autoRedirect) {
        console.warn("401 Unauthorized: Redirecting to SAML login");
        // Use BASE_URL (e.g. /workshopper/) and append saml2 path
        // Note: Central IT SAML ACS endpoint configuration must match this domain
        window.location.href = import.meta.env.BASE_URL + "saml2/authenticate/tum";
      }
      return true;
    }
    if (err.status === 403) {
      toast({ title: "Access denied", description: "You do not have permission to modify this session.", variant: "destructive" });
      return true;
    }
  }
  return false;
}

/** Parse a JSON body, tolerating empty responses (e.g. 200 with no body, 204). */
async function parseJson<T>(res: Response): Promise<T> {
  const text = await res.text();
  return (text ? JSON.parse(text) : {}) as T;
}

/** The CSRF token the server keeps in the XSRF-TOKEN cookie (readable by scripts on purpose). */
function csrfToken(): string | undefined {
  if (typeof document === "undefined") return undefined;
  const match = document.cookie.split("; ").find(c => c.startsWith("XSRF-TOKEN="));
  return match ? decodeURIComponent(match.slice("XSRF-TOKEN=".length)) : undefined;
}

/**
 * Sends a request to the Workshopper API with the session cookie; every write also sends the CSRF
 * token as X-XSRF-TOKEN. Throws an ApiError for any non-2xx answer.
 */
async function send(path: string, init: RequestInit = {}, base: string = API_BASE, uploadRetried = false): Promise<Response> {
  const headers = new Headers(init.headers);
  const method = (init.method ?? "GET").toUpperCase();
  const token = method === "GET" || method === "HEAD" ? undefined : csrfToken();
  if (token) headers.set("X-XSRF-TOKEN", token);
  const res = await fetch(`${base}${path}`, { ...init, headers, credentials: "same-origin" });
  if (!res.ok) {
    const body = await res.text();
    if (!uploadRetried && uploadConfirmationHandler && isUploadConfirmationRequired(res.status, body)
        && await uploadConfirmationHandler()) {
      return send(path, init, base, true);
    }
    throw new ApiError(res.status, readableError(body) || `HTTP ${res.status}`);
  }
  return res;
}

/**
 * Asked when the server refuses an upload with 428 UPLOAD_CONFIRMATION_REQUIRED (the user has not yet
 * confirmed that uploads contain no personal data of others); resolves true once they confirmed, so
 * the upload is repeated. Registered by the upload confirmation dialog.
 */
let uploadConfirmationHandler: (() => Promise<boolean>) | null = null;
export function setUploadConfirmationHandler(handler: (() => Promise<boolean>) | null): void {
  uploadConfirmationHandler = handler;
}

export function isUploadConfirmationRequired(status: number, body: string): boolean {
  if (status !== 428) return false;
  try {
    return JSON.parse(body)?.code === "UPLOAD_CONFIRMATION_REQUIRED";
  } catch {
    return false;
  }
}

/** The notice users confirm once before their first upload (in any of the three tools). */
export interface UploadConfirmation {
  confirmed: boolean;
  noticeVersion: string;
  /** Markdown */
  text: string;
  checkboxLabel: string;
  confirmedAt: string | null;
}

export function getUploadConfirmation(): Promise<UploadConfirmation> {
  return send("/privacy/upload-confirmation", {}, CORE_API_BASE).then(res => parseJson<UploadConfirmation>(res));
}

export function confirmUploads(noticeVersion: string): Promise<UploadConfirmation> {
  return send("/privacy/upload-confirmation", jsonBody("POST", { noticeVersion }), CORE_API_BASE)
    .then(res => parseJson<UploadConfirmation>(res));
}

/** A warning recorded while the user was away; shown once after the next login. */
export interface InactivityNotice {
  lastSeenAt: string;
  warnedAt: string;
  afterDays: number;
  deleteAfterDays: number;
}

export function getInactivityNotices(): Promise<InactivityNotice[]> {
  return send("/privacy/inactivity-notices", {}, CORE_API_BASE).then(res => parseJson<InactivityNotice[]>(res));
}

export async function acknowledgeInactivityNotices(): Promise<void> {
  await send("/privacy/inactivity-notices/acknowledge", { method: "POST" }, CORE_API_BASE);
}

/** The message in an error answer: {"error": …} from ApiExceptionHandler, {"detail": …} from Spring, or the text. */
function readableError(body: string): string {
  try {
    const json = JSON.parse(body);
    if (json && typeof json === "object") return json.error || json.detail || json.message || json.title || body;
  } catch { /* not JSON */ }
  return body;
}

function jsonBody(method: string, body: unknown): RequestInit {
  return { method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) };
}

const get = <T>(path: string) => send(path).then(res => parseJson<T>(res));
const post = <T>(path: string, body: unknown) => send(path, jsonBody("POST", body)).then(res => parseJson<T>(res));
const put = <T>(path: string, body: unknown) => send(path, jsonBody("PUT", body)).then(res => parseJson<T>(res));
const del = <T>(path: string) => send(path, { method: "DELETE" }).then(res => parseJson<T>(res));
/** POSTs JSON and returns the answer as a file (exports). */
const postForBlob = (path: string, body: unknown) => send(path, jsonBody("POST", body)).then(res => res.blob());
const postForm = (path: string, form: FormData) => send(path, { method: "POST", body: form });

/** The message to show for a failed request: the Error's own message, otherwise the fallback. */
export function errorMessage(err: unknown, fallback: string): string {
  if (err instanceof ApiError) return err.status >= 500 || !err.message ? fallback : err.message;
  return err instanceof Error && err.message ? err.message : fallback;
}

/**
 * Download file names, made from the session or course title as it is shown
 * (e.g. "Intro to ML - Slides.pptx"), with only the characters file systems forbid removed.
 */
export function safeFileName(title: string | undefined, fallback = "Workshop"): string {
  const clean = (title || "")
    .replace(/[\\/:*?"<>|\u0000-\u001f]/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .replace(/^\.+/, "") // a leading dot would hide the file
    .slice(0, 100)
    .trim();
  return clean || fallback;
}

/** The file names of the downloads, the same wherever they are downloaded from. */
export const downloadName = {
  slides: (title?: string) => `${safeFileName(title)} - Slides.pptx`,
  timetable: (title?: string) => `${safeFileName(title)} - Timetable.pdf`,
  course: (title?: string) => `${safeFileName(title, "Course")}.zip`,
};

/** Generates the session timetable from the goals, settings and client-built skeleton */
export function generateSession(
  goals: LearningGoalPlan[],
  meta: WorkshopInput,
  skeleton: SessionSkeleton
): Promise<WorkshopSession> {
  const availableMaterials = meta.availableMaterials?.join("\n") || "";
  return post<WorkshopSession>("/workshop/session", { goals, meta, availableMaterials, skeleton });
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
  courseId?: string
): Promise<{ id: string; courseId?: string }> {
  const title = draft.session?.title ?? draft.workshopInput?.title ?? (draft.workshopInput?.sessionType
    ? `${draft.workshopInput.sessionType.charAt(0).toUpperCase() + draft.workshopInput.sessionType.slice(1)} Session`
    : "Workshop Session");
  const body = {
    sessionId: sessionId ?? undefined,
    currentStep,
    title,
    draftStateJson: JSON.stringify(draft),
    courseId
  };

  // The answer names the session's course too: without a chosen one it is the personal course
  return post<{ id: string; courseId?: string }>("/workshop/sessions/draft", body);
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

/** Rename a course */
export function renameCourse(id: string, title: string): Promise<void> {
  return put<void>(`/workshop/courses/${id}/rename`, { title });
}

/** Moves a session to another course; the server removes its links to goals of the old course. */
export function moveSessionToCourse(id: string, courseId: string): Promise<void> {
  return put<void>(`/workshop/sessions/${id}/course`, { courseId });
}

/** The learning goals of a course (core), for the goal picker */
export function listCourseGoals(courseId: string): Promise<CourseGoal[]> {
  return get<CourseGoal[]>(`/workshop/courses/${courseId}/goals`);
}

/**
 * Saves the goals of the Goals step in the course: goals with a core id are changed if their text
 * differs, goals without one are created. Returns them in the same order.
 */
export function saveCourseGoals(courseId: string, goals: { id?: string; text: string }[]): Promise<CourseGoal[]> {
  return put<CourseGoal[]>(`/workshop/courses/${courseId}/goals`, goals);
}

/** Reorder sessions */
export function reorderSessions(sessionIds: string[]): Promise<void> {
  return put<void>('/workshop/sessions/reorder', sessionIds);
}

// ── Generation ─────────────────────────────────────────────────────────────

/** Generates the slides of one timetable block */
export function generateBlockSlides(block: ActivityBlock, meta: WorkshopInput, goals: LearningGoalPlan[]): Promise<SlideData[]> {
  return post<SlideData[]>("/workshop/export/block-slides", { block, meta, goals });
}

/** Saves the slides of a session, keyed by block index */
export function saveSlides(sessionId: string, slides: Record<number, SlideData[]>): Promise<void> {
  return post<void>(`/workshop/sessions/${sessionId}/slides`, slides);
}

/** Checks one learning goal and returns suggestions to split or refine it */
export function refineGoal(goal: string, context: Record<string, unknown>): Promise<GoalSuggestion[]> {
  return post<GoalSuggestion[]>("/workshop/refine-goal", { goal, context });
}

/** Fixes the grammar of the goals; returns them in the same order */
export function fixGoalsGrammar(goals: string[]): Promise<string[]> {
  return post<string[]>("/workshop/fix-goals-grammar", goals);
}

// ── Exports ────────────────────────────────────────────────────────────────

/** The session plan as PDF */
export function exportPdf(session: WorkshopSession, meta?: WorkshopInput, goals?: LearningGoalPlan[]): Promise<Blob> {
  return postForBlob("/workshop/export/pdf", { session, meta, goals });
}

/** The slide deck as PPTX, generating the slides with the LLM */
export function exportPptx(session: WorkshopSession, meta?: WorkshopInput, goals?: LearningGoalPlan[]): Promise<Blob> {
  return postForBlob("/workshop/export/pptx", { session, meta, goals });
}

/** The slide deck as PPTX, built from slides that were already generated */
export function assemblePptx(session: WorkshopSession, meta: WorkshopInput, prebuiltSlides: SlideData[]): Promise<Blob> {
  return postForBlob("/workshop/export/pptx-assemble", { session, meta, prebuiltSlides });
}

/** The slide deck as PPTX in the slide template stored for the session (or its course) */
export async function exportPptxWithTemplate(session: WorkshopSession, meta: WorkshopInput, slides: SlideData[]): Promise<Blob> {
  const form = new FormData();
  form.append("session", JSON.stringify(session));
  form.append("meta", JSON.stringify(meta));
  form.append("slides", JSON.stringify(slides));
  return (await postForm("/workshop/export/pptx-with-template", form)).blob();
}

/** Loads a saved session for export, with the title the dashboard shows */
async function loadForExport(id: string): Promise<{ session: WorkshopSession; meta?: WorkshopInput }> {
  const detail = await getSessionDetail(id);
  if (!detail.session) throw new Error("No session generated yet");
  if (detail.title) detail.session.title = detail.title;
  const meta = detail.draftStateJson ? JSON.parse(detail.draftStateJson).workshopInput : undefined;
  return { session: detail.session, meta };
}

/** Downloads the PDF of a saved session (dashboard) */
export async function downloadPdf(id: string): Promise<void> {
  const { session, meta } = await loadForExport(id);
  saveAs(await exportPdf(session, meta), downloadName.timetable(session.title));
}

/** Downloads the PPTX of a saved session (dashboard) */
export async function downloadPptx(id: string): Promise<void> {
  const { session, meta } = await loadForExport(id);
  saveAs(await exportPptx(session, meta), downloadName.slides(session.title));
}

/** Download ZIP of all Course Sessions */
export async function downloadCourseZip(id: string, title?: string): Promise<void> {
  const blob = await send(`/workshop/export/course/${id}/zip`).then(res => res.blob());
  saveAs(blob, downloadName.course(title));
}

/** Fetch all courses */
export function listCourses(): Promise<SessionSummary[]> {
  return get<SessionSummary[]>("/workshop/courses");
}

/** Save a course draft */
export async function saveCourseDraft(
  draft: DraftState,
  sessionId: string | null,
  currentStep: string
): Promise<string> {
  const title = draft.workshopInput?.title ?? "Workshop Course";
  const body = {
    sessionId: sessionId ?? undefined,
    currentStep,
    title,
    // A course only has settings; anything else in the wizard belongs to a session
    draftStateJson: JSON.stringify({ workshopInput: draft.workshopInput })
  };
  const res = await post<{ id: string }>("/workshop/courses/draft", body);
  return res.id;
}

/** Delete a course */
export function deleteCourse(id: string): Promise<void> {
  return del<void>(`/workshop/courses/${id}`);
}

export function getCourseDetail(id: string): Promise<SessionDetail> {
  return get<SessionDetail>(`/workshop/courses/${id}`);
}

export async function uploadCourseTemplate(courseId: string, file: File): Promise<void> {
  const formData = new FormData();
  formData.append('template', file);
  await postForm(`/workshop/courses/${courseId}/template`, formData);
}

export function deleteCourseTemplate(courseId: string): Promise<void> {
  return del<void>(`/workshop/courses/${courseId}/template`);
}

export async function uploadSessionTemplate(sessionId: string, file: File): Promise<void> {
  const formData = new FormData();
  formData.append('template', file);
  await postForm(`/workshop/sessions/${sessionId}/template`, formData);
}

/** Removes the template from this session only; the course template stays for other sessions. */
export function deleteSessionTemplate(sessionId: string): Promise<void> {
  return del<void>(`/workshop/sessions/${sessionId}/template`);
}

// ── Alignment review (core) ─────────────────────────────────────────────────

/** The course's alignment links marked for review because their goal changed or was removed. */
export function listReviews(courseId: string): Promise<AlignmentReview[]> {
  return send(`/courses/${encodeURIComponent(courseId)}/reviews`, {}, CORE_API_BASE).then(res => parseJson<AlignmentReview[]>(res));
}

/**
 * Confirms a link after review (needs EDITOR in the course) for the goal version the user saw;
 * answers 409 (ApiError) if the goal changed again since.
 */
export function confirmReview(courseId: string, linkId: string, goalVersion: number): Promise<void> {
  return send(
    `/courses/${encodeURIComponent(courseId)}/reviews/${encodeURIComponent(linkId)}/confirm`,
    { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ goalVersion }) },
    CORE_API_BASE,
  ).then(() => undefined);
}
