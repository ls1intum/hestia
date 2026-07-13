/**
 * Typed client for the Spring Boot backend (see `backend/`). This is now the
 * single transport for all data, storage, and admin operations — Supabase is
 * gone. Auth is a static bearer token (single-user backend); the matching
 * secret is `app.auth.token` on the server.
 *
 * Base URL comes from `VITE_API_BASE_URL` (default http://localhost:8081).
 */
import type { Exam, Section, SectionBlock, SectionFigure, Task } from "@/lib/exam-helpers";
import type { TaskAnswer, TaskGrade } from "@/lib/grading";
import type { BloomLevel, LearningGoalResponse, LghCourse, SoloLevel } from "@/lib/learning-goals";

const BASE_URL =
  (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/$/, "") || "http://localhost:8081";

const AUTH_TOKEN = (import.meta.env.VITE_API_AUTH_TOKEN ?? "dev-local-token") as string;

export function apiBaseUrl(): string {
  return BASE_URL;
}

export function apiToken(): string {
  return AUTH_TOKEN;
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
  return AUTH_TOKEN ? { Authorization: `Bearer ${AUTH_TOKEN}` } : {};
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
    throw new ApiError(resp.status, text, message);
  }

  if (resp.status === 204) return undefined as T;
  const ct = resp.headers.get("content-type") ?? "";
  if (ct.includes("application/json")) return (await resp.json()) as T;
  return (await resp.text()) as unknown as T;
}

// ---------------------------------------------------------------------------
// Health / smoke
// ---------------------------------------------------------------------------
export interface HealthzResponse { status: string; time: string; }
export function getHealthz(): Promise<HealthzResponse> {
  return apiRequest<HealthzResponse>("/api/healthz", { skipAuth: true });
}

// ---------------------------------------------------------------------------
// Exams
// ---------------------------------------------------------------------------

/**
 * Exam row as returned by the list endpoint, augmented with the progress counts
 * the dashboard table's "Progress" column needs. Counts are relative to
 * `task_count`; see backend `ExamProgressService`.
 */
export interface ExamListItem extends Exam {
  task_count: number;
  scored_count: number;
  answered_count: number;
  graded_count: number;
}

export const listExams = () => apiRequest<ExamListItem[]>("/api/exams");
export const getExam = (id: string) => apiRequest<Exam>(`/api/exams/${id}`);
export const createExam = (body: Record<string, unknown>) =>
  apiRequest<Exam>("/api/exams", { method: "POST", json: body });
export const patchExam = (id: string, patch: Record<string, unknown>) =>
  apiRequest<Exam>(`/api/exams/${id}`, { method: "PATCH", json: patch });
export const deleteExam = (id: string) =>
  apiRequest<void>(`/api/exams/${id}`, { method: "DELETE" });
export const duplicateExam = (id: string) =>
  apiRequest<Exam>(`/api/exams/${id}/duplicate`, { method: "POST" });
/** Cancel an in-progress parse/evaluate; backend reverts the exam to `failed`. */
export const cancelExam = (id: string) =>
  apiRequest<Exam>(`/api/exams/${id}/cancel`, { method: "POST" });

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
export const listTasks = (examId: string) => apiRequest<Task[]>(`/api/exams/${examId}/tasks`);
export const createTask = (body: Record<string, unknown>) =>
  apiRequest<Task>("/api/tasks", { method: "POST", json: body });
export const patchTask = (id: string, patch: Record<string, unknown>) =>
  apiRequest<Task>(`/api/tasks/${id}`, { method: "PATCH", json: patch });
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
  apiRequest<TaskAnswer[]>(`/api/exams/${examId}/answers`);
export const listGrades = (examId: string) =>
  apiRequest<TaskGrade[]>(`/api/exams/${examId}/grades`);
export const upsertGrade = (body: Record<string, unknown>) =>
  apiRequest<TaskGrade>("/api/task-grades", { method: "PUT", json: body });

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
// Parse survey
// ---------------------------------------------------------------------------
export const submitParseSurvey = (body: Record<string, unknown>) =>
  apiRequest<{ ok: boolean; id: string }>("/api/parse-survey", { method: "POST", json: body });

// ---------------------------------------------------------------------------
// Figures + files
// ---------------------------------------------------------------------------
export const listFigures = (blockId: string) =>
  apiRequest<SectionFigure[]>(`/api/blocks/${blockId}/figures`);
export function uploadFigure(blockId: string, file: File, position?: number) {
  const form = new FormData();
  form.append("file", file);
  if (position !== undefined) form.append("position", String(position));
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

// ---------------------------------------------------------------------------
// Admin
// ---------------------------------------------------------------------------
export const adminSurvey = () => apiRequest<unknown[]>("/api/admin/survey");
export const adminSurveyByModel = () => apiRequest<unknown[]>("/api/admin/survey-by-model");
