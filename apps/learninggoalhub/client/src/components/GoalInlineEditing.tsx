import { useLayoutEffect, useRef, useState, type ReactNode } from "react";

/** Icon button in a row's hover actions; keeps its click and keys away from the row itself. */
export function RowAction({
  label,
  onClick,
  className,
  children,
}: {
  label: string;
  onClick: () => void;
  className: string;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      onClick={(e) => {
        e.stopPropagation();
        onClick();
      }}
      onKeyDown={(e) => e.stopPropagation()}
      className={`flex h-6 w-6 items-center justify-center rounded-md text-hestia-text-muted transition ${className}`}
    >
      <svg
        viewBox="0 0 20 20"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
        className="h-4 w-4"
      >
        {children}
      </svg>
    </button>
  );
}

/**
 * In-place rename of a goal's full wording. Enter or leaving the field saves, Escape cancels;
 * `onDone` gets the new text, or `null` when nothing changed or the edit was dropped.
 */
export function RenameField({
  text,
  onDone,
}: {
  text: string;
  onDone: (text: string | null) => void;
}) {
  const [draft, setDraft] = useState(text);
  const ref = useRef<HTMLTextAreaElement>(null);
  // Enter and Escape unmount the field, which can blur it once more on the way out.
  const finished = useRef(false);
  const finish = (save: boolean) => {
    if (finished.current) return;
    finished.current = true;
    const trimmed = draft.trim();
    onDone(save && trimmed !== "" && trimmed !== text ? trimmed : null);
  };
  useLayoutEffect(() => {
    const field = ref.current;
    if (!field) return;
    field.focus();
    field.setSelectionRange(field.value.length, field.value.length);
  }, []);
  return (
    <textarea
      ref={ref}
      value={draft}
      rows={1}
      aria-label="Goal wording"
      onChange={(e) => setDraft(e.target.value)}
      onClick={(e) => e.stopPropagation()}
      onBlur={() => finish(true)}
      onKeyDown={(e) => {
        // The row opens the goal on Enter/Space, and the grid closes things on Escape.
        e.stopPropagation();
        if (e.key === "Enter" && !e.shiftKey) {
          e.preventDefault();
          finish(true);
        } else if (e.key === "Escape") {
          e.preventDefault();
          finish(false);
        }
      }}
      className="min-w-0 flex-1 resize-none rounded-sm border-[1.5px] border-hestia-primary bg-hestia-bg px-1.5 py-0.5 text-sm leading-relaxed text-hestia-text shadow-[0_0_0_3px_var(--hestia-primary-muted)] outline-none [field-sizing:content]"
    />
  );
}
