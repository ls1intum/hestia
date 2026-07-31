import type { CSSProperties } from "react";
import Button from "./Button.tsx";

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
          <Button
            variant="neutral"
            size="sm"
            onClick={onCancel}
            disabled={pending}
          >
            Cancel
          </Button>
          <Button
            type="submit"
            size="sm"
            disabled={value.trim() === "" || pending}
          >
            {pending ? (
              <span className="flex items-center gap-1.5">
                <span
                  aria-hidden="true"
                  className="h-3 w-3 animate-spin rounded-full border-2 border-current/40 border-t-current"
                />
                Adding…
              </span>
            ) : (
              "Add"
            )}
          </Button>
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
