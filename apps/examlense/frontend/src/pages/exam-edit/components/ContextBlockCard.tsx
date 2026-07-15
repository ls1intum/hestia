import { useState, type CSSProperties, type HTMLAttributes } from "react";
import { MoreVertical } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
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
import { useInlineTextEdit } from "@/hooks/ui/use-inline-text-edit";
import type { SectionBlock } from "@/lib/exam/exam-helpers";
import { MarkdownEditField } from "@/components/shared/exam-content/MarkdownEditField";
import { BlockHeader } from "@/components/shared/exam-content/BlockHeader";
import { BlockCard } from "@/components/shared/exam-content/BlockCard";

interface Props {
  block: SectionBlock;
  onToggleCollapsed: () => void;
  onPatch: (patch: Partial<SectionBlock>) => void;
  onDelete: () => void;
  dragHandleProps?: HTMLAttributes<HTMLButtonElement>;
  setNodeRef?: (el: HTMLElement | null) => void;
  style?: CSSProperties;
  isDragging?: boolean;
}

export const ContextBlockCard = ({
  block,
  onToggleCollapsed,
  onPatch,
  onDelete,
  dragHandleProps,
  setNodeRef,
  style,
  isDragging,
}: Props) => {
  const [confirmDelete, setConfirmDelete] = useState(false);
  const field = useInlineTextEdit({
    value: block.content,
    onCommit: (content) => onPatch({ content }),
  });

  const header = (
    <BlockHeader
      expanded
      onToggle={onToggleCollapsed}
      label="Context"
      labelVariant="eyebrow"
      quietControls
      dragAlwaysVisible
      actionsMenu={
        <ContextActionsMenu setConfirmDelete={setConfirmDelete} />
      }
      dragHandleProps={dragHandleProps}
    />
  );

  const body = (
    <div>
      <MarkdownEditField
        field={field}
        placeholder="Context for the tasks below: instructions, definitions, references…"
        ariaLabel="Context for the tasks below: instructions, definitions, references…"
        rows={2}
        // Card-less read view: the context flows on the section background;
        // a soft tint on hover (and a ring on keyboard focus) hints that
        // it is editable. The field box only appears while editing.
        readViewClassName="-mx-hestia-2 cursor-text rounded-hestia-sm px-hestia-2 py-1 transition-colors hover:bg-hestia-primary-muted/25 focus:outline-none focus-visible:ring-2 focus-visible:ring-hestia-primary/40"
        markdownClassName="text-hestia-text/90"
      />
    </div>
  );

  return (
    <>
      <BlockCard
        variant="muted"
        setNodeRef={setNodeRef}
        style={style}
        isDragging={isDragging}
        header={header}
        body={body}
      />

      <AlertDialog open={confirmDelete} onOpenChange={setConfirmDelete}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete this context block?</AlertDialogTitle>
            <AlertDialogDescription>
              Any figures attached to it will also be removed.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={onDelete}
              className="bg-hestia-danger text-white hover:bg-hestia-danger/90"
            >
              Delete
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
};

const ContextActionsMenu = ({
  setConfirmDelete,
}: {
  setConfirmDelete: (v: boolean) => void;
}) => (
  <DropdownMenu>
    <DropdownMenuTrigger
      aria-label="Context actions"
      className="rounded-hestia-sm p-1 text-hestia-text-muted hover:bg-hestia-primary-muted/40 hover:text-hestia-text"
    >
      <MoreVertical size={16} />
    </DropdownMenuTrigger>
    <DropdownMenuContent align="end">
      <DropdownMenuItem
        onClick={() => setConfirmDelete(true)}
        className="text-hestia-danger focus:text-hestia-danger"
      >
        Delete context block
      </DropdownMenuItem>
    </DropdownMenuContent>
  </DropdownMenu>
);
