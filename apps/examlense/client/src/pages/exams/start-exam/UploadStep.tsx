import { useRef, useState } from "react";
import { FileText, FileUp, X } from "lucide-react";
import { quickCheckUpload, UPLOAD_ACCEPT } from "@/lib/parsing/pdf-precheck";
import { formatBytes, truncateFilename } from "./shared";
import { FastModeToggle } from "./FastModeToggle";

interface Props {
  file: File | null;
  onChange: (file: File | null) => void;
  onError: (msg: string) => void;
  fastMode: boolean;
  onFastModeChange: (enabled: boolean) => void;
}

/**
 * First step of the PDF flow: drop or browse for an exam PDF or Word .docx (plus
 * the Fast Mode parsing toggle). Validates type and size; the selected file is
 * lifted to the orchestrator via onChange.
 */
export const UploadStep = ({
  file,
  onChange,
  onError,
  fastMode,
  onFastModeChange,
}: Props) => {
  const [dragOver, setDragOver] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  const handleFile = (f: File | null | undefined) => {
    if (!f) return;
    const msg = quickCheckUpload(f);
    if (msg) {
      onError(msg);
      return;
    }
    onChange(f);
  };

  const fileArea = file ? (
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
            File selected
          </p>
          <p
            className="mt-hestia-2 max-w-full truncate text-base font-semibold text-hestia-text"
            title={file.name}
          >
            {truncateFilename(file.name)}
          </p>
          <p className="mt-1 text-sm text-hestia-text-muted">
            {formatBytes(file.size)}
          </p>
        </div>

        <div className="mt-hestia-4 rounded-hestia-md border border-hestia-border bg-hestia-bg/40 px-hestia-3 py-hestia-2 text-center">
          <p className="text-xs leading-relaxed text-hestia-text-muted">
            Ready to continue. Remove this file to choose another one.
          </p>
        </div>
      </div>
  ) : (
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
        Drop your PDF or Word (.docx) file here
      </p>
      <p className="text-xs text-hestia-text-muted">
        or click to browse · PDF or .docx · max 10 MB
      </p>
      <input
        ref={inputRef}
        type="file"
        accept={UPLOAD_ACCEPT}
        className="sr-only"
        onChange={(e) => handleFile(e.target.files?.[0])}
      />
    </label>
  );

  return (
    <div className="space-y-hestia-3">
      {fileArea}
      <FastModeToggle checked={fastMode} onChange={onFastModeChange} />
    </div>
  );
};
