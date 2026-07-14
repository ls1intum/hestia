import { useEffect } from "react";
import type { LearningGoal } from "../api/client.ts";
import {
  BLOOM_DESC,
  COMPETENCY_ROLE_META,
  KIND_DESC,
  SOLO_DESC,
  titleCase,
  type CompetencyRole,
  type RelationshipGroup,
} from "../lib/goals.ts";

/**
 * Goal detail overlay shared by the map, tree and list views, styled like the sibling-picker: no
 * panel chrome, the pieces float over the blurred backdrop. Top row names the dialog and carries
 * the ✕; the goal itself appears as a box (an optional role badge for the competency views, then
 * its text); Bloom and SOLO follow as two tiles with a filled dot scale, level name and one-line
 * explanation; a kind tile explains explicit vs implicit; a source tile quotes the exact
 * snippet(s) each source contributed; and — for the list view — a relationships tile lists the
 * goal's links to other goals, grouped by type. Pass `role` for the competency views (drives the
 * badge) and `relationships` to show the relationships tile.
 */
export default function CompetencyGoalModal({
  goal,
  role,
  relationships,
  onClose,
}: {
  goal: LearningGoal | null;
  /** Competency views pass the node's role for the header badge; the list view omits it. */
  role?: CompetencyRole;
  /** The list view passes the goal's grouped relationships to show the relationships tile. */
  relationships?: RelationshipGroup[];
  onClose: () => void;
}) {
  useEffect(() => {
    if (!goal) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [goal, onClose]);

  if (!goal) return null;
  const sources = goal.sources ?? [];
  const rels = relationships ?? [];

  return (
    <div
      onClick={onClose}
      className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-hestia-bg/75 p-4 backdrop-blur-[2px] sm:p-8"
      role="dialog"
      aria-modal="true"
      aria-label="Goal details"
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="flex w-full max-w-lg flex-col gap-3.5 sm:mt-[6vh]"
      >
        <div className="flex items-center justify-between">
          <span className="text-[0.6rem] font-semibold uppercase tracking-wider text-hestia-text">
            Goal details
          </span>
          <button
            onClick={onClose}
            aria-label="Close"
            className="flex h-8 w-8 items-center justify-center rounded-md text-hestia-text-muted transition hover:bg-hestia-text/10 hover:text-hestia-text"
          >
            <svg
              viewBox="0 0 20 20"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              className="h-4 w-4"
            >
              <path d="M5 5l10 10M15 5L5 15" />
            </svg>
          </button>
        </div>
        {/* The goal, dressed as the box that was just clicked. */}
        <div
          className="comp-unfold flex flex-col gap-2 rounded-lg border border-hestia-border bg-hestia-surface p-4 shadow-lg"
          style={{ animationFillMode: "backwards" }}
        >
          {role && (
            <div>
              <RoleBadge role={role} />
            </div>
          )}
          <p className="text-[0.95rem] font-medium leading-relaxed text-hestia-text">
            {goal.text}
          </p>
        </div>
        {(goal.bloomLevel || goal.soloLevel) && (
          <div className="grid gap-3 sm:grid-cols-2">
            {goal.bloomLevel && (
              <TaxonomyTile
                label="Bloom"
                term={titleCase(goal.bloomLevel)}
                desc={BLOOM_DESC}
                dotClass="bg-hestia-accent"
                delayMs={35}
              />
            )}
            {goal.soloLevel && (
              <TaxonomyTile
                label="SOLO"
                term={titleCase(goal.soloLevel)}
                desc={SOLO_DESC}
                dotClass="bg-hestia-primary"
                delayMs={70}
              />
            )}
          </div>
        )}
        {goal.kind && (
          <div
            className="comp-unfold rounded-lg border border-hestia-border bg-hestia-surface p-3.5 shadow-lg"
            style={{
              animationDelay: "105ms",
              animationFillMode: "backwards",
            }}
          >
            <span className="text-[0.6rem] font-semibold uppercase tracking-wider text-hestia-text-muted">
              Kind
            </span>
            <p className="mt-2 text-sm font-semibold text-hestia-text">
              {titleCase(goal.kind)}
            </p>
            {KIND_DESC[titleCase(goal.kind)] && (
              <p className="mt-0.5 text-xs leading-snug text-hestia-text-muted">
                {KIND_DESC[titleCase(goal.kind)]}
              </p>
            )}
          </div>
        )}
        {sources.length > 0 && (
          <div
            className="comp-unfold rounded-lg border border-hestia-border bg-hestia-surface p-3.5 shadow-lg"
            style={{
              animationDelay: "140ms",
              animationFillMode: "backwards",
            }}
          >
            <span className="text-[0.6rem] font-semibold uppercase tracking-wider text-hestia-text-muted">
              Source
            </span>
            <ul className="mt-2 space-y-2.5">
              {sources.map((source, i) => (
                <li key={i} className="text-xs text-hestia-text">
                  {source.filename && (
                    <p className="truncate font-medium">{source.filename}</p>
                  )}
                  {source.snippet && (
                    <p className="mt-1 line-clamp-3 border-l-2 border-hestia-border pl-2.5 italic leading-relaxed text-hestia-text-muted">
                      “{source.snippet}”
                    </p>
                  )}
                </li>
              ))}
            </ul>
          </div>
        )}
        {rels.length > 0 && (
          <div
            className="comp-unfold rounded-lg border border-hestia-border bg-hestia-surface p-3.5 shadow-lg"
            style={{
              animationDelay: "175ms",
              animationFillMode: "backwards",
            }}
          >
            <span className="text-[0.6rem] font-semibold uppercase tracking-wider text-hestia-text-muted">
              Relationships
            </span>
            <ul className="mt-2 space-y-2.5">
              {rels.map((rel) => (
                <li key={rel.type}>
                  <p className="text-sm font-semibold text-hestia-text">
                    {rel.phrase}{" "}
                    <span className="tabular-nums">{rel.count}</span> goal
                    {rel.count === 1 ? "" : "s"}
                  </p>
                  {rel.targets.length > 0 && (
                    <ul className="mt-1 space-y-1 border-l-2 border-hestia-border pl-2.5">
                      {rel.targets.map((target, i) => (
                        <li
                          key={i}
                          className="text-xs leading-relaxed text-hestia-text-muted"
                        >
                          {target}
                        </li>
                      ))}
                    </ul>
                  )}
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </div>
  );
}

/**
 * One taxonomy as a floating tile: a dot scale filled up to the goal's level (the taxonomy's
 * ladder is the insertion order of its description map), then the level name and explanation.
 */
function TaxonomyTile({
  label,
  term,
  desc,
  dotClass,
  delayMs,
}: {
  label: string;
  term: string;
  /** The taxonomy's level → description map, in ladder order. */
  desc: Record<string, string>;
  dotClass: string;
  delayMs: number;
}) {
  const ladder = Object.keys(desc);
  const index = ladder.indexOf(term);
  return (
    <div
      className="comp-unfold rounded-lg border border-hestia-border bg-hestia-surface p-3.5 shadow-lg"
      style={{ animationDelay: `${delayMs}ms`, animationFillMode: "backwards" }}
    >
      <span className="text-[0.6rem] font-semibold uppercase tracking-wider text-hestia-text-muted">
        {label}
      </span>
      <div className="mt-2 flex items-center gap-1.5" aria-hidden="true">
        {ladder.map((step, i) => (
          <span
            key={step}
            className={`h-2.5 w-2.5 rounded-full ${
              index >= 0 && i <= index ? dotClass : "bg-hestia-text/15"
            }`}
          />
        ))}
      </div>
      <p className="mt-2 text-sm font-semibold text-hestia-text">{term}</p>
      {desc[term] && (
        <p className="mt-0.5 text-xs leading-snug text-hestia-text-muted">
          {desc[term]}
        </p>
      )}
    </div>
  );
}

/** Pill naming a node's role in the competency tree, tinted in the role's colour. Shared by the
 * map's boxes and this modal. */
export function RoleBadge({ role }: { role: CompetencyRole }) {
  const meta = COMPETENCY_ROLE_META[role];
  const isGap = role === "gap";
  return (
    <span
      className="inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[0.6rem] font-semibold uppercase tracking-wide"
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
