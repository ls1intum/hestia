import { useEffect, useRef } from "react";

/**
 * Excel-AutoFilter-style multi-select checkbox popover, anchored below its trigger. Shared by the
 * competency tree-grid (per-column funnel) and the list view's filter bar so both filter the same
 * way. Must be rendered as a child of the same `relative` element that holds the trigger button —
 * the outside-click guard closes on clicks outside that parent (the trigger toggles it itself).
 */
export default function FilterPopover({
  options,
  selected,
  display,
  alignRight,
  onToggle,
  onClear,
  onClose,
}: {
  options: string[];
  selected: Set<string>;
  display: (value: string) => string;
  alignRight?: boolean;
  onToggle: (value: string) => void;
  onClear: () => void;
  onClose: () => void;
}) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    const onDown = (e: MouseEvent) => {
      // The trigger button toggles the popover itself; only close on truly-outside clicks.
      if (
        ref.current &&
        !ref.current.parentElement!.contains(e.target as Node)
      )
        onClose();
    };
    window.addEventListener("keydown", onKey);
    window.addEventListener("mousedown", onDown);
    return () => {
      window.removeEventListener("keydown", onKey);
      window.removeEventListener("mousedown", onDown);
    };
  }, [onClose]);

  return (
    <div
      ref={ref}
      className={`comp-unfold absolute top-full z-20 mt-0.5 min-w-44 origin-top rounded-lg border border-hestia-border bg-hestia-surface p-1.5 font-normal normal-case tracking-normal shadow-lg ${
        alignRight ? "right-1" : "left-1"
      }`}
    >
      {options.map((value) => (
        <label
          key={value}
          className="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1 text-sm text-hestia-text hover:bg-hestia-text/5"
        >
          <input
            type="checkbox"
            checked={selected.has(value)}
            onChange={() => onToggle(value)}
            className="h-3.5 w-3.5 accent-hestia-primary"
          />
          {display(value)}
        </label>
      ))}
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
    </div>
  );
}
