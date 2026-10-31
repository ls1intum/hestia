import { TREE_ROLE_META, type TreeNode } from "../../lib/goals.ts";
import Button from "../Button.tsx";
import { RenameField, RowAction } from "../GoalInlineEditing.tsx";
import { CheckIcon } from "./PhaseTick.tsx";

/**
 * A topic's row: its name, how many skills it groups, and what an instructor can do to a topic —
 * accept all of its skills at once, or dismiss it. Dismissing deletes everything beneath the topic
 * with it, so it asks once more in place. Clicking the name folds the group; the pencil renames it
 * in place, as in the table.
 */
export function TopicHeader({
  topic,
  number,
  skillCount,
  groupedSkillCount,
  subSkillCount,
  pendingCount,
  acceptingAll,
  onAcceptAll,
  expanded,
  confirming,
  dismissing,
  onToggle,
  onDismiss,
  onCancelDismiss,
  onConfirmDismiss,
  renaming,
  onStartRename,
  onRename,
}: {
  topic: TreeNode;
  /** The topic's lecture-order number, as the table shows it: "3". */
  number: string;
  /** Items up for review in this topic: its skills, or its sub-skills when it has no skills. */
  skillCount: number;
  /** Skills grouped under the topic. */
  groupedSkillCount: number;
  /** Sub-skills reviewed in place of skills; non-zero only for a topic without skills. */
  subSkillCount: number;
  pendingCount: number;
  acceptingAll: boolean;
  onAcceptAll: () => void;
  expanded: boolean;
  confirming: boolean;
  dismissing: boolean;
  onToggle: () => void;
  onDismiss: () => void;
  onCancelDismiss: () => void;
  onConfirmDismiss: () => void;
  renaming: boolean;
  onStartRename: () => void;
  /** Gets the new name, or null when nothing changed or the edit was dropped. */
  onRename: (text: string | null) => void;
}) {
  const groupedSkillWord = groupedSkillCount === 1 ? "1 skill" : `${groupedSkillCount} skills`;
  const countLabel = groupedSkillCount === 0 && subSkillCount > 0
    ? subSkillCount === 1 ? "1 sub-skill" : `${subSkillCount} sub-skills`
    : groupedSkillWord;

  const name = (
    <>
      <span
        aria-hidden="true"
        className="mr-1 w-[3px] shrink-0 self-stretch rounded-full"
        style={{ backgroundColor: TREE_ROLE_META.topic.color }}
      />
      {/* Chevron and name are centred as one block, so a wrapped name keeps the chevron on its
          first line, beside the number. */}
      <span className="flex min-w-0 flex-1 items-start gap-1">
        <span className="flex h-[1lh] w-5 shrink-0 items-center justify-center text-sm leading-relaxed text-hestia-text-muted">
          <svg
            viewBox="0 0 20 20"
            fill="none"
            stroke="currentColor"
            strokeWidth="2.5"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
            className={`h-3 w-3 transition-transform ${expanded ? "rotate-90" : ""}`}
          >
            <path d="M7 5l6 5-6 5" />
          </svg>
        </span>
        {/* As in a skill row: the number keeps the first line and a long name wraps beside it. */}
        <span className="flex min-w-0 flex-1 items-baseline text-sm font-semibold leading-relaxed text-hestia-text">
          <span className="mr-1 shrink-0 tabular-nums text-hestia-text-muted">{number}.</span>
          {renaming ? (
            <RenameField text={topic.goal.text ?? ""} onDone={onRename} />
          ) : (
            <span className="min-w-0 flex-1">
              {topic.goal.text}
              {topic.goal.originalText != null && (
                <span
                  title={`Generated as: ${topic.goal.originalText}`}
                  className="ml-2 inline-flex h-[22px] items-center whitespace-nowrap rounded-md border border-hestia-border px-2 align-middle text-xs font-medium text-hestia-text-muted"
                >
                  Edited
                </span>
              )}
            </span>
          )}
        </span>
      </span>
      <span className="shrink-0 tabular-nums text-xs text-hestia-text-muted">{countLabel}</span>
    </>
  );

  return (
    // The Topic tree table's topic row: the same tint, chevron and coloured rail.
    <div className="group flex flex-wrap items-center gap-x-3 gap-y-2 bg-[color-mix(in_srgb,var(--hestia-text)_3%,var(--hestia-surface))] px-4 py-2.5">
      {/* A field can't sit inside a button, so the row stops folding while it is renamed. */}
      {renaming ? (
        <div className="flex min-w-0 flex-1 items-center gap-1 self-stretch text-left">{name}</div>
      ) : (
        <button type="button" onClick={onToggle} aria-expanded={expanded} className="flex min-w-0 flex-1 items-center gap-1 self-stretch text-left">
          {name}
        </button>
      )}
      {confirming ? (
        <div className="flex shrink-0 items-center gap-1">
          <span className="mr-1 text-xs text-hestia-text-muted">
            {topic.children.length === 0 ? "Remove this topic?" : "Remove it and everything beneath it?"}
          </span>
          <Button variant="neutral" size="sm" disabled={dismissing} onClick={onCancelDismiss}>
            Cancel
          </Button>
          <Button variant="danger" size="sm" disabled={dismissing} onClick={onConfirmDismiss}>
            {dismissing ? "Removing…" : "Remove"}
          </Button>
        </div>
      ) : (
        <div className="flex shrink-0 items-center gap-1">
          {!renaming && (
            <span className="opacity-0 transition focus-within:opacity-100 group-hover:opacity-100">
              <RowAction
                label="Rename this topic"
                onClick={onStartRename}
                className="hover:bg-hestia-primary-muted hover:text-hestia-text"
              >
                <path d="M13.5 3.5l3 3L7 16l-3.7.7L4 13z" />
              </RowAction>
            </span>
          )}
          {skillCount > 0 && (pendingCount > 0 ? (
            // Outlined rather than filled: a gold button on every topic turned the list into a wall
            // of them, and Done is the one filled action here, as on the course page.
            <Button variant="neutral" size="sm" disabled={acceptingAll} onClick={onAcceptAll}>
              <CheckIcon />
              {acceptingAll ? "Accepting…" : "Accept all"}
            </Button>
          ) : (
            <span className="inline-flex items-center gap-1.5 px-2.5 py-1 text-xs font-medium text-hestia-primary">
              <svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true" className="h-3.5 w-3.5">
                <path
                  fillRule="evenodd"
                  d="M16.7 5.3a1 1 0 010 1.4l-7.5 7.5a1 1 0 01-1.4 0l-3.5-3.5a1 1 0 011.4-1.4l2.8 2.8 6.8-6.8a1 1 0 011.4 0z"
                  clipRule="evenodd"
                />
              </svg>
              All accepted
            </span>
          ))}
          <Button
            variant="neutral"
            size="sm"
            title="Remove this topic and everything beneath it from the course"
            onClick={onDismiss}
          >
            Dismiss
          </Button>
        </div>
      )}
    </div>
  );
}

/**
 * One skill under its topic, or a sub-skill standing in for a topic without skills: Accept marks it
 * reviewed, the pencil renames it in place as in the table, Dismiss deletes it after asking once more in
 * place, like a topic.
 */
export function SkillRow({
  skill,
  number,
  accepting,
  confirming,
  dismissing,
  renaming,
  onAccept,
  onDismiss,
  onCancelDismiss,
  onConfirmDismiss,
  onStartRename,
  onRename,
}: {
  skill: TreeNode;
  /**
   * Lecture-order number as the table shows it, "3.2": counted over every child of the topic, so a
   * skill keeps the number it has in the table.
   */
  number: string;
  accepting: boolean;
  confirming: boolean;
  dismissing: boolean;
  onAccept: (approved: boolean) => void;
  onDismiss: () => void;
  onCancelDismiss: () => void;
  onConfirmDismiss: () => void;
  /** The name is an open field, as in the table; Enter or leaving it saves, Escape cancels. */
  renaming: boolean;
  onStartRename: () => void;
  /** Gets the new name, or null when nothing changed or the edit was dropped. */
  onRename: (text: string | null) => void;
}) {
  const approved = skill.goal.status === "APPROVED";
  const noun = TREE_ROLE_META[skill.role].label.toLowerCase();
  // Extraction leaves this null, so a tag here means the skill did not come out of the materials.
  const provenance = skill.goal.creationProvenance;
  const provenanceLabel = provenance === "USER_CREATED"
    ? "Added by you"
    : provenance === "WIZARD_AI_SUBTREE"
      ? "AI added"
      : null;
  return (
    <li
      className={`group flex items-center gap-1 border-t border-hestia-border/60 py-2 pl-4 pr-4 transition-colors first:border-t-0 ${
        approved ? "bg-[color-mix(in_srgb,var(--hestia-primary)_7%,transparent)]" : ""
      }`}
    >
      {/* Indented like the table: 20px per tier below the topic, the tier rail, then the chevron's
          empty slot. A sub-skill standing in for a missing skill still sits a tier deeper. */}
      <span
        className={`${skill.role === "subSkill" ? "w-10" : "w-5"} shrink-0`}
        aria-hidden="true"
      />
      <span
        aria-hidden="true"
        className="mr-1 w-[3px] shrink-0 self-stretch rounded-full"
        style={{ backgroundColor: TREE_ROLE_META[skill.role].color }}
      />
      <span className="h-5 w-5 shrink-0" aria-hidden="true" />
      {/* No wrapping here: the number keeps its own column and a long name wraps beside it. */}
      <span className="mr-2 flex min-w-0 flex-1 items-baseline text-[13px] leading-relaxed text-hestia-text">
        <span className="mr-1 shrink-0 tabular-nums text-hestia-text-muted">{number}.</span>
        {renaming ? (
          <RenameField text={skill.goal.text ?? ""} onDone={onRename} />
        ) : (
          <span className="min-w-0 flex-1">
            {skill.goal.text}
            {skill.goal.originalText != null && (
              <span
                title={`Generated as: ${skill.goal.originalText}`}
                className="ml-2 inline-flex h-[22px] items-center whitespace-nowrap rounded-md border border-hestia-border px-2 align-middle text-xs font-medium text-hestia-text-muted"
              >
                Edited
              </span>
            )}
            {provenanceLabel && (
              <span className="ml-2 inline-flex h-[22px] items-center whitespace-nowrap rounded-md border border-hestia-border px-2 align-middle text-xs font-medium text-hestia-text-muted">
                {provenanceLabel}
              </span>
            )}
          </span>
        )}
      </span>
      {confirming ? (
        <div className="flex shrink-0 items-center gap-1">
          <span className="mr-1 text-xs text-hestia-text-muted">
            {skill.children.length > 0
              ? `Remove this ${noun} and everything beneath it?`
              : `Remove this ${noun}?`}
          </span>
          <Button variant="neutral" size="sm" disabled={dismissing} onClick={onCancelDismiss}>
            Cancel
          </Button>
          <Button variant="danger" size="sm" disabled={dismissing} onClick={onConfirmDismiss}>
            {dismissing ? "Removing…" : "Remove"}
          </Button>
        </div>
      ) : (
        <div className="flex shrink-0 items-center gap-1">
          {!renaming && (
            // The table's rename action, revealed on hover or keyboard focus as it is there.
            <span className="opacity-0 transition focus-within:opacity-100 group-hover:opacity-100">
              <RowAction
                label={`Rename this ${noun}`}
                onClick={onStartRename}
                className="hover:bg-hestia-primary-muted hover:text-hestia-text"
              >
                <path d="M13.5 3.5l3 3L7 16l-3.7.7L4 13z" />
              </RowAction>
            </span>
          )}
          {approved ? (
            <Button
              variant="ghost"
              size="sm"
              disabled={accepting}
              title="Accepted — click to undo"
              aria-label="Undo accept"
              onClick={() => onAccept(false)}
              className="text-hestia-primary"
            >
              <svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true" className="h-3.5 w-3.5">
                <path
                  fillRule="evenodd"
                  d="M16.7 5.3a1 1 0 010 1.4l-7.5 7.5a1 1 0 01-1.4 0l-3.5-3.5a1 1 0 011.4-1.4l2.8 2.8 6.8-6.8a1 1 0 011.4 0z"
                  clipRule="evenodd"
                />
              </svg>
              Accepted
            </Button>
          ) : (
            <Button variant="neutral" size="sm" disabled={accepting} onClick={() => onAccept(true)}>
              <CheckIcon />
              {accepting ? "Accepting…" : "Accept"}
            </Button>
          )}
          <Button
            variant="neutral"
            size="sm"
            title={`Remove this ${noun} from the course`}
            onClick={onDismiss}
          >
            Dismiss
          </Button>
        </div>
      )}
    </li>
  );
}
