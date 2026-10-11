import { COMPETENCY_ROLE_META, type CompetencyRole } from "../lib/goals.ts";

/** Pill naming a node's role in the competency tree, tinted in the role's colour. */
export function RoleBadge({ role }: { role: CompetencyRole }) {
  const meta = COMPETENCY_ROLE_META[role];
  const isGap = role === "gap";
  return (
    <span
      className="inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-semibold"
      style={{
        color: meta.color,
        backgroundColor: `color-mix(in srgb, ${meta.color} 15%, transparent)`,
      }}
    >
      {isGap && <GapIcon />}
      {meta.label}
    </span>
  );
}

/** Red pill flagging a goal the instructor accepted as an AI suggestion (WIZARD_AI_SUBTREE). */
export function AiInferredBadge({ compact = false }: { compact?: boolean } = {}) {
  return (
    <span
      className="inline-flex items-center rounded-full px-2 py-0.5 text-xs font-semibold"
      style={{
        color: "var(--hestia-danger)",
        backgroundColor: "color-mix(in srgb, var(--hestia-danger) 15%, transparent)",
      }}
    >
      {compact ? "AI" : "AI-inferred"}
    </span>
  );
}

/** Amber pill flagging a goal the instructor added manually (USER_CREATED). */
export function ManualBadge() {
  return (
    <span
      className="inline-flex items-center rounded-full px-2 py-0.5 text-xs font-semibold"
      style={{
        color: "var(--hestia-warning)",
        backgroundColor: "color-mix(in srgb, var(--hestia-warning) 15%, transparent)",
      }}
    >
      Manual
    </span>
  );
}

function GapIcon() {
  return (
    <svg
      viewBox="0 0 20 20"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      className="h-3 w-3"
    >
      <path d="M10 3.5L2.5 16.5h15z" />
      <path d="M10 8v3.5" />
      <path d="M10 14h.01" />
    </svg>
  );
}
