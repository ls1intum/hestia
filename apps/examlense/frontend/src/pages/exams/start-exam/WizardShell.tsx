import type { ReactNode } from "react";
import { ArrowLeft, Loader2 } from "lucide-react";
import { cn } from "@/lib/utils/utils";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Heading for the current step. */
  title: string;
  /** Optional one-line helper under the title. */
  subtitle?: string;
  /** Zero-based index of the current step. */
  stepIndex: number;
  /** Total number of steps in this flow. */
  stepCount: number;
  /** Called when Back is pressed. Omit to hide the Back button (first step). */
  onBack?: () => void;
  /** Called when the primary button is pressed. */
  onNext: () => void;
  /** Label for the primary button (e.g. "Continue", "Parse exam"). */
  nextLabel: string;
  /**
   * Visual tone of the primary button. "muted" renders a greyer, secondary
   * button — used when the step can be skipped without a real choice.
   */
  nextVariant?: "primary" | "muted";
  /** Optional muted helper shown just above the footer (e.g. a skip caveat). */
  nextNote?: string;
  /** Disables the primary button (step invalid). */
  nextDisabled?: boolean;
  /** Shows a spinner + disables navigation while a transition runs. */
  busy?: boolean;
  children: ReactNode;
}

/**
 * Shared chrome for the guided exam-creation wizards. Owns the dialog frame,
 * the step progress indicator, the step title, and the Back / primary-action
 * footer so every step in both flows looks and behaves identically. Step
 * bodies are passed as children and stay purely presentational.
 */
export const WizardShell = ({
  open,
  onOpenChange,
  title,
  subtitle,
  stepIndex,
  stepCount,
  onBack,
  onNext,
  nextLabel,
  nextVariant = "primary",
  nextNote,
  nextDisabled,
  busy,
  children,
}: Props) => {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-[560px] bg-hestia-surface border-hestia-border">
        <DialogHeader>
          <div className="flex items-center gap-hestia-2">
            <span className="hestia-eyebrow text-hestia-text-muted">
              {`Step ${stepIndex + 1} of ${stepCount}`}
            </span>
            <div className="flex items-center gap-1">
              {Array.from({ length: stepCount }).map((_, i) => (
                <span
                  key={i}
                  aria-hidden
                  className={cn(
                    "h-1.5 w-1.5 rounded-full transition-colors",
                    i <= stepIndex ? "bg-hestia-primary" : "bg-hestia-border",
                  )}
                />
              ))}
            </div>
          </div>
          <DialogTitle className="font-display text-2xl text-hestia-text">
            {title}
          </DialogTitle>
          {subtitle && (
            <p className="text-sm text-hestia-text-muted">{subtitle}</p>
          )}
        </DialogHeader>

        <div className="mt-hestia-2 min-h-[220px]">{children}</div>

        <div className="mt-hestia-2 flex flex-col gap-hestia-1">
          {nextNote && (
            <p className="text-right text-xs text-hestia-text-muted">
              {nextNote}
            </p>
          )}
          <div className="flex items-center justify-between gap-hestia-3">
            {onBack ? (
              <button
                type="button"
                onClick={onBack}
                disabled={busy}
                className="inline-flex items-center gap-1 rounded-hestia-md px-hestia-3 py-hestia-2 text-sm font-medium text-hestia-text-muted transition-colors hover:bg-hestia-primary-muted/30 hover:text-hestia-text disabled:opacity-50"
              >
                <ArrowLeft size={14} />
                Back
              </button>
            ) : (
              <span />
            )}
            <button
              type="button"
              onClick={onNext}
              disabled={nextDisabled || busy}
              className={cn(
                "inline-flex items-center justify-center gap-hestia-2 rounded-hestia-md px-hestia-4 py-hestia-2 text-sm font-semibold shadow-hestia-sm transition-colors disabled:opacity-50",
                nextVariant === "muted"
                  ? "bg-hestia-border/60 text-hestia-text-muted hover:bg-hestia-border hover:text-hestia-text"
                  : "bg-hestia-primary text-white hover:bg-hestia-primary-hover",
              )}
            >
              {busy && <Loader2 size={14} className="animate-spin" />}
              {nextLabel}
            </button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
};
