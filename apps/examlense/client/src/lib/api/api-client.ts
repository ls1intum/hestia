/**
 * Typed client for the Spring Boot backend (see `server/`). This is now the
 * single transport for all data, storage, and admin operations — Supabase is
 * gone. Auth is a per-user bearer token held in `token-store.ts`; a user obtains
 * one by claiming an enrolment invite.
 *
 * Base URL comes from `VITE_API_BASE_URL` (default http://localhost:8081).
 */
import type { Examination, Section, SectionBlock, SectionFigure, TaskBlock } from "@/lib/exam/exam-helpers";
import type { AIAnswer, Grade } from "@/lib/grading/grading";
import type { BloomLevel, LearningGoalResponse, LghCourse, SoloLevel } from "@/lib/learning-goals/learning-goals";
import { clearToken, getToken, setToken } from "@/lib/api/token-store";

const BASE_URL =
  (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "") || "http://localhost:8081";

export function apiBaseUrl(): string {
  return BASE_URL;
}

/**
 * The single read point for the credential — `sse.ts` uses it too, for the
 * `?token=` query param an `EventSource` needs (it cannot set headers). Reading
 * through the store on every call is what lets sign-in and sign-out take effect
 * without a page reload.
 */
export function apiToken(): string {
  return getToken();
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

function authHeader(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

export interface ApiRequestOptions extends Omit<RequestInit, "headers" | "body"> {
  /** JSON body — will be stringified and Content-Type set automatically. */
  json?: unknown;
  /** Raw body for non-JSON requests (FormData, blobs, ...). Content-Type is left to the browser. */
  body?: BodyInit;
  headers?: Record<string, string>;
  /** Skip auth header injection (only for unauthenticated probes). */
  skipAuth?: boolean;
}

export async function apiRequest<T = unknown>(
  path: string,
  options: ApiRequestOptions = {},
): Promise<T> {
  const { json, body, headers, skipAuth, ...rest } = options;
  const finalHeaders: Record<string, string> = {
    Accept: "application/json",
    ...(skipAuth ? {} : authHeader()),
    ...(headers ?? {}),
  };

  let finalBody: BodyInit | undefined = body;
  if (json !== undefined) {
    finalHeaders["Content-Type"] = finalHeaders["Content-Type"] ?? "application/json";
    finalBody = JSON.stringify(json);
  }

  const resp = await fetch(`${BASE_URL}${path.startsWith("/") ? "" : "/"}${path}`, {
    ...rest,
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
    // A rejected credential is unrecoverable for this session, so drop it and let
    // the gate take over rather than leaving every subsequent call to fail too.
    // `skipAuth` calls are unauthenticated by nature and say nothing about it.
    if (resp.status === 401 && !skipAuth) clearToken();
    throw new ApiError(resp.status, text, message);
  }

  if (resp.status === 204) return undefined as T;
  const ct = resp.headers.get("content-type") ?? "";
  if (ct.includes("application/json")) return (await resp.json()) as T;
  return (await resp.text()) as unknown as T;
}

// ---------------------------------------------------------------------------
// Identity and enrolment
// ---------------------------------------------------------------------------

export interface Quota {
  parse_remaining: number;
  parse_limit: number;
  solve_remaining: number;
  solve_limit: number;
}

export interface Me {
  id: string;
  /** The TUM ID, or a generated `anon-…` handle if the user hasn't linked one. */
  external_id: string;
  display_name: string | null;
  is_admin: boolean;
  quota: Quota;
  /** False until a TUM ID is linked; unlinked accounts don't survive the SAML cutover. */
  has_tum_id: boolean;
}

/** Doubles as the token-validity probe: a 200 means the stored token works. */
export const getMe = () => apiRequest<Me>("/api/me");

export interface Registration {
  token: string;
  user: Me;
}

/**
 * Create an account for a visitor who has none, and store its token.
 *
 * `skipAuth` because the caller has no credential yet — that is the whole point of
 * the endpoint. On success the token is persisted, so subsequent calls authenticate
 * normally.
 */
export async function register(): Promise<Registration> {
  const result = await apiRequest<Registration>("/api/auth/register", {
    method: "POST",
    skipAuth: true,
  });
  setToken(result.token);
  return result;
}

/**
 * Attach a TUM ID to the signed-in account. Optional, but it is what carries the
 * account's exams across the eventual switch to TUM sign-in.
 */
export const linkTumId = (externalId: string) =>
  apiRequest<Me>("/api/me", { method: "PATCH", json: { external_id: externalId } });

export function signOut(): void {
  clearToken();
}

// --- Admin: users and invites -----------------------------------------------

export interface AdminUser {
  id: string;
  external_id: string;
  display_name: string | null;
  is_admin: boolean;
  has_active_token: boolean;
}

export const listAdminUsers = () => apiRequest<AdminUser[]>("/api/admin/users");

export const setUserAdmin = (id: string, isAdmin: boolean) =>
  apiRequest<AdminUser>(`/api/admin/users/${id}`, { method: "PATCH", json: { is_admin: isAdmin } });

export const revokeUserTokens = (id: string) =>
  apiRequest<void>(`/api/admin/users/${id}/tokens`, { method: "DELETE" });

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
// LearningGoalHub proxy (backend-mediated; LGH itself is VPN-only)
// ---------------------------------------------------------------------------
interface LearningGoalDto {
  id: number;
  text: string;
  bloom_level: BloomLevel | null;
  solo_level: SoloLevel | null;
  status: "PENDING" | "APPROVED" | null;
}

export const listLghCourses = () => apiRequest<LghCourse[]>("/api/lgh/courses");

/** Create a new, empty LearningGoalHub course (name only) and return it with its id. */
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
