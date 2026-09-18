import { useState } from "react";
import AnchoredPopover from "./AnchoredPopover.tsx";

/** One set of checkboxes in the popover; a column can filter on more than one attribute. */
export type FilterGroup = {
  key: string;
  /** Names the group in the switch shown when the popover holds more than one group. */
  label: string;
  options: string[];
  selected: Set<string>;
  display: (value: string) => string;
  onToggle: (value: string) => void;
};

/**
 * Excel-AutoFilter-style multi-select checkbox popover, anchored below its trigger. Used by the
 * competency tree-grid's per-column funnels. A column filtering on several attributes passes one
 * group per attribute, and a switch at the top picks which group's checkboxes are listed; the
 * selections of every group apply at once. Must be rendered as a child of the same `relative`
 * element that holds the trigger button (see `AnchoredPopover`).
 */
export default function FilterPopover({
  groups,
  alignRight,
  onClear,
  onClose,
}: {
  groups: FilterGroup[];
  alignRight?: boolean;
  /** Clears every group. */
  onClear: () => void;
  onClose: () => void;
}) {
  const shown = groups.filter((group) => group.options.length > 0);
  // Opens on the first group that already filters, so a set filter is visible straight away.
  const [activeKey, setActiveKey] = useState(
    () => (shown.find((group) => group.selected.size > 0) ?? shown[0])?.key,
  );
  const active = shown.find((group) => group.key === activeKey) ?? shown[0];
  return (
    <AnchoredPopover
      alignRight={alignRight}
      onClose={onClose}
      className="flex min-w-44 flex-col rounded-lg border border-hestia-border bg-hestia-surface p-1.5 font-normal normal-case tracking-normal shadow-lg"
    >
      {shown.length > 1 && (
        <div
          role="radiogroup"
          aria-label="Filter by"
          className="mb-1 inline-flex self-start rounded-full border border-hestia-border bg-hestia-surface"
        >
          {shown.map((group) => {
            const selected = group.key === active.key;
            return (
              <button
                key={group.key}
                type="button"
                role="radio"
                aria-checked={selected}
                onClick={() => setActiveKey(group.key)}
                className={`px-3 py-1 text-xs transition first:rounded-l-full last:rounded-r-full ${
                  selected
                    ? "bg-hestia-primary font-semibold text-hestia-on-primary"
                    : "font-medium text-hestia-text-muted hover:text-hestia-text"
                }`}
              >
                {group.label}
                {group.selected.size > 0 && ` (${group.selected.size})`}
              </button>
            );
          })}
        </div>
      )}
      <div className="min-h-0 flex-1 overflow-y-auto">
        {active?.options.map((value) => (
          <label
            key={value}
            className="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1 text-sm text-hestia-text hover:bg-hestia-text/5"
          >
            <input
              type="checkbox"
              checked={active.selected.has(value)}
              onChange={() => active.onToggle(value)}
              className="h-3.5 w-3.5 shrink-0 accent-hestia-primary"
            />
            {active.display(value)}
          </label>
        ))}
      </div>
      <div className="mt-1 flex justify-between gap-2 border-t border-hestia-border px-2 pb-0.5 pt-1.5">
        <button
          type="button"
          onClick={onClear}
          className="text-xs font-semibold text-hestia-primary transition hover:text-hestia-primary-hover"
        >
          Clear
        </button>
        <button
          type="button"
          onClick={onClose}
          className="text-xs font-semibold text-hestia-primary transition hover:text-hestia-primary-hover"
        >
          Done
        </button>
      </div>
    </AnchoredPopover>
  );
}
