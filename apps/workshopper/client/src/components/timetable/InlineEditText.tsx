import React, { useState, useRef, useEffect } from "react";

export function InlineEditText({
  value, editing, alwaysEdit = false, onStartEdit, onSave, multiline = false, className = "", inputClassName = "text-sm px-2 py-0.5", boxStyle = false, disabled = false
}: {
  value: string;
  editing: boolean;
  alwaysEdit?: boolean;
  onStartEdit: () => void;
  onSave: (v: string) => void;
  multiline?: boolean;
  className?: string;
  inputClassName?: string;
  boxStyle?: boolean;
  disabled?: boolean;
}) {
  const [draft, setDraft] = useState(value);
  const inputRef = useRef<HTMLInputElement & HTMLTextAreaElement>(null);
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
    if (multiline) {
      return (
        <textarea
          ref={inputRef as React.Ref<HTMLTextAreaElement>}
          value={draft}
          onChange={e => setDraft(e.target.value)}
          onBlur={commit}
          onKeyDown={e => { if (e.key === "Escape") { setDraft(value); onSave(value); } }}
          rows={3}
          className={`w-full text-sm bg-background border border-primary/60 rounded-md p-2 resize-none focus:outline-none focus:ring-1 focus:ring-primary shadow-sm ${className}`}
        />
      );
    }
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
          ref={inputRef as React.Ref<HTMLInputElement>}
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
      className={`${disabled ? "" : "cursor-text"} select-text ${boxStyle ? "inline-block border border-border/60 rounded px-1 py-0.5 bg-background/50 hover:bg-background transition-colors" : ""} ${className}`}
      onClick={e => {
        if (disabled) return;
        e.stopPropagation();
        onStartEdit();
      }}
      title={disabled ? "" : "Click to edit"}
    >
      {value || (boxStyle ? "0" : "Click to edit...")}
    </span>
  );
}
