import type { FilterChip } from "./useGoalFilters.ts";
import { CrossIcon } from "./icons.tsx";

/** The active search and filters as removable chips, with what they leave together. */
export function FilterChips({
  chips,
  matchCount,
  total,
  onClearAll,
}: {
  chips: FilterChip[];
  matchCount: number;
  total: number;
  onClearAll: () => void;
}) {
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      {chips.map((chip, i) => (
        <span
          key={i}
          className="inline-flex items-center gap-1.5 rounded-full border border-[color-mix(in_srgb,var(--hestia-primary)_35%,transparent)] bg-hestia-primary-muted py-0.5 pl-2.5 pr-1.5 text-xs"
        >
          <span>
            <b className="font-semibold">{chip.label}:</b> {chip.value}
          </span>
          <span className="border-l border-[color-mix(in_srgb,var(--hestia-primary)_30%,transparent)] pl-1.5 tabular-nums text-hestia-text-muted">
            {chip.count === 1 ? "1 goal" : `${chip.count} goals`}
          </span>
          <button
            type="button"
            onClick={chip.onRemove}
            aria-label={`Remove filter ${chip.label} ${chip.value}`}
            className="flex rounded-full text-hestia-text-muted transition hover:text-hestia-danger"
          >
            <CrossIcon />
          </button>
        </span>
      ))}
      {/* What the chips leave together, which only says something a single chip's own count
          does not once a second chip narrows it. */}
      {chips.length > 1 && (
        <span className="text-xs text-hestia-text-muted" aria-live="polite">
          {matchCount === 1 ? "1 goal" : `${matchCount} goals`} of{" "}
          {total}
        </span>
      )}
      {chips.length > 1 && (
        <button
          type="button"
          onClick={onClearAll}
          className="text-xs text-hestia-text-muted underline transition hover:text-hestia-text"
        >
          Clear all
        </button>
      )}
    </div>
  );
}
