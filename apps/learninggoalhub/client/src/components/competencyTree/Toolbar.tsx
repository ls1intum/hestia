import type { ReactNode } from "react";
import AnchoredPopover from "../AnchoredPopover.tsx";
import Button from "../Button.tsx";
import type { AttributeKey, ColumnKey, ColumnPrefs, Layout } from "./model.ts";
import { COLUMN_BY_KEY, ATTRIBUTE_KEYS } from "./columns.ts";
import { DiagramIcon, TableIcon, SlidersIcon } from "./icons.tsx";

/**
 * Toolbar menu choosing which attribute columns the grid shows. The learning-goal column is listed
 * but locked, since the tree's carets live in it. Moving and resizing happen on the headers.
 */
/**
 * The table's reading settings in one menu: short or full goal names, and which columns show. Both
 * are set once and left alone, so they sit behind a button instead of taking toolbar space.
 */
export function DisplayMenu({
  fullWording,
  onChangeWording,
  prefs,
  open,
  onToggleOpen,
  onClose,
  onToggleHidden,
  onReset,
}: {
  fullWording: boolean;
  onChangeWording: (full: boolean) => void;
  prefs: ColumnPrefs;
  open: boolean;
  onToggleOpen: () => void;
  onClose: () => void;
  onToggleHidden: (key: AttributeKey) => void;
  onReset: () => void;
}) {
  const customised =
    prefs.hidden.length > 0 ||
    Object.keys(prefs.widths).length > 0 ||
    prefs.order.some((key, i) => key !== ATTRIBUTE_KEYS[i]);
  return (
    <div className="relative">
      <Button
        variant="neutral"
        className={`h-9 ${open ? "border-hestia-primary" : ""}`}
        aria-expanded={open}
        aria-haspopup="dialog"
        onClick={onToggleOpen}
      >
        <SlidersIcon />
        Display
        {prefs.hidden.length > 0 && (
          <span className="text-xs text-hestia-text-muted">({prefs.hidden.length} hidden)</span>
        )}
        <svg
          viewBox="0 0 20 20"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
          className={`h-3.5 w-3.5 text-hestia-text-muted transition-transform ${open ? "rotate-180" : ""}`}
        >
          <path d="M5.5 8l4.5 4.5L14.5 8" />
        </svg>
      </Button>
      {open && (
        <AnchoredPopover
          alignRight
          onClose={onClose}
          className="flex w-64 flex-col gap-3 rounded-lg border border-hestia-border bg-hestia-surface p-3 shadow-lg"
        >
          <div role="dialog" aria-label="Display options" className="flex min-h-0 flex-1 flex-col gap-3">
            <div>
              <p className="mb-1.5 text-xs font-semibold text-hestia-text-muted">Goal names</p>
              <WordingSwitch full={fullWording} onChange={onChangeWording} />
            </div>
            <div className="flex min-h-0 flex-col">
              <p className="mb-1 text-xs font-semibold text-hestia-text-muted">Columns</p>
              <div className="-mx-1.5 min-h-0 flex-1 overflow-y-auto">
                {(["text", ...prefs.order] as ColumnKey[]).map((key) => {
                  const locked = key === "text";
                  return (
                    <label
                      key={key}
                      className={`flex items-center gap-2 rounded-md px-1.5 py-1 text-sm text-hestia-text ${
                        locked ? "opacity-60" : "cursor-pointer hover:bg-hestia-text/5"
                      }`}
                    >
                      <input
                        type="checkbox"
                        checked={locked || !prefs.hidden.includes(key as AttributeKey)}
                        disabled={locked}
                        onChange={() => onToggleHidden(key as AttributeKey)}
                        className="h-3.5 w-3.5 shrink-0 accent-hestia-primary"
                      />
                      {COLUMN_BY_KEY.get(key)!.label}
                    </label>
                  );
                })}
              </div>
              <p className="mt-1.5 text-xs leading-snug text-hestia-text-muted">
                Drag a header to move its column, or its right edge to resize it.
              </p>
            </div>
            <div className="flex justify-between gap-2 border-t border-hestia-border pt-2">
              <button
                type="button"
                onClick={onReset}
                disabled={!customised}
                className="text-xs font-semibold text-hestia-primary transition hover:text-hestia-primary-hover disabled:cursor-not-allowed disabled:opacity-50"
              >
                Reset columns
              </button>
              <button
                type="button"
                onClick={onClose}
                className="text-xs font-semibold text-hestia-primary transition hover:text-hestia-primary-hover"
              >
                Done
              </button>
            </div>
          </div>
        </AnchoredPopover>
      )}
    </div>
  );
}

/**
 * Segmented control switching the grid between its two layouts. Follows the styleguide's toggle:
 * one surface track, the selected segment filled with primary. It shares the toolbar's 36px height
 * and 8px radius so the row reads as one set of controls.
 */
export function LayoutSwitch({
  layout,
  onChange,
}: {
  layout: Layout;
  onChange: (layout: Layout) => void;
}) {
  const options: { key: Layout; label: string; icon: ReactNode }[] = [
    { key: "table", label: "Table", icon: <TableIcon /> },
    { key: "diagram", label: "Diagram", icon: <DiagramIcon /> },
  ];
  return (
    <div
      role="tablist"
      aria-label="Tree layout"
      className="inline-flex h-9 gap-0.5 rounded-md border border-hestia-border bg-hestia-surface p-[3px]"
    >
      {options.map((option) => {
        const active = layout === option.key;
        return (
          <button
            key={option.key}
            type="button"
            role="tab"
            aria-selected={active}
            onClick={() => onChange(option.key)}
            className={`inline-flex items-center gap-1.5 rounded-[6px] px-3 text-sm font-medium transition ${
              active
                ? "bg-hestia-primary text-hestia-on-primary"
                : "text-hestia-text-muted hover:text-hestia-text"
            }`}
          >
            {option.icon}
            {option.label}
          </button>
        );
      })}
    </div>
  );
}

/** Switch between short labels and every goal's full wording in the goal column. */
export function WordingSwitch({
  full,
  onChange,
}: {
  full: boolean;
  onChange: (full: boolean) => void;
}) {
  const options = [
    { full: false, label: "Short" },
    { full: true, label: "Full" },
  ];
  return (
    <div
      role="radiogroup"
      aria-label="Goal names"
      className="flex h-8 gap-0.5 rounded-md border border-hestia-border bg-hestia-surface p-[3px]"
    >
      {options.map((option) => {
        const active = full === option.full;
        return (
          <button
            key={option.label}
            type="button"
            role="radio"
            aria-checked={active}
            onClick={() => onChange(option.full)}
            className={`flex-1 rounded-[5px] text-xs font-medium transition ${
              active
                ? "bg-hestia-primary text-hestia-on-primary"
                : "text-hestia-text-muted hover:text-hestia-text"
            }`}
          >
            {option.label}
          </button>
        );
      })}
    </div>
  );
}
