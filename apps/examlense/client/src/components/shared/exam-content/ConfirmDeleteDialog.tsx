import { type ReactNode } from "react";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: ReactNode;
  description: ReactNode;
  onConfirm: (event: React.MouseEvent<HTMLButtonElement>) => void;
  /** Confirm-button copy (defaults to "Delete"). */
  confirmLabel?: string;
}

/**
 * Destructive-confirm AlertDialog scaffold shared by the editor's delete/convert
 * prompts. The confirm button carries the danger styling so every destructive
 * action reads the same.
 */
export const ConfirmDeleteDialog = ({
  open,
  onOpenChange,
  title,
  description,
  onConfirm,
  confirmLabel = "Delete",
}: Props) => (
  <AlertDialog open={open} onOpenChange={onOpenChange}>
    <AlertDialogContent>
      <AlertDialogHeader>
        <AlertDialogTitle>{title}</AlertDialogTitle>
        <AlertDialogDescription>{description}</AlertDialogDescription>
      </AlertDialogHeader>
      <AlertDialogFooter>
        <AlertDialogCancel className="hover:bg-hestia-primary-muted hover:text-hestia-text">
          Cancel
        </AlertDialogCancel>
        <AlertDialogAction
          onClick={onConfirm}
          className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
        >
          {confirmLabel}
        </AlertDialogAction>
      </AlertDialogFooter>
    </AlertDialogContent>
  </AlertDialog>
);
