import type { CSSProperties } from "react";
import Button from "./Button.tsx";

function Spinner() {
  return (
    <span
      aria-hidden="true"
      className="h-3 w-3 animate-spin rounded-full border-2 border-current/40 border-t-current"
    />
  );
}

export default function CompetencyCreationField({
  value,
  placeholder,
  error,
  pending,
  onChange,
  onSubmit,
  onCancel,
  onGenerate,
  generating = false,
  onFind,
  className = "",
  style,
  stacked = false,
  autoFocus = true,
}: {
  value: string;
  placeholder: string;
  error?: string;
  pending: boolean;
  onChange: (value: string) => void;
  onSubmit: () => void;
  /** Closes the field. Without it the field stays open: no Cancel, no closing on blur or Escape. */
  onCancel?: () => void;
  /**
   * Offers "Generate with AI" beside "Add": the typed text is created together with goals an AI
   * writes beneath it. Without it, the field only adds exactly what was typed.
   */
  onGenerate?: () => void;
  /** The pending creation is a generation, so its button carries the spinner. */
  generating?: boolean;
  /** Offers "Find in the slides" beside "Add": the typed text is looked up in the course's pages. */
  onFind?: () => void;
  className?: string;
  style?: CSSProperties;
  /** Stacks the field above its buttons, for the map's fixed-width boxes where a row would overflow. */
  stacked?: boolean;
  /** Focuses the field on mount; off for a field that is always on screen. */
  autoFocus?: boolean;
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
            if (value.trim() === "" && !pending) onCancel?.();
          }}
          onKeyDown={(event) => {
            if (event.key === "Escape") {
              event.preventDefault();
              if (!pending) onCancel?.();
            }
          }}
          autoFocus={autoFocus}
          disabled={pending}
          placeholder={placeholder}
          className="min-w-0 flex-1 rounded-sm border-[1.5px] border-hestia-border bg-hestia-surface px-2.5 py-1.5 text-sm text-hestia-text transition focus:border-hestia-primary focus:outline-none"
        />
        <div
          className={`flex shrink-0 items-center gap-1.5 ${
            stacked ? "justify-end" : ""
          }`}
        >
          {onCancel && (
            <Button
              variant="neutral"
              size="sm"
              onClick={onCancel}
              disabled={pending}
            >
              Cancel
            </Button>
          )}
          {onFind && (
            <Button
              variant="neutral"
              size="sm"
              title="Read the pages that teach it and create it with the sub-skills found there"
              onClick={onFind}
              disabled={value.trim() === "" || pending}
            >
              Find in the slides
            </Button>
          )}
          {onGenerate && (
            <Button
              variant="neutral"
              size="sm"
              title="Also write skills, sub-skills and knowledge beneath it with AI, without a source"
              onClick={onGenerate}
              disabled={value.trim() === "" || pending}
            >
              {pending && generating ? (
                <span className="flex items-center gap-1.5">
                  <Spinner />
                  Generating…
                </span>
              ) : (
                "Generate with AI"
              )}
            </Button>
          )}
          <Button
            type="submit"
            size="sm"
            disabled={value.trim() === "" || pending}
          >
            {pending && !generating ? (
              <span className="flex items-center gap-1.5">
                <Spinner />
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
