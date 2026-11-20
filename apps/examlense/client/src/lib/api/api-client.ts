/**
 * Typed client for the Spring Boot backend (see `server/`), the single transport for all data
 * and storage calls. Auth is the session login of the hestia server (TUM SAML): the session
 * cookie travels with every request (`credentials: "include"`) and non-GET requests echo the
 * `XSRF-TOKEN` cookie in the `X-XSRF-TOKEN` header. A 401 sends the browser to the SAML login.
 *
 * Base URL comes from `VITE_API_BASE_URL` (default http://localhost:8081).
 */
import type { Examination, Section, SectionBlock, SectionFigure, TaskBlock } from "@/lib/exam/exam-helpers";
import type { AIAnswer, Grade } from "@/lib/grading/grading";
import type { BloomLevel, LearningGoalResponse, LghCourse, SoloLevel } from "@/lib/learning-goals/learning-goals";

const BASE_URL =
  (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "") || "http://localhost:8081";

export function apiBaseUrl(): string {
  return BASE_URL;
}

/** Where core serves its own endpoints (SAML login, logout): the origin of the API, not its /examlense prefix. */
function coreUrl(path: string): string {
  return /^https?:\/\//.test(BASE_URL) ? new URL(path, BASE_URL).href : `${BASE_URL}${path}`;
}

/** Core's API next to the page (under the app's base path in production, proxied by vite in dev). */
export function corePageUrl(path: string): string {
  return `${import.meta.env.BASE_URL.replace(/\/$/, "")}${path.startsWith("/") ? "" : "/"}${path}`;
}

/** The SAML login the browser is sent to when there is no session. */
export const loginUrl = () => coreUrl("/saml2/authenticate/tum");

/** Value of the `XSRF-TOKEN` cookie, which the server expects back in `X-XSRF-TOKEN` on writes. */
export function xsrfToken(): string | undefined {
  const match = /(?:^|;\s*)XSRF-TOKEN=([^;]*)/.exec(document.cookie);
  return match ? decodeURIComponent(match[1]) : undefined;
}

export class ApiClientNotConfiguredError extends Error {
  constructor() {
    super("VITE_API_BASE_URL is not set; backend is unreachable.");
    this.name = "ApiClientNotConfiguredError";
  }
}

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly body: string,
    message?: string,
  ) {
    super(message ?? `API request failed with ${status}`);
    this.name = "ApiError";
  }
}

export function isApiClientConfigured(): boolean {
  return BASE_URL.length > 0;
}

export interface ApiRequestOptions extends Omit<RequestInit, "headers" | "body"> {
  /** JSON body — will be stringified and Content-Type set automatically. */
  json?: unknown;
  /** Raw body for non-JSON requests (FormData, blobs, ...). Content-Type is left to the browser. */
  body?: BodyInit;
  headers?: Record<string, string>;
  /** Call core's API (`/core/api/...`) on the page's own origin instead of the ExamLense API. */
  core?: boolean;
  /** Do not redirect to the login on 401 (only for probes that expect it). */
  skipAuth?: boolean;
  /** Set on the retry after the user consented; a second 428 is then an error. */
  consentRetried?: boolean;
}

/**
 * Asked when the server answers 428 CONSENT_REQUIRED (an external AI model needs the user consent):
 * shows the notice and resolves true if the user consented, so the request is repeated.
 * Registered by the consent dialog; without one the 428 is just an error.
 */
type ConsentHandler = () => Promise<boolean>;
let consentHandler: ConsentHandler | null = null;
export function setConsentHandler(handler: ConsentHandler | null): void {
  consentHandler = handler;
}

export function isConsentRequired(status: number, body: string): boolean {
  if (status !== 428) return false;
  try {
    return JSON.parse(body)?.code === "CONSENT_REQUIRED";
  } catch {
    return false;
  }
}

export async function apiRequest<T = unknown>(
  path: string,
  options: ApiRequestOptions = {},
): Promise<T> {
  const { json, body, headers, skipAuth, consentRetried, core, ...rest } = options;
  const method = (rest.method ?? "GET").toUpperCase();
  const csrf = method === "GET" || method === "HEAD" ? undefined : xsrfToken();
  const finalHeaders: Record<string, string> = {
    Accept: "application/json",
    ...(csrf ? { "X-XSRF-TOKEN": csrf } : {}),
    ...(headers ?? {}),
  };

  let finalBody: BodyInit | undefined = body;
  if (json !== undefined) {
    finalHeaders["Content-Type"] = finalHeaders["Content-Type"] ?? "application/json";
    finalBody = JSON.stringify(json);
  }

  const url = core ? corePageUrl(path) : `${BASE_URL}${path.startsWith("/") ? "" : "/"}${path}`;
  const resp = await fetch(url, {
    ...rest,
    credentials: "include",
    headers: finalHeaders,
    body: finalBody,
  });

  if (!resp.ok) {
    const text = await resp.text().catch(() => "");
    let message: string | undefined;
    try {
      const parsed = JSON.parse(text);
      if (parsed && typeof parsed === "object" && "error" in parsed) message = String(parsed.error);
    } catch {
      /* not json */
    }
    // No (or an expired) session: the only way forward is the login. `skipAuth` calls say
    // nothing about it.
    if (resp.status === 401 && !skipAuth) {
      window.location.assign(loginUrl());
    }
    if (!consentRetried && consentHandler && isConsentRequired(resp.status, text)) {
      if (await consentHandler()) {
        return apiRequest<T>(path, { ...options, consentRetried: true });
      }
    }
    throw new ApiError(resp.status, text, message);
  }

  if (resp.status === 204) return undefined as T;
  const ct = resp.headers.get("content-type") ?? "";
  if (ct.includes("application/json")) return (await resp.json()) as T;
  return (await resp.text()) as unknown as T;
}

// ---------------------------------------------------------------------------
// Identity
// ---------------------------------------------------------------------------

/** Daily LLM usage of the user; a limit of 0 means no limit. */
export interface Quota {
  parse_remaining: number;
  parse_limit: number;
  solve_remaining: number;
  solve_limit: number;
  parse_used: number;
  solve_used: number;
}

export interface Me {
  id: string;
  display_name: string | null;
  /** Platform admin: may see the parse-metrics dashboard. */
  is_admin: boolean;
  quota: Quota;
}

/** Doubles as the session probe: a 200 means the user is signed in. */
export const getMe = () => apiRequest<Me>("/api/me");

/** The user consent to external AI models and the notice they consent to (Markdown). */
export interface AiConsent {
  granted: boolean;
  notice_version: string;
  last_action: "GRANTED" | "REVOKED" | null;
  last_action_at: string | null;
  notice: { version: string; text: string };
}

export const getAiConsent = () => apiRequest<AiConsent>("/api/ai-consent");
export const grantAiConsent = () => apiRequest<AiConsent>("/api/ai-consent", { method: "POST" });
export const revokeAiConsent = () => apiRequest<AiConsent>("/api/ai-consent", { method: "DELETE" });

/** End the session (server answers 204), then reload so the app starts from the login. */
export async function signOut(): Promise<void> {
  const csrf = xsrfToken();
  await fetch(coreUrl("/api/logout"), {
    method: "POST",
    credentials: "include",
    headers: csrf ? { "X-XSRF-TOKEN": csrf } : {},
  });
  window.location.reload();
}

// ---------------------------------------------------------------------------
// ---------------------------------------------------------------------------
// Exams
// ---------------------------------------------------------------------------

/**
 * Examination row as returned by the list endpoint, augmented with the progress counts
 * the dashboard table's "Progress" column needs. Counts are relative to
 * `task_count`; see backend `ExamProgressService`.
 */
export interface ExaminationListItem extends Examination {
  task_count: number;
  scored_count: number;
  answered_count: number;
  graded_count: number;
  /** Total sections and how many are confirmed — backs the dashboard "prepare" step. */
  section_count: number;
  confirmed_section_count: number;
}

export const listExams = () => apiRequest<ExaminationListItem[]>("/api/exams");
export const getExam = (id: string) => apiRequest<Examination>(`/api/exams/${id}`);
export const createExam = (body: Record<string, unknown>) =>
  apiRequest<Examination>("/api/exams", { method: "POST", json: body });
export const patchExam = (id: string, patch: Record<string, unknown>) =>
  apiRequest<Examination>(`/api/exams/${id}`, { method: "PATCH", json: patch });
export const deleteExam = (id: string) =>
  apiRequest<void>(`/api/exams/${id}`, { method: "DELETE" });
/** Move an exam to another course; links of its tasks to goals of the old course are removed. */
export const moveExamToCourse = (id: string, courseId: string) =>
  apiRequest<Examination>(`/api/exams/${id}/course`, { method: "PUT", json: { courseId } });
export const duplicateExam = (
  id: string,
  body?: { title?: string; solver_model?: string },
) =>
  apiRequest<Examination>(`/api/exams/${id}/duplicate`, {
    method: "POST",
    ...(body ? { json: body } : {}),
  });

/**
 * Cancel an in-progress parse/evaluate. The backend reverts a mid-solve exam to
 * `ready` (back to editable) and a mid-parse exam to `failed` (offers a
 * re-parse); that revert is also what stops the still-running fire-and-forget
 * job from finalizing. See `ExamRepository.cancelEvaluating` for the reasoning.
 */
export const cancelExam = (id: string) =>
  apiRequest<Examination>(`/api/exams/${id}/cancel`, { method: "POST" });

// ---------------------------------------------------------------------------
// Sections
// ---------------------------------------------------------------------------
export const listSections = (examId: string) =>
  apiRequest<Section[]>(`/api/exams/${examId}/sections`);
export const createSection = (body: Record<string, unknown>) =>
  apiRequest<Section>("/api/sections", { method: "POST", json: body });
export const patchSection = (id: string, patch: Record<string, unknown>) =>
  apiRequest<Section>(`/api/sections/${id}`, { method: "PATCH", json: patch });
export const deleteSection = (id: string) =>
  apiRequest<void>(`/api/sections/${id}`, { method: "DELETE" });
export const confirmSection = (id: string) =>
  apiRequest<Section>(`/api/sections/${id}/confirm`, { method: "POST" });
export const unconfirmSection = (id: string) =>
  apiRequest<Section>(`/api/sections/${id}/unconfirm`, { method: "POST" });

// ---------------------------------------------------------------------------
// Tasks
// ---------------------------------------------------------------------------
export const listTasks = (examId: string) => apiRequest<TaskBlock[]>(`/api/exams/${examId}/tasks`);
export const createTask = (body: Record<string, unknown>) =>
  apiRequest<TaskBlock>("/api/tasks", { method: "POST", json: body });
export const patchTask = (id: string, patch: Record<string, unknown>) =>
  apiRequest<TaskBlock>(`/api/tasks/${id}`, { method: "PATCH", json: patch });
export const deleteTask = (id: string) =>
  apiRequest<void>(`/api/tasks/${id}`, { method: "DELETE" });
export const deleteTasksBySection = (examId: string, sectionId: string) =>
  apiRequest<void>(`/api/exams/${examId}/tasks?section_id=${sectionId}`, { method: "DELETE" });

// ---------------------------------------------------------------------------
// Section blocks
// ---------------------------------------------------------------------------
export const listBlocks = (examId: string) =>
  apiRequest<SectionBlock[]>(`/api/exams/${examId}/blocks`);
export const createBlock = (body: Record<string, unknown>) =>
  apiRequest<SectionBlock>("/api/blocks", { method: "POST", json: body });
export const patchBlock = (id: string, patch: Record<string, unknown>) =>
  apiRequest<SectionBlock>(`/api/blocks/${id}`, { method: "PATCH", json: patch });
export const deleteBlock = (id: string) =>
  apiRequest<void>(`/api/blocks/${id}`, { method: "DELETE" });
export const deleteBlocksBySection = (examId: string, sectionId: string) =>
  apiRequest<void>(`/api/exams/${examId}/blocks?section_id=${sectionId}`, { method: "DELETE" });

// ---------------------------------------------------------------------------
// Answers / grades
// ---------------------------------------------------------------------------
export const listAnswers = (examId: string) =>
  apiRequest<AIAnswer[]>(`/api/exams/${examId}/answers`);
export const listGrades = (examId: string) =>
  apiRequest<Grade[]>(`/api/exams/${examId}/grades`);
export const upsertGrade = (body: Record<string, unknown>) =>
  apiRequest<Grade>("/api/task-grades", { method: "PUT", json: body });

// ---------------------------------------------------------------------------
// Courses and learning goals
// ---------------------------------------------------------------------------
interface LearningGoalDto {
  id: string;
  text: string;
  bloom_level: BloomLevel | null;
  solo_level: SoloLevel | null;
  status: "PENDING" | "APPROVED" | null;
}

/** Marks a goal link of a task as checked (needs EDITOR in the goal's course). */
export const confirmGoalReview = (courseId: string, linkId: string) =>
  apiRequest<unknown>(`/core/api/courses/${courseId}/reviews/${linkId}/confirm`, {
    method: "POST",
    core: true,
  });

export const listLghCourses = () => apiRequest<LghCourse[]>("/api/lgh/courses");

/** Create a new, empty course (name only; the caller owns it) and return it with its id. */
export const createLghCourse = (name: string) =>
  apiRequest<LghCourse>("/api/lgh/courses", { method: "POST", json: { name } });

/** The resolved learning goals linked to an exam's tasks. */
export async function getExamLearningGoals(examId: string): Promise<LearningGoalResponse[]> {
  const rows = await apiRequest<LearningGoalDto[]>(`/api/exams/${examId}/learning-goals`);
  return rows.map((r) => ({
    id: r.id,
    text: r.text,
    status: r.status,
    bloomLevel: r.bloom_level,
    soloLevel: r.solo_level,
  }));
}

// ---------------------------------------------------------------------------
// Figures + files
// ---------------------------------------------------------------------------
export const listFigures = (blockId: string) =>
  apiRequest<SectionFigure[]>(`/api/blocks/${blockId}/figures`);
export function uploadFigure(blockId: string, file: File) {
  const form = new FormData();
  form.append("file", file);
  return apiRequest<SectionFigure>(`/api/blocks/${blockId}/figures`, { method: "POST", body: form });
}
export const patchFigure = (id: string, patch: Record<string, unknown>) =>
  apiRequest<SectionFigure>(`/api/figures/${id}`, { method: "PATCH", json: patch });
export const deleteFigure = (id: string) =>
  apiRequest<void>(`/api/figures/${id}`, { method: "DELETE" });
export const getFigureSignedUrl = (id: string) =>
  apiRequest<{ signed_url: string }>(`/api/figures/${id}/signed-url`);
export function uploadExamPdf(examId: string, file: File) {
  const form = new FormData();
  form.append("file", file);
  return apiRequest<{ storage_path: string }>(`/api/exams/${examId}/pdf`, { method: "POST", body: form });
}
