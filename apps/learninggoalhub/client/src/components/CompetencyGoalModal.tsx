import { useEffect, useState } from "react";
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
/** Maps a title-cased ladder term back to its API enum value ("Extended Abstract" → "EXTENDED_ABSTRACT"). */
const toEnum = (term: string) => term.toUpperCase().replace(/ /g, "_");

type GoalChanges = {
  text?: string;
  bloomLevel?: LearningGoal["bloomLevel"];
  soloLevel?: LearningGoal["soloLevel"];
};

export default function CompetencyGoalModal({
  goal: freshGoal,
  role,
  relationships,
  onClose,
  onUpdate,
  onDelete,
}: {
  goal: LearningGoal | null;
  /** Competency views pass the node's role for the header badge; the list view omits it. */
  role?: CompetencyRole;
  /** The list view passes the goal's grouped relationships to show the relationships tile. */
  relationships?: RelationshipGroup[];
  onClose: () => void;
  /** Enables in-place editing: the goal text via a pencil, Bloom/SOLO by clicking a dot. */
  onUpdate?: (goalId: number, changes: GoalChanges) => void;
  /** Delete action in the header; the modal closes itself before handing the goal over. */
  onDelete?: (goal: LearningGoal) => void;
}) {
  // Edits show immediately: changes overlay the goal until the refetched goal (a new object
  // identity) confirms them. Editing state for the text field lives here too.
  const [pending, setPending] = useState<GoalChanges>({});
  const [draft, setDraft] = useState<string | null>(null);
  useEffect(() => setPending({}), [freshGoal]);
  // The draft only resets when another goal opens — a background refetch must not eat typing.
  useEffect(() => setDraft(null), [freshGoal?.id]);

  useEffect(() => {
    if (!freshGoal) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [freshGoal, onClose]);

  if (!freshGoal) return null;
  const goal: LearningGoal = { ...freshGoal, ...pending };
  const update = (changes: GoalChanges) => {
    setPending((prev) => ({ ...prev, ...changes }));
    onUpdate!(goal.id!, changes);
  };
  const sources = goal.sources ?? [];
  const rels = relationships ?? [];
  const session = goal.hierarchy?.session ?? goal.hierarchy?.exercise;

  const saveDraft = () => {
    const trimmed = (draft ?? "").trim();
    if (trimmed !== "" && trimmed !== goal.text) update({ text: trimmed });
    setDraft(null);
  };

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
          <div className="flex items-center gap-0.5">
            {onDelete && (
              <button
                onClick={() => {
                  onClose();
                  onDelete(goal);
                }}
                title="Delete this goal permanently."
                aria-label="Delete goal"
                className="flex h-8 w-8 items-center justify-center rounded-md text-hestia-text-muted transition hover:bg-hestia-danger hover:text-white"
              >
                <svg
                  viewBox="0 0 20 20"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  className="h-4 w-4"
                >
                  <path d="M4 6h12M8 6V4h4v2M6 6l1 10h6l1-10" />
                </svg>
              </button>
            )}
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
        </div>
        {/* The goal, dressed as the box that was just clicked. */}
        <div
          className="comp-unfold flex flex-col gap-2 rounded-lg border border-hestia-border bg-hestia-surface p-4 shadow-lg"
          style={{ animationFillMode: "backwards" }}
        >
          {(role || onUpdate) && (
            <div className="flex items-start justify-between gap-2">
              {role ? <RoleBadge role={role} /> : <span />}
              {onUpdate && draft == null && (
                <button
                  onClick={() => setDraft(goal.text ?? "")}
                  title="Edit this goal's wording."
                  aria-label="Edit goal text"
                  className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md text-hestia-text-muted transition hover:bg-hestia-primary-muted hover:text-hestia-text"
                >
                  <svg
                    viewBox="0 0 20 20"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="1.8"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    className="h-4 w-4"
                  >
                    <path d="M13.5 3.5l3 3L7 16l-3.7.7L4 13z" />
                  </svg>
                </button>
              )}
            </div>
          )}
          {draft == null ? (
            <p className="text-[0.95rem] font-medium leading-relaxed text-hestia-text">
              {goal.text}
            </p>
          ) : (
            <div className="flex flex-col gap-2">
              <textarea
                value={draft}
                autoFocus
                rows={3}
                onChange={(e) => setDraft(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Escape") {
                    e.stopPropagation();
                    setDraft(null);
                  }
                  if (e.key === "Enter" && !e.shiftKey) {
                    e.preventDefault();
                    saveDraft();
                  }
                }}
                className="w-full resize-y rounded-sm border-[1.5px] border-hestia-border bg-hestia-bg p-2.5 text-sm leading-relaxed text-hestia-text transition focus:border-hestia-primary focus:shadow-[0_0_0_3px_var(--hestia-primary-muted)] focus:outline-none"
              />
              <div className="flex items-center justify-end gap-2">
                <button
                  onClick={() => setDraft(null)}
                  className="rounded-md px-2.5 py-1 text-xs font-medium text-hestia-text-muted transition hover:bg-hestia-text/10 hover:text-hestia-text"
                >
                  Cancel
                </button>
                <button
                  onClick={saveDraft}
                  disabled={(draft ?? "").trim() === ""}
                  className="rounded-md bg-hestia-primary px-2.5 py-1 text-xs font-semibold text-white transition hover:bg-hestia-primary-hover disabled:opacity-50"
                >
                  Save
                </button>
              </div>
            </div>
          )}
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
                onSelect={
                  onUpdate
                    ? (term) =>
                        update({
                          bloomLevel: toEnum(
                            term,
                          ) as LearningGoal["bloomLevel"],
                        })
                    : undefined
                }
              />
            )}
            {goal.soloLevel && (
              <TaxonomyTile
                label="SOLO"
                term={titleCase(goal.soloLevel)}
                desc={SOLO_DESC}
                dotClass="bg-hestia-primary"
                delayMs={70}
                onSelect={
                  onUpdate
                    ? (term) =>
                        update({
                          soloLevel: toEnum(term) as LearningGoal["soloLevel"],
                        })
                    : undefined
                }
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
        {session && (
          <div
            className="comp-unfold rounded-lg border border-hestia-border bg-hestia-surface p-3.5 shadow-lg"
            style={{
              animationDelay: "140ms",
              animationFillMode: "backwards",
            }}
          >
            <span className="text-[0.6rem] font-semibold uppercase tracking-wider text-hestia-text-muted">
              Session
            </span>
            <p className="mt-2 text-sm font-semibold text-hestia-text">
              {session}
            </p>
          </div>
        )}
        {sources.length > 0 && (
          <div
            className="comp-unfold rounded-lg border border-hestia-border bg-hestia-surface p-3.5 shadow-lg"
            style={{
              animationDelay: session ? "175ms" : "140ms",
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
              animationDelay: session ? "210ms" : "175ms",
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
 * With `onSelect` the dots become buttons — star-rating style: hovering (or focusing) a dot
 * previews that level, filling the scale up to it and swapping the name/description below to
 * the would-be level; clicking commits it. A quiet header hint keeps this discoverable.
 */
function TaxonomyTile({
  label,
  term,
  desc,
  dotClass,
  delayMs,
  onSelect,
}: {
  label: string;
  term: string;
  /** The taxonomy's level → description map, in ladder order. */
  desc: Record<string, string>;
  dotClass: string;
  delayMs: number;
  onSelect?: (term: string) => void;
}) {
  const ladder = Object.keys(desc);
  const index = ladder.indexOf(term);
  const [hover, setHover] = useState<number | null>(null);
  const previewing = onSelect != null && hover != null && hover !== index;
  const shownTerm = previewing ? ladder[hover] : term;
  // Preview fill: kept dots stay solid, newly gained dots are half-strength, dropped dots fade.
  const dotStyle = (i: number): string => {
    if (previewing) {
      if (i <= Math.min(index, hover)) return dotClass;
      if (i <= hover) return `${dotClass} opacity-50`;
      if (i <= index) return `${dotClass} opacity-20`;
      return "bg-hestia-text/15";
    }
    return index >= 0 && i <= index ? dotClass : "bg-hestia-text/15";
  };
  return (
    <div
      className="comp-unfold rounded-lg border border-hestia-border bg-hestia-surface p-3.5 shadow-lg"
      style={{ animationDelay: `${delayMs}ms`, animationFillMode: "backwards" }}
    >
      <span className="text-[0.6rem] font-semibold uppercase tracking-wider text-hestia-text-muted">
        {label}
      </span>
      <div
        className="mt-1 flex items-center"
        aria-hidden={onSelect == null}
        onMouseLeave={() => setHover(null)}
      >
        {ladder.map((step, i) => {
          const dot = (
            <span
              className={`h-2.5 w-2.5 rounded-full transition ${dotStyle(i)}`}
            />
          );
          return onSelect ? (
            <button
              key={step}
              type="button"
              title={`Set ${label} to ${step}`}
              aria-label={`Set ${label} to ${step}`}
              disabled={i === index}
              onClick={() => onSelect(step)}
              onMouseEnter={() => setHover(i)}
              onFocus={() => setHover(i)}
              onBlur={() => setHover(null)}
              className="flex h-6 w-6 items-center justify-center rounded-full transition hover:bg-hestia-text/10 [&>span]:hover:scale-125"
            >
              {dot}
            </button>
          ) : (
            <span
              key={step}
              className="flex h-6 w-6 items-center justify-center"
            >
              {dot}
            </span>
          );
        })}
      </div>
      <p className="mt-1 text-sm font-semibold text-hestia-text">
        {shownTerm}
      </p>
      {desc[shownTerm] && (
        <p className="mt-0.5 text-xs leading-snug text-hestia-text-muted">
          {desc[shownTerm]}
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
