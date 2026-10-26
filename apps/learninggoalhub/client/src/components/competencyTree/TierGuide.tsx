import type { ReactNode } from "react";
import AnchoredPopover from "../AnchoredPopover.tsx";
import Button from "../Button.tsx";
import type { CompetencyRole } from "../../lib/goals.ts";
import { TierChip } from "./Chips.tsx";
import { InfoIcon, DocumentIcon, GeneratedIcon } from "./icons.tsx";

/**
 * The toolbar's explanation of the four tiers: which are generated to organise the course and which
 * are read from its material, and what the source and coverage cells say about a row.
 */
export function TierGuideMenu({
  open,
  flash,
  onToggleOpen,
  onClose,
}: {
  open: boolean;
  /** Draws the eye to the button right after the first-visit strip folded into it. */
  flash: boolean;
  onToggleOpen: () => void;
  onClose: () => void;
}) {
  return (
    <div className="relative">
      <Button
        variant="neutral"
        className={`h-9 ${open ? "border-hestia-primary" : ""} ${
          flash ? "border-hestia-primary shadow-[0_0_0_3px_var(--hestia-primary-muted)]" : ""
        }`}
        aria-expanded={open}
        aria-haspopup="dialog"
        onClick={onToggleOpen}
      >
        <InfoIcon />
        How to read this
      </Button>
      {open && (
        <AnchoredPopover
          alignRight
          onClose={onClose}
          className="flex w-96 flex-col overflow-y-auto rounded-lg border border-hestia-border bg-hestia-surface p-4 shadow-lg"
        >
          <div role="dialog" aria-label="How to read this table" className="flex flex-col gap-3 text-sm text-hestia-text">
            <p className="text-xs leading-snug">
              Each topic breaks down into skills, sub-skills and knowledge, from broad to concrete.
            </p>
            <TierGuideGroup
              icon={<GeneratedIcon />}
              heading="Generated to organise the course"
              tiers={[
                ["topic", "A theme of the course. It has no quote and no Bloom or SOLO level."],
                [
                  "skill",
                  "Names what a group of sub-skills adds up to, at least at their level. Hover its source to see which.",
                ],
              ]}
            />
            <TierGuideGroup
              icon={<DocumentIcon />}
              heading="From your course material"
              tiers={[
                ["subSkill", "Something students should be able to do, with the passage it was read from."],
                ["knowledge", "A fact, term or concept a sub-skill relies on, also with its passage."],
              ]}
            />
            <ul className="flex flex-col gap-1.5 border-t border-hestia-border pt-3 text-xs leading-snug text-hestia-text-muted">
              <li>
                <b className="font-semibold text-hestia-text">Source:</b> hover it to read the quote,
                click it to open the page beside the table. An exam goal shows the exam task instead.
              </li>
              <li>
                <b className="font-semibold text-hestia-text">Coverage:</b> whether a sub-skill was found
                in a lecture or an exercise, or derived from an exam task.
              </li>
              <li>
                <b className="font-semibold text-hestia-text">Kind:</b> Manual marks goals you added,
                AI-inferred the ones the AI wrote beneath a topic you added. Neither has a source.
              </li>
            </ul>
          </div>
        </AnchoredPopover>
      )}
    </div>
  );
}

export function TierGuideGroup({
  icon,
  heading,
  tiers,
}: {
  icon: ReactNode;
  heading: string;
  tiers: [CompetencyRole, string][];
}) {
  return (
    <div className="overflow-hidden rounded-md border border-hestia-border">
      <p className="flex items-center gap-1.5 bg-hestia-text/5 px-3 py-1.5 text-xs font-semibold text-hestia-text-muted">
        {icon}
        {heading}
      </p>
      {tiers.map(([role, text]) => (
        <div key={role} className="grid grid-cols-[5.5rem_1fr] items-start gap-2 border-t border-hestia-border px-3 py-2">
          <span>
            <TierChip role={role} />
          </span>
          <span className="text-xs leading-snug">{text}</span>
        </div>
      ))}
    </div>
  );
}

/** First-visit hint above the table; both of its actions fold it into the toolbar button. */
export function TierGuideStrip({ onOpenGuide, onDismiss }: { onOpenGuide: () => void; onDismiss: () => void }) {
  return (
    <div className="flex items-start gap-2.5 rounded-lg border border-[color-mix(in_srgb,var(--hestia-primary)_35%,transparent)] bg-hestia-primary-muted px-3 py-2.5 text-sm text-hestia-text">
      <span className="mt-0.5 text-hestia-primary">
        <InfoIcon />
      </span>
      <p className="min-w-0 flex-1 leading-snug">
        <b className="font-semibold">Topics</b> and <b className="font-semibold">skills</b> are generated to
        organise the course. <b className="font-semibold">Sub-skills</b> and{" "}
        <b className="font-semibold">knowledge</b> come from your course material, each with the passage it
        was read from.
      </p>
      <div className="flex shrink-0 items-center gap-3">
        <button
          type="button"
          onClick={onOpenGuide}
          className="text-xs font-semibold text-hestia-primary transition hover:text-hestia-primary-hover"
        >
          How to read this
        </button>
        <button
          type="button"
          onClick={onDismiss}
          className="text-xs font-semibold text-hestia-primary transition hover:text-hestia-primary-hover"
        >
          Got it
        </button>
      </div>
    </div>
  );
}
