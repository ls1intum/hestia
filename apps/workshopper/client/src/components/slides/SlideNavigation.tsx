import React from "react";
import { ChevronLeft, ChevronRight } from "lucide-react";

const DOT_WINDOW = 15;

const arrowStyle: React.CSSProperties = { border: "1px solid var(--hestia-border)", color: "var(--hestia-text)" };
const hover = (enabled: boolean) => ({
  onMouseEnter: (e: React.MouseEvent<HTMLButtonElement>) => { if (enabled) e.currentTarget.style.background = "var(--hestia-primary-muted)"; },
  onMouseLeave: (e: React.MouseEvent<HTMLButtonElement>) => { e.currentTarget.style.background = "transparent"; },
});

/** Previous/next arrows, a window of dots around the current slide, and "n / total". */
export function SlideNavigation({ current, total, onChange }: {
  current: number;
  total: number;
  onChange: (index: number) => void;
}) {
  let start = Math.max(0, current - Math.floor(DOT_WINDOW / 2));
  const end = Math.min(total, start + DOT_WINDOW);
  start = Math.max(0, end - DOT_WINDOW);
  const arrowClass = "h-8 w-8 rounded-md flex items-center justify-center transition-colors disabled:opacity-30 focus:outline-none focus-visible:ring-2";

  return (
    <div className="h-12 shrink-0 flex items-center justify-between px-6 z-10"
      style={{ background: "var(--hestia-surface)", borderTop: "1px solid var(--hestia-border)" }}>
      <button onClick={() => onChange(Math.max(0, current - 1))} disabled={current === 0}
        className={arrowClass} style={arrowStyle} {...hover(current > 0)}>
        <ChevronLeft className="h-4 w-4" />
      </button>

      <div className="flex items-center gap-1.5 overflow-hidden px-4">
        {Array.from({ length: end - start }, (_, i) => start + i).map(idx => (
          <button
            key={idx}
            onClick={() => onChange(idx)}
            className="rounded-full transition-all shrink-0 focus:outline-none focus-visible:ring-2"
            style={{
              width: idx === current ? "1.5rem" : "6px",
              height: "6px",
              background: idx === current ? "var(--hestia-primary)" : "var(--hestia-border)",
            }}
            onMouseEnter={e => { if (idx !== current) e.currentTarget.style.background = "var(--hestia-primary-muted)"; }}
            onMouseLeave={e => { if (idx !== current) e.currentTarget.style.background = "var(--hestia-border)"; }}
          />
        ))}
      </div>

      <div className="flex items-center gap-3 shrink-0">
        <span style={{ fontFamily: "var(--hestia-font-mono)", fontSize: "0.875rem", color: "var(--hestia-text-muted)" }}>
          {current + 1} / {total}
        </span>
        <button onClick={() => onChange(Math.min(total - 1, current + 1))} disabled={current === total - 1}
          className={arrowClass} style={arrowStyle} {...hover(current < total - 1)}>
          <ChevronRight className="h-4 w-4" />
        </button>
      </div>
    </div>
  );
}
