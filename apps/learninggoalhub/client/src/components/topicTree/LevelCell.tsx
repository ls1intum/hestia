import type { KeyboardEvent as ReactKeyboardEvent } from "react";
import AnchoredPopover from "../AnchoredPopover.tsx";
import { titleCase } from "../../lib/goals.ts";
import { toEnum, LEVEL_SCALES, type LevelScale } from "./model.ts";
import { LevelPips } from "./Chips.tsx";

/**
 * A goal's Bloom or SOLO level as a cell: the level name, or a "Set" placeholder for a goal nobody
 * classified. Clicking it opens the taxonomy's ladder, each step with its dot scale and description.
 */
export function LevelCell({
  scale,
  value,
  interactive,
  open,
  onToggle,
  onClose,
  onSelect,
}: {
  scale: LevelScale;
  value: string | null | undefined;
  interactive: boolean;
  open: boolean;
  onToggle: () => void;
  onClose: () => void;
  /** Receives the API enum value of the chosen step. */
  onSelect: (value: string) => void;
}) {
  const meta = LEVEL_SCALES[scale];
  const term = value ? titleCase(value) : null;
  const ladder = Object.keys(meta.desc);
  const index = term == null ? -1 : ladder.indexOf(term);
  const reading = term && (
    <span
      className="flex min-w-0 items-center gap-1"
      aria-label={`${meta.label} level ${index + 1} of ${ladder.length}: ${term}`}
    >
      <LevelPips index={index} steps={ladder.length} />
      <span className="truncate" aria-hidden="true">{term}</span>
    </span>
  );
  if (!interactive) {
    return reading ? <span className="flex text-xs text-hestia-text">{reading}</span> : null;
  }
  // Escape has to reach the popover's own listener, so only the row's activation keys are held back.
  const keepRowKeys = (e: ReactKeyboardEvent) => {
    if (e.key !== "Escape") e.stopPropagation();
  };
  return (
    <div className="relative inline-flex max-w-full">
      <button
        type="button"
        aria-expanded={open}
        title={term ? `${meta.label}: ${term}` : `Set the ${meta.label} level`}
        onClick={(e) => {
          e.stopPropagation();
          onToggle();
        }}
        onKeyDown={keepRowKeys}
        className={`-mx-1 flex max-w-[calc(100%+0.5rem)] min-w-0 items-center rounded-md px-1 py-0.5 text-xs transition ${
          term
            ? `text-hestia-text hover:bg-hestia-text/5 ${open ? "bg-hestia-text/5" : ""}`
            : "border border-dashed border-hestia-border text-hestia-text-muted hover:border-hestia-primary hover:text-hestia-primary"
        }`}
      >
        {reading || "Set"}
      </button>
      {open && (
        <AnchoredPopover
          onClose={onClose}
          className="flex w-72 flex-col rounded-lg border border-hestia-border bg-hestia-surface p-1.5 shadow-lg"
        >
          {/* The panel is portalled, but React still bubbles its events up to the row. */}
          <div
            onClick={(e) => e.stopPropagation()}
            onKeyDown={keepRowKeys}
            className="min-h-0 overflow-y-auto"
          >
            {ladder.map((step, i) => (
              <button
                key={step}
                type="button"
                aria-current={i === index}
                onClick={() => {
                  if (i !== index) onSelect(toEnum(step));
                  onClose();
                }}
                className={`flex w-full items-start gap-2.5 rounded-md px-2 py-1.5 text-left transition hover:bg-hestia-text/5 ${
                  i === index ? "bg-hestia-primary-muted" : ""
                }`}
              >
                <span className="mt-2">
                  <LevelPips index={i} steps={ladder.length} />
                </span>
                <span className="min-w-0">
                  <span className="block text-sm font-medium text-hestia-text">{step}</span>
                  <span className="block text-xs leading-snug text-hestia-text-muted">
                    {meta.desc[step as keyof typeof meta.desc]}
                  </span>
                </span>
              </button>
            ))}
          </div>
        </AnchoredPopover>
      )}
    </div>
  );
}
