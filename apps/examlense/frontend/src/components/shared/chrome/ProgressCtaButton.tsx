import { cn } from "@/lib/utils/utils";

export type ProgressCtaTone = "progress" | "success" | "warning";

interface Props {
  /** Text shown centered on the button. */
  label: string;
  /** Optional leading icon. */
  icon?: React.ReactNode;
  /** Colour scheme of the button + fill bar. */
  tone: ProgressCtaTone;
  /** Width (0–100) of the left-to-right fill bar. */
  fillPct: number;
  /** Renders the button as non-interactive. */
  disabled?: boolean;
  /** Shows the pulsing success halo (draws the eye to the green CTA). */
  showPing?: boolean;
  onClick: () => void;
}

/**
 * Footer CTA button used across the exam editor and grading view. Purely
 * presentational — the caller resolves label/icon/tone/fill and the click
 * action. Visualises completion as a left-to-right fill under a centered label.
 */
export const ProgressCtaButton = ({
  label,
  icon = null,
  tone,
  fillPct,
  disabled = false,
  showPing = false,
  onClick,
}: Props) => (
  <span className="relative inline-block w-full max-w-[24rem] sm:w-[20rem]">
    {showPing && (
      <span
        aria-hidden
        className="pointer-events-none absolute inset-0 rounded-hestia-md ring-2 ring-hestia-success/60 animate-ping-sm"
      />
    )}
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      aria-label={label}
      className={cn(
        "group relative h-8 w-full cursor-pointer overflow-hidden rounded-hestia-md border text-xs font-semibold shadow-sm transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-hestia-primary active:scale-[0.98]",
        disabled
          ? "cursor-not-allowed border-hestia-border bg-hestia-bg/40 text-hestia-text-muted"
          : tone === "success"
            ? "border-hestia-success/60 bg-hestia-success/15 text-hestia-success hover:bg-hestia-success/25"
            : tone === "warning"
              ? "border-hestia-warning/60 bg-hestia-warning/10 text-hestia-warning hover:bg-hestia-warning/20"
              : "border-hestia-primary/40 bg-hestia-bg/60 text-hestia-text hover:border-hestia-primary hover:bg-hestia-primary-muted/40 hover:shadow-md",
      )}
    >
      {/* Fill bar */}
      <span
        aria-hidden
        className={cn(
          "absolute inset-y-0 left-0 transition-[width] duration-500 ease-out",
          tone === "success"
            ? "bg-hestia-success/30"
            : tone === "warning"
              ? "bg-hestia-warning/30"
              : "bg-hestia-primary/25",
        )}
        style={{ width: `${fillPct}%` }}
      />
      {/* Overlay label */}
      <span className="relative z-10 flex items-center justify-center gap-1.5 whitespace-nowrap px-hestia-3">
        {icon}
        <span className="truncate">{label}</span>
      </span>
    </button>
  </span>
);
