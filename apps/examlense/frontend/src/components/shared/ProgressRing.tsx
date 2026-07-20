import type { ReactNode } from "react";
import { cn } from "@/lib/utils/utils";

interface ProgressRingProps {
  /** Fill percentage, 0–100. */
  value: number;
  /** Rendered diameter in pixels. */
  size?: number;
  /** Stroke width in the 0–36 viewBox scale. */
  strokeWidth?: number;
  /**
   * Text-color class for the unfilled track (stroke = currentColor). Must NOT
   * use an `/opacity` modifier on an alpha-baked color token (e.g.
   * `text-hestia-border/60`) — those tokens already carry an alpha channel, so
   * the modifier compiles to invalid double-alpha CSS and the stroke silently
   * falls back to the inherited color. Use a plain token.
   */
  trackClassName?: string;
  /** Text-color class for the filled arc (stroke = currentColor). Same caveat. */
  indicatorClassName?: string;
  /** Wrapper layout classes (NOT a color source — circles set their own color). */
  className?: string;
  /** Centered content (icon or number). */
  children?: ReactNode;
}

/**
 * Small determinate SVG donut ring. Uses the `viewBox="0 0 36 36"` / `r=15.9`
 * trick (circumference ≈ 100) so `value` maps straight onto `strokeDasharray`,
 * and `-rotate-90` starts the arc at 12 o'clock. Both strokes are
 * `currentColor`, so color is set with `text-*` utility classes via
 * `trackClassName` / `indicatorClassName`.
 */
export const ProgressRing = ({
  value,
  size = 24,
  strokeWidth = 3,
  trackClassName = "text-hestia-border",
  indicatorClassName = "text-hestia-primary",
  className,
  children,
}: ProgressRingProps) => {
  const filled = Math.max(0, Math.min(100, value));
  return (
    <div
      className={cn("relative flex shrink-0 items-center justify-center", className)}
      style={{ width: size, height: size }}
    >
      <svg viewBox="0 0 36 36" className="h-full w-full -rotate-90">
        <circle
          cx="18"
          cy="18"
          r="15.9"
          pathLength={100}
          fill="none"
          stroke="currentColor"
          strokeWidth={strokeWidth}
          className={trackClassName}
        />
        {filled > 0 && (
          <circle
            cx="18"
            cy="18"
            r="15.9"
            pathLength={100}
            fill="none"
            stroke="currentColor"
            strokeWidth={strokeWidth}
            strokeDasharray="100"
            strokeDashoffset={100 - filled}
            // Butt (not round) caps: round caps extend ~½ the stroke width past
            // each end and, on a small ring, visually close a nearly-full arc's
            // small gap — making e.g. 95% look like a full circle.
            strokeLinecap="butt"
            className={cn("transition-all duration-500", indicatorClassName)}
          />
        )}
      </svg>
      {children != null && (
        <div className="absolute inset-0 flex items-center justify-center">
          {children}
        </div>
      )}
    </div>
  );
};
