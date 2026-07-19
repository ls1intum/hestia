import type { ReactNode } from "react";
import type { LearningGoal } from "../api/client.ts";
import { LEVEL_META, RELATIONSHIP_LABELS, levelOf } from "../lib/goals.ts";

type BadgeVariant = "primary" | "accent" | "warning" | "neutral";

const BADGE_VARIANTS: Record<BadgeVariant, string> = {
  primary: "bg-hestia-primary-muted text-hestia-primary",
  accent:
    "bg-[color-mix(in_srgb,var(--hestia-accent)_15%,transparent)] text-hestia-accent",
  warning:
    "bg-[color-mix(in_srgb,var(--hestia-warning)_20%,var(--hestia-surface))] text-hestia-text",
  neutral: "bg-hestia-bg text-hestia-text-muted",
};

/**
 * Read-only detail panel shown on the right of both the List and Graph views (mockup
 * screens 4 + 5). Approve/edit/delete live on the list cards; in the graph view a
 * "View in list" affordance is offered via {@code onViewInList}.
 */
export default function GoalDetailPanel({
  goal,
  onViewInList,
}: {
  goal: LearningGoal | null;
  onViewInList?: () => void;
}) {
  if (!goal) {
    return (
      <div className="rounded-xl border border-dashed border-hestia-border p-5 text-sm text-hestia-text-muted">
        Select a learning goal to see its level, taxonomy, sources and
        relationships.
      </div>
    );
  }

  return (
    <div className="rounded-xl border border-hestia-border bg-hestia-surface p-5 shadow-sm">
      <GoalDetailContent goal={goal} />
      {onViewInList && (
        <button
          onClick={onViewInList}
          className="mt-5 text-sm font-medium text-hestia-primary transition hover:text-hestia-primary-hover"
        >
          View in list →
        </button>
      )}
    </div>
  );
}

/** The shared detail body — taxonomy, level, sources and relationships — for a single goal. */
export function GoalDetailContent({ goal }: { goal: LearningGoal }) {
  const level = levelOf(goal);
  const path = [
    goal.hierarchy?.module,
    goal.hierarchy?.session,
    goal.hierarchy?.exercise,
  ].filter((l): l is string => Boolean(l));
  const sources = goal.sources ?? [];
  const relationships = goal.relationships ?? [];

  return (
    <div className="flex flex-col gap-5">
      <div>
        <h3 className="text-sm font-semibold text-hestia-text">Goal details</h3>
        <p className="mt-2 text-sm leading-relaxed text-hestia-text">
          {goal.text}
        </p>
      </div>

      <div className="flex flex-wrap gap-2">
        {goal.status === "APPROVED" && (
          <Badge variant="accent">✓ approved</Badge>
        )}
        {goal.kind && (
          <Badge variant={goal.kind === "EXPLICIT" ? "primary" : "warning"}>
            {goal.kind.toLowerCase()}
          </Badge>
        )}
        {goal.bloomLevel && (
          <Badge variant="accent">Bloom: {goal.bloomLevel}</Badge>
        )}
        {goal.soloLevel && (
          <Badge variant="neutral">SOLO: {goal.soloLevel}</Badge>
        )}
      </div>

      <Field label="Level">
        <span className="inline-flex items-center gap-1.5 text-sm text-hestia-text">
          <span
            className="inline-block h-2.5 w-2.5 rounded-full"
            style={{ backgroundColor: LEVEL_META[level].color }}
          />
          {LEVEL_META[level].label}
        </span>
        {path.length > 0 && (
          <p className="mt-1 text-xs text-hestia-text-muted">
            {path.join(" › ")}
          </p>
        )}
      </Field>

      {sources.length > 0 && (
        <Field label="Sources">
          <ul className="space-y-2 text-sm text-hestia-text">
            {sources.map((source, i) => (
              <li key={i}>
                <div className="flex items-baseline gap-2">
                  {source.filename && (
                    <p className="min-w-0 truncate font-medium">{source.filename}</p>
                  )}
                  {source.grounded === false && (
                    <span
                      title="Snippet could not be located in the document"
                      className="shrink-0 text-[0.65rem] font-normal text-hestia-text-muted"
                    >
                      unverified
                    </span>
                  )}
                </div>
                {source.snippet && (
                  <p className="mt-1 line-clamp-3 border-l-2 border-hestia-border pl-2.5 text-xs italic leading-relaxed text-hestia-text-muted">
                    “{source.snippet}”
                  </p>
                )}
              </li>
            ))}
          </ul>
        </Field>
      )}

      {relationships.length > 0 && (
        <Field label={`Relationships (${relationships.length})`}>
          <ul className="space-y-1.5 text-sm">
            {relationships.map((rel, i) => (
              <li key={i} className="text-hestia-text-muted">
                <span className="font-medium text-hestia-text">
                  {RELATIONSHIP_LABELS[rel.type ?? ""] ?? rel.type}
                </span>{" "}
                → {rel.targetText}
              </li>
            ))}
          </ul>
        </Field>
      )}
    </div>
  );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <p className="text-xs font-medium uppercase tracking-wide text-hestia-text-muted">
        {label}
      </p>
      <div className="mt-1.5">{children}</div>
    </div>
  );
}

function Badge({
  children,
  variant,
}: {
  children: ReactNode;
  variant: BadgeVariant;
}) {
  return (
    <span
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-semibold ${BADGE_VARIANTS[variant]}`}
    >
      {children}
    </span>
  );
}
