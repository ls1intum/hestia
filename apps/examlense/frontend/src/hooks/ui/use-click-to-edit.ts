import { useEffect, useState } from "react";

/**
 * Click-to-edit title state machine shared by InlineTitle and SectionTitleInput:
 * a resting affordance flips to an input that commits the trimmed draft on blur
 * / Enter and reverts on Escape. The commit guard (e.g. skip if unchanged) is
 * left to the caller's `onSave`.
 */
export function useClickToEdit(value: string, onSave: (next: string) => void) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(value);
  useEffect(() => setDraft(value), [value]);

  return {
    editing,
    startEditing: () => setEditing(true),
    inputProps: {
      value: draft,
      autoFocus: true,
      onChange: (e: React.ChangeEvent<HTMLInputElement>) =>
        setDraft(e.target.value),
      onBlur: () => {
        onSave(draft.trim());
        setEditing(false);
      },
      onKeyDown: (e: React.KeyboardEvent<HTMLInputElement>) => {
        if (e.key === "Enter") (e.target as HTMLInputElement).blur();
        if (e.key === "Escape") {
          setDraft(value);
          setEditing(false);
        }
      },
    },
  };
}
