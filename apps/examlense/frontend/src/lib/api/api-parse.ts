/**
 * Frontend helper for the Spring Boot `parse-exam-pdf` endpoint.
 *
 * The backend is now the only transport (Supabase is gone), so this always
 * routes to `POST /api/parse-exam-pdf`.
 */
import { apiRequest } from "@/lib/api/api-client";

export interface ParseExamArgs {
  examId: string;
  storagePath: string;
  parserModel?: string | null;
  fastMode?: boolean;
  languageHint?: string | null;
}

/**
 * Kick off PDF parsing for an exam. Resolves once the server has accepted
 * the request — actual extraction continues in the background and progress
 * is observed via the exam SSE stream.
 */
export async function parseExamPdf(args: ParseExamArgs): Promise<void> {
  await apiRequest<{ ok: boolean }>("/api/parse-exam-pdf", {
    method: "POST",
    json: {
      exam_id: args.examId,
      storage_path: args.storagePath,
      parser_model: args.parserModel ?? undefined,
      fast_mode: args.fastMode ?? undefined,
      language_hint: args.languageHint ?? undefined,
    },
  });
}
