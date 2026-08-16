import type { QueryClient } from "@tanstack/react-query";
import { patchExam, type ExamListItem } from "@/lib/api/api-client";
import { parseExamPdf } from "@/lib/api/api-parse";
import { toast } from "@/hooks/ui/use-toast";

type RetryableExam = Pick<ExamListItem, "id" | "source_file_url">;

/**
 * Re-run parsing for an existing/failed exam: reset it to "parsing" (clearing
 * the prior error), then fire the parse request. Shared by the dashboard row
 * action and the editor error screen so the retry logic lives in one place.
 */
export async function retryParse(
  exam: RetryableExam,
  queryClient: QueryClient,
): Promise<void> {
  if (!exam.source_file_url) {
    toast({
      title: "Couldn't start parsing. Please try again.",
      variant: "destructive",
    });
    return;
  }
  try {
    await patchExam(exam.id, { status: "parsing", parse_error: null });
  } catch {
    toast({
      title: "Couldn't start parsing. Please try again.",
      variant: "destructive",
    });
    return;
  }
  queryClient.invalidateQueries({ queryKey: ["exams-list"] });
  queryClient.invalidateQueries({ queryKey: ["exam", exam.id] });
  parseExamPdf({
    examId: exam.id,
    storagePath: exam.source_file_url,
  }).catch((e) => console.error("retry invoke failed", e));
  toast({
    title: "Parsing started — we'll update your dashboard when it's ready.",
  });
}
