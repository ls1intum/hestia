import {
  lazy,
  Suspense,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { useParams } from "react-router-dom";
import { API_PREFIX, type GoalSource, type LearningGoal } from "../api/client.ts";
import Button from "./Button.tsx";
import ErrorBoundary from "./ErrorBoundary.tsx";
import { RoleBadge } from "./CompetencyGoalModal.tsx";
import {
  BLOOM_DESC,
  KIND_DESC,
  SOLO_DESC,
  titleCase,
  type CompetencyRole,
} from "../lib/goals.ts";
// Lazily loaded so the heavy pdf.js bundle only ships once a quote is opened, not on first paint.
const SourcePdfPane = lazy(() => import("./SourcePdfPane.tsx"));

type GoalChanges = {
  text?: string;
  bloomLevel?: LearningGoal["bloomLevel"];
  soloLevel?: LearningGoal["soloLevel"];
};

/** A skill under the capability, with the number it carries in the tree and its knowledge. */
export type CapabilitySkill = {
  goal: LearningGoal;
  number: string;
  knowledge: LearningGoal[];
};

/** Maps a title-cased ladder term back to its API enum value ("Extended Abstract" → "EXTENDED_ABSTRACT"). */
const toEnum = (term: string) => term.toUpperCase().replace(/ /g, "_");

const label = (goal: LearningGoal) => goal.shortLabel ?? goal.text ?? "";

const documentName = (source: GoalSource) => source.displayName ?? source.filename ?? "";

/** "p. 3" or "pp. 3–5" over every page the goals' sources point at; empty when none do. */
function pageSpan(goals: LearningGoal[]): string {
  const pages = goals
    .flatMap((goal) => goal.sources ?? [])
    .map((source) => source.page)
    .filter((page): page is number => page != null);
  if (pages.length === 0) return "";
  const min = Math.min(...pages);
  const max = Math.max(...pages);
  return min === max ? `p. ${min}` : `pp. ${min}–${max}`;
}

const TILE = "rounded-lg border border-hestia-border bg-hestia-surface shadow-lg";
const TILE_LABEL = "text-xs font-semibold uppercase tracking-wider text-hestia-text-muted";

/**
 * Detail view of a capability, in the same floating-tile language as the goal detail modal. A
 * capability is generated over the skills beneath it and has no source of its own, so the view is
 * built around what it contains: a card naming it and where it sits in the tree, one row of tiles
 * for its kind, levels and the documents it was derived from, then its skills. Each skill unfolds
 * into its knowledge, and its arrow opens the skill's own detail. Every skill and knowledge item
 * quotes its source; clicking a quote opens that page in a PDF preview beside the modal, so the
 * list stays in view while the reader steps from quote to quote. The levels stay editable in place
 * — clicking a dot sets Bloom or SOLO — and the wording is edited from the card's pencil.
 */
export default function CapabilityModal({
  goal: freshGoal,
  number,
  topicNumber,
  topicLabel,
  skills,
  onClose,
  onUpdate,
  onDelete,
  onOpenGoal,
}: {
  goal: LearningGoal | null;
  /** The capability's number in the tree, e.g. "4.1". */
  number: string;
  topicNumber?: string;
  topicLabel?: string;
  skills: CapabilitySkill[];
  onClose: () => void;
  onUpdate?: (goalId: number, changes: GoalChanges) => void;
  /** Delete action in the header; the modal closes itself before handing the goal over. */
  onDelete?: (goal: LearningGoal) => void;
  /** Opens a skill or a knowledge goal in the goal detail modal. */
  onOpenGoal: (goal: LearningGoal, role: CompetencyRole) => void;
}) {
  // The modal only renders under /courses/:courseId; the id builds the preview's document URL.
  const { courseId } = useParams();
  const numericCourseId = Number(courseId);

  // Edits show immediately: changes overlay the goal until the refetched goal (a new object
  // identity) confirms them.
  const [pending, setPending] = useState<GoalChanges>({});
  const [draft, setDraft] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Set<number>>(new Set());
  const [openSource, setOpenSource] = useState<GoalSource | null>(null);
  const pdfPaneRef = useRef<HTMLDivElement>(null);
  const sourceTriggerRef = useRef<HTMLElement | null>(null);
  const showSource = (source: GoalSource, trigger: HTMLElement) => {
    sourceTriggerRef.current = trigger;
    setOpenSource(source);
  };

  useEffect(() => setPending({}), [freshGoal]);
  // The draft, the unfolded rows and the preview only reset when another goal opens.
  useEffect(() => {
    setDraft(null);
    setExpanded(new Set());
    setOpenSource(null);
  }, [freshGoal?.id]);

  useEffect(() => {
    if (!freshGoal) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape" || e.defaultPrevented) return;
      if (openSource) {
        e.preventDefault();
        setOpenSource(null);
      } else {
        onClose();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [freshGoal, onClose, openSource]);

  // Focus follows the preview: into the pane on open, back to the quote that asked for it on close.
  useEffect(() => {
    if (openSource) {
      pdfPaneRef.current?.focus();
    } else if (sourceTriggerRef.current?.isConnected) {
      sourceTriggerRef.current.focus();
      sourceTriggerRef.current = null;
    }
  }, [openSource]);

  if (!freshGoal) return null;
  const goal: LearningGoal = { ...freshGoal, ...pending };
  const update = (changes: GoalChanges) => {
    setPending((prev) => ({ ...prev, ...changes }));
    onUpdate!(goal.id!, changes);
  };
  const saveDraft = () => {
    const trimmed = (draft ?? "").trim();
    if (trimmed !== "" && trimmed !== goal.text) update({ text: trimmed });
    setDraft(null);
  };

  const kind =
    goal.creationProvenance === "USER_CREATED"
      ? { label: "Manual", desc: "Added by hand, not derived from source material." }
      : goal.creationProvenance === "WIZARD_AI_SUBTREE"
        ? { label: "AI-inferred", desc: "Generated without a source reference." }
        : goal.kind
          ? { label: titleCase(goal.kind), desc: KIND_DESC[titleCase(goal.kind)] }
          : { label: "—", desc: undefined };
  // Every document a skill or its knowledge quotes, by name, in the order they first appear.
  const documents = [
    ...new Set(
      skills
        .flatMap((skill) => [skill.goal, ...skill.knowledge])
        .flatMap((item) => item.sources ?? [])
        .map(documentName)
        .filter((name) => name !== ""),
    ),
  ];
  const expandable = skills
    .filter((skill) => skill.knowledge.length > 0)
    .map((skill) => skill.goal.id!);
  const allOpen =
    expandable.length > 0 && expandable.every((id) => expanded.has(id));
  const toggle = (id: number) =>
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  return (
    <div
      className="fixed inset-0 z-50"
      role="dialog"
      aria-modal="true"
      aria-label="Skill details"
    >
      <div aria-hidden="true" className="absolute inset-0 bg-hestia-bg/90" />
      <div
        onClick={onClose}
        className="absolute inset-0 flex items-start justify-center overflow-y-auto p-4 sm:p-8"
      >
        <div
          onClick={(e) => e.stopPropagation()}
          className={`comp-unfold flex w-full flex-col gap-3.5 sm:mt-[6vh] ${
            openSource ? "max-w-[92rem]" : "max-w-4xl"
          }`}
        >
          {/* The header spans the whole panel rather than riding on the first column: with the
              preview open the close button must stay where it was. */}
          <div className="flex items-center justify-between gap-2">
            <span className="text-xs font-semibold uppercase tracking-wider text-hestia-text">
              Skill details
            </span>
            <div className="flex items-center gap-0.5">
              {onDelete && (
                <Button
                  variant="ghost"
                  size="icon-sm"
                  onClick={() => {
                    onClose();
                    onDelete(goal);
                  }}
                  title="Delete this skill permanently."
                  aria-label="Delete skill"
                  className="hover:bg-hestia-danger hover:text-hestia-on-danger"
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
                </Button>
              )}
              <Button variant="ghost" size="icon-sm" onClick={onClose} aria-label="Close">
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
              </Button>
            </div>
          </div>

          <div className="flex w-full flex-col gap-3.5 lg:flex-row lg:items-start">
            <div className="flex min-w-0 flex-1 flex-col gap-3.5">
              {/* The capability itself. */}
              <div className={`${TILE} flex flex-col gap-2.5 p-5`}>
                <div className="flex items-center justify-between gap-2">
                  <div className="flex min-w-0 items-center gap-2.5">
                    <RoleBadge role="capability" />
                    {topicLabel && (
                      <span className="truncate text-sm text-hestia-text-muted">
                        <span className="tabular-nums">{topicNumber}.</span> {topicLabel}
                        <span className="px-1.5 text-hestia-border">/</span>
                        <span className="tabular-nums">{number}</span>
                      </span>
                    )}
                  </div>
                  {onUpdate && draft == null && (
                    <button
                      type="button"
                      onClick={() => setDraft(goal.text ?? "")}
                      title="Edit this skill's wording."
                      aria-label="Edit skill text"
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
                {draft == null ? (
                  <h2 className="text-2xl text-hestia-text [text-wrap:balance]">{goal.text}</h2>
                ) : (
                  <div className="flex flex-col gap-2">
                    <textarea
                      value={draft}
                      autoFocus
                      rows={2}
                      onChange={(e) => setDraft(e.target.value)}
                      onKeyDown={(e) => {
                        if (e.key === "Escape") {
                          e.preventDefault();
                          e.stopPropagation();
                          setDraft(null);
                        }
                        if (e.key === "Enter" && !e.shiftKey) {
                          e.preventDefault();
                          saveDraft();
                        }
                      }}
                      className="w-full resize-y rounded-sm border-[1.5px] border-hestia-border bg-hestia-bg p-2.5 text-base leading-relaxed text-hestia-text transition focus:border-hestia-primary focus:shadow-[0_0_0_3px_var(--hestia-primary-muted)] focus:outline-none"
                    />
                    <div className="flex items-center justify-end gap-2">
                      <Button variant="ghost" size="sm" onClick={() => setDraft(null)}>
                        Cancel
                      </Button>
                      <Button
                        size="sm"
                        onClick={saveDraft}
                        disabled={(draft ?? "").trim() === ""}
                      >
                        Save
                      </Button>
                    </div>
                  </div>
                )}
              </div>

              {/* One row of attribute tiles; two per row on narrow screens. */}
              <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
                <AttributeTile label="Kind" value={kind.label} note={kind.desc} />
                <LevelTile
                  name="Bloom"
                  term={goal.bloomLevel ? titleCase(goal.bloomLevel) : null}
                  desc={BLOOM_DESC}
                  dotClass="bg-hestia-accent"
                  onSelect={
                    onUpdate
                      ? (term) =>
                          update({ bloomLevel: toEnum(term) as LearningGoal["bloomLevel"] })
                      : undefined
                  }
                />
                <LevelTile
                  name="SOLO"
                  term={goal.soloLevel ? titleCase(goal.soloLevel) : null}
                  desc={SOLO_DESC}
                  dotClass="bg-hestia-primary"
                  onSelect={
                    onUpdate
                      ? (term) =>
                          update({ soloLevel: toEnum(term) as LearningGoal["soloLevel"] })
                      : undefined
                  }
                />
                <AttributeTile
                  label="Derived from"
                  value={`${skills.length} ${skills.length === 1 ? "sub-skill" : "sub-skills"}`}
                  note={documents.length > 0 ? documents.join(" · ") : undefined}
                  noteTitle={documents.join("\n")}
                />
              </div>

              {/* The skills this capability consists of. */}
              <div className={`${TILE} flex flex-col p-5 pb-2`}>
                <div className="flex items-center justify-between gap-2 border-b border-hestia-border pb-2.5">
                  <span className={TILE_LABEL}>
                    Contains
                    <span className="ml-1.5 font-normal normal-case tracking-normal tabular-nums">
                      {skills.length} {skills.length === 1 ? "sub-skill" : "sub-skills"}
                    </span>
                  </span>
                  {expandable.length > 0 && (
                    <button
                      type="button"
                      onClick={() => setExpanded(allOpen ? new Set() : new Set(expandable))}
                      className="text-xs font-medium text-hestia-text-muted underline underline-offset-2 transition hover:text-hestia-text"
                    >
                      {allOpen ? "Collapse all" : "Expand all"}
                    </button>
                  )}
                </div>
                {skills.length === 0 ? (
                  <p className="py-6 text-center text-sm text-hestia-text-muted">
                    No sub-skills under this skill yet.
                  </p>
                ) : (
                  <ul className="flex flex-col">
                    {skills.map((skill) => (
                      <SkillRow
                        key={skill.goal.id}
                        skill={skill}
                        open={expanded.has(skill.goal.id!)}
                        openSource={openSource}
                        onToggle={() => toggle(skill.goal.id!)}
                        onOpenGoal={onOpenGoal}
                        onShowSource={showSource}
                      />
                    ))}
                  </ul>
                )}
              </div>
            </div>

            {openSource && (
              <div
                ref={pdfPaneRef}
                tabIndex={-1}
                className="w-full outline-none lg:sticky lg:top-0 lg:w-[min(44vw,42rem)] lg:shrink-0"
              >
                <ErrorBoundary
                  resetKey={openSource.documentId}
                  fallback={
                    <div className="flex min-h-[32rem] w-full flex-col items-center justify-center gap-2 rounded-lg border border-hestia-border bg-hestia-surface text-center text-xs text-hestia-text-muted">
                      <p>Could not load the PDF preview.</p>
                      {openSource.contentAvailable && (
                        <a
                          href={`${API_PREFIX}/api/courses/${numericCourseId}/documents/${openSource.documentId}/content${openSource.page ? `#page=${openSource.page}` : ""}`}
                          target="_blank"
                          rel="noreferrer"
                          className="font-medium text-hestia-primary underline underline-offset-2"
                        >
                          Open PDF in a new tab
                        </a>
                      )}
                    </div>
                  }
                >
                  <Suspense
                    fallback={
                      <div className="flex min-h-[32rem] w-full items-center justify-center rounded-lg border border-hestia-border bg-hestia-surface text-xs text-hestia-text-muted">
                        Loading preview…
                      </div>
                    }
                  >
                    <SourcePdfPane
                      courseId={numericCourseId}
                      source={openSource}
                      onClose={() => setOpenSource(null)}
                    />
                  </Suspense>
                </ErrorBoundary>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

/**
 * One attribute as a small floating tile: its label (with an optional control beside it), the
 * value, and a short explanation underneath. Every tile shares these three lines, so a row of
 * them reads level.
 */
function AttributeTile({
  label: tileLabel,
  value,
  valueMuted = false,
  note,
  noteTitle,
  aside,
  onMouseLeave,
}: {
  label: string;
  value: string;
  valueMuted?: boolean;
  note?: string;
  noteTitle?: string;
  /** Sits on the label line, right-aligned — the level tiles put their dot scale here. */
  aside?: ReactNode;
  onMouseLeave?: () => void;
}) {
  return (
    <div onMouseLeave={onMouseLeave} className={`${TILE} flex min-w-0 flex-col p-3.5`}>
      <div className="flex min-h-6 items-center justify-between gap-2">
        <span className={TILE_LABEL}>{tileLabel}</span>
        {aside}
      </div>
      <p
        className={`mt-1 truncate text-sm font-semibold ${
          valueMuted ? "text-hestia-text-muted" : "text-hestia-text"
        }`}
      >
        {value}
      </p>
      {note && (
        <p
          title={noteTitle}
          className="mt-0.5 line-clamp-2 text-xs leading-snug text-hestia-text-muted"
        >
          {note}
        </p>
      )}
    </div>
  );
}

/**
 * A taxonomy level as an attribute tile, with its dot scale on the label line. With `onSelect`
 * each dot sets that level, and hovering one previews its name and explanation.
 */
function LevelTile({
  name,
  term,
  desc,
  dotClass,
  onSelect,
}: {
  name: string;
  term: string | null;
  /** The taxonomy's level → description map, in ladder order. */
  desc: Record<string, string>;
  dotClass: string;
  onSelect?: (term: string) => void;
}) {
  const ladder = Object.keys(desc);
  const index = term == null ? -1 : ladder.indexOf(term);
  const [hover, setHover] = useState<number | null>(null);
  const shownIndex = onSelect != null && hover != null ? hover : index;
  const shownTerm = shownIndex >= 0 ? ladder[shownIndex] : null;
  const dots = (
    <span className="flex items-center" aria-hidden={onSelect == null}>
      {ladder.map((step, i) => {
        const dot = (
          <span
            className={`h-2 w-2 rounded-full transition ${
              i <= shownIndex
                ? `${dotClass} ${hover != null && i > index ? "opacity-50" : ""}`
                : "bg-hestia-text/15"
            }`}
          />
        );
        return onSelect ? (
          <button
            key={step}
            type="button"
            title={`Set ${name} to ${step}`}
            aria-label={`Set ${name} to ${step}`}
            disabled={i === index}
            onClick={() => onSelect(step)}
            onMouseEnter={() => setHover(i)}
            onFocus={() => setHover(i)}
            onBlur={() => setHover(null)}
            className="flex h-5 w-3.5 items-center justify-center [&>span]:hover:scale-125"
          >
            {dot}
          </button>
        ) : (
          <span key={step} className="flex h-5 w-3.5 items-center justify-center">
            {dot}
          </span>
        );
      })}
    </span>
  );
  return (
    <AttributeTile
      label={name}
      aside={dots}
      value={shownTerm ?? "Not set"}
      valueMuted={shownTerm == null}
      note={
        shownTerm
          ? desc[shownTerm]
          : onSelect
            ? "Pick a dot to set the level."
            : "Not classified."
      }
      onMouseLeave={() => setHover(null)}
    />
  );
}

/**
 * A goal's source quote in small type. Clicking it opens the quoted page in the preview; a
 * figure-derived goal shows its AI description instead, and a goal without a source says so.
 */
function Quote({
  source,
  active,
  onShow,
}: {
  source: GoalSource | undefined;
  active: boolean;
  onShow: (source: GoalSource, trigger: HTMLElement) => void;
}) {
  if (!source || (!source.snippet && source.evidenceKind !== "FIGURE")) {
    return <p className="text-xs italic text-hestia-text-muted">No source passage.</p>;
  }
  const figure = source.evidenceKind === "FIGURE";
  return (
    <button
      type="button"
      onClick={(e) => onShow(source, e.currentTarget)}
      title={`Open ${documentName(source)}${source.page ? `, page ${source.page}` : ""}`}
      className={`w-full rounded-r-md border-l-2 py-0.5 pl-2.5 pr-1 text-left text-xs italic leading-relaxed transition hover:bg-[color-mix(in_srgb,var(--hestia-primary)_7%,transparent)] hover:text-hestia-text ${
        active
          ? "border-hestia-primary bg-[color-mix(in_srgb,var(--hestia-primary)_9%,transparent)] text-hestia-text"
          : "border-hestia-border text-hestia-text-muted"
      }`}
    >
      <span className="line-clamp-2">
        {figure
          ? `Figure: ${source.figureDescription ?? "described by AI"}`
          : `“${source.snippet}”`}
      </span>
    </button>
  );
}

/** A skill in the Contains list: unfolds into its knowledge, and its arrow opens the skill. */
function SkillRow({
  skill,
  open,
  openSource,
  onToggle,
  onOpenGoal,
  onShowSource,
}: {
  skill: CapabilitySkill;
  open: boolean;
  openSource: GoalSource | null;
  onToggle: () => void;
  onOpenGoal: (goal: LearningGoal, role: CompetencyRole) => void;
  onShowSource: (source: GoalSource, trigger: HTMLElement) => void;
}) {
  const { goal, number, knowledge } = skill;
  const canOpen = knowledge.length > 0;
  const source = goal.sources?.[0];
  const pages = pageSpan(goal.sources?.length ? [goal] : knowledge);
  const meta = [
    `${knowledge.length} knowledge ${knowledge.length === 1 ? "item" : "items"}`,
    goal.bloomLevel ? titleCase(goal.bloomLevel) : null,
    pages || null,
  ].filter((part): part is string => part != null);
  return (
    <li className="border-b border-hestia-border/60 last:border-b-0">
      <div className="flex items-start gap-2.5 py-3">
        <button
          type="button"
          onClick={onToggle}
          disabled={!canOpen}
          aria-expanded={canOpen ? open : undefined}
          aria-label={
            canOpen ? `${open ? "Hide" : "Show"} knowledge for ${label(goal)}` : "No knowledge"
          }
          className={`mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-sm text-hestia-text-muted transition hover:bg-hestia-primary-muted hover:text-hestia-text ${
            canOpen ? "" : "invisible"
          }`}
        >
          <svg
            viewBox="0 0 20 20"
            fill="none"
            stroke="currentColor"
            strokeWidth="2.5"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
            className={`h-3 w-3 transition-transform ${open ? "rotate-90" : ""}`}
          >
            <path d="M7 5l6 5-6 5" />
          </svg>
        </button>
        <div className="flex min-w-0 flex-1 flex-col gap-1.5">
          <button
            type="button"
            onClick={onToggle}
            disabled={!canOpen}
            tabIndex={-1}
            className="flex flex-col gap-0.5 text-left disabled:cursor-default"
          >
            <span className="text-sm font-medium leading-relaxed text-hestia-text">
              <span className="mr-1.5 tabular-nums text-hestia-text-muted">{number}</span>
              {goal.text ?? label(goal)}
            </span>
            <span className="flex flex-wrap items-center text-xs tabular-nums text-hestia-text-muted">
              {meta.map((part, i) => (
                <span
                  key={part}
                  className={i > 0 ? "ml-2 border-l border-hestia-border pl-2" : ""}
                >
                  {part}
                </span>
              ))}
            </span>
          </button>
          <Quote
            source={source}
            active={source != null && source === openSource}
            onShow={onShowSource}
          />
        </div>
        <Button
          variant="ghost"
          size="icon-sm"
          onClick={() => onOpenGoal(goal, "skill")}
          title="Open sub-skill details"
          aria-label={`Open details for ${label(goal)}`}
        >
          <span aria-hidden="true">↗</span>
        </Button>
      </div>
      {open && (
        <ul className="comp-unfold mb-3 ml-[1.875rem] mr-10 flex flex-col gap-1 border-l-2 border-hestia-border/60">
          {knowledge.map((item) => {
            const itemSource = item.sources?.[0];
            const page = pageSpan([item]);
            return (
              <li key={item.id} className="flex flex-col gap-1 py-1.5 pl-3">
                <button
                  type="button"
                  onClick={() => onOpenGoal(item, "knowledge")}
                  title="Open knowledge details"
                  className="flex w-full items-baseline justify-between gap-3 text-left text-sm text-hestia-text transition hover:text-hestia-primary"
                >
                  <span className="min-w-0">{item.text ?? label(item)}</span>
                  {page && (
                    <span className="shrink-0 text-xs tabular-nums text-hestia-text-muted">
                      {page}
                    </span>
                  )}
                </button>
                <Quote
                  source={itemSource}
                  active={itemSource != null && itemSource === openSource}
                  onShow={onShowSource}
                />
              </li>
            );
          })}
        </ul>
      )}
    </li>
  );
}
