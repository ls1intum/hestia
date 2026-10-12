import { useState, useRef, useEffect } from "react";

/** A block title that turns into an input on click (or always, while the block is in edit mode). */
export function InlineEditText({
  value, editing, alwaysEdit = false, onStartEdit, onSave, className = "",
}: {
  value: string;
  editing: boolean;
  alwaysEdit?: boolean;
  onStartEdit: () => void;
  onSave: (v: string) => void;
  className?: string;
}) {
  const inputClassName = "text-sm px-2 py-0.5";
  const [draft, setDraft] = useState(value);
  const inputRef = useRef<HTMLInputElement>(null);
  const mirrorRef = useRef<HTMLSpanElement>(null);

  useEffect(() => {
    setDraft(value);
  }, [value]);

  useEffect(() => {
    if (editing && !alwaysEdit) {
      setTimeout(() => inputRef.current?.focus(), 30);
    }
  }, [editing, alwaysEdit]);

  // Sync input width to mirror span width
  useEffect(() => {
    if (inputRef.current && mirrorRef.current) {
      const w = mirrorRef.current.offsetWidth;
      inputRef.current.style.width = `${Math.max(40, w + 8)}px`;
    }
  }, [draft]);

  const commit = () => onSave(draft);

  if (editing || alwaysEdit) {
    return (
      <span className="relative inline-flex items-center">
        {/* Hidden mirror span to measure text width */}
        <span
          ref={mirrorRef}
          aria-hidden
          className={`invisible absolute whitespace-pre font-body font-semibold ${inputClassName} ${className}`}
          style={{ pointerEvents: "none" }}
        >
          {draft || " "}
        </span>
        <input
          ref={inputRef}
          value={draft}
          onChange={e => setDraft(e.target.value)}
          onBlur={commit}
          onKeyDown={e => { if (e.key === "Enter") commit(); if (e.key === "Escape") { setDraft(value); onSave(value); } }}
          className={`font-body font-semibold bg-background border border-primary/60 rounded-md focus:outline-none focus:ring-1 focus:ring-primary ${inputClassName} ${className}`}
          style={{ minWidth: 40 }}
        />
      </span>
    );
  }

  return (
    <span
      className={`cursor-text select-text ${className}`}
      onClick={e => {
        e.stopPropagation();
        onStartEdit();
      }}
      title="Click to edit"
    >
      {value || "Click to edit..."}
    </span>
  );
}
