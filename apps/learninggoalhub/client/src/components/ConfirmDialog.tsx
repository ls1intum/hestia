import { useEffect } from "react";
import Button from "./Button.tsx";

/**
 * Small centered confirmation overlay for actions that cannot be taken back. `tone` colours the
 * confirm button: `danger` for deletions, `primary` for a step that only replaces something.
 */
export default function ConfirmDialog({
  title,
  message,
  confirmLabel,
  tone = "danger",
  busy,
  error,
  onConfirm,
  onCancel,
}: {
  title: string;
  message: string;
  confirmLabel: string;
  tone?: "danger" | "primary";
  busy?: boolean;
  error?: string;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onCancel();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onCancel]);

  return (
    <div
      onClick={onCancel}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
      role="dialog"
      aria-modal="true"
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-sm rounded-xl border border-hestia-border bg-hestia-surface p-6 shadow-lg"
      >
        <h3 className="text-lg text-hestia-text">{title}</h3>
        <p className="mt-2 text-sm leading-relaxed text-hestia-text-muted">
          {message}
        </p>
        {error && <p className="mt-3 text-sm text-hestia-danger">{error}</p>}
        <div className="mt-6 flex justify-end gap-2">
          <Button variant="neutral" onClick={onCancel} disabled={busy}>
            Cancel
          </Button>
          <Button
            variant={tone === "danger" ? "danger" : "primary"}
            onClick={onConfirm}
            disabled={busy}
          >
            {confirmLabel}
          </Button>
        </div>
      </div>
    </div>
  );
}
