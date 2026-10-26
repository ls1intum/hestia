import type { CSSProperties, ReactNode } from "react";
import type { ExamTask } from "../../api/client.ts";
import InfoTooltip from "../InfoTooltip.tsx";
import {
  TREE_ROLE_META,
  displayedGoalLabel,
  tierNoun,
  type TreeRole,
  type CoverageCounts,
} from "../../lib/goals.ts";
import { isGrouping, tierRailColor, kindLabel, type Row } from "./model.ts";
import { GeneratedIcon } from "./icons.tsx";

/**
 * Says which topics no exercise practises. This is a statement about the material, not a fault in
 * it — a lecture-only topic is perfectly normal — so it reads as an informational note rather than
 * a warning. Every other topic leaves the cell empty.
 */
export function TopicCoverage({ coverage }: { coverage: CoverageCounts }) {
  const known = coverage.total - coverage.unknown;
  if (known === 0 || coverage.practised > 0) return null;
  return (
    <Chip tone="neutral" title={`None of its ${known} sub-skills comes from an exercise`}>
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
        <circle cx="10" cy="10" r="7.25" />
        <path d="M10 9.25v4.25M10 6.6v.01" />
      </svg>
      No exercises
    </Chip>
  );
}

/** A preview dot in its tier's rail colour, so a collapsed branch previews in the colours it unfolds to. */
export function TierDot({ role }: { role: TreeRole }) {
  return (
    <span
      aria-hidden="true"
      className="h-1.5 w-1.5 shrink-0 rounded-full"
      style={{ backgroundColor: tierRailColor(role) }}
    />
  );
}

export function KindChip({ row }: { row: Row }) {
  const label = kindLabel(row);
  return label ? <Chip tone="neutral">{label}</Chip> : null;
}

/**
 * A goal's own source as a control that opens it in the PDF panel: the document and page, with the
 * session beneath, and the quoted passage on hover. A generated topic or skill has no source, so it
 * says so instead, a skill naming the sub-skills its name sums up. Renders nothing otherwise.
 */
export function SourceChip({
  row,
  members,
  inline = false,
  disabled = false,
  open,
  onOpen,
}: {
  row: Row;
  /** A skill's sub-skills, which its generated name sums up. */
  members?: Row[];
  /** Keeps the generated chip on one line, where a diagram box has the width for it. */
  inline?: boolean;
  disabled?: boolean;
  /** This goal's source is the one shown in the PDF panel. */
  open: boolean;
  onOpen: (trigger: HTMLElement) => void;
}) {
  if (row.goal.examTask) return <ExamTaskChip task={row.goal.examTask} />;
  const source = row.goal.sources?.[0];
  if (!source) {
    return isGrouping(row.role) && row.goal.creationProvenance !== "USER_CREATED" ? (
      <GeneratedChip members={members} inline={inline} />
    ) : null;
  }
  // The document names the source; the session is where in the course it sits. A session named
  // exactly like its file (one lecture per PDF) would only repeat the label, so it is left out.
  const documentLabel = source.displayName || source.filename || "Source document";
  const sessionLabel = row.session && row.session !== documentLabel ? row.session : null;
  // A figure source rests on the AI's description of a slide image rather than on quoted text.
  const figure = source.evidenceKind === "FIGURE";
  const quote = source.snippet?.trim();
  return (
    <InfoTooltip
      className="flex max-w-full min-w-0"
      content={
        <div className="flex flex-col gap-2.5 text-xs text-hestia-text">
          <p className="text-[10px] font-semibold uppercase tracking-wide text-hestia-accent">
            {figure ? "From a figure in your course material" : "From your course material"}
          </p>
          {quote && (
            <p className="line-clamp-6 border-l-[3px] border-[color-mix(in_srgb,var(--hestia-accent)_25%,transparent)] pl-2.5 leading-snug">
              “{quote}”
            </p>
          )}
          {figure && source.figureDescription && (
            <p className="line-clamp-6 border-l-[3px] border-[color-mix(in_srgb,var(--hestia-accent)_25%,transparent)] pl-2.5 leading-snug">
              <span className="text-hestia-text-muted">AI description: </span>
              {source.figureDescription}
            </p>
          )}
          <div className="flex text-hestia-text-muted">
            <span className="min-w-0">
              <span className="flex min-w-0">
                <span className="truncate">{documentLabel}</span>
                {source.page != null && (
                  <span className="shrink-0 whitespace-pre tabular-nums"> · p. {source.page}</span>
                )}
              </span>
              {sessionLabel && <span className="block truncate">Session: {sessionLabel}</span>}
            </span>
          </div>
        </div>
      }
    >
      <button
        type="button"
        disabled={disabled}
        aria-pressed={open}
        aria-label={`Source: ${documentLabel}${source.page != null ? `, page ${source.page}` : ""}`}
        onClick={(e) => {
          e.stopPropagation();
          onOpen(e.currentTarget);
        }}
        // The row itself opens the goal on click and on Enter/Space, so this control has to
        // keep both to itself.
        onKeyDown={(e) => e.stopPropagation()}
        className={`flex max-w-full min-w-0 flex-col items-start rounded-md border px-1.5 py-0.5 text-left transition ${
          open
            ? "border-[color-mix(in_srgb,var(--hestia-primary)_40%,transparent)] bg-hestia-primary-muted text-hestia-primary"
            : "border-hestia-border/60 bg-hestia-text/3 hover:border-[color-mix(in_srgb,var(--hestia-primary)_40%,transparent)] hover:text-hestia-text"
        }`}
      >
        <span className="flex max-w-full min-w-0 items-center gap-1">
          {figure && (
            <svg
              viewBox="0 0 20 20"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.6"
              strokeLinecap="round"
              strokeLinejoin="round"
              role="img"
              aria-label="Figure source"
              className="h-3.5 w-3.5 shrink-0"
            >
              <rect x="3" y="4" width="14" height="12" rx="2" />
              <path d="M3 13l4-4 3 3 2-2 5 5" />
              <circle cx="13" cy="8" r="1.2" />
            </svg>
          )}
          <span className="min-w-0 truncate">{documentLabel}</span>
          {source.page != null && (
            <span className="shrink-0 tabular-nums">· p. {source.page}</span>
          )}
        </span>
        {sessionLabel && (
          <span className="max-w-full truncate opacity-75">
            Session: {sessionLabel}
          </span>
        )}
      </button>
    </InfoTooltip>
  );
}

/**
 * Where an exam goal came from: the exam task it was derived from, read on hover. There is no page
 * to open, so unlike a document source it is not a button. The consumer sends no exam name, so the
 * exam is told apart by the day it was submitted.
 */
export function ExamTaskChip({ task }: { task: ExamTask }) {
  const exam = task.submittedAt
    ? `Exam of ${new Date(task.submittedAt).toLocaleDateString(undefined, {
        year: "numeric",
        month: "short",
        day: "numeric",
      })}`
    : "Exam";
  const taskLabel = task.taskNumber != null ? `Task ${task.taskNumber}` : "Task";
  const context = task.context?.trim();
  return (
    <InfoTooltip
      className="flex max-w-full min-w-0"
      content={
        <div className="flex flex-col gap-2.5 text-xs text-hestia-text">
          <p className="text-[10px] font-semibold uppercase tracking-wide text-hestia-accent">
            From an exam task
          </p>
          {task.text && (
            <p className="line-clamp-6 border-l-[3px] border-[color-mix(in_srgb,var(--hestia-accent)_25%,transparent)] pl-2.5 leading-snug">
              “{task.text.trim()}”
            </p>
          )}
          {context && (
            <p className="line-clamp-4 leading-snug text-hestia-text-muted">
              <span className="font-medium">Context: </span>
              {context}
            </p>
          )}
          <p className="text-hestia-text-muted">
            {exam} · {taskLabel}
            {task.taskType ? ` · ${task.taskType}` : ""}
          </p>
        </div>
      }
    >
      <span
        tabIndex={0}
        aria-label={`Source: ${exam}, ${taskLabel}`}
        // Reading the task is not a click on the row it sits in.
        onClick={(e) => e.stopPropagation()}
        onKeyDown={(e) => e.stopPropagation()}
        className="flex max-w-full min-w-0 flex-col items-start rounded-md border border-hestia-border/60 bg-hestia-text/3 px-1.5 py-0.5 text-left outline-none"
      >
        <span className="max-w-full truncate">{exam}</span>
        <span className="max-w-full truncate opacity-75">{taskLabel}</span>
      </span>
    </InfoTooltip>
  );
}

// The hover card doesn't scroll, so a long list is cut short rather than clipped mid-item.
export const GENERATED_FROM_SHOWN = 6;

/**
 * Marks a topic or skill as generated rather than read from the material. A skill also counts the
 * sub-skills its name sums up and lists them on hover, each with where it was found, which is the
 * way back to the slides; a topic has nothing so direct to point at, so it stays a plain label.
 */
export function GeneratedChip({ members, inline = false }: { members?: Row[]; inline?: boolean }) {
  const chip = (
    <span
      className={`flex max-w-full min-w-0 rounded-md border border-dashed border-hestia-text-muted/60 px-1.5 py-0.5 text-left ${
        inline ? "items-center gap-1" : "flex-col items-start"
      }`}
    >
      <span className="flex shrink-0 items-center gap-1">
        <GeneratedIcon />
        Generated
      </span>
      {members != null && members.length > 0 && (
        <span className="min-w-0 truncate opacity-75">
          from {members.length} {tierNoun("subSkill", members.length)}
        </span>
      )}
    </span>
  );
  if (members == null || members.length === 0) return chip;
  return (
    <InfoTooltip
      className="flex max-w-full min-w-0"
      content={
        <div className="flex flex-col gap-2 text-xs text-hestia-text">
          <p className="text-[10px] font-semibold uppercase tracking-wide text-hestia-text-muted">
            Generated from
          </p>
          <ul className="flex flex-col gap-1">
            {members.slice(0, GENERATED_FROM_SHOWN).map((member) => {
              const source = member.goal.sources?.[0];
              const where = source
                ? `${source.displayName || source.filename || ""}${source.page != null ? ` · p. ${source.page}` : ""}`
                : member.goal.creationProvenance === "USER_CREATED"
                  ? "Manual"
                  : member.goal.creationProvenance === "WIZARD_AI_SUBTREE"
                    ? "AI-inferred"
                    : "";
              return (
                <li key={member.id} className="flex min-w-0 flex-col">
                  <span className="leading-snug">{displayedGoalLabel(member.goal)}</span>
                  {where && <span className="truncate text-hestia-text-muted">{where}</span>}
                </li>
              );
            })}
          </ul>
          {members.length > GENERATED_FROM_SHOWN && (
            <p className="text-hestia-text-muted">
              +{members.length - GENERATED_FROM_SHOWN} more
            </p>
          )}
        </div>
      }
    >
      <span
        tabIndex={0}
        className="flex max-w-full min-w-0 rounded-md outline-none focus-visible:ring-2 focus-visible:ring-hestia-primary"
      >
        {chip}
      </span>
    </InfoTooltip>
  );
}

/**
 * The table's chip styles. Colour carries meaning only twice: the tier chips take the hue of their
 * rail (topic in primary, skill and sub-skill in accent), and a problem takes warning. Everything
 * else is a quiet neutral outline. The tints mix with the surface rather than going transparent so
 * the label keeps its contrast on a zebra row too.
 */
export const CHIP_TONES = {
  topic: {
    color: "var(--hestia-primary)",
    backgroundColor: "color-mix(in srgb, var(--hestia-primary) 14%, var(--hestia-surface))",
    borderColor: "transparent",
  },
  skill: {
    color: "var(--hestia-accent)",
    backgroundColor: "color-mix(in srgb, var(--hestia-accent) 15%, var(--hestia-surface))",
    borderColor: "transparent",
  },
  subSkill: {
    color: "var(--hestia-accent)",
    backgroundColor: "transparent",
    borderColor: "color-mix(in srgb, var(--hestia-accent) 50%, var(--hestia-surface))",
  },
  neutral: {
    color: "var(--hestia-text-muted)",
    backgroundColor: "transparent",
    borderColor: "var(--hestia-border)",
  },
  // Warning is too light to be text, so the label stays in the text colour on the warning tint.
  warning: {
    color: "var(--hestia-text)",
    backgroundColor: "color-mix(in srgb, var(--hestia-warning) 22%, var(--hestia-surface))",
    borderColor: "transparent",
  },
} satisfies Record<string, CSSProperties>;

export type ChipTone = keyof typeof CHIP_TONES;

export function Chip({
  tone,
  title,
  children,
}: {
  tone: ChipTone;
  title?: string;
  children: ReactNode;
}) {
  return (
    <span
      title={title}
      className="inline-flex h-[22px] items-center gap-1 whitespace-nowrap rounded-md border px-2 text-xs font-medium"
      style={CHIP_TONES[tone]}
    >
      {children}
    </span>
  );
}

/** A goal's tier as a chip in its rail's hue; knowledge, the plain leaf tier, is plain text. */
export function TierChip({ role }: { role: TreeRole }) {
  const label = TREE_ROLE_META[role].label;
  if (role === "knowledge") return <span className="text-xs text-hestia-text-muted">{label}</span>;
  return <Chip tone={role}>{label}</Chip>;
}

/**
 * Where a level sits on its taxonomy's ladder, as a row of squares. Neutral on purpose: a higher
 * Bloom or SOLO level is not better, so a hue ramp would suggest a judgement the scale doesn't make.
 */
export function LevelPips({ index, steps }: { index: number; steps: number }) {
  return (
    <span className="flex shrink-0 gap-0.5" aria-hidden="true">
      {Array.from({ length: steps }, (_, i) => (
        <span
          key={i}
          className="h-[5px] w-[5px] rounded-[1px]"
          style={{
            backgroundColor:
              i <= index
                ? "color-mix(in srgb, var(--hestia-text) 75%, var(--hestia-surface))"
                : "var(--hestia-border)",
          }}
        />
      ))}
    </span>
  );
}
