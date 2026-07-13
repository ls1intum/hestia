import type { CSSProperties, HTMLAttributes, ReactNode } from "react";
import { cn } from "@/lib/utils";
import { BlockHeader } from "./BlockHeader";
import { BlockCard } from "./BlockCard";

export type BlockRowKind = "task" | "context" | "figure";

interface Props {
  kind: BlockRowKind;
  /** Header label — for tasks: "a)", for context/figure: their title. */
  label: ReactNode;
  /** Task-only. */
  points?: number | null;
  /** Task-only. */
  missingScore?: boolean;
  /** Pre-built type badge (rendered on the right). */
  badge: ReactNode;
  /** Optional decorative icon shown next to the collapse button. */
  leadingIcon?: ReactNode;
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
 * Renders as a card with a prominent header row (task index / block title)
 * and an optional subtitle area below (truncated prompt preview for tasks).
 */
export const BlockRow = ({
  kind,
  label,
  points,
  missingScore,
  badge,
  leadingIcon,
  subtitle,
  onToggle,
  dragHandleProps,
  setNodeRef,
  style,
  isDragging,
}: Props) => {
  const rightMeta =
    kind === "task" && points != null && points > 0 ? (
      <span className="tabular-nums text-xs text-hestia-text-muted">
        {`${points} pts`}
      </span>
    ) : null;

  const labelNode =
    kind === "task" ? (
      <span className="inline-flex min-w-0 items-center gap-hestia-2">
        <span
          aria-hidden
          className={cn(
            "h-1.5 w-1.5 shrink-0 rounded-full",
            missingScore ? "bg-hestia-border" : "bg-hestia-success",
          )}
        />
        <span className="min-w-0 truncate">{label}</span>
      </span>
    ) : (
      label
    );

  // Card-less context/figure rows: the eyebrow label already names the kind,
  // so the type badge and leading icon would be redundant chrome.
  const isTask = kind === "task";
  return (
    <BlockCard
      variant={isTask ? "primary" : "muted"}
      setNodeRef={setNodeRef}
      style={style}
      isDragging={isDragging}
      header={
        <BlockHeader
          expanded={false}
          onToggle={onToggle}
          label={labelNode}
          labelVariant={isTask ? "title" : "eyebrow"}
          quietControls={!isTask}
          leadingIcon={isTask ? leadingIcon : undefined}
          rightMeta={rightMeta}
          missingScore={isTask && missingScore}
          badge={isTask ? badge : undefined}
          dragHandleProps={dragHandleProps}
        />
      }
      body={subtitle}
    />
  );
};
