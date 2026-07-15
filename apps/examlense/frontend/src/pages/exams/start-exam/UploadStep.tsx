import { useRef, useState } from "react";
import { FileText, FileUp, X } from "lucide-react";
import { MAX_BYTES, formatBytes } from "./shared";

interface Props {
  file: File | null;
  onChange: (file: File | null) => void;
  onError: (msg: string) => void;
}

/**
 * First step of the PDF flow: drop or browse for an exam PDF. Validates type
 * and size; the selected file is lifted to the orchestrator via onChange.
 */
export const UploadStep = ({ file, onChange, onError }: Props) => {
  const [dragOver, setDragOver] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  const validate = (f: File): boolean => {
    if (f.type !== "application/pdf") {
      onError("Only PDF files are supported.");
      return false;
    }
    if (f.size > MAX_BYTES) {
      onError("File is too large. Maximum size is 10 MB.");
      return false;
    }
    return true;
  };

  const handleFile = (f: File | null | undefined) => {
    if (!f) return;
    if (validate(f)) onChange(f);
  };

  if (file) {
    return (
      <div className="relative flex min-h-[200px] w-full flex-col justify-between overflow-hidden rounded-hestia-lg border border-hestia-border bg-hestia-surface px-hestia-5 py-hestia-5 shadow-hestia-sm">
        <button
          type="button"
          onClick={() => onChange(null)}
          aria-label="Choose a different file"
          title="Choose a different file"
          className="absolute right-hestia-3 top-hestia-3 inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-hestia-md text-hestia-text-muted transition-colors hover:bg-hestia-primary-muted hover:text-hestia-text focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-hestia-primary focus-visible:ring-offset-2 focus-visible:ring-offset-hestia-surface"
        >
          <X size={14} />
        </button>

        <div className="flex min-w-0 flex-1 flex-col items-center justify-center text-center">
          <div className="mb-hestia-4 inline-flex h-16 w-16 items-center justify-center rounded-hestia-lg border border-hestia-border bg-hestia-bg/60 text-hestia-primary">
            <FileText size={30} aria-hidden="true" />
          </div>
          <p className="hestia-eyebrow text-hestia-text-muted">
            PDF selected
          </p>
          <p
            className="mt-hestia-2 max-w-full truncate text-base font-semibold text-hestia-text"
            title={file.name}
          >
            {file.name}
          </p>
          <p className="mt-1 text-sm text-hestia-text-muted">
            {formatBytes(file.size)}
          </p>
        </div>

        <div className="mt-hestia-4 rounded-hestia-md border border-hestia-border bg-hestia-bg/40 px-hestia-3 py-hestia-2 text-center">
          <p className="text-xs leading-relaxed text-hestia-text-muted">
            Ready to continue. Remove this file to choose another PDF.
          </p>
        </div>
      </div>
    );
  }

  return (
    <label
      onDragOver={(e) => {
        e.preventDefault();
        setDragOver(true);
      }}
      onDragLeave={() => setDragOver(false)}
      onDrop={(e) => {
        e.preventDefault();
        setDragOver(false);
        handleFile(e.dataTransfer.files?.[0]);
      }}
      className={`flex min-h-[200px] flex-col items-center justify-center rounded-hestia-lg border-2 border-dashed px-hestia-4 py-hestia-5 text-center transition-colors cursor-pointer ${
        dragOver
          ? "border-hestia-primary bg-hestia-primary-muted/30"
          : "border-hestia-border bg-hestia-bg/40"
      }`}
    >
      <FileUp size={28} className="text-hestia-text-muted" />
      <p className="mt-hestia-3 text-sm font-medium text-hestia-text">
        Drop your PDF here
      </p>
      <p className="text-xs text-hestia-text-muted">
        or click to browse · max 10 MB
      </p>
      <input
        ref={inputRef}
        type="file"
        accept="application/pdf"
        className="sr-only"
        onChange={(e) => handleFile(e.target.files?.[0])}
      />
    </label>
  );
};
