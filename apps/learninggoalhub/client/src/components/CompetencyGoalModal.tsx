import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, API_PREFIX } from "../api/client.ts";
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
 * its text); a session tile and a kind tile (explicit vs implicit) share the next row; a
 * full-width source tile quotes the exact snippet(s) each source contributed; Bloom and SOLO
 * follow as two tiles with a filled dot scale, level name and one-line explanation; and — for the
 * list view — a relationships tile lists the goal's links to other goals, grouped by type. Pass
 * `role` for the competency views (drives the badge) and `relationships` to show the
 * relationships tile.
 */
/** Maps a title-cased ladder term back to its API enum value ("Extended Abstract" → "EXTENDED_ABSTRACT"). */
const toEnum = (term: string) => term.toUpperCase().replace(/ /g, "_");

type GoalChanges = {
  text?: string;
  bloomLevel?: LearningGoal["bloomLevel"];
  soloLevel?: LearningGoal["soloLevel"];
};

type PendingChanges = GoalChanges & { shortLabel?: string | null };

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
  // The modal only renders under /courses/:courseId; the id builds the source deep links.
  const { courseId } = useParams();
  const numericCourseId = Number(courseId);
  const queryClient = useQueryClient();

  // Edits show immediately: changes overlay the goal until the refetched goal (a new object
  // identity) confirms them. Editing state for the text field lives here too.
  const [pending, setPending] = useState<PendingChanges>({});
  const [draft, setDraft] = useState<string | null>(null);
  const [editingSourceIndex, setEditingSourceIndex] = useState<number | null>(
    null,
  );
  const [sourceDraft, setSourceDraft] = useState("");
  const [editingSession, setEditingSession] = useState(false);
  const [sessionDraft, setSessionDraft] = useState("");
  const renameMutation = useMutation({
    mutationFn: async (vars: {
      documentId: number;
      displayName: string | null;
    }) => {
      const { error } = await api.PATCH(
        "/api/courses/{courseId}/documents/{documentId}",
        {
          params: {
            path: { courseId: numericCourseId, documentId: vars.documentId },
          },
          body: { displayName: vars.displayName ?? undefined },
          // openapi-fetch drops undefined body keys, but clearing needs an explicit null.
          bodySerializer: (body) =>
            JSON.stringify({ displayName: body?.displayName ?? null }),
        },
      );
      if (error) throw new Error("Could not rename the document.");
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({
        queryKey: ["documents", numericCourseId],
      });
      await queryClient.invalidateQueries({
        queryKey: ["goals", numericCourseId],
      });
      setEditingSourceIndex(null);
    },
  });
  const sessionRenameMutation = useMutation({
    mutationFn: async (vars: { sessionId: number; label: string }) => {
      const { error } = await api.PATCH(
        "/api/courses/{courseId}/hierarchy-nodes/{nodeId}",
        {
          params: {
            path: { courseId: numericCourseId, nodeId: vars.sessionId },
          },
          body: { label: vars.label },
        },
      );
      if (error) throw new Error("Could not rename the session.");
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({
        queryKey: ["goals", numericCourseId],
      });
      setEditingSession(false);
    },
  });
  useEffect(() => setPending({}), [freshGoal]);
  // The draft only resets when another goal opens — a background refetch must not eat typing.
  useEffect(() => {
    setDraft(null);
    setEditingSourceIndex(null);
    setEditingSession(false);
  }, [freshGoal?.id]);

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
    setPending((prev) => ({
      ...prev,
      ...changes,
      ...(changes.text !== undefined ? { shortLabel: null } : {}),
    }));
    onUpdate!(goal.id!, changes);
  };
  const sources = goal.sources ?? [];
  const rels = relationships ?? [];
  const session = goal.hierarchy?.session ?? goal.hierarchy?.exercise;
  const sessionId = goal.hierarchy?.sessionId;

  const saveDraft = () => {
    const trimmed = (draft ?? "").trim();
    if (trimmed !== "" && trimmed !== goal.text) update({ text: trimmed });
    setDraft(null);
  };
  const saveSession = () => {
    const trimmed = sessionDraft.trim();
    if (
      sessionId != null &&
      trimmed !== "" &&
      trimmed !== session &&
      !sessionRenameMutation.isPending
    ) {
      sessionRenameMutation.mutate({ sessionId, label: trimmed });
    }
  };

  return (
    <div
      className="fixed inset-0 z-50"
      role="dialog"
      aria-modal="true"
      aria-label="Goal details"
    >
      {/* The blur sits on its own static layer: sharing it with the scroll container would make
          the browser re-blur the whole view underneath on every scrolled frame. */}
      <div
        aria-hidden="true"
        className="absolute inset-0 bg-hestia-bg/75 backdrop-blur-[2px]"
      />
      <div
        onClick={onClose}
        className="absolute inset-0 flex items-start justify-center overflow-y-auto p-4 sm:p-8"
      >
        {/* One animation for the whole panel rather than per tile: every element animating over
            the backdrop-filter keeps the browser from caching the blurred layer. */}
        <div
          onClick={(e) => e.stopPropagation()}
          className="comp-unfold flex w-full max-w-lg flex-col gap-3.5 sm:mt-[6vh]"
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
          <div className="flex flex-col gap-2 rounded-lg border border-hestia-border bg-hestia-surface p-4 shadow-lg">
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
          {/* Session and kind are both one-liners, so they share a row. */}
          {(session || goal.kind) && (
            <div
              className={`grid gap-3 ${session && goal.kind ? "sm:grid-cols-2" : ""}`}
            >
              {session && (
                <div className="rounded-lg border border-hestia-border bg-hestia-surface p-3.5 shadow-lg">
                  <div className="flex items-start justify-between gap-2">
                    <span className="text-[0.6rem] font-semibold uppercase tracking-wider text-hestia-text-muted">
                      Session
                    </span>
                    {sessionId != null && !editingSession && (
                      <button
                        type="button"
                        title="Rename session"
                        aria-label={`Rename ${session}`}
                        onClick={() => {
                          sessionRenameMutation.reset();
                          setSessionDraft(session);
                          setEditingSession(true);
                        }}
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
                  {editingSession ? (
                    <form
                      onSubmit={(e) => {
                        e.preventDefault();
                        saveSession();
                      }}
                      onKeyDown={(e) => {
                        if (e.key === "Escape") {
                          e.stopPropagation();
                          sessionRenameMutation.reset();
                          setEditingSession(false);
                        }
                      }}
                      className="mt-2 flex flex-col gap-2"
                    >
                      <input
                        value={sessionDraft}
                        onChange={(e) => setSessionDraft(e.target.value)}
                        autoFocus
                        className="w-full rounded-sm border-[1.5px] border-hestia-border bg-hestia-bg px-2.5 py-1.5 text-xs text-hestia-text transition focus:border-hestia-primary focus:outline-none"
                      />
                      {sessionRenameMutation.isError && (
                        <p className="text-xs text-hestia-danger">
                          {(sessionRenameMutation.error as Error).message}
                        </p>
                      )}
                      <div className="flex justify-end gap-2">
                        <button
                          type="button"
                          onClick={() => {
                            sessionRenameMutation.reset();
                            setEditingSession(false);
                          }}
                          disabled={sessionRenameMutation.isPending}
                          className="rounded-md border border-hestia-border px-2.5 py-1 text-xs font-medium text-hestia-text transition hover:bg-hestia-primary-muted disabled:opacity-50"
                        >
                          Cancel
                        </button>
                        <button
                          type="submit"
                          disabled={
                            sessionDraft.trim() === "" ||
                            sessionDraft.trim() === session ||
                            sessionRenameMutation.isPending
                          }
                          className="rounded-md bg-hestia-primary px-2.5 py-1 text-xs font-medium text-white transition hover:bg-hestia-primary-hover disabled:opacity-50"
                        >
                          {sessionRenameMutation.isPending ? "Saving…" : "Save"}
                        </button>
                      </div>
                    </form>
                  ) : (
                    <p className="mt-2 text-sm font-semibold text-hestia-text">
                      {session}
                    </p>
                  )}
                </div>
              )}
              {goal.kind && (
                <div className="rounded-lg border border-hestia-border bg-hestia-surface p-3.5 shadow-lg">
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
            </div>
          )}
          {/* The snippets need room, so source gets its own full-width tile. */}
          {sources.length > 0 && (
            <div className="rounded-lg border border-hestia-border bg-hestia-surface p-3.5 shadow-lg">
              <span className="text-[0.6rem] font-semibold uppercase tracking-wider text-hestia-text-muted">
                Source
              </span>
              <ul className="mt-2 space-y-2.5">
                {sources.map((source, i) => {
                  const shown = source.displayName ?? source.filename ?? "";
                  const editing = editingSourceIndex === i;
                  const trimmed = sourceDraft.trim();
                  const canSave =
                    trimmed !== "" &&
                    trimmed !== shown &&
                    !renameMutation.isPending;
                  const saveSource = () => {
                    if (canSave) {
                      renameMutation.mutate({
                        documentId: source.documentId!,
                        displayName: trimmed,
                      });
                    }
                  };

                  return (
                    <li key={i} className="text-xs text-hestia-text">
                      {editing ? (
                        <form
                          onSubmit={(e) => {
                            e.preventDefault();
                            saveSource();
                          }}
                          onKeyDown={(e) => {
                            if (e.key === "Escape") {
                              e.stopPropagation();
                              renameMutation.reset();
                              setEditingSourceIndex(null);
                            }
                          }}
                          className="flex flex-col gap-2"
                        >
                          <input
                            value={sourceDraft}
                            onChange={(e) => setSourceDraft(e.target.value)}
                            autoFocus
                            className="w-full rounded-sm border-[1.5px] border-hestia-border bg-hestia-bg px-2.5 py-1.5 text-xs text-hestia-text transition focus:border-hestia-primary focus:outline-none"
                          />
                          {renameMutation.isError && (
                            <p className="text-xs text-hestia-danger">
                              {(renameMutation.error as Error).message}
                            </p>
                          )}
                          <div className="flex items-center justify-between gap-2">
                            {source.displayName ? (
                              <button
                                type="button"
                                disabled={renameMutation.isPending}
                                onClick={() =>
                                  renameMutation.mutate({
                                    documentId: source.documentId!,
                                    displayName: null,
                                  })
                                }
                                className="text-xs text-hestia-text-muted underline transition hover:text-hestia-text disabled:opacity-50"
                              >
                                Reset to filename
                              </button>
                            ) : (
                              <span />
                            )}
                            <div className="flex gap-2">
                              <button
                                type="button"
                                onClick={() => {
                                  renameMutation.reset();
                                  setEditingSourceIndex(null);
                                }}
                                disabled={renameMutation.isPending}
                                className="rounded-md border border-hestia-border px-2.5 py-1 text-xs font-medium text-hestia-text transition hover:bg-hestia-primary-muted disabled:opacity-50"
                              >
                                Cancel
                              </button>
                              <button
                                type="submit"
                                disabled={!canSave}
                                className="rounded-md bg-hestia-primary px-2.5 py-1 text-xs font-medium text-white transition hover:bg-hestia-primary-hover disabled:opacity-50"
                              >
                                {renameMutation.isPending ? "Saving…" : "Save"}
                              </button>
                            </div>
                          </div>
                        </form>
                      ) : (
                        <div className="group/source flex min-w-0 items-start gap-2">
                          <div className="min-w-0 flex-1">
                            {shown && source.contentAvailable ? (
                              // Opens the stored document in the browser's PDF viewer; #page=N jumps to the
                              // page the snippet was located on (or the session's first page as fallback).
                              <a
                                href={`${API_PREFIX}/api/courses/${courseId}/documents/${source.documentId}/content${source.page ? `#page=${source.page}` : ""}`}
                                target="_blank"
                                rel="noreferrer"
                                onClick={(e) => e.stopPropagation()}
                                className="flex min-w-0 items-baseline gap-1.5 font-medium text-hestia-text transition hover:text-hestia-primary"
                              >
                                <span className="truncate underline decoration-[color-mix(in_srgb,var(--hestia-primary)_40%,transparent)] underline-offset-[3px] group-hover/source:decoration-hestia-primary">
                                  {shown}
                                </span>
                                {source.page && (
                                  <span className="shrink-0 text-hestia-text-muted">
                                    p. {source.page}
                                  </span>
                                )}
                              </a>
                            ) : (
                              <p className="truncate font-medium">{shown}</p>
                            )}
                            {source.displayName && (
                              <p className="mt-0.5 truncate text-xs text-hestia-text-muted">
                                {source.filename}
                              </p>
                            )}
                          </div>
                          <button
                            type="button"
                            title="Rename source document"
                            aria-label={`Rename ${shown}`}
                            onClick={() => {
                              renameMutation.reset();
                              setSourceDraft(shown);
                              setEditingSourceIndex(i);
                            }}
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
                          {source.grounded === false && (
                            <span
                              title="Snippet could not be located in the document"
                              className="shrink-0 pt-1 text-[0.65rem] font-normal text-hestia-text-muted"
                            >
                              unverified
                            </span>
                          )}
                        </div>
                      )}
                      {source.snippet && (
                        <p className="mt-1 line-clamp-3 border-l-2 border-hestia-border pl-2.5 italic leading-relaxed text-hestia-text-muted">
                          “{source.snippet}”
                        </p>
                      )}
                    </li>
                  );
                })}
              </ul>
            </div>
          )}
          {(goal.bloomLevel || goal.soloLevel) && (
            <div className="grid gap-3 sm:grid-cols-2">
              {goal.bloomLevel && (
                <TaxonomyTile
                  label="Bloom"
                  term={titleCase(goal.bloomLevel)}
                  desc={BLOOM_DESC}
                  dotClass="bg-hestia-accent"
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
                  onSelect={
                    onUpdate
                      ? (term) =>
                          update({
                            soloLevel: toEnum(
                              term,
                            ) as LearningGoal["soloLevel"],
                          })
                      : undefined
                  }
                />
              )}
            </div>
          )}
          {rels.length > 0 && (
            <div className="rounded-lg border border-hestia-border bg-hestia-surface p-3.5 shadow-lg">
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
  onSelect,
}: {
  label: string;
  term: string;
  /** The taxonomy's level → description map, in ladder order. */
  desc: Record<string, string>;
  dotClass: string;
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
    <div className="rounded-lg border border-hestia-border bg-hestia-surface p-3.5 shadow-lg">
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
      <p className="mt-1 text-sm font-semibold text-hestia-text">{shownTerm}</p>
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
