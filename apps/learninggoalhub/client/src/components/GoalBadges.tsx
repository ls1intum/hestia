import { COMPETENCY_ROLE_META, type CompetencyRole } from "../lib/goals.ts";

/** Pill naming a node's role in the competency tree, tinted in the role's colour. */
export function RoleBadge({ role }: { role: CompetencyRole }) {
  const meta = COMPETENCY_ROLE_META[role];
  return (
    <span
      className="inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-semibold"
      style={{
        color: meta.color,
        backgroundColor: `color-mix(in srgb, ${meta.color} 15%, transparent)`,
      }}
    >
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
