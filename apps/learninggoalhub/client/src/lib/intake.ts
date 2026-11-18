import { API_PREFIX, api } from "../api/client.ts";
import type { Schemas } from "../api/client.ts";
import { requestError } from "./requestError.ts";

export type IntakeTerms = Schemas["TermsResponse"];
export type IntakeMe = Schemas["MeResponse"];
export type IntakeSubmission = Schemas["SubmissionResponse"];
export type IntakeFile = Schemas["FileResponse"];

/** Where the intake lives inside the client; the login sends people back here. */
export const INTAKE_PATH = "/einreichen";
const RETURN_KEY = "lgh:returnTo";

export const ACCEPTED_EXTENSIONS = [".pdf", ".pptx", ".ppt", ".docx", ".doc", ".xlsx", ".odt", ".odp", ".zip", ".txt", ".md", ".tex"];
export const MAX_FILES = 10;
export const MAX_FILE_BYTES = 50 * 1024 * 1024;

export async function fetchTerms(): Promise<IntakeTerms> {
  const result = await api.GET("/api/intake/terms");
  if (!result.data) throw requestError("Die Nutzungsbedingungen konnten nicht geladen werden.", result);
  return result.data;
}

export async function fetchMe(): Promise<IntakeMe> {
  const result = await api.GET("/api/intake/me");
  if (!result.data) throw requestError("Die Anmeldung konnte nicht geprüft werden.", result);
  return result.data;
}

export async function fetchMine(): Promise<IntakeSubmission[]> {
  const result = await api.GET("/api/intake/submissions");
  if (!result.data) throw requestError("Ihre Einreichungen konnten nicht geladen werden.", result);
  return result.data;
}

export async function fetchAll(): Promise<IntakeSubmission[]> {
  const result = await api.GET("/api/intake/admin/submissions");
  if (!result.data) throw requestError("Die Einreichungen konnten nicht geladen werden.", result);
  return result.data;
}

export async function deleteSubmission(id: string): Promise<void> {
  const result = await api.DELETE("/api/intake/admin/submissions/{submissionId}", {
    params: { path: { submissionId: id } },
  });
  if (!result.response.ok) throw requestError("Die Einreichung konnte nicht gelöscht werden.", result);
}

export function fileUrl(submissionId: string, fileId: string): string {
  return `${API_PREFIX}/api/intake/admin/submissions/${submissionId}/files/${fileId}`;
}

export interface SubmissionForm {
  terms: IntakeTerms;
  files: File[];
  confirmedRights: boolean;
  confirmedNoStudentData: boolean;
  consentPublication: boolean;
  consentExternalAi: boolean;
  contactEmail: string;
  note: string;
}

/** Multipart upload; openapi-fetch does not build multipart bodies, so this uses fetch directly. */
export async function submit(form: SubmissionForm): Promise<IntakeSubmission> {
  const body = new FormData();
  for (const file of form.files) body.append("files", file);
  body.append("termsVersion", form.terms.version ?? "");
  body.append("termsSha256", form.terms.sha256 ?? "");
  body.append("confirmedRights", String(form.confirmedRights));
  body.append("confirmedNoStudentData", String(form.confirmedNoStudentData));
  body.append("consentPublication", String(form.consentPublication));
  body.append("consentExternalAi", String(form.consentExternalAi));
  if (form.contactEmail.trim()) body.append("contactEmail", form.contactEmail.trim());
  if (form.note.trim()) body.append("note", form.note.trim());

  const response = await fetch(`${API_PREFIX}/api/intake/submissions`, { method: "POST", body });
  if (response.status === 401) {
    window.dispatchEvent(new CustomEvent("lgh:unauthorized"));
  }
  if (!response.ok) {
    let error: unknown;
    try {
      error = await response.json();
    } catch {
      error = undefined;
    }
    throw requestError(uploadFailure(response.status), { error, response });
  }
  return (await response.json()) as IntakeSubmission;
}

function uploadFailure(status: number): string {
  switch (status) {
    case 409:
      return "Die Nutzungsbedingungen haben sich geändert. Bitte laden Sie die Seite neu.";
    case 413:
      return "Eine Datei ist zu groß (höchstens 50 MB je Datei).";
    case 415:
      return "Ein Dateityp wird nicht angenommen.";
    default:
      return "Das Hochladen ist fehlgeschlagen.";
  }
}

/** Remembers to come back to the intake after the SAML round trip, which always lands on "/". */
export function rememberReturn(path: string): void {
  try {
    sessionStorage.setItem(RETURN_KEY, path);
  } catch {
    // Storage blocked: the user lands on the start page instead.
  }
}

export function takeReturn(): string | null {
  try {
    const path = sessionStorage.getItem(RETURN_KEY);
    sessionStorage.removeItem(RETURN_KEY);
    return path;
  } catch {
    return null;
  }
}

export function formatBytes(bytes: number | undefined): string {
  if (bytes == null) return "";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export function hasAcceptedExtension(name: string): boolean {
  const lower = name.toLowerCase();
  return ACCEPTED_EXTENSIONS.some((extension) => lower.endsWith(extension));
}

export function formatDate(iso: string | undefined): string {
  if (!iso) return "";
  return new Date(iso).toLocaleString("de-DE", { dateStyle: "medium", timeStyle: "short" });
}
