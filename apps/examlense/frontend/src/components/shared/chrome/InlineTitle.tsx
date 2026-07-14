import { useEffect, useState } from "react";
import { Pencil } from "lucide-react";
import { Input } from "@/components/ui/input";

interface Props {
  value: string;
  onSave: (v: string) => void;
}

export const InlineTitle = ({ value, onSave }: Props) => {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(value);
  useEffect(() => setDraft(value), [value]);

  if (!editing) {
    return (
      <button
        type="button"
        onClick={() => setEditing(true)}
        className="group flex max-w-full items-center gap-1.5 text-left font-body text-base font-semibold leading-tight text-hestia-text transition-colors hover:text-hestia-primary"
      >
        <span className="truncate">
          {value || (
            <span className="text-hestia-text-muted">
              Untitled exam
            </span>
          )}
        </span>
        <Pencil
          size={12}
          className="shrink-0 text-hestia-text-muted transition-colors group-hover:text-hestia-primary"
          aria-hidden
        />
      </button>
    );
  }
  return (
    <Input
      autoFocus
      value={draft}
      onChange={(e) => setDraft(e.target.value)}
      onBlur={() => {
        onSave(draft.trim());
        setEditing(false);
      }}
      onKeyDown={(e) => {
        if (e.key === "Enter") (e.target as HTMLInputElement).blur();
        if (e.key === "Escape") {
          setDraft(value);
          setEditing(false);
        }
      }}
      className="h-auto border-hestia-border bg-transparent py-1 font-body text-base font-semibold"
    />
  );
};

/** Static, non-editable title slot styled to match {@link InlineTitle}'s
 *  resting state. Use for read-only chrome (e.g. grading view). */
export const StaticTitle = ({ value }: { value: string }) => {
  return (
    <span className="flex max-w-full items-center text-left font-body text-base font-semibold leading-tight text-hestia-text">
      <span className="truncate">
        {value || (
          <span className="text-hestia-text-muted">
            Untitled exam
          </span>
        )}
      </span>
    </span>
  );
};