import { cn } from "@/lib/utils/utils";
import "./ForgeAnimation.css";

/**
 * Hephaestos-themed "the exam is being forged" loading scene, shown on the
 * parsing / evaluating splash (EvaluatingView) in place of a plain spinner.
 *
 * A glowing exam sheet rests on a bronze anvil while a hammer swings down and
 * strikes it on a steady 2s loop; each impact bursts sparks and flares the
 * sheet's glow. A soft warm glow behind the anvil gently breathes to suggest
 * the forge's heat. All motion is CSS-driven (see ForgeAnimation.css) and
 * colored with the HESTIA theme tokens so it adapts to light/dark. Under
 * `prefers-reduced-motion: reduce` the scene rests as a static tableau (hammer
 * raised, no sparks).
 *
 * Six sparks share one keyframe; each carries its own scatter vector via the
 * `--sx` / `--sy` custom properties.
 */
const SPARKS: Array<{ sx: number; sy: number; r: number }> = [
  { sx: -16, sy: -14, r: 1.4 },
  { sx: -8, sy: -22, r: 1.1 },
  { sx: 2, sy: -25, r: 1.5 },
  { sx: 12, sy: -21, r: 1.2 },
  { sx: 18, sy: -11, r: 1.3 },
  { sx: -20, sy: -5, r: 1 },
];

export const ForgeAnimation = ({ className }: { className?: string }) => {
  return (
    <svg
      viewBox="0 0 120 100"
      role="img"
      aria-label="Forging your exam"
      className={cn("h-24 w-28", className)}
      xmlns="http://www.w3.org/2000/svg"
    >
      <title>Forging your exam</title>
      <desc>
        A hammer strikes a glowing exam sheet on an anvil that glows with the
        forge's heat, throwing off sparks.
      </desc>

      <defs>
        <filter id="forge-glow-blur" x="-50%" y="-50%" width="200%" height="200%">
          <feGaussianBlur stdDeviation="3" />
        </filter>
      </defs>

      {/* Warm glow behind the anvil — the forge's heat. Rendered first so it
          sits behind everything; gently breathes to read as a live forge. */}
      <ellipse
        cx="60"
        cy="66"
        rx="27"
        ry="21"
        className="forge-ember"
        filter="url(#forge-glow-blur)"
      />

      {/* Anvil */}
      <g>
        {/* base */}
        <path d="M45 75 L75 75 L81 87 L39 87 Z" className="forge-anvil" />
        {/* waist */}
        <rect x="53" y="64" width="14" height="12" className="forge-anvil" />
        {/* top block + horn */}
        <path
          d="M35 56 H84 L96 60 L84 64 H35 Z"
          className="forge-anvil"
        />
        {/* top face highlight */}
        <rect x="35" y="56" width="49" height="2.4" className="forge-anvil-top" />
      </g>

      {/* Glowing exam sheet on the anvil (flares on each strike) */}
      <ellipse
        cx="61"
        cy="52"
        rx="20"
        ry="8"
        className="forge-glow"
        filter="url(#forge-glow-blur)"
      />
      <g>
        <rect
          x="48"
          y="49"
          width="26"
          height="7.5"
          rx="1.5"
          className="forge-sheet"
        />
        <rect x="51" y="50.6" width="20" height="1" rx="0.5" className="forge-sheet-line" />
        <rect x="51" y="52.6" width="16" height="1" rx="0.5" className="forge-sheet-line" />
        <rect x="51" y="54.6" width="18" height="1" rx="0.5" className="forge-sheet-line" />
      </g>

      {/* Sparks — burst from the impact point, timed to the strike */}
      <g>
        {SPARKS.map((s, i) => (
          <circle
            key={i}
            cx="63"
            cy="47"
            r={s.r}
            className="forge-spark"
            style={
              {
                "--sx": `${s.sx}px`,
                "--sy": `${s.sy}px`,
              } as React.CSSProperties
            }
          />
        ))}
      </g>

      {/* Hammer — pivots about the smith's grip and strikes the sheet */}
      <g className="forge-hammer">
        <g transform="translate(61 42) rotate(-38)">
          <rect
            x="5"
            y="-2.6"
            width="30"
            height="5.2"
            rx="2.6"
            style={{ fill: "#8a5a2b" }}
          />
          <rect
            x="-6"
            y="-9"
            width="12"
            height="18"
            rx="2.5"
            className="forge-hammer-head"
          />
          <rect
            x="-6"
            y="4.5"
            width="12"
            height="4.5"
            rx="1.8"
            className="forge-hammer-face"
          />
        </g>
      </g>
    </svg>
  );
};
