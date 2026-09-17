import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { ExtractionStatus, LearningGoal } from "../api/client.ts";
import { api } from "../api/client.ts";
import { EXTRACTION_PHASES } from "../lib/extraction.ts";
import { fetchAllGoals } from "../lib/fetchGoals.ts";
import { createTopic } from "../lib/createTopic.ts";
import {
  buildCompetencyForest,
  COMPETENCY_ROLE_META,
  type CompetencyNode,
} from "../lib/goals.ts";
import { useTheme } from "../theme/context.ts";
import iconLight from "../assets/logos/icon-light.svg";
import iconDark from "../assets/logos/icon-dark.svg";
import Button from "./Button.tsx";
import CompetencyCreationField from "./CompetencyCreationField.tsx";
import TopicSearchDialog from "./TopicSearchDialog.tsx";

type Props = {
  open: boolean;
  /** Latest polled snapshot for the run. */
  status?: ExtractionStatus;
  /** Set when the run failed. */
  error?: string | null;
  courseId?: number | null;
  /**
   * Opens straight into the skill review, ignoring run status entirely. The review reads the
   * course's goals from its own query, so it stays reachable long after the run — and after a
   * restart, when the in-memory progress tracker no longer remembers the run at all.
   */
  reviewOnly?: boolean;
  onClose: () => void;
};

/**
 * "Analyzing course materials" overlay driven by the live status snapshot. Once the run succeeds it
 * switches to the existing summary and skill-review flow.
 */
export default function ExtractionProgressModal({
  open,
  status,
  error,
  courseId,
  reviewOnly = false,
  onClose,
}: Props) {
  const { resolved } = useTheme();
  const flame = resolved === "dark" ? iconDark : iconLight;
  const queryClient = useQueryClient();
  // The topic or skill whose dismissal waits for a second click, and the topics folded open. Topics
  // start folded shut, so the review opens on the course's outline.
  const [confirmingGoal, setConfirmingGoal] = useState<number | null>(null);
  const [expandedTopics, setExpandedTopics] = useState<Set<number>>(() => new Set());
  // The wording of a topic being added, or null while the add field is closed.
  const [newTopic, setNewTopic] = useState<string | null>(null);
  // The typed topic being looked up in the slides, over the review.
  const [findingTopic, setFindingTopic] = useState<string | null>(null);
  const treeOnlyRetry = status?.status === "FAILED"
    && status.phase === "SYNTHESIZING"
    && (status.summary?.goalsCreated ?? 0) > 0
    && (status.failedSessions ?? 0) === 0;

  const retryMutation = useMutation({
    mutationFn: async () => {
      if (courseId == null) throw new Error("Course is unavailable.");
      const result = treeOnlyRetry
        ? await api.POST("/api/courses/{courseId}/competency-tree", {
            params: { path: { courseId }, query: { force: false } },
          })
        : await api.POST("/api/courses/{courseId}/extract", {
            params: { path: { courseId }, query: { force: true } },
          });
      if (result.error || !result.data) {
        throw new Error(
          result.response.status === 409
            ? "Another extraction or tree rebuild is already running. Please wait."
            : treeOnlyRetry
              ? "Could not rebuild the competency tree. The extracted goals are still saved."
              : "Could not restart the extraction.",
        );
      }
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["extract-status", courseId] });
      await queryClient.invalidateQueries({ queryKey: ["extraction-current"] });
      await queryClient.invalidateQueries({ queryKey: ["course", courseId] });
      await queryClient.invalidateQueries({ queryKey: ["courses"] });
      await queryClient.invalidateQueries({ queryKey: ["goals", courseId] });
    },
  });

  const done = reviewOnly || status?.status === "SUCCEEDED";
  // The review is a one-way, deliberate step: no backdrop click, no Escape, no ✕. "Done" is the
  // only exit, and it is what records the review as taken care of.
  const reviewLocked = done;
  const goalsQuery = useQuery({
    queryKey: ["goals", courseId],
    queryFn: () => fetchAllGoals(courseId as number),
    enabled: open && done && courseId != null,
  });

  const goals: LearningGoal[] = useMemo(
    () => goalsQuery.data ?? [],
    [goalsQuery.data],
  );
  // The review walks the tree two tiers deep: each topic, and the skills grouped beneath it. A topic
  // is never accepted — it is a noun phrase naming an area of the course, with nothing in it for an
  // instructor to agree or disagree with.
  const topics = useMemo(
    () => buildCompetencyForest(goals).filter((node) => node.role === "topic"),
    [goals],
  );
  const skills = useMemo(() => topics.flatMap(reviewItemsOf), [topics]);

  useEffect(() => {
    if (!open) {
      setConfirmingGoal(null);
      setNewTopic(null);
      retryMutation.reset();
    }
  }, [open]); // eslint-disable-line react-hooks/exhaustive-deps -- reset modal-local mutations on close

  // Escape closes while the run is in flight — the extraction is a background job, so dismissing
  // this view only stops watching it, it never abandons the run. The review step is exempt.
  useEffect(() => {
    if (!open || reviewLocked) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, reviewLocked, onClose]);

  // The review only triages: accept or dismiss. Renaming and adding happen afterwards in the table,
  // so "accepted" keeps one meaning — the generated item was right as it stood. Accepting flips the
  // goal out of PENDING; dismissing has no state of its own, the item is deleted.
  const approveMutation = useMutation({
    mutationFn: async (vars: { goalId: number; approved: boolean }) => {
      const { error: updateError } = await api.PATCH(
        "/api/courses/{courseId}/learning-goals/{goalId}",
        {
          params: { path: { courseId: courseId as number, goalId: vars.goalId } },
          body: { status: vars.approved ? "APPROVED" : "PENDING" },
        },
      );
      if (updateError) throw new Error("Could not update the skill.");
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["goals", courseId] }),
  });

  // Accepts every skill of one topic that is still pending, one PATCH per skill. The topic itself is
  // never accepted — it only groups the skills.
  const acceptAllMutation = useMutation({
    mutationFn: async (vars: { topicId: number; goalIds: number[] }) => {
      const results = await Promise.all(
        vars.goalIds.map((goalId) =>
          api.PATCH("/api/courses/{courseId}/learning-goals/{goalId}", {
            params: { path: { courseId: courseId as number, goalId } },
            body: { status: "APPROVED" },
          }),
        ),
      );
      if (results.some((result) => result.error)) {
        throw new Error("Could not accept every skill of this topic.");
      }
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: ["goals", courseId] }),
  });

  const deleteGoalMutation = useMutation({
    mutationFn: async (goalId: number) => {
      const { error: deleteError } = await api.DELETE(
        "/api/courses/{courseId}/learning-goals/{goalId}",
        { params: { path: { courseId: courseId as number, goalId } } },
      );
      if (deleteError) throw new Error("Could not remove it.");
    },
    onSuccess: async () => {
      setConfirmingGoal(null);
      await queryClient.invalidateQueries({ queryKey: ["goals", courseId] });
      await queryClient.invalidateQueries({ queryKey: ["course", courseId] });
      await queryClient.invalidateQueries({ queryKey: ["courses"] });
    },
  });

  // A topic the run missed can be named here, before the review closes. It opens straight away, so
  // skills generated beneath it come up for review like every other skill.
  const createTopicMutation = useMutation({
    mutationFn: (vars: { text: string; generate: boolean }) =>
      createTopic(courseId as number, vars.text, vars.generate),
    onSuccess: async (topic) => {
      setNewTopic(null);
      const topicId = topic.id;
      if (topicId != null) setExpandedTopics((current) => new Set(current).add(topicId));
      await queryClient.invalidateQueries({ queryKey: ["goals", courseId] });
      await queryClient.invalidateQueries({ queryKey: ["course", courseId] });
      await queryClient.invalidateQueries({ queryKey: ["courses"] });
    },
  });
  const submitNewTopic = (generate: boolean) => {
    const text = (newTopic ?? "").trim();
    if (text !== "") createTopicMutation.mutate({ text, generate });
  };

  if (!open) return null;

  const failed = error != null || status?.status === "FAILED";
  const running = !done && !failed;

  const total = status?.total ?? 0;
  const completed = status?.completed ?? 0;
  const percent = status?.percent ?? 0;
  // Sessions the run dropped. Zero when reopened via reviewOnly: the tracker is in-memory, so the
  // durable record of a thinned-out run is the extraction_run audit row, not this screen.
  const failedSessions = status?.failedSessions ?? 0;
  const failedSessionNames = status?.failedSessionNames ?? [];
  // Index of the phase the backend currently reports; -1 until the first poll lands ("Starting…").
  const activeIndex = status?.phase
    ? EXTRACTION_PHASES.findIndex((p) => p.key === status.phase)
    : -1;

  const accepted = skills.filter((skill) => skill.goal.status === "APPROVED").length;

  const title = done
    ? "Review your skills"
    : failed
      ? "Analysis failed"
      : "Analyzing course materials";
  const subtitle = done
    ? "Accept or dismiss each skill we extracted, grouped by topic."
    : failed
      ? null
      : "This runs once per upload. You can review and adjust everything afterwards.";

  const closeButton = (
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
  );

  return (
    <div
      className="fixed inset-0 z-50"
      role="dialog"
      aria-modal="true"
      aria-labelledby="extraction-progress-title"
    >
      {/* An opaque scrim rather than a backdrop-blur, matching the create-course and goal dialogs:
          blurring the page through this layer repaints all of it every frame. */}
      <div aria-hidden="true" className="absolute inset-0 bg-hestia-bg/90" />
      <div
        onClick={reviewLocked ? undefined : onClose}
        className="absolute inset-0 flex items-start justify-center overflow-y-auto p-4 sm:p-8"
      >
        {/* One animation for the whole panel rather than per card, as in the other two dialogs. */}
        <div
          onClick={(e) => e.stopPropagation()}
          className={`comp-unfold flex w-full flex-col gap-3.5 sm:mt-[6vh] ${
            "max-w-2xl"
          }`}
        >
          {/* The header spans both columns, so the close button never rides on one of them. */}
          <div className="flex items-start justify-between gap-2">
            <div className="flex min-w-0 items-center gap-2.5">
              <img
                src={flame}
                alt=""
                className={`h-6 w-6 shrink-0 ${running ? "animate-pulse" : ""}`}
              />
              <div className="flex min-w-0 flex-col gap-1">
                <span
                  id="extraction-progress-title"
                  className="text-xs font-semibold uppercase tracking-wider text-hestia-text"
                >
                  {title}
                </span>
                {subtitle && <p className="text-xs text-hestia-text-muted">{subtitle}</p>}
              </div>
            </div>
            {!reviewLocked && closeButton}
          </div>

          {running && (
            <div className="flex flex-col gap-4 rounded-lg border border-hestia-border bg-hestia-surface p-4 shadow-lg">
              <div className="h-1.5 w-full overflow-hidden rounded-full bg-hestia-primary-muted">
                {total > 0 ? (
                  <div
                    className="h-full rounded-full bg-hestia-primary transition-[width] duration-500 ease-out"
                    style={{ width: `${percent}%` }}
                  />
                ) : (
                  <div className="progress-sweep h-full rounded-full bg-hestia-primary" />
                )}
              </div>
              <ol className="flex flex-col gap-3">
                {EXTRACTION_PHASES.map((phase, i) => {
                  const state =
                    activeIndex < 0
                      ? i === 0
                        ? "active"
                        : "pending"
                      : i < activeIndex
                        ? "done"
                        : i === activeIndex
                          ? "active"
                          : "pending";
                  return (
                    <li key={phase.key} className="flex items-center gap-3">
                      <PhaseTick state={state} index={i} />
                      <span
                        className={`text-sm ${
                          state === "active"
                            ? "font-medium text-hestia-text"
                            : state === "done"
                              ? "text-hestia-text-muted"
                              : "text-hestia-text-muted/60"
                        }`}
                      >
                        {phase.label}
                      </span>
                      {state === "active" && total > 0 && (
                        <span className="ml-auto tabular-nums text-xs text-hestia-text-muted">
                          {completed}/{total}
                        </span>
                      )}
                    </li>
                  );
                })}
              </ol>
            </div>
          )}

          {done && (
            <div className="flex w-full flex-col gap-3.5 lg:flex-row lg:items-stretch">
              <div className="flex w-full min-w-0 flex-1 flex-col gap-3.5">
                {failedSessions > 0 && (
                  <p className="rounded-lg border border-hestia-warning/40 bg-hestia-warning/10 px-4 py-3 text-sm text-hestia-text shadow-lg">
                    <span aria-hidden="true">⚠ </span>
                    {failedSessions === 1
                      ? "One session could not be analysed and contributed no skills."
                      : `${failedSessions} sessions could not be analysed and contributed no skills.`}{" "}
                    You can add anything that is missing afterwards in the table.
                  </p>
                )}

                <div className="flex flex-col rounded-lg border border-hestia-border bg-hestia-surface shadow-lg">
                  <div className="flex items-center justify-between gap-3 border-b border-hestia-border px-4 py-3">
                    <span className="text-xs font-semibold uppercase tracking-wider text-hestia-text-muted">
                      Skills by topic
                    </span>
                    {skills.length > 0 && (
                      <span className="tabular-nums text-xs text-hestia-text-muted">
                        {accepted} of {skills.length} accepted
                      </span>
                    )}
                  </div>
                  <div className="max-h-[60vh] overflow-y-auto">
                    {goalsQuery.isLoading && (
                      <p className="px-4 py-6 text-center text-sm text-hestia-text-muted">
                        Loading skills…
                      </p>
                    )}
                    {goalsQuery.isError && (
                      <p className="px-4 py-6 text-center text-sm text-hestia-danger">
                        {(goalsQuery.error as Error).message}
                      </p>
                    )}
                    {!goalsQuery.isLoading && !goalsQuery.isError && topics.length === 0 && (
                      <p className="px-4 py-6 text-center text-sm text-hestia-text-muted">
                        No skills were extracted for this course.
                      </p>
                    )}
                    {topics.length > 0 && (
                      <ul className="divide-y divide-hestia-border">
                        {topics.map((topic, topicIndex) => {
                          const topicId = topic.goal.id;
                          const topicSkills = topic.children.filter(
                            (child) => child.role === "capability",
                          );
                          const reviewItems = reviewItemsOf(topic);
                          const pendingSkillIds = reviewItems
                            .filter((item) => item.goal.status !== "APPROVED")
                            .map((item) => item.goal.id)
                            .filter((id): id is number => id != null);
                          const expanded = topicId == null || expandedTopics.has(topicId);
                          return (
                            <li key={topicId ?? topic.goal.text}>
                              <TopicHeader
                                topic={topic}
                                number={`${topicIndex + 1}`}
                                skillCount={reviewItems.length}
                                groupedSkillCount={topicSkills.length}
                                subSkillCount={topicSkills.length === 0 ? reviewItems.length : 0}
                                pendingCount={pendingSkillIds.length}
                                acceptingAll={
                                  acceptAllMutation.isPending
                                  && acceptAllMutation.variables?.topicId === topicId
                                }
                                onAcceptAll={() => {
                                  if (topicId != null && pendingSkillIds.length > 0) {
                                    acceptAllMutation.mutate({ topicId, goalIds: pendingSkillIds });
                                  }
                                }}
                                expanded={expanded}
                                confirming={topicId != null && confirmingGoal === topicId}
                                dismissing={
                                  deleteGoalMutation.isPending
                                  && deleteGoalMutation.variables === topicId
                                }
                                onToggle={() => {
                                  if (topicId == null) return;
                                  setExpandedTopics((current) => {
                                    const next = new Set(current);
                                    if (next.has(topicId)) next.delete(topicId);
                                    else next.add(topicId);
                                    return next;
                                  });
                                }}
                                onDismiss={() => setConfirmingGoal(topicId ?? null)}
                                onCancelDismiss={() => setConfirmingGoal(null)}
                                onConfirmDismiss={() => {
                                  if (topicId != null) deleteGoalMutation.mutate(topicId);
                                }}
                              />
                              {expanded && (
                                <ul className="pb-2">
                                  {reviewItems.map((skill) => (
                                    <SkillRow
                                      key={skill.goal.id ?? skill.goal.text}
                                      skill={skill}
                                      number={`${topicIndex + 1}.${topic.children.indexOf(skill) + 1}`}
                                      accepting={
                                        approveMutation.isPending
                                        && approveMutation.variables?.goalId === skill.goal.id
                                      }
                                      confirming={
                                        skill.goal.id != null && confirmingGoal === skill.goal.id
                                      }
                                      dismissing={
                                        deleteGoalMutation.isPending
                                        && deleteGoalMutation.variables === skill.goal.id
                                      }
                                      onAccept={(approved) => {
                                        if (skill.goal.id != null) {
                                          approveMutation.mutate({ goalId: skill.goal.id, approved });
                                        }
                                      }}
                                      onDismiss={() => setConfirmingGoal(skill.goal.id ?? null)}
                                      onCancelDismiss={() => setConfirmingGoal(null)}
                                      onConfirmDismiss={() => {
                                        if (skill.goal.id != null) deleteGoalMutation.mutate(skill.goal.id);
                                      }}
                                    />
                                  ))}
                                </ul>
                              )}
                            </li>
                          );
                        })}
                      </ul>
                    )}
                  </div>
                  {!goalsQuery.isLoading && !goalsQuery.isError && courseId != null && (
                    <div className="border-t border-hestia-border px-4 py-3">
                      {newTopic != null ? (
                        <CompetencyCreationField
                          value={newTopic}
                          placeholder="Name a topic that is missing…"
                          error={
                            createTopicMutation.isError
                              ? (createTopicMutation.error as Error).message
                              : undefined
                          }
                          pending={createTopicMutation.isPending}
                          onChange={(value) => {
                            if (createTopicMutation.isError) createTopicMutation.reset();
                            setNewTopic(value);
                          }}
                          onSubmit={() => submitNewTopic(false)}
                          onCancel={() => {
                            if (createTopicMutation.isPending) return;
                            createTopicMutation.reset();
                            setNewTopic(null);
                          }}
                          onGenerate={() => submitNewTopic(true)}
                          generating={createTopicMutation.variables?.generate === true}
                          onFind={() => {
                            const text = newTopic.trim();
                            if (text !== "") setFindingTopic(text);
                          }}
                        />
                      ) : (
                        <button
                          type="button"
                          onClick={() => setNewTopic("")}
                          className="inline-flex items-center gap-1.5 text-sm font-medium text-hestia-primary transition hover:underline"
                        >
                          <span aria-hidden="true">+</span>
                          Add topic
                        </button>
                      )}
                    </div>
                  )}
                </div>

                <div className="flex flex-col gap-3 rounded-lg border border-hestia-border bg-hestia-surface p-4 shadow-lg">
                  {acceptAllMutation.isError && (
                    <p className="text-sm text-hestia-danger">
                      {(acceptAllMutation.error as Error).message}
                    </p>
                  )}
                  {approveMutation.isError && (
                    <p className="text-sm text-hestia-danger">
                      {(approveMutation.error as Error).message}
                    </p>
                  )}
                  {deleteGoalMutation.isError && (
                    <p className="text-sm text-hestia-danger">
                      {(deleteGoalMutation.error as Error).message}
                    </p>
                  )}
                  <div className="flex flex-wrap items-center justify-between gap-3">
                    <p className="text-xs text-hestia-text-muted">
                      Rename or add skills afterwards in the table.
                    </p>
                    <Button size="lg" onClick={onClose}>
                      Done
                    </Button>
                  </div>
                </div>
              </div>
            </div>
          )}

          {failed && (
            <div className="flex flex-col gap-4 rounded-lg border border-hestia-border bg-hestia-surface p-4 shadow-lg">
              <div className="rounded-md border border-hestia-danger/40 bg-hestia-danger/10 px-3 py-3 text-sm text-hestia-danger">
                <p className="font-medium">
                  {failedSessions > 0
                    ? failedSessions === 1
                      ? "One session could not be analysed. No partial skill tree was saved."
                      : `${failedSessions} sessions could not be analysed. No partial skill tree was saved.`
                    : error ?? "Extraction failed."}
                </p>
                {failedSessionNames.length > 0 && (
                  <ul className="mt-2 list-disc space-y-1 pl-5 text-xs">
                    {failedSessionNames.map((name) => <li key={name}>{name}</li>)}
                  </ul>
                )}
              </div>
              {retryMutation.isError && (
                <p className="text-sm text-hestia-danger">
                  {(retryMutation.error as Error).message}
                </p>
              )}
              <div className="flex justify-end gap-2">
                <Button variant="ghost" size="lg" onClick={onClose}>
                  Close
                </Button>
                <Button
                  size="lg"
                  onClick={() => retryMutation.mutate()}
                  disabled={retryMutation.isPending || courseId == null}
                >
                  {retryMutation.isPending
                    ? treeOnlyRetry ? "Rebuilding tree…" : "Retrying…"
                    : treeOnlyRetry ? "Retry competency tree" : "Retry extraction"}
                </Button>
              </div>
            </div>
          )}
        </div>
      </div>
      {findingTopic != null && courseId != null && (
        <TopicSearchDialog
          courseId={courseId}
          topic={findingTopic}
          onClose={() => setFindingTopic(null)}
          onCreated={(topic) => {
            setFindingTopic(null);
            setNewTopic(null);
            const topicId = topic.id;
            if (topicId != null) setExpandedTopics((current) => new Set(current).add(topicId));
          }}
        />
      )}
    </div>
  );
}

/**
 * A topic's row: its name, how many skills it groups, and what an instructor can do to a topic —
 * accept all of its skills at once, or dismiss it. Dismissing deletes everything beneath the topic
 * with it, so it asks once more in place. Clicking the name folds the group.
 */
function TopicHeader({
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
}: {
  topic: CompetencyNode;
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
}) {
  const groupedSkillWord = groupedSkillCount === 1 ? "1 skill" : `${groupedSkillCount} skills`;
  const countLabel = groupedSkillCount === 0 && subSkillCount > 0
    ? subSkillCount === 1 ? "1 sub-skill" : `${subSkillCount} sub-skills`
    : groupedSkillWord;

  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-2 bg-hestia-bg/40 px-4 py-3">
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={expanded}
        className="flex min-w-0 flex-1 items-center gap-3 text-left"
      >
        <svg
          viewBox="0 0 20 20"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
          className={`h-3.5 w-3.5 shrink-0 text-hestia-text-muted transition-transform ${
            expanded ? "rotate-90" : ""
          }`}
        >
          <path d="M7.5 5l5 5-5 5" />
        </svg>
        <span
          aria-hidden="true"
          className="h-2.5 w-2.5 shrink-0 rounded-full"
          style={{ backgroundColor: COMPETENCY_ROLE_META.topic.color }}
        />
        <span className="min-w-0 flex-1 text-sm font-semibold leading-relaxed text-hestia-text">
          <span className="mr-1 tabular-nums text-hestia-text-muted">{number}.</span>
          {topic.goal.text}
        </span>
        <span className="shrink-0 tabular-nums text-xs text-hestia-text-muted">{countLabel}</span>
      </button>
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
          {skillCount > 0 && (pendingCount > 0 ? (
            <Button size="sm" disabled={acceptingAll} onClick={onAcceptAll}>
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
 * What a topic puts up for review: its skills, or — for a topic the structuring left without skills,
 * which would otherwise show nothing — the sub-skills directly beneath it.
 */
function reviewItemsOf(topic: CompetencyNode): CompetencyNode[] {
  const skills = topic.children.filter((child) => child.role === "capability");
  return skills.length > 0 ? skills : topic.children.filter((child) => child.role === "skill");
}

/**
 * One skill under its topic, or a sub-skill standing in for a topic without skills: Accept marks it
 * reviewed, Dismiss deletes it after asking once more in place, like a topic.
 */
function SkillRow({
  skill,
  number,
  accepting,
  confirming,
  dismissing,
  onAccept,
  onDismiss,
  onCancelDismiss,
  onConfirmDismiss,
}: {
  skill: CompetencyNode;
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
}) {
  const approved = skill.goal.status === "APPROVED";
  const noun = COMPETENCY_ROLE_META[skill.role].label.toLowerCase();
  // Extraction leaves this null, so a tag here means the skill did not come out of the materials.
  const provenance = skill.goal.creationProvenance;
  const provenanceLabel = provenance === "USER_CREATED"
    ? "Added by you"
    : provenance === "WIZARD_AI_SUBTREE"
      ? "AI added"
      : null;
  return (
    <li
      className={`flex items-start gap-3 py-2.5 pl-12 pr-4 transition-colors ${
        approved ? "bg-hestia-primary-muted/20" : ""
      }`}
    >
      <span
        aria-hidden="true"
        className="mt-1.5 h-2 w-2 shrink-0 rounded-full"
        style={{ backgroundColor: COMPETENCY_ROLE_META[skill.role].color }}
      />
      <span className="min-w-0 flex-1 text-sm leading-relaxed text-hestia-text">
        <span className="mr-1 tabular-nums text-hestia-text-muted">{number}.</span>
        {skill.goal.text}
        {skill.role === "skill" && (
          <span className="ml-2 whitespace-nowrap rounded-full border border-hestia-border px-2 py-0.5 text-[0.65rem] font-semibold uppercase tracking-wider text-hestia-text-muted">
            {COMPETENCY_ROLE_META.skill.label}
          </span>
        )}
        {provenanceLabel && (
          <span className="ml-2 whitespace-nowrap rounded-full border border-hestia-border px-2 py-0.5 text-[0.65rem] font-semibold uppercase tracking-wider text-hestia-text-muted">
            {provenanceLabel}
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
            <Button size="sm" disabled={accepting} onClick={() => onAccept(true)}>
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

function PhaseTick({
  state,
  index,
}: {
  state: "done" | "active" | "pending";
  index: number;
}) {
  if (state === "done") {
    return (
      <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-hestia-primary text-hestia-on-primary">
        <svg viewBox="0 0 20 20" fill="currentColor" className="h-3 w-3">
          <path
            fillRule="evenodd"
            d="M16.7 5.3a1 1 0 010 1.4l-7.5 7.5a1 1 0 01-1.4 0l-3.5-3.5a1 1 0 011.4-1.4l2.8 2.8 6.8-6.8a1 1 0 011.4 0z"
            clipRule="evenodd"
          />
        </svg>
      </span>
    );
  }
  if (state === "active") {
    return (
      <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full border-[1.5px] border-hestia-primary text-xs font-semibold tabular-nums text-hestia-primary ring-2 ring-hestia-primary-muted">
        {index + 1}
      </span>
    );
  }
  return (
    <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full border-[1.5px] border-hestia-border text-xs font-semibold tabular-nums text-hestia-text-muted/60">
      {index + 1}
    </span>
  );
}
