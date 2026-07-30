import type { CSSProperties } from "react";

export default function CompetencyCreationField({
  value,
  placeholder,
  error,
  pending,
  onChange,
  onSubmit,
  onCancel,
  className = "",
  style,
  stacked = false,
}: {
  value: string;
  placeholder: string;
  error?: string;
  pending: boolean;
  onChange: (value: string) => void;
  onSubmit: () => void;
  onCancel: () => void;
  className?: string;
  style?: CSSProperties;
  /** Stacks the field above its buttons, for the map's fixed-width boxes where a row would overflow. */
  stacked?: boolean;
}) {
  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        if (value.trim() !== "" && !pending) onSubmit();
      }}
      className={`flex min-w-0 flex-col gap-1.5 ${className}`}
      style={style}
    >
      <div
        className={`flex min-w-0 gap-1.5 ${
          stacked ? "flex-col items-stretch" : "items-center"
        }`}
      >
        <input
          value={value}
          onChange={(event) => onChange(event.target.value)}
          onBlur={() => {
            if (value.trim() === "" && !pending) onCancel();
          }}
          onKeyDown={(event) => {
            if (event.key === "Escape") {
              event.preventDefault();
              if (!pending) onCancel();
            }
          }}
          autoFocus
          disabled={pending}
          placeholder={placeholder}
          className="min-w-0 flex-1 rounded-sm border-[1.5px] border-hestia-border bg-hestia-surface px-2.5 py-1.5 text-sm text-hestia-text transition focus:border-hestia-primary focus:outline-none"
        />
        <div
          className={`flex shrink-0 items-center gap-1.5 ${
            stacked ? "justify-end" : ""
          }`}
        >
          <button
            type="button"
            onClick={onCancel}
            disabled={pending}
            className="rounded-md border border-hestia-border px-2 py-1.5 text-xs font-medium text-hestia-text transition hover:bg-hestia-primary-muted disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={value.trim() === "" || pending}
            className="rounded-md bg-hestia-primary px-2.5 py-1.5 text-xs font-semibold text-white transition hover:bg-hestia-primary-hover disabled:opacity-50"
          >
            {pending ? (
              <span className="flex items-center gap-1.5">
                <span
                  aria-hidden="true"
                  className="h-3 w-3 animate-spin rounded-full border-2 border-white/40 border-t-white"
                />
                Adding…
              </span>
            ) : (
              "Add"
            )}
          </button>
        </div>
      </div>
      {error && (
        <p role="alert" className="text-xs text-hestia-danger">
          {error}
        </p>
      )}
    </form>
  );
}
