import { API_PREFIX, csrfHeaders } from "../api/client.ts";

/**
 * The privacy notices of core (`/core/api/privacy/...`, under the app's base path like its own API): the upload
 * confirmation users give once before their first upload in any of the three tools, and the
 * inactivity warnings recorded while they were away.
 */
const PRIVACY_API = `${API_PREFIX}/core/api/privacy`;

export interface UploadConfirmation {
  confirmed: boolean;
  noticeVersion: string;
  /** Markdown */
  text: string;
  checkboxLabel: string;
  confirmedAt: string | null;
}

export interface InactivityNotice {
  lastSeenAt: string;
  warnedAt: string;
  afterDays: number;
  deleteAfterDays: number;
}

async function request<T>(path: string, method = "GET", body?: unknown): Promise<T> {
  const response = await fetch(`${PRIVACY_API}${path}`, {
    method,
    credentials: "same-origin",
    headers: {
      ...csrfHeaders(method),
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export const getUploadConfirmation = () => request<UploadConfirmation>("/upload-confirmation");
export const confirmUploads = (noticeVersion: string) =>
  request<UploadConfirmation>("/upload-confirmation", "POST", { noticeVersion });
export const getInactivityNotices = () => request<InactivityNotice[]>("/inactivity-notices");
export const acknowledgeInactivityNotices = () => request<void>("/inactivity-notices/acknowledge", "POST");

/**
 * Asks the user for the upload confirmation unless it was already given; resolves true once it is
 * (or was), false if the user cancelled. The dialog registers itself as the asker; without it
 * (e.g. in unit tests) the server's 428 is the only check.
 */
type Asker = (notice: UploadConfirmation) => Promise<boolean>;
let asker: Asker | null = null;
export function setUploadConfirmationAsker(next: Asker | null): void {
  asker = next;
}

export async function ensureUploadConfirmed(): Promise<boolean> {
  if (!asker) return true;
  const current = await getUploadConfirmation();
  return current.confirmed || asker(current);
}
