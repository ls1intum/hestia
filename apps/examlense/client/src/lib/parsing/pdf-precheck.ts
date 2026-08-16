/**
 * Client-side upload pre-checks for exam files. These run before an exam is even
 * created so the author gets immediate, specific feedback instead of a
 * server-side failure. Two kinds are accepted:
 *   - PDF: fully checked here (type, size, %PDF magic bytes, and page count via
 *     pdfjs — mirroring the backend MAX_PAGES).
 *   - Word .docx: only type + size are checked here (the browser can't reliably
 *     page-count or validate a docx); it is converted to PDF server-side at upload
 *     and the page-count / structure checks run on that PDF via ParseErrorMessages.
 * Copy mirrors the backend messages for these cases. Legacy .doc is not supported.
 */
import * as pdfjs from "pdfjs-dist";
// Vite's `?url` gives the raw, untransformed worker asset URL (honoring the
// production base "/examlense/"). Importing the worker any other way lets Vite's
// dev server inject "/@vite/client" into the file, which then throws inside the
// Worker context (no DOM / import.meta.hot) and silently breaks page counting in
// `npm run dev`. Do not hardcode a path or CDN.
import pdfWorkerUrl from "pdfjs-dist/build/pdf.worker.min.mjs?url";

pdfjs.GlobalWorkerOptions.workerSrc = pdfWorkerUrl;

/** Max upload size for exam files (10 MB). Mirrors backend `MAX_BYTES`. */
export const MAX_PDF_BYTES = 10 * 1024 * 1024;

/** Max PDF pages. Mirrors backend `ParseExamService.MAX_PAGES`. */
export const MAX_PDF_PAGES = 100;

const PDF_TYPE = "application/pdf";
const DOCX_TYPE =
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
const DOC_TYPE = "application/msword";

/** MIME types + extensions accepted by the upload input's `accept` attribute. */
export const UPLOAD_ACCEPT = [PDF_TYPE, DOCX_TYPE, ".pdf", ".docx"].join(",");

export const PDF_PRECHECK_MESSAGES = {
  notSupported:
    "Only PDF and Word .docx files are supported. Please upload one of those and retry.",
  legacyDoc:
    "Legacy Word (.doc) isn't supported. Please save it as .docx or PDF and retry.",
  tooLarge:
    "This file is larger than the 10 MB limit. Please upload a smaller file (compress or split it) and retry.",
  invalid:
    "This file doesn't look like a valid PDF. Please upload a proper PDF exam and retry.",
  tooManyPages: `This PDF has more than ${MAX_PDF_PAGES} pages, over the ${MAX_PDF_PAGES}-page limit. Please upload a shorter document (split it if needed) and retry.`,
} as const;

type UploadKind = "pdf" | "docx";

/** Classify by MIME type, falling back to the filename extension. */
function uploadKind(file: File): UploadKind | "doc" | null {
  if (file.type === PDF_TYPE) return "pdf";
  if (file.type === DOCX_TYPE) return "docx";
  if (file.type === DOC_TYPE) return "doc";
  const name = file.name.toLowerCase();
  if (name.endsWith(".pdf")) return "pdf";
  if (name.endsWith(".docx")) return "docx";
  if (name.endsWith(".doc")) return "doc";
  return null;
}

/** Synchronous checks (type + size) — for instant on-select feedback. */
export function quickCheckUpload(file: File): string | null {
  const kind = uploadKind(file);
  if (kind === "doc") return PDF_PRECHECK_MESSAGES.legacyDoc;
  if (kind === null) return PDF_PRECHECK_MESSAGES.notSupported;
  if (file.size > MAX_PDF_BYTES) return PDF_PRECHECK_MESSAGES.tooLarge;
  return null;
}

export interface UploadCheck {
  /** User-facing message when a completable check failed, else null. */
  error: string | null;
  /**
   * PDF page count from the single pdfjs parse, reused by the caller for the
   * pre-parse time estimate. Null for docx (page-counted server-side after
   * conversion) or when pdfjs couldn't open the PDF — treat null as "unknown".
   */
  pageCount: number | null;
}

/**
 * Full pre-check completable client-side. PDFs are validated by magic bytes and
 * page count (via a single pdfjs parse, whose count is also returned for the
 * time estimate); docx files pass through after type + size (converted and fully
 * checked server-side). Returns the first failing message, or null error if ok.
 */
export async function validateUploadFile(file: File): Promise<UploadCheck> {
  const quick = quickCheckUpload(file);
  if (quick) return { error: quick, pageCount: null };

  // docx: nothing more we can reliably check in the browser.
  if (uploadKind(file) !== "pdf") return { error: null, pageCount: null };

  let buffer: ArrayBuffer;
  try {
    buffer = await file.arrayBuffer();
  } catch {
    return { error: PDF_PRECHECK_MESSAGES.invalid, pageCount: null };
  }

  // Magic bytes: look for "%PDF-" within the first 1 KB rather than requiring it
  // at byte 0. The spec tolerates some leading bytes (BOM / whitespace) before
  // the header, and plenty of otherwise-valid PDFs have them — an exact byte-0
  // check rejected those. latin1 maps each byte to a char so the search is safe.
  // Keep this rule in sync with the backend gate (FileController.isPdf).
  const head = new TextDecoder("latin1").decode(
    new Uint8Array(buffer.slice(0, 1024)),
  );
  if (!head.includes("%PDF-")) return { error: PDF_PRECHECK_MESSAGES.invalid, pageCount: null };

  // Single pdfjs parse serves both the page-limit check and the caller's time
  // estimate. Advisory only: pdfjs legitimately fails to open some PDFs the
  // server can still parse (encryption, linearization, unusual structure), so a
  // failure here must NOT block the upload — it just leaves pageCount unknown.
  // Only a confirmed over-limit count fails. The backend enforces the real limit.
  let pageCount: number | null = null;
  try {
    const task = pdfjs.getDocument({ data: new Uint8Array(buffer) });
    const doc = await task.promise;
    pageCount = doc.numPages;
    await task.destroy();
    if (pageCount > MAX_PDF_PAGES) return { error: PDF_PRECHECK_MESSAGES.tooManyPages, pageCount };
  } catch {
    // Ignore — leave validity/page-count to the server.
  }
  return { error: null, pageCount };
}
