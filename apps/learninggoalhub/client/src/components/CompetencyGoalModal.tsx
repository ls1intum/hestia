import { lazy, Suspense, useEffect, useRef, useState } from "react";
import { useParams } from "react-router-dom";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, API_PREFIX, type GoalSource } from "../api/client.ts";
import type { LearningGoal } from "../api/client.ts";
import CompetencyCreationField from "./CompetencyCreationField.tsx";
import ConfirmDialog from "./ConfirmDialog.tsx";
import ErrorBoundary from "./ErrorBoundary.tsx";
import Button from "./Button.tsx";
// Lazily loaded so the heavy pdf.js bundle only ships once a source is opened, not on first paint.
const SourcePdfPane = lazy(() => import("./SourcePdfPane.tsx"));
import {
  BLOOM_DESC,
  COMPETENCY_ROLE_META,
  KIND_DESC,
  SOLO_DESC,
  titleCase,
  type CompetencyRole,
} from "../lib/goals.ts";

/**
 * Goal detail overlay shared by the map, tree and list views, styled like the sibling-picker: no
 * panel chrome, the pieces float over the blurred backdrop. Top row names the dialog and carries
 * the ✕; the goal itself appears as a box (an optional role badge for the competency views, then
 * its text); a session tile and a kind tile (explicit vs implicit) share the next row; a
 * full-width source tile quotes the exact snippet(s) each source contributed; and Bloom and SOLO
 * follow as two tiles with a filled dot scale, level name and one-line explanation. Pass `role`
 * for the competency views, which drives the badge.
 */
/** Maps a title-cased ladder term back to its API enum value ("Extended Abstract" → "EXTENDED_ABSTRACT"). */
const toEnum = (term: string) => term.toUpperCase().replace(/ /g, "_");

/** Goal wording reduced to what a reader would call the same sentence, for comparing two versions. */
const normalize = (text: string) =>
  text
    .toLowerCase()
    .replace(/\s+/g, " ")
    .replace(/[.!?;:,]+$/, "")
    .trim();

type GoalChanges = {
  text?: string;
  bloomLevel?: LearningGoal["bloomLevel"];
  soloLevel?: LearningGoal["soloLevel"];
};

type PendingChanges = GoalChanges & { shortLabel?: string };

export default function CompetencyGoalModal({
  goal: freshGoal,
  role,
  knowledge,
  generatedChildCount,
  onClose,
  onUpdate,
  onDelete,
  onOpenGoal,
  onBack,
  backLabel,
}: {
  goal: LearningGoal | null;
  /** The node's role in the competency tree, shown as the header badge. */
  role?: CompetencyRole;
  /** Immediate knowledge goals under a sub-skill, shown as read-only evidence rows. */
  knowledge?: LearningGoal[];
  /**
   * Wizard-generated sub-skills under this goal, which enables the subtree action: a number means
   * the goal is a terminal skill (0 = its generation failed or was never run), `undefined` means it
   * is not one and the action stays out of the modal.
   */
  generatedChildCount?: number;
  onClose: () => void;
  /** Enables in-place editing: the goal text via a pencil, Bloom/SOLO by clicking a dot. */
  onUpdate?: (goalId: number, changes: GoalChanges) => void;
  /** Delete action in the header; the modal closes itself before handing the goal over. */
  onDelete?: (goal: LearningGoal) => void;
  /** Opens a knowledge goal in this modal for editing. */
  onOpenGoal?: (goal: LearningGoal) => void;
  /** Returns to the goal this one was opened from; absent when nothing led here. */
  onBack?: () => void;
  /** Names the goal `onBack` returns to, so the control says where it goes. */
  backLabel?: string;
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
  const [openSource, setOpenSource] = useState<GoalSource | null>(null);
  const [addingKnowledge, setAddingKnowledge] = useState(false);
  const [knowledgeDraft, setKnowledgeDraft] = useState("");
  // Which column asked for the preview. That column stays beside it; the other one steps aside, so
  // whatever was clicked keeps its context while its page is on screen.
  const [sourceOrigin, setSourceOrigin] = useState<"attributes" | "evidence">(
    "attributes",
  );
  const pdfPaneRef = useRef<HTMLDivElement>(null);
  const sourceTriggerRef = useRef<HTMLElement | null>(null);
  /** Opens the preview, remembering its origin column and the control that asked for it. */
  const showSource = (
    source: GoalSource,
    trigger: HTMLElement | null,
    origin: "attributes" | "evidence",
  ) => {
    sourceTriggerRef.current = trigger;
    setSourceOrigin(origin);
    setOpenSource(source);
  };
  const [editingSession, setEditingSession] = useState(false);
  const [sessionDraft, setSessionDraft] = useState("");
  const [confirmRegenerate, setConfirmRegenerate] = useState(false);
  // Set when a rename actually changed the wording the AI children were derived from.
  const [staleSubtree, setStaleSubtree] = useState(false);
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
  const regenerateMutation = useMutation({
    mutationFn: async (goalId: number) => {
      const { error } = await api.POST(
        "/api/courses/{courseId}/learning-goals/{goalId}/subtree",
        { params: { path: { courseId: numericCourseId, goalId } } },
      );
      if (error) throw new Error("Could not generate the sub-skills.");
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({
        queryKey: ["goals", numericCourseId],
      });
      setConfirmRegenerate(false);
      setStaleSubtree(false);
    },
  });
  const addKnowledgeMutation = useMutation({
    mutationFn: async (vars: { goalId: number; text: string }) => {
      const result = await api.POST(
        "/api/courses/{courseId}/learning-goals/{goalId}/children",
        {
          params: { path: { courseId: numericCourseId, goalId: vars.goalId } },
          body: { text: vars.text },
        },
      );
      if (!result.data) {
        throw new Error(
          result.response.status === 409
            ? "Knowledge nodes cannot have children."
            : "Could not add the goal.",
        );
      }
      return result.data;
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({
        queryKey: ["goals", numericCourseId],
      });
      await queryClient.invalidateQueries({
        queryKey: ["course", numericCourseId],
      });
      await queryClient.invalidateQueries({ queryKey: ["courses"] });
      setKnowledgeDraft("");
      setAddingKnowledge(false);
    },
  });
  useEffect(() => setPending({}), [freshGoal]);
  // The draft only resets when another goal opens — a background refetch must not eat typing.
  useEffect(() => {
    setDraft(null);
    setEditingSourceIndex(null);
    setEditingSession(false);
    setOpenSource(null);
    setConfirmRegenerate(false);
    setStaleSubtree(false);
  }, [freshGoal?.id]);

  useEffect(() => {
    if (!freshGoal) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        if (openSource) {
          e.preventDefault();
          setOpenSource(null);
        } else {
          onClose();
        }
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [freshGoal, onClose, openSource]);

  // Opening the preview takes the attribute column off screen on wide viewports, so focus has to be
  // moved deliberately: into the pane on open, back to the row or link that asked for it on close.
  // Without this the keyboard user is left on a detached element and lands back at the document.
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
    setPending((prev) => ({
      ...prev,
      ...changes,
      ...(changes.text !== undefined ? { shortLabel: undefined } : {}),
    }));
    onUpdate!(goal.id!, changes);
  };
  const sources = goal.sources ?? [];
  // `kind` describes how a goal relates to the source material, which says nothing true about a node
  // the wizard generated or the instructor typed: both are stored as IMPLICIT with no source at all,
  // so the plain kind tile would claim they were "inferred from the content". Provenance is the more
  // specific fact and wins here, the way the tree's Kind column already replaces the kind pill.
  const kindTile =
    goal.creationProvenance === "USER_CREATED"
      ? { label: "Manual", desc: "Added by hand, not derived from source material." }
      : goal.creationProvenance === "WIZARD_AI_SUBTREE"
        ? {
            label: "AI-inferred",
            desc: "Generated from the skill's wording, without a source reference.",
          }
        : goal.kind
          ? { label: titleCase(goal.kind), desc: KIND_DESC[titleCase(goal.kind)] }
          : null;
  const session = goal.hierarchy?.session ?? goal.hierarchy?.exercise;
  const sessionId = goal.hierarchy?.sessionId;
  const knowledgeSources = knowledge?.map((item) => item.sources?.[0]);
  const knowledgePages = knowledgeSources
    ?.map((source) => source?.page)
    .filter((page): page is number => page != null);
  const knowledgePageSummary =
    knowledgePages && knowledgePages.length > 0
      ? knowledgePages.length === 1
        ? ` · page ${knowledgePages[0]}`
        : ` · pages ${Math.min(...knowledgePages)}–${Math.max(...knowledgePages)}`
      : "";
  // Counted exactly as the rows below mark themselves, so the summary can never disagree with the
  // pills: a missing source and an ungrounded one both read as unsupported.
  const unsupportedKnowledgeCount = knowledgeSources?.filter(
    (source) => !source || source.grounded === false,
  ).length;
  // Only a sub-skill has an evidence column; every other role keeps the original single column.
  const showEvidence = role === "sub-skill" && knowledge !== undefined;

  // A terminal skill the pipeline clustered has no wizard subtree to replace — the server refuses
  // it, so the action never appears for one (its creation provenance is null).
  const canRegenerate =
    generatedChildCount != null && goal.creationProvenance != null;

  const saveDraft = () => {
    const trimmed = (draft ?? "").trim();
    if (trimmed !== "" && trimmed !== goal.text) {
      update({ text: trimmed });
      // The sub-skills were derived from the old wording. Only flag a real rewording: correcting a
      // typo or the punctuation leaves them just as valid as before.
      if (
        canRegenerate &&
        generatedChildCount! > 0 &&
        normalize(trimmed) !== normalize(goal.text ?? "")
      )
        setStaleSubtree(true);
    }
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
          className={`comp-unfold flex w-full flex-col gap-3.5 sm:mt-[6vh] ${
            openSource ? "max-w-6xl" : showEvidence ? "max-w-5xl" : "max-w-lg"
          }`}
        >
          {/* The header spans the whole panel rather than riding on the first column: with the
              preview open that column steps aside, and the close button must never go with it. */}
          <div className="flex items-center justify-between gap-2">
            {/* Drilling into a knowledge goal replaces what the modal shows, so the trail back to
                the goal that led here takes the header's naming slot. */}
            {onBack ? (
              <button
                type="button"
                onClick={onBack}
                className="flex min-w-0 items-center gap-1 text-xs font-semibold uppercase tracking-wider text-hestia-text-muted transition hover:text-hestia-text"
              >
                <svg
                  viewBox="0 0 20 20"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2.5"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  aria-hidden="true"
                  className="h-3 w-3 shrink-0"
                >
                  <path d="M12 5l-5 5 5 5" />
                </svg>
                <span className="truncate">{backLabel ?? "Back"}</span>
              </button>
            ) : (
              <span className="text-xs font-semibold uppercase tracking-wider text-hestia-text">
                Goal details
              </span>
            )}
            <div className="flex items-center gap-0.5">
              {onDelete && (
                <Button
                  variant="ghost"
                  size="icon-sm"
                  onClick={() => {
                    onClose();
                    onDelete(goal);
                  }}
                  title="Delete this goal permanently."
                  aria-label="Delete goal"
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
              <Button
                variant="ghost"
                size="icon-sm"
                onClick={onClose}
                aria-label="Close"
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
              </Button>
            </div>
          </div>
          <div className="flex w-full flex-col gap-3.5 lg:flex-row lg:items-start">
          {/* Attributes. They stay when the preview was opened from the sub-skill's own source and
              step aside when a knowledge row asked for it. Below `lg` everything stacks. */}
          <div
            className={`flex min-w-0 flex-col gap-3.5 ${
              showEvidence || openSource
                ? "w-full lg:max-w-lg lg:shrink-0"
                : "w-full"
            } ${openSource && sourceOrigin === "evidence" ? "lg:hidden" : ""}`}
          >
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
              <p className="text-base font-medium leading-relaxed text-hestia-text">
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
          {/* The subtree action belongs to the skill above, so it sits right under its box rather
              than as an icon in the header, where nothing would say what it regenerates. */}
          {canRegenerate && (
            <div className="flex flex-col gap-1.5">
              {staleSubtree && (
                <p className="text-xs leading-snug text-hestia-text-muted">
                  The sub-skills were generated from the previous wording.
                </p>
              )}
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={() =>
                    generatedChildCount! > 0
                      ? setConfirmRegenerate(true)
                      : regenerateMutation.mutate(goal.id!)
                  }
                  disabled={regenerateMutation.isPending}
                  className="rounded-md border border-hestia-border bg-hestia-surface px-2.5 py-1 text-xs font-medium text-hestia-text shadow-sm transition hover:bg-hestia-primary-muted disabled:opacity-50"
                >
                  {regenerateMutation.isPending
                    ? "Generating…"
                    : generatedChildCount! > 0
                      ? "Regenerate AI sub-skills"
                      : "Generate AI sub-skills"}
                </button>
                {staleSubtree && (
                  <button
                    type="button"
                    onClick={() => setStaleSubtree(false)}
                    className="text-xs text-hestia-text-muted underline transition hover:text-hestia-text"
                  >
                    Keep them
                  </button>
                )}
              </div>
              {regenerateMutation.isError && !confirmRegenerate && (
                <p className="text-xs text-hestia-danger">
                  {(regenerateMutation.error as Error).message}
                </p>
              )}
            </div>
          )}
          {/* Session and kind are both one-liners, so they share a row. */}
          {(session || kindTile) && (
            <div
              className={`grid gap-3 ${session && kindTile ? "sm:grid-cols-2" : ""}`}
            >
              {session && (
                <div className="rounded-lg border border-hestia-border bg-hestia-surface p-3.5 shadow-lg">
                  <div className="flex items-start justify-between gap-2">
                    <span className="text-xs font-semibold uppercase tracking-wider text-hestia-text-muted">
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
                        <Button
                          variant="neutral"
                          size="sm"
                          onClick={() => {
                            sessionRenameMutation.reset();
                            setEditingSession(false);
                          }}
                          disabled={sessionRenameMutation.isPending}
                        >
                          Cancel
                        </Button>
                        <Button
                          type="submit"
                          size="sm"
                          disabled={
                            sessionDraft.trim() === "" ||
                            sessionDraft.trim() === session ||
                            sessionRenameMutation.isPending
                          }
                        >
                          {sessionRenameMutation.isPending ? "Saving…" : "Save"}
                        </Button>
                      </div>
                    </form>
                  ) : (
                    <p className="mt-2 text-sm font-semibold text-hestia-text">
                      {session}
                    </p>
                  )}
                </div>
              )}
              {kindTile && (
                <div className="rounded-lg border border-hestia-border bg-hestia-surface p-3.5 shadow-lg">
                  <span className="text-xs font-semibold uppercase tracking-wider text-hestia-text-muted">
                    Kind
                  </span>
                  <p className="mt-2 text-sm font-semibold text-hestia-text">
                    {kindTile.label}
                  </p>
                  {kindTile.desc && (
                    <p className="mt-0.5 text-xs leading-snug text-hestia-text-muted">
                      {kindTile.desc}
                    </p>
                  )}
                </div>
              )}
            </div>
          )}
          {/* The snippets need room, so source gets its own full-width tile. */}
          {sources.length > 0 && (
            <div className="rounded-lg border border-hestia-border bg-hestia-surface p-3.5 shadow-lg">
              <span className="text-xs font-semibold uppercase tracking-wider text-hestia-text-muted">
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
                              <Button
                                variant="neutral"
                                size="sm"
                                onClick={() => {
                                  renameMutation.reset();
                                  setEditingSourceIndex(null);
                                }}
                                disabled={renameMutation.isPending}
                              >
                                Cancel
                              </Button>
                              <Button type="submit" size="sm" disabled={!canSave}>
                                {renameMutation.isPending ? "Saving…" : "Save"}
                              </Button>
                            </div>
                          </div>
                        </form>
                      ) : (
                        <div className="group/source flex min-w-0 items-start gap-2">
                          <div className="min-w-0 flex-1">
                            {shown && source.contentAvailable ? (
                              <button
                                type="button"
                                onClick={(e) => {
                                  e.stopPropagation();
                                  showSource(source, e.currentTarget, "attributes");
                                }}
                                className="flex min-w-0 items-baseline gap-1.5 font-medium text-hestia-text transition hover:text-hestia-primary"
                                title="View source in the PDF preview"
                              >
                                <span className="truncate underline decoration-[color-mix(in_srgb,var(--hestia-primary)_40%,transparent)] underline-offset-[3px] group-hover/source:decoration-hestia-primary">
                                  {shown}
                                </span>
                                {source.page && (
                                  <span className="shrink-0 text-hestia-text-muted">
                                    p. {source.page}
                                  </span>
                                )}
                              </button>
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
                          {source.grounded === false && source.evidenceKind !== "FIGURE" && (
                            <span
                              title="Snippet could not be located in the document"
                              className="shrink-0 pt-1 text-xs font-normal text-hestia-text-muted"
                            >
                              unverified
                            </span>
                          )}
                        </div>
                      )}
                      {source.evidenceKind === "FIGURE" ? (
                        <div className="mt-1.5 border-l-2 border-hestia-primary pl-2.5 leading-relaxed text-hestia-text-muted">
                          <span className="inline-flex rounded-full border border-hestia-primary/40 bg-hestia-primary-muted px-1.5 py-0.5 text-[10px] font-medium text-hestia-primary">
                            Figure-derived (AI description)
                          </span>
                          {source.figureDescription && (
                            <p className="mt-1 italic">{source.figureDescription}</p>
                          )}
                        </div>
                      ) : source.snippet ? (
                        <p className="mt-1 line-clamp-3 border-l-2 border-hestia-border pl-2.5 italic leading-relaxed text-hestia-text-muted">
                          “{source.snippet}”
                        </p>
                      ) : null}
                    </li>
                  );
                })}
              </ul>
            </div>
          )}
          {/* An editable modal always shows both scales: a manually added goal starts unclassified,
              and the empty scale is the only place its levels can be set. Read-only views keep
              hiding a level that was never assigned. */}
          {(goal.bloomLevel || goal.soloLevel || onUpdate) && (
            <div className="grid gap-3 sm:grid-cols-2">
              {(goal.bloomLevel || onUpdate) && (
                <TaxonomyTile
                  label="Bloom"
                  term={goal.bloomLevel ? titleCase(goal.bloomLevel) : null}
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
              {(goal.soloLevel || onUpdate) && (
                <TaxonomyTile
                  label="SOLO"
                  term={goal.soloLevel ? titleCase(goal.soloLevel) : null}
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
          </div>
          {/* Evidence. Its own column beside the attributes, so the sub-skill and the material it
              rests on read at once. It stays beside the preview when a knowledge row opened it, so
              the reviewer can step from one quote to the next, and yields when the preview belongs
              to the sub-skill's own source. */}
          {showEvidence && (
            <div
              className={`flex w-full min-w-0 flex-col gap-3.5 lg:max-w-md lg:shrink-0 ${
                openSource && sourceOrigin === "attributes" ? "lg:hidden" : ""
              }`}
            >
            <div className="rounded-lg border border-hestia-border bg-hestia-surface p-3.5 shadow-lg">
              <div className="flex items-center justify-between gap-2">
                <span className="text-xs font-semibold uppercase tracking-wider text-hestia-text-muted">
                  Knowledge
                </span>
                <span className="text-right text-xs tabular-nums text-hestia-text-muted">
                  {knowledge.length} items
                  {knowledgePageSummary}
                  {unsupportedKnowledgeCount
                    ? ` · ${unsupportedKnowledgeCount} unsupported`
                    : ""}
                </span>
              </div>
              {knowledge.length === 0 ? (
                <p className="mt-2 text-xs text-hestia-text-muted">
                  No knowledge is attached to this sub-skill.
                </p>
              ) : (
                <ul className="mt-2">
                  {knowledge.map((knowledgeGoal, i) => {
                    const source = knowledgeGoal.sources?.[0];
                    // The full wording, not the short label: the evidence list is where the goal is
                    // actually read and judged, and the tree already carries the abbreviated form.
                    const label = knowledgeGoal.text ?? knowledgeGoal.shortLabel;
                    const noSource = !source || source.grounded === false;
                    return (
                      <li
                        key={knowledgeGoal.id ?? i}
                        className={`text-xs text-hestia-text ${i > 0 ? "border-t border-hestia-border/60" : ""}`}
                      >
                        <div className="flex min-w-0 items-start gap-1">
                          <button
                            type="button"
                            onClick={(e) =>
                              source && showSource(source, e.currentTarget, "evidence")
                            }
                            aria-label={`View evidence for ${label}`}
                            className={`min-w-0 flex-1 rounded-md px-1.5 py-2 text-left transition hover:bg-[color-mix(in_srgb,var(--hestia-primary)_7%,transparent)] ${source === openSource ? "bg-[color-mix(in_srgb,var(--hestia-primary)_9%,transparent)]" : ""}`}
                          >
                            <span className="flex min-w-0 items-start justify-between gap-2">
                              {/* Wraps rather than truncates: the wording is the thing being judged. */}
                              <span className="min-w-0 flex-1 font-medium text-hestia-text">
                                {label}
                              </span>
                              <span className="flex shrink-0 flex-wrap items-center justify-end gap-1">
                                {knowledgeGoal.kind && (
                                  <EvidencePill
                                    label={titleCase(knowledgeGoal.kind)}
                                    color="var(--hestia-text-muted)"
                                  />
                                )}
                                {source?.evidenceKind === "FIGURE" && (
                                  <EvidencePill
                                    label="Figure"
                                    color="var(--hestia-primary)"
                                  />
                                )}
                                {noSource && (
                                  <EvidencePill
                                    label="No source"
                                    color="var(--hestia-danger)"
                                  />
                                )}
                                {source?.page != null && (
                                  <span className="text-xs tabular-nums text-hestia-text-muted">
                                    p. {source.page}
                                  </span>
                                )}
                              </span>
                            </span>
                            {source?.snippet ? (
                              <p className="mt-1 line-clamp-2 border-l-2 border-hestia-border pl-2.5 italic leading-relaxed text-hestia-text-muted">
                                “{source.snippet}”
                              </p>
                            ) : (
                              <p className="mt-1 text-xs italic text-hestia-text-muted">
                                No passage in the session supports this goal.
                              </p>
                            )}
                          </button>
                          {onOpenGoal && (
                            <Button
                              variant="ghost"
                              size="icon-sm"
                              aria-label={`Open ${label}`}
                              title="Open this goal for editing"
                              onClick={(e) => {
                                e.stopPropagation();
                                onOpenGoal(knowledgeGoal);
                              }}
                            >
                              <svg
                                viewBox="0 0 20 20"
                                fill="none"
                                stroke="currentColor"
                                strokeWidth="1.8"
                                strokeLinecap="round"
                                strokeLinejoin="round"
                                className="h-4 w-4"
                                aria-hidden="true"
                              >
                                <path d="M13.5 3.5l3 3L7 16l-3.7.7L4 13z" />
                              </svg>
                            </Button>
                          )}
                        </div>
                      </li>
                    );
                  })}
                </ul>
              )}
              {onOpenGoal && !addingKnowledge && (
                <button
                  type="button"
                  onClick={() => {
                    addKnowledgeMutation.reset();
                    setKnowledgeDraft("");
                    setAddingKnowledge(true);
                  }}
                  className="mt-2 text-xs font-medium text-hestia-text-muted underline underline-offset-2 transition hover:text-hestia-text"
                >
                  Add knowledge
                </button>
              )}
              {onOpenGoal && addingKnowledge && (
                // Escape has to stop here: the field cancels itself on it, and the modal's window
                // listener would otherwise read the same key as "close the whole dialog".
                <div
                  onKeyDown={(e) => {
                    if (e.key === "Escape") e.stopPropagation();
                  }}
                >
                <CompetencyCreationField
                  value={knowledgeDraft}
                  placeholder="Describe a knowledge goal…"
                  error={
                    addKnowledgeMutation.isError
                      ? (addKnowledgeMutation.error as Error).message
                      : undefined
                  }
                  pending={addKnowledgeMutation.isPending}
                  onChange={(text) => {
                    if (addKnowledgeMutation.isError) addKnowledgeMutation.reset();
                    setKnowledgeDraft(text);
                  }}
                  onSubmit={() =>
                    addKnowledgeMutation.mutate({
                      goalId: goal.id!,
                      text: knowledgeDraft.trim(),
                    })
                  }
                  onCancel={() => {
                    if (!addKnowledgeMutation.isPending) {
                      addKnowledgeMutation.reset();
                      setKnowledgeDraft("");
                      setAddingKnowledge(false);
                    }
                  }}
                  className="mt-2"
                  stacked
                />
                </div>
              )}
            </div>
            </div>
          )}
          {openSource && (
            <div
              ref={pdfPaneRef}
              tabIndex={-1}
              className="w-full outline-none lg:w-[min(44vw,42rem)] lg:shrink-0"
            >
            <ErrorBoundary
              resetKey={openSource.documentId}
              fallback={
                <div className="flex min-h-[32rem] w-full flex-col items-center justify-center gap-2 rounded-lg border border-hestia-border bg-hestia-surface text-center text-xs text-hestia-text-muted lg:w-[min(44vw,42rem)]">
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
                  <div className="flex min-h-[32rem] w-full items-center justify-center rounded-lg border border-hestia-border bg-hestia-surface text-xs text-hestia-text-muted lg:w-[min(44vw,42rem)]">
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
      {confirmRegenerate && (
        <ConfirmDialog
          title="Regenerate the AI sub-skills?"
          message={`This replaces the ${generatedChildCount} generated sub-skill${
            generatedChildCount === 1 ? "" : "s"
          } under this skill, and the knowledge below them, with a fresh set derived from its current wording. Anything you added by hand stays.`}
          confirmLabel={
            regenerateMutation.isPending ? "Generating…" : "Regenerate"
          }
          tone="primary"
          busy={regenerateMutation.isPending}
          error={
            regenerateMutation.isError
              ? (regenerateMutation.error as Error).message
              : undefined
          }
          onConfirm={() => regenerateMutation.mutate(goal.id!)}
          onCancel={() => {
            regenerateMutation.reset();
            setConfirmRegenerate(false);
          }}
        />
      )}
    </div>
  );
}

/**
 * One taxonomy as a floating tile: a dot scale filled up to the goal's level (the taxonomy's
 * ladder is the insertion order of its description map), then the level name and explanation.
 * With `onSelect` the dots become buttons — star-rating style: hovering (or focusing) a dot
 * previews that level, filling the scale up to it and swapping the name/description below to
 * the would-be level; clicking commits it. A quiet header hint keeps this discoverable.
 * A `null` term is the empty state of a goal nobody classified — an untouched scale whose dots
 * still set the level.
 */
function TaxonomyTile({
  label,
  term,
  desc,
  dotClass,
  onSelect,
}: {
  label: string;
  /** The goal's level, or `null` when it has none yet. */
  term: string | null;
  /** The taxonomy's level → description map, in ladder order. */
  desc: Record<string, string>;
  dotClass: string;
  onSelect?: (term: string) => void;
}) {
  const ladder = Object.keys(desc);
  const index = term == null ? -1 : ladder.indexOf(term);
  const [hover, setHover] = useState<number | null>(null);
  const previewing = onSelect != null && hover != null && hover !== index;
  const shownTerm = previewing ? ladder[hover] : term;
  // Preview fill: kept dots stay solid, newly gained dots are half-strength, dropped dots fade.
  const dotStyle = (i: number): string => {
    if (previewing) {
      if (i <= Math.min(index, hover)) return dotClass;
      // With nothing set the preview is the whole answer, so it fills solid instead of half.
      if (i <= hover) return index < 0 ? dotClass : `${dotClass} opacity-50`;
      if (i <= index) return `${dotClass} opacity-20`;
      return "bg-hestia-text/15";
    }
    return index >= 0 && i <= index ? dotClass : "bg-hestia-text/15";
  };
  return (
    <div className="rounded-lg border border-hestia-border bg-hestia-surface p-3.5 shadow-lg">
      <span className="text-xs font-semibold uppercase tracking-wider text-hestia-text-muted">
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
      <p
        className={`mt-1 text-sm font-semibold ${shownTerm ? "text-hestia-text" : "text-hestia-text-muted"}`}
      >
        {shownTerm ?? "Not set"}
      </p>
      {shownTerm ? (
        desc[shownTerm] && (
          <p className="mt-0.5 text-xs leading-snug text-hestia-text-muted">
            {desc[shownTerm]}
          </p>
        )
      ) : (
        <p className="mt-0.5 text-xs leading-snug text-hestia-text-muted">
          {onSelect
            ? "Pick a dot to set the level."
            : "This goal has not been classified."}
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
      className="inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-semibold uppercase tracking-wide"
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
      className="inline-flex items-center rounded-full px-2 py-0.5 text-xs font-semibold uppercase tracking-wide"
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
      className="inline-flex items-center rounded-full px-2 py-0.5 text-xs font-semibold uppercase tracking-wide"
      style={{
        color: "var(--hestia-warning)",
        backgroundColor: "color-mix(in srgb, var(--hestia-warning) 15%, transparent)",
      }}
    >
      Manual
    </span>
  );
}

/** Small tinted pill for evidence attributes in the knowledge tile. */
function EvidencePill({ label, color }: { label: string; color: string }) {
  return (
    <span
      className="inline-flex items-center whitespace-nowrap rounded-full px-1.5 py-0.5 text-[10px] font-medium"
      style={{
        color,
        backgroundColor: `color-mix(in srgb, ${color} 15%, transparent)`,
      }}
    >
      {label}
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
