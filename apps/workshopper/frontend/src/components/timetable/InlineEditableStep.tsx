import { useState, useEffect, useRef } from "react";


export function InlineEditableStep({
  text,
  cleanText,
  isEditMode,
  onSave,
  className,
  style
}: {
  text: string;
  cleanText: string;
  isEditMode: boolean;
  onSave: (newText: string) => void;
  className?: string;
  style?: React.CSSProperties;
}) {
  const [val, setVal] = useState(cleanText);
  
  useEffect(() => {
    setVal(cleanText);
  }, [cleanText]);

  const handleBlur = () => {
    if (val !== cleanText) {
      // Reconstruct original prefixes by replacing the clean portion
      // Be careful if cleanText is empty or not found, just fallback to val
      if (cleanText && text.includes(cleanText)) {
        onSave(text.replace(cleanText, val));
      } else {
        onSave(val);
      }
    }
  };

  if (isEditMode) {
    return (
      <textarea rows={2}
        value={val}
        onChange={(e: any) => setVal(e.target.value)}
        onBlur={handleBlur}
        onKeyDown={(e: any) => {
          if (e.key === "Enter" && !e.shiftKey) {
            e.preventDefault();
            e.currentTarget.blur();
          }
        }}
        className={`w-full bg-transparent border border-primary/30 rounded focus:border-primary focus:ring-1 focus:ring-primary focus:outline-none resize-none overflow-hidden ${className || ""}`}
        style={style}
      />
    );
  }

  return (
    <div className={className} style={style}>
      {cleanText}
    </div>
  );
}
