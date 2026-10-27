import React, { useState, useEffect } from "react";

/** Small numeric minutes field; commits on blur or Enter, reverts on Escape. */
export function MinutesInput({ value, onSave, className = "", title }: {
  value: number;
  onSave: (v: string) => void;
  className?: string;
  title?: string;
}) {
  const [draft, setDraft] = useState(String(value));

  useEffect(() => {
    setDraft(String(value));
  }, [value]);

  const commit = () => {
    if (draft !== String(value)) onSave(draft);
  };

  return (
    <input
      type="number"
      min={0}
      value={draft}
      title={title}
      onChange={e => setDraft(e.target.value)}
      onBlur={commit}
      onClick={e => e.stopPropagation()}
      onPointerDown={e => e.stopPropagation()}
      onKeyDown={e => {
        if (e.key === "Enter") e.currentTarget.blur();
        if (e.key === "Escape") { setDraft(String(value)); }
      }}
      className={`bg-background border border-primary/60 rounded-md focus:outline-none focus:ring-1 focus:ring-primary [appearance:textfield] [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none ${className}`}
    />
  );
}
