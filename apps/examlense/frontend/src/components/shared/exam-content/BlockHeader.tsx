import type { HTMLAttributes, ReactNode } from "react";
import { ChevronDown, ChevronRight, GripVertical, AlertCircle } from "lucide-react";
import { cn } from "@/lib/utils/utils";

interface Props {
  expanded: boolean;
  onToggle: () => void;
  /** Main label / title. Truncates with ellipsis. */
  label: ReactNode;
  /** Optional decorative icon shown after the chevron and before the label. */
  leadingIcon?: ReactNode;
  /** Optional small meta (e.g. task points pill). Lives just before the badge. */
  rightMeta?: ReactNode;
  /** Type badge (Context / Figure 1.1 / Free text …). Right-aligned, before menu. */
  badge?: ReactNode;
  /** Actions menu (⋮). Rightmost element. */
  actionsMenu?: ReactNode;
  /** Show a small "missing score" warning before the meta cluster. */
  missingScore?: boolean;
  dragHandleProps?: HTMLAttributes<HTMLButtonElement>;
  /** Visually emphasize the label (default true). */
  emphasizeLabel?: boolean;
  /**
   * "title" (default): semibold body-font title — task cards.
   * "eyebrow": small uppercase mono label — card-less context/figure blocks.
   */
  labelVariant?: "title" | "eyebrow";
  /**
   * Fade the drag handle and actions menu until the row is hovered or
   * focused. Used by card-less blocks so their chrome stays out of the way.
   * Layout is stable — only opacity changes.
   */
  quietControls?: boolean;
  /**
   * Keep the drag handle fully visible even when `quietControls` is set.
   * Used by context/figure blocks so their reorder affordance always shows.
   */
  dragAlwaysVisible?: boolean;
}

/**
 * Shared row header used by both the collapsed BlockRow and the expanded
 * editor cards (TaskCard / ContextBlockCard / FigureBlockCard). Keeping the
 * structure identical across collapsed/expanded states means clicking the
 * chevron only reveals/hides the body — the header itself never reflows.
 *
 * Layout: [drag] [chevron] [label …flex…] [icon] [meta] [badge] [⋮]
 */
export const BlockHeader = ({
  expanded,
  onToggle,
  label,
  leadingIcon,
  rightMeta,
  badge,
  actionsMenu,
  missingScore,
  dragHandleProps,
  emphasizeLabel = true,
  labelVariant = "title",
  quietControls = false,
  dragAlwaysVisible = false,
}: Props) => {
  const Chevron = expanded ? ChevronDown : ChevronRight;
  const quiet = quietControls
    ? "opacity-0 transition-opacity duration-150 group-hover:opacity-100 group-focus-within:opacity-100"
    : undefined;

  return (
    <div className="group flex min-w-0 items-center gap-hestia-2">
      {dragHandleProps && (
        <button
          type="button"
          aria-label="Drag to reorder"
          title="Drag to reorder"
          className={cn(
            "shrink-0 cursor-grab rounded-hestia-sm p-1 text-hestia-text-muted hover:bg-hestia-primary-muted/40 hover:text-hestia-text active:cursor-grabbing touch-none",
            !dragAlwaysVisible && quiet,
          )}
          {...dragHandleProps}
        >
          <GripVertical size={14} />
        </button>
      )}
      <button
        type="button"
        onClick={onToggle}
        aria-label={expanded ? "Collapse block" : "Expand block"}
        aria-expanded={expanded}
        className="flex min-w-0 flex-1 items-center gap-hestia-2 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-hestia-primary focus-visible:ring-offset-1 focus-visible:ring-offset-hestia-bg rounded-hestia-sm py-1"
      >
        <Chevron size={14} className="shrink-0 text-hestia-text-muted" aria-hidden />
        <span
          className={cn(
            "min-w-0 truncate",
            labelVariant === "eyebrow"
              ? "hestia-eyebrow text-hestia-text-muted"
              : "font-body text-base font-semibold leading-snug",
            labelVariant === "title" &&
              (emphasizeLabel ? "text-hestia-text" : "text-hestia-text-muted"),
          )}
        >
          {label}
        </span>
        {leadingIcon && <span className="shrink-0">{leadingIcon}</span>}
        <span className="flex-1" aria-hidden />
      </button>
      {missingScore && (
        <span
          className="inline-flex shrink-0 items-center gap-1 text-[11px] font-medium text-hestia-warning"
          title="Missing score"
        >
          <AlertCircle size={12} />
          Missing score
        </span>
      )}
      {rightMeta && <div className="shrink-0">{rightMeta}</div>}
      {badge && <div className="shrink-0">{badge}</div>}
      {actionsMenu && <div className={cn("shrink-0", quiet)}>{actionsMenu}</div>}
    </div>
  );
};
