import { PenLine } from "lucide-react";
import { useClickToEdit } from "@/hooks/ui/use-click-to-edit";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";

interface Props {
  title: string;
  onRename: (next: string) => void;
}

/**
 * Title cell for the dashboard: the truncated title plus a pencil affordance that
 * flips it into an inline input (commit on blur/Enter, revert on Escape). The
 * pencil and input stop click propagation so editing never triggers the row's
 * navigation; clicking the title text still opens the exam.
 */
export const ExamTitleCell = ({ title, onRename }: Props) => {
  const { editing, startEditing, inputProps } = useClickToEdit(title, onRename);
  const display = title || "Untitled exam";

  if (editing) {
    return (
      <input
        {...inputProps}
        onClick={(e) => e.stopPropagation()}
        placeholder="Untitled exam"
        className="w-full bg-transparent text-sm font-medium text-hestia-text placeholder:font-normal placeholder:text-hestia-text-muted focus:outline-none border-b border-hestia-primary"
      />
    );
  }

  return (
    <div className="group flex min-w-0 items-center gap-1.5">
      <Tooltip>
        <TooltipTrigger asChild>
          <span className="block truncate font-medium text-hestia-text">{display}</span>
        </TooltipTrigger>
        <TooltipContent>{display}</TooltipContent>
      </Tooltip>
      <button
        type="button"
        aria-label="Rename exam"
        onClick={(e) => {
          e.stopPropagation();
          startEditing();
        }}
        className="shrink-0 text-hestia-text-muted transition-colors hover:text-hestia-primary"
      >
        <PenLine size={13} aria-hidden />
      </button>
    </div>
  );
};
