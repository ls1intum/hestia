import { toast } from "@/hooks/ui/use-toast";
import { ParseFailureDetails } from "@/components/shared/ParseFailureDetails";

const PARSE_FALLBACK =
  "We couldn't read this PDF. Please retry or upload a different file.";
const EVAL_FALLBACK =
  "Something went wrong while evaluating. You can retry evaluation.";

/**
 * Fire a slim failure snackbar. Shared by the dashboard (per exam that
 * transitions to failed) and the editor error path so both look identical.
 * `isParse` picks parse-failure vs. evaluation-failure copy.
 */
export function notifyExamFailure(
  examTitle: string | null | undefined,
  errorMessage: string | null | undefined,
  isParse: boolean,
) {
  const label = isParse ? "Parsing failed" : "Evaluation failed";
  toast({
    variant: "destructive",
    // Longer than the default so there's time to click "View details"; Radix
    // also pauses the timer while the pointer is over the toast.
    duration: 10000,
    title: examTitle ? `${label}: ${examTitle}` : label,
    description: (
      <ParseFailureDetails
        message={errorMessage || (isParse ? PARSE_FALLBACK : EVAL_FALLBACK)}
      />
    ),
  });
}
