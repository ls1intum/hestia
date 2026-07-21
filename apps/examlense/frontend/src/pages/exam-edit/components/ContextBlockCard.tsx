import { useState, type CSSProperties, type HTMLAttributes } from "react";
import { useInlineTextEdit } from "@/hooks/ui/use-inline-text-edit";
import type { SectionBlock } from "@/lib/exam/exam-helpers";
import { MarkdownEditField } from "@/components/shared/exam-content/MarkdownEditField";
import { WarningBanner } from "@/components/shared/exam-content/WarningBanner";
import { BlockHeader } from "@/components/shared/exam-content/BlockHeader";
import { BlockCard } from "@/components/shared/exam-content/BlockCard";
import { BlockActionsMenu } from "@/components/shared/exam-content/BlockActionsMenu";
import { ConfirmDeleteDialog } from "@/components/shared/exam-content/ConfirmDeleteDialog";

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
        <BlockActionsMenu
          ariaLabel="Context actions"
          onDelete={() => setConfirmDelete(true)}
          deleteLabel="Delete context block"
        />
      }
      dragHandleProps={dragHandleProps}
    />
  );

  const body = (
    <div>
      {field.isEmpty && <WarningBanner text="No context added." />}
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

      <ConfirmDeleteDialog
        open={confirmDelete}
        onOpenChange={setConfirmDelete}
        title="Delete this context block?"
        description="Any figures attached to it will also be removed."
        onConfirm={onDelete}
      />
    </>
  );
};
