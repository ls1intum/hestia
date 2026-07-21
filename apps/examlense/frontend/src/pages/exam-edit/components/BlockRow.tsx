import type { CSSProperties, HTMLAttributes, ReactNode } from "react";
import { BlockHeader } from "@/components/shared/exam-content/BlockHeader";
import { BlockCard } from "@/components/shared/exam-content/BlockCard";

export type BlockRowKind = "task" | "context" | "figure";

interface Props {
  kind: BlockRowKind;
  /** Header label — for tasks: "Question a)", for context/figure: their title. */
  label: ReactNode;
  /** Task-only warning shown when the task has no score yet. */
  missingScore?: boolean;
  /** Pre-built type badge (rendered on the right). */
  badge: ReactNode;
  /** Optional body region under the header (e.g. truncated prompt preview). */
  subtitle?: ReactNode;
  /** Click anywhere on the row → expand. */
  onToggle: () => void;
  /** Drag-and-drop wiring (from SortableItem). */
  dragHandleProps?: HTMLAttributes<HTMLButtonElement>;
  setNodeRef?: (el: HTMLElement | null) => void;
  style?: CSSProperties;
  isDragging?: boolean;
}

/**
 * Collapsed-state representation of a single block (task / context / figure).
 * Kept deliberately plain — an eyebrow (mono) label like the collapsed context
 * block, with the type badge / prompt preview but no leading icon, status dot,
 * or points meta.
 */
export const BlockRow = ({
  kind,
  label,
  missingScore,
  badge,
  subtitle,
  onToggle,
  dragHandleProps,
  setNodeRef,
  style,
  isDragging,
}: Props) => {
  const isTask = kind === "task";
  return (
    <BlockCard
      variant={isTask ? "primary" : "muted"}
      bodyDivider={false}
      setNodeRef={setNodeRef}
      style={style}
      isDragging={isDragging}
      header={
        <BlockHeader
          expanded={false}
          onToggle={onToggle}
          label={label}
          labelVariant="eyebrow"
          quietControls
          dragAlwaysVisible
          missingScore={isTask && missingScore}
          badge={isTask ? badge : undefined}
          dragHandleProps={dragHandleProps}
        />
      }
      body={subtitle}
    />
  );
};
