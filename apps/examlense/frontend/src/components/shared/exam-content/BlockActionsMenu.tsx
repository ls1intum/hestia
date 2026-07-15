import { type ReactNode } from "react";
import { MoreVertical } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

interface Props {
  /** aria-label for the "…" trigger (e.g. "Task actions"). */
  ariaLabel: string;
  /** Invoked when the destructive item is chosen (usually opens a confirm). */
  onDelete: () => void;
  /** Destructive item copy (defaults to "Delete"). */
  deleteLabel?: string;
  /** Extra menu items rendered above the delete item (a separator is auto-added). */
  children?: ReactNode;
}

/**
 * The "…" actions dropdown shared by the editable block cards. Renders any
 * extra items, then a separator (only when extras exist), then the destructive
 * delete item.
 */
export const BlockActionsMenu = ({
  ariaLabel,
  onDelete,
  deleteLabel = "Delete",
  children,
}: Props) => (
  <DropdownMenu>
    <DropdownMenuTrigger
      aria-label={ariaLabel}
      className="rounded-hestia-sm p-1 text-hestia-text-muted hover:bg-hestia-primary-muted/40 hover:text-hestia-text"
    >
      <MoreVertical size={16} />
    </DropdownMenuTrigger>
    <DropdownMenuContent align="end">
      {children}
      {children && <DropdownMenuSeparator />}
      <DropdownMenuItem
        onClick={onDelete}
        className="text-hestia-danger focus:text-hestia-danger"
      >
        {deleteLabel}
      </DropdownMenuItem>
    </DropdownMenuContent>
  </DropdownMenu>
);
