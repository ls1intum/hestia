import AnchoredPopover from "./AnchoredPopover.tsx";

/** One set of checkboxes in the popover; a column can filter on more than one attribute. */
export type FilterGroup = {
  key: string;
  /** Heading shown above the group; only rendered when the popover holds more than one group. */
  label: string;
  options: string[];
  selected: Set<string>;
  display: (value: string) => string;
  onToggle: (value: string) => void;
};

/**
 * Excel-AutoFilter-style multi-select checkbox popover, anchored below its trigger. Used by the
 * competency tree-grid's per-column funnels. A column filtering on several attributes passes one
 * group per attribute. Must be rendered as a child of the same `relative` element that holds the
 * trigger button (see `AnchoredPopover`).
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
  const headed = groups.length > 1;
  return (
    <AnchoredPopover
      alignRight={alignRight}
      onClose={onClose}
      className="flex min-w-44 flex-col rounded-lg border border-hestia-border bg-hestia-surface p-1.5 font-normal normal-case tracking-normal shadow-lg"
    >
      <div className="min-h-0 flex-1 overflow-y-auto">
        {groups
          .filter((group) => group.options.length > 0)
          .map((group, index) => (
            <div
              key={group.key}
              className={headed && index > 0 ? "mt-1 border-t border-hestia-border pt-1" : ""}
            >
              {headed && (
                <p className="px-2 pb-0.5 pt-1 text-xs font-semibold text-hestia-text-muted">
                  {group.label}
                </p>
              )}
              {group.options.map((value) => (
                <label
                  key={value}
                  className="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1 text-sm text-hestia-text hover:bg-hestia-text/5"
                >
                  <input
                    type="checkbox"
                    checked={group.selected.has(value)}
                    onChange={() => group.onToggle(value)}
                    className="h-3.5 w-3.5 shrink-0 accent-hestia-primary"
                  />
                  {group.display(value)}
                </label>
              ))}
            </div>
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
