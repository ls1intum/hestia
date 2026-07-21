import type { ReactNode } from "react";
import { cn } from "@/lib/utils/utils";

type Tone = "primary" | "warning";

const TONE: Record<Tone, { border: string; text: string; hover: string; ring: string }> = {
  primary: {
    border: "border-hestia-primary/30",
    text: "text-hestia-primary",
    hover: "hover:border-hestia-primary hover:bg-hestia-primary-muted/40",
    ring: "focus-visible:ring-hestia-primary",
  },
  warning: {
    border: "border-hestia-warning/50",
    text: "text-hestia-warning",
    hover: "hover:bg-hestia-warning/10",
    ring: "focus-visible:ring-hestia-warning",
  },
};

const BASE =
  "inline-flex items-center gap-1.5 whitespace-nowrap rounded-hestia-full border bg-hestia-surface px-hestia-3 py-1.5 text-xs font-semibold shadow-hestia-md";

interface Props {
  label: string;
  icon?: ReactNode;
  /** Which side of the label the icon sits on. */
  iconSide?: "start" | "end";
  tone?: Tone;
  /** Positioning / animation / overrides supplied by the caller. */
  className?: string;
  /** When set the pill is an interactive button; otherwise a decorative span. */
  onClick?: () => void;
}

/**
 * Small floating "pill" used for wayfinding hints across the editor — the
 * "Start here" nudge, the "Score needs to be set" pointer, and the
 * content-missing indicator. Callers own positioning + animation via
 * `className`; this component owns the shared pill chrome and tone.
 */
export const WayfindingPill = ({
  label,
  icon,
  iconSide = "start",
  tone = "primary",
  className,
  onClick,
}: Props) => {
  const t = TONE[tone];
  const inner = (
    <>
      {iconSide === "start" && icon}
      <span>{label}</span>
      {iconSide === "end" && icon}
    </>
  );

  if (onClick) {
    return (
      <button
        type="button"
        onClick={onClick}
        className={cn(
          BASE,
          t.border,
          t.text,
          t.hover,
          t.ring,
          "transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:ring-offset-hestia-bg",
          className,
        )}
      >
        {inner}
      </button>
    );
  }

  return (
    <span aria-hidden className={cn(BASE, t.border, t.text, className)}>
      {inner}
    </span>
  );
};
