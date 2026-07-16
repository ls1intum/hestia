import { useEffect, useRef, useState } from "react";
import { useAutosizeTextarea } from "@/hooks/ui/use-autosize-textarea";
import { useDebouncedCallback } from "@/hooks/ui/use-debounced-callback";

interface InlineTextEditOptions {
  /** Source-of-truth value (e.g. task.prompt / block.content). */
  value: string;
  /** Persist a committed value. Debounced on keystroke, flushed on blur. */
  onCommit: (value: string) => void;
  debounceMs?: number;
}

export interface InlineTextEdit {
  /** Local mirror of the text (fast typing without cascading re-renders). */
  value: string;
  editing: boolean;
  /** Enter edit mode and focus the textarea with the caret at the end. */
  enterEdit: () => void;
  /** True while the local mirror is blank (forces the editor open). */
  isEmpty: boolean;
  textareaRef: ReturnType<typeof useAutosizeTextarea<HTMLTextAreaElement>>;
  textareaProps: {
    value: string;
    onChange: (e: React.ChangeEvent<HTMLTextAreaElement>) => void;
    onBlur: () => void;
  };
}

/**
 * Click-to-edit text field state machine shared by the editable task and
 * context cards: a debounced local mirror, an `editing` toggle that starts open
 * when the value is blank, a focus-caret-on-enter effect, and a blur handler
 * that flushes the pending patch and closes edit mode once non-empty.
 */
export function useInlineTextEdit({
  value: source,
  onCommit,
  debounceMs = 250,
}: InlineTextEditOptions): InlineTextEdit {
  const [value, setValue] = useState(source);
  useEffect(() => setValue(source), [source]);

  const textareaRef = useAutosizeTextarea<HTMLTextAreaElement>(value);
  const [editing, setEditing] = useState(() => (source ?? "").trim() === "");
  const justEnteredEdit = useRef(false);

  useEffect(() => {
    if (editing && justEnteredEdit.current) {
      justEnteredEdit.current = false;
      const el = textareaRef.current;
      if (el) {
        el.focus();
        const len = el.value.length;
        try {
          el.setSelectionRange(len, len);
        } catch {
          /* noop */
        }
      }
    }
  }, [editing, textareaRef]);

  const debounced = useDebouncedCallback(
    (next: string) => onCommit(next),
    debounceMs,
  );

  const enterEdit = () => {
    justEnteredEdit.current = true;
    setEditing(true);
  };

  return {
    value,
    editing,
    enterEdit,
    isEmpty: value.trim() === "",
    textareaRef,
    textareaProps: {
      value,
      onChange: (e) => {
        setValue(e.target.value);
        debounced(e.target.value);
      },
      onBlur: () => {
        debounced.flush();
        if (value !== source) onCommit(value);
        if (value.trim() !== "") setEditing(false);
      },
    },
  };
}
