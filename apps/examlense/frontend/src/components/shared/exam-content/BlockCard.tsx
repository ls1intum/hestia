import type { CSSProperties, ReactNode } from "react";
import { cn } from "@/lib/utils/utils";

export type BlockCardVariant = "primary" | "muted";

interface Props {
  variant?: BlockCardVariant;
  isDragging?: boolean;
  setNodeRef?: (el: HTMLElement | null) => void;
  style?: CSSProperties;
  /** Extra classes on the card shell (e.g. a state-driven border/animation). */
  className?: string;
  /** Header row — typically a `<BlockHeader />` invocation. */
  header: ReactNode;
  /** Body region under the header. Collapsed: subtitle preview. Expanded: full editor body. */
  body?: ReactNode;
}

/**
 * Shell for a single block (task / context / figure) in the section list.
 *
 * Tasks (`primary`) are the section's work objects and render as cards:
 * surface + border + soft shadow. Context/figure blocks (`muted`) are
 * reference material and render card-less — they flow on the section
 * background like the preamble of a printed exam, so the tasks stay the
 * only "objects" on the page. While dragging, a muted block temporarily
 * materializes as a card ghost so the moved thing reads as one unit.
 *
 * Muted blocks get extra air above (margin adds to the list's `space-y`)
 * but keep the default gap below: context binds to the tasks that follow it.
 */
export const BlockCard = ({
  variant = "primary",
  isDragging,
  setNodeRef,
  style,
  className,
  header,
  body,
}: Props) => {
  const flow = variant === "muted";
  return (
    <article
      ref={setNodeRef}
      style={style}
      className={cn(
        "transition-colors",
        flow
          ? "mt-hestia-4 rounded-hestia-lg"
          : "rounded-hestia-lg border border-hestia-border bg-hestia-surface shadow-hestia-md",
        // Muted blocks (context/figure) get a slight warm tint so they read as
        // reference material — distinct from the canvas, but not full cards.
        flow && !isDragging && "bg-hestia-primary-muted/25",
        isDragging &&
          (flow
            ? "bg-hestia-surface opacity-60 shadow-hestia-md ring-2 ring-hestia-primary/40"
            : "opacity-60 ring-2 ring-hestia-primary/40"),
        className,
      )}
    >
      <div className={cn(flow ? "px-hestia-3 pt-hestia-2 pb-1" : "px-hestia-3 pt-hestia-3 pb-hestia-2")}>
        {header}
      </div>
      {body && (
        <div
          className={cn(
            flow
              ? "px-hestia-3 pb-hestia-3 pt-1"
              : "border-t border-hestia-border/15 px-hestia-3 pb-hestia-3 pt-hestia-2",
          )}
        >
          {body}
        </div>
      )}
    </article>
  );
};
