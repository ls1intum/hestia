import { Ban, Copy, MoreVertical, RefreshCw, Trash2 } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

/**
 * Per-row overflow menu (3-dot). Stops event propagation so opening the menu or
 * picking an item never triggers the row's navigation click.
 */
export const ExamActionsMenu = ({
  onRetry,
  onCancel,
  onDuplicate,
  onDelete,
  labels,
}: {
  onRetry?: () => void;
  onCancel?: () => void;
  onDuplicate?: () => void;
  onDelete: () => void;
  labels: {
    more: string;
    retry?: string;
    cancel?: string;
    duplicate?: string;
    delete: string;
  };
}) => {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        onClick={(ev) => {
          ev.preventDefault();
          ev.stopPropagation();
        }}
        aria-label={labels.more}
        className="inline-flex h-8 w-8 items-center justify-center rounded-hestia-md text-hestia-text-muted transition-colors hover:bg-hestia-primary-muted hover:text-hestia-primary"
      >
        <MoreVertical size={14} />
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" onClick={(ev) => ev.stopPropagation()}>
        {onRetry && (
          <DropdownMenuItem
            onSelect={(ev) => {
              ev.preventDefault();
              onRetry();
            }}
          >
            <RefreshCw size={14} className="mr-2" />
            {labels.retry}
          </DropdownMenuItem>
        )}
        {onCancel && (
          <DropdownMenuItem
            onSelect={(ev) => {
              ev.preventDefault();
              onCancel();
            }}
          >
            <Ban size={14} className="mr-2" />
            {labels.cancel}
          </DropdownMenuItem>
        )}
        {onDuplicate && (
          <DropdownMenuItem
            onSelect={(ev) => {
              ev.preventDefault();
              onDuplicate();
            }}
          >
            <Copy size={14} className="mr-2" />
            {labels.duplicate}
          </DropdownMenuItem>
        )}
        {(onRetry || onCancel || onDuplicate) && <DropdownMenuSeparator />}
        <DropdownMenuItem
          onSelect={(ev) => {
            ev.preventDefault();
            onDelete();
          }}
          className="text-destructive focus:bg-destructive/10 focus:text-destructive"
        >
          <Trash2 size={14} className="mr-2" />
          {labels.delete}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};
