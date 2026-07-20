import { useNavigate } from "react-router-dom";
import { formatDistanceToNow } from "date-fns";
import { enUS } from "date-fns/locale";
import type { ExamListItem } from "@/lib/api/api-client";
import { examModePath, isParseFailure } from "@/lib/exam/exam-helpers";
import { cn } from "@/lib/utils/utils";
import { ExamStatusBadge } from "@/pages/exams/components/ExamStatusBadge";
import { ModelLogo } from "@/components/shared/ModelLogo";
import { solverModelLabel } from "@/lib/exam/llm-models";
import { TableCell, TableRow } from "@/components/ui/table";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { ExamProgressCell } from "./ExamProgressCell";
import { ExamActionsMenu } from "./ExamActionsMenu";

/** Where a row navigates on click — the exam's canonical mode for its status. */
export const examHref = (e: ExamListItem): string => examModePath(e.id, e.status);

export interface ExamRowHandlers {
  /** Re-parse a PDF that failed during parsing. */
  onRetry: (exam: ExamListItem) => void;
  /** Re-run evaluation for an exam that failed while solving / was cancelled. */
  onRetryEvaluation: (exam: ExamListItem) => void;
  onCancel: (exam: ExamListItem) => void;
  onDuplicate: (exam: ExamListItem) => void;
  onDelete: (exam: ExamListItem) => void;
}

export const ExamTableRow = ({
  exam,
  handlers,
}: {
  exam: ExamListItem;
  handlers: ExamRowHandlers;
}) => {
  const navigate = useNavigate();
  const title = exam.title || "Untitled exam";
  const isParsing = exam.status === "parsing";
  const isEvaluating = exam.status === "evaluating";
  const isFailed = exam.status === "failed";
  // A parse failure has no exam to open (re-parse via the row action); an
  // evaluation failure keeps its content, so the row opens the exam.
  const parseFailed = isParseFailure(exam);
  const evalFailed = isFailed && !parseFailed;

  return (
    <TableRow
      // Constant row height so slimmer rows (e.g. the single-line "Failed"
      // progress cell) match the tallest (the two-line parsing/journey cells).
      // On a <tr>, `height` behaves as a minimum and `align-middle` centres.
      className={cn("h-20", parseFailed ? "cursor-default" : "cursor-pointer")}
      onClick={parseFailed ? undefined : () => navigate(examHref(exam))}
      aria-label={
        parseFailed
          ? `Exam ${title} (parsing failed)`
          : evalFailed
            ? `Open exam ${title} (evaluation failed)`
            : `Open exam ${title}`
      }
    >
      {/* Title (truncated, full text on hover). max-w-0 lets the cell shrink
          below its content so the span can ellipsize instead of widening the table. */}
      <TableCell className="max-w-0">
        <Tooltip>
          <TooltipTrigger asChild>
            <span className="block truncate font-medium text-hestia-text">{title}</span>
          </TooltipTrigger>
          <TooltipContent>{title}</TooltipContent>
        </Tooltip>
      </TableCell>

      {/* Status */}
      <TableCell className="text-center">
        <ExamStatusBadge status={exam.status} />
      </TableCell>

      {/* Progress */}
      <TableCell>
        <ExamProgressCell exam={exam} />
      </TableCell>

      {/* Solver model (icon only, full label on hover) */}
      <TableCell className="text-center">
        {exam.solver_model ? (
          <Tooltip>
            <TooltipTrigger asChild>
              <span
                className="inline-flex h-9 w-9 items-center justify-center rounded-hestia-md border border-hestia-border bg-hestia-bg/70 p-1.5"
                aria-label={solverModelLabel(exam.solver_model)}
              >
                <ModelLogo modelId={exam.solver_model} />
              </span>
            </TooltipTrigger>
            <TooltipContent>{solverModelLabel(exam.solver_model)}</TooltipContent>
          </Tooltip>
        ) : (
          <span className="text-xs text-hestia-text-muted">—</span>
        )}
      </TableCell>

      {/* Creation date */}
      <TableCell className="text-xs text-hestia-text-muted">
        {`${formatDistanceToNow(new Date(exam.created_at), { locale: enUS })} ago`}
      </TableCell>

      {/* Actions */}
      <TableCell className="text-right">
        <ExamActionsMenu
          onRetry={
            parseFailed
              ? () => handlers.onRetry(exam)
              : evalFailed
                ? () => handlers.onRetryEvaluation(exam)
                : undefined
          }
          onCancel={isParsing || isEvaluating ? () => handlers.onCancel(exam) : undefined}
          onDuplicate={
            isParsing || isFailed ? undefined : () => handlers.onDuplicate(exam)
          }
          onDelete={() => handlers.onDelete(exam)}
          labels={{
            more: "More actions",
            retry: parseFailed ? "Retry parsing" : "Retry evaluation",
            cancel: "Cancel processing",
            duplicate: "Duplicate",
            delete: "Delete",
          }}
        />
      </TableCell>
    </TableRow>
  );
};
