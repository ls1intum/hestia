import { useNavigate } from "react-router-dom";
import { formatDistanceToNow } from "date-fns";
import { enUS } from "date-fns/locale";
import { AlertTriangle } from "lucide-react";
import type { ExamListItem } from "@/lib/api-client";
import { ExamStatusBadge } from "@/components/ExamStatusBadge";
import { ModelLogo } from "@/components/ModelLogo";
import { solverModelLabel } from "@/lib/llm-models";
import { TableCell, TableRow } from "@/components/ui/table";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { ExamProgressCell } from "./ExamProgressCell";
import { ExamActionsMenu } from "./ExamActionsMenu";

/** Where a row navigates on click, matching the per-status routing of the old cards. */
export const examHref = (e: ExamListItem): string =>
  `/exams/${e.id}/${
    e.status === "finished" ? "results" : e.status === "grading" ? "grade" : "edit"
  }`;

export interface ExamRowHandlers {
  onRetry: (exam: ExamListItem) => void;
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

  return (
    <TableRow
      className="cursor-pointer"
      onClick={() => navigate(examHref(exam))}
      aria-label={`Open exam ${title}`}
    >
      {/* Title */}
      <TableCell className="max-w-0">
        <div className="flex items-center gap-2">
          <span className="truncate font-medium text-hestia-text">{title}</span>
          {isFailed && (
            <Tooltip>
              <TooltipTrigger asChild>
                <AlertTriangle size={14} className="shrink-0 text-destructive" />
              </TooltipTrigger>
              <TooltipContent>{exam.parse_error || "We couldn't read this PDF."}</TooltipContent>
            </Tooltip>
          )}
        </div>
      </TableCell>

      {/* Status */}
      <TableCell>
        <ExamStatusBadge status={exam.status} />
      </TableCell>

      {/* Progress */}
      <TableCell>
        <ExamProgressCell exam={exam} />
      </TableCell>

      {/* Solver model (icon only, full label on hover) */}
      <TableCell>
        {exam.solver_model ? (
          <Tooltip>
            <TooltipTrigger asChild>
              <span
                className="inline-flex h-7 w-7 items-center justify-center rounded-hestia-md border border-hestia-border bg-hestia-bg/70 p-1"
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
          onRetry={isFailed ? () => handlers.onRetry(exam) : undefined}
          onCancel={isParsing || isEvaluating ? () => handlers.onCancel(exam) : undefined}
          onDuplicate={
            isParsing || isFailed ? undefined : () => handlers.onDuplicate(exam)
          }
          onDelete={() => handlers.onDelete(exam)}
          labels={{
            more: "More actions",
            retry: "Retry",
            cancel: "Cancel processing",
            duplicate: "Duplicate",
            delete: "Delete",
          }}
        />
      </TableCell>
    </TableRow>
  );
};
