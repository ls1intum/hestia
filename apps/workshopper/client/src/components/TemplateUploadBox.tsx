import React from "react";
import { Upload, RefreshCw, X, File as FileIcon } from "lucide-react";

interface Props {
  /** Name of the template shown in the box; null shows the empty upload state. */
  fileName: string | null;
  /** Small line under the file name, e.g. "Click to replace" or "From course template". */
  caption?: string;
  isBusy?: boolean;
  /** Called with the picked .pptx file. Leave out for a read-only box. */
  onUpload?: (file: File) => void;
  /** Called when the remove button is clicked. Leave out to hide the button. */
  onRemove?: () => void;
  removeTitle?: string;
  /** Text under "Upload" in the empty state. */
  emptyHint?: string;
  /** Shown below the box, e.g. why it cannot be changed here. */
  note?: string;
}

/** The PowerPoint template drop zone shared by the slide workstation and the course settings. */
export function TemplateUploadBox({
  fileName, caption, isBusy = false, onUpload, onRemove, removeTitle = "Remove template",
  emptyHint = "", note,
}: Props) {
  const readOnly = !onUpload;
  const filled = fileName !== null;

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    // Reset the input so the same file can be picked again after it was removed
    e.target.value = "";
    if (file && onUpload) onUpload(file);
  };

  const filledStyle: React.CSSProperties = {
    borderColor: 'var(--hestia-accent)',
    background: 'color-mix(in srgb, var(--hestia-accent) 5%, var(--hestia-surface))',
    borderRadius: 'var(--hestia-radius-md)',
  };
  const emptyStyle: React.CSSProperties = {
    borderColor: 'var(--hestia-border)',
    background: 'var(--hestia-surface)',
    borderRadius: 'var(--hestia-radius-md)',
  };

  return (
    <div className="space-y-1.5">
      <div className="relative group">
        {!readOnly && (
          <input
            type="file"
            accept=".pptx"
            onChange={handleChange}
            disabled={isBusy}
            className="absolute inset-0 w-full h-full opacity-0 cursor-pointer z-10"
          />
        )}
        <div
          className={`border-[2px] ${readOnly ? "border-solid" : "border-dashed"} rounded-md p-3 flex flex-col items-center justify-center gap-1 transition-all min-h-[72px]`}
          style={filled ? filledStyle : emptyStyle}
          onMouseEnter={(e) => { if (!filled && !readOnly) { e.currentTarget.style.borderColor = 'var(--hestia-accent)'; e.currentTarget.style.background = 'color-mix(in srgb, var(--hestia-accent) 5%, var(--hestia-surface))'; } }}
          onMouseLeave={(e) => { if (!filled && !readOnly) { e.currentTarget.style.borderColor = 'var(--hestia-border)'; e.currentTarget.style.background = 'var(--hestia-surface)'; } }}
        >
          {isBusy ? (
            <RefreshCw className="h-5 w-5 animate-spin" style={{ color: 'var(--hestia-accent)' }} />
          ) : filled ? (
            <div className="relative flex flex-col items-center justify-center w-full h-full">
              {onRemove && (
                <button
                  type="button"
                  onClick={(e) => { e.preventDefault(); e.stopPropagation(); onRemove(); }}
                  // The file input covers the whole box (z-10); the remove button has to sit above it
                  className="absolute -top-1 -right-1 z-20 p-1 rounded-full bg-red-50 hover:bg-red-100 text-red-500 hover:text-red-700 transition-colors opacity-0 group-hover:opacity-100 shadow-sm"
                  title={removeTitle}
                >
                  <X className="h-3 w-3" />
                </button>
              )}
              <div className="h-8 w-8 rounded-md flex items-center justify-center mb-1" style={{ background: 'color-mix(in srgb, var(--hestia-accent) 12%, var(--hestia-surface))' }}>
                <FileIcon className="h-4 w-4" style={{ color: 'var(--hestia-accent)' }} />
              </div>
              <span className="text-xs font-medium text-center truncate w-full px-1" style={{ color: 'var(--hestia-accent)', fontFamily: 'var(--hestia-font-body)' }}>{fileName || "Saved Template"}</span>
              {caption && (
                <span className="text-xs text-center" style={{ color: 'color-mix(in srgb, var(--hestia-accent) 70%, transparent)', fontFamily: 'var(--hestia-font-body)' }}>{caption}</span>
              )}
            </div>
          ) : (
            <>
              <Upload className="h-5 w-5 mb-1 transition-colors group-hover:text-[var(--hestia-primary)]" style={{ color: 'var(--hestia-text-muted)' }} />
              <span className="text-[0.875rem] font-medium text-center" style={{ color: 'var(--hestia-text)', fontFamily: 'var(--hestia-font-body)' }}>{readOnly ? "No template" : "Upload"}</span>
              <span className="text-[0.75rem] text-center" style={{ color: 'var(--hestia-text-muted)', fontFamily: 'var(--hestia-font-body)' }}>{emptyHint}</span>
            </>
          )}
        </div>
      </div>
      {note && (
        <p className="text-[0.75rem]" style={{ color: 'var(--hestia-text-muted)', fontFamily: 'var(--hestia-font-body)' }}>{note}</p>
      )}
    </div>
  );
}
