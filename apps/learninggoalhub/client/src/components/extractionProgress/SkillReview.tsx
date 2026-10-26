import { useMemo, useState, type Dispatch, type SetStateAction } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, type ExtractionStatus, type LearningGoal } from "../../api/client.ts";
import { fetchAllGoals } from "../../lib/fetchGoals.ts";
import { createTopic } from "../../lib/createTopic.ts";
import { buildCompetencyForest } from "../../lib/goals.ts";
import Button from "../Button.tsx";
import CompetencyCreationField from "../CompetencyCreationField.tsx";
import { TopicHeader, SkillRow } from "./ReviewRows.tsx";
import { reviewItemsOf } from "./review.ts";

/**
 * The skill review after a run: every topic with its skills to accept, rename or dismiss, a field to
 * add a topic the run missed, and what the run left out. The topics folded open and the typed topic
 * are kept by the modal, which also hosts the search for a typed topic in the slides.
 */
export function SkillReview({
  courseId,
  status,
  expandedTopics,
  setExpandedTopics,
  newTopic,
  setNewTopic,
  onFindTopic,
  onDone,
}: {
  courseId?: number | null;
  status?: ExtractionStatus;
  expandedTopics: Set<number>;
  setExpandedTopics: Dispatch<SetStateAction<Set<number>>>;
  newTopic: string;
  setNewTopic: (text: string) => void;
  onFindTopic: (text: string) => void;
  onDone: () => void;
}) {
  const queryClient = useQueryClient();
  // The topic or skill whose dismissal waits for a second click.
  const [confirmingGoal, setConfirmingGoal] = useState<number | null>(null);
  // The skill whose name is being edited in place.
  const [renamingGoal, setRenamingGoal] = useState<number | null>(null);
  const goalsQuery = useQuery({
    queryKey: ["goals", courseId],
    queryFn: () => fetchAllGoals(courseId as number),
    enabled: courseId != null,
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

  // The review triages: accept, rename or dismiss. Accepting flips the goal out of PENDING;
  // dismissing has no state of its own, the item is deleted.
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

  // Renaming a skill accepts it in its new wording; a topic is renamed only, it is never accepted.
  // The server keeps the generated wording on the first rename, so the evaluation still tells a
  // reworded item from one accepted as it stood.
  const renameMutation = useMutation({
    mutationFn: async (vars: { goalId: number; text: string; accept: boolean }) => {
      const { error: updateError } = await api.PATCH(
        "/api/courses/{courseId}/learning-goals/{goalId}",
        {
          params: { path: { courseId: courseId as number, goalId: vars.goalId } },
          body: vars.accept ? { text: vars.text, status: "APPROVED" } : { text: vars.text },
        },
    );
      if (updateError) throw new Error("Could not rename it.");
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
      setNewTopic("");
      const topicId = topic.id;
      if (topicId != null) setExpandedTopics((current) => new Set(current).add(topicId));
      await queryClient.invalidateQueries({ queryKey: ["goals", courseId] });
      await queryClient.invalidateQueries({ queryKey: ["course", courseId] });
      await queryClient.invalidateQueries({ queryKey: ["courses"] });
    },
  });
  const submitNewTopic = (generate: boolean) => {
    const text = newTopic.trim();
    if (text !== "") createTopicMutation.mutate({ text, generate });
};

  // What the run left out. A review opened later reads it from the durable run record.
  const failedSessions = status?.failedSessions ?? 0;
  const droppedOutcomes = status?.droppedOutcomes ?? 0;
  const accepted = skills.filter((skill) => skill.goal.status === "APPROVED").length;

  return (
    <div className="flex w-full flex-col gap-4 lg:flex-row lg:items-stretch">
      <div className="flex w-full min-w-0 flex-1 flex-col gap-4">
        {(failedSessions > 0 || droppedOutcomes > 0) && (
          // The table's warning tint: the warning hue is too light for text, so the text
          // stays in the text colour and only the icon carries the hue.
          <p className="flex items-start gap-2.5 rounded-xl bg-[color-mix(in_srgb,var(--hestia-warning)_22%,var(--hestia-surface))] px-4 py-3 text-sm text-hestia-text">
            <svg
              viewBox="0 0 20 20"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
              className="mt-0.5 h-4 w-4 shrink-0 text-[color-mix(in_srgb,var(--hestia-warning)_70%,var(--hestia-text))]"
            >
              <path d="M10 3.5l7 12.5H3z" />
              <path d="M10 8.5v3.5M10 14.2v.01" />
            </svg>
            <span>
            {failedSessions === 1
              ? "One session could not be analysed and contributed no skills. "
              : failedSessions > 1
                ? `${failedSessions} sessions could not be analysed and contributed no skills. `
                : null}
            {droppedOutcomes === 1
              ? "One extracted skill or knowledge item failed validation and was left out. "
              : droppedOutcomes > 1
                ? `${droppedOutcomes} extracted skills or knowledge items failed validation and were left out. `
                : null}
            You can add anything that is missing afterwards in the table.
            </span>
          </p>
        )}

        {/* One card: the column-name band, the topics, adding a topic, and the footer. */}
        <div className="flex flex-col overflow-hidden rounded-xl border border-hestia-border bg-hestia-surface shadow-sm">
          <div className="flex items-center justify-between gap-3 border-b border-hestia-border bg-[color-mix(in_srgb,var(--hestia-text)_4%,var(--hestia-surface))] px-4 py-2">
            <span className="text-xs font-semibold text-hestia-text-muted">
              Skills by topic
            </span>
            {skills.length > 0 && (
              <span className="tabular-nums text-xs text-hestia-text-muted">
                {accepted} of {skills.length} accepted
              </span>
            )}
          </div>
          {/* Sized to the window: the title, the band, the add-topic field and the footer take about
              22rem around it, so the card ends just above the window's bottom edge. */}
          <div className="max-h-[calc(100vh-22rem)] min-h-40 overflow-y-auto">
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
              <ul className="divide-y divide-hestia-border/60">
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
                        renaming={topicId != null && renamingGoal === topicId}
                        onStartRename={() => {
                          renameMutation.reset();
                          setRenamingGoal(topicId ?? null);
                        }}
                        onRename={(text) => {
                          setRenamingGoal(null);
                          if (text != null && topicId != null) {
                            renameMutation.mutate({ goalId: topicId, text, accept: false });
                          }
                        }}
                      />
                      {expanded && (
                        <ul className="border-t border-hestia-border/60">
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
                              renaming={
                                skill.goal.id != null && renamingGoal === skill.goal.id
                              }
                              onStartRename={() => {
                                renameMutation.reset();
                                setRenamingGoal(skill.goal.id ?? null);
                              }}
                              onRename={(text) => {
                                setRenamingGoal(null);
                                if (text != null && skill.goal.id != null) {
                                  renameMutation.mutate({
                                    goalId: skill.goal.id,
                                    text,
                                    accept: true,
                                  });
                                }
                              }}
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
              {/* Always open, so the three ways in are visible without clicking first. */}
              <p className="mb-2 text-sm text-hestia-text-muted">
                <span className="font-semibold text-hestia-text">Missing a topic?</span>{" "}
                Name it, then add it as is, generate its skills with AI, or find it in the
                slides.
              </p>
              <CompetencyCreationField
                value={newTopic}
                placeholder="Name a topic that is missing…"
                autoFocus={false}
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
                onGenerate={() => submitNewTopic(true)}
                generating={createTopicMutation.variables?.generate === true}
                onFind={() => {
                  const text = newTopic.trim();
                  if (text !== "") onFindTopic(text);
                }}
              />
            </div>
          )}
          <div className="flex flex-col gap-3 border-t border-hestia-border bg-[color-mix(in_srgb,var(--hestia-text)_4%,var(--hestia-surface))] px-4 py-3">
          {acceptAllMutation.isError && (
            <p className="text-sm text-hestia-danger">
              {(acceptAllMutation.error as Error).message}
            </p>
          )}
          {renameMutation.isError && (
            <p className="text-sm text-hestia-danger">
              {(renameMutation.error as Error).message}
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
          <div className="flex flex-wrap items-center justify-end gap-3">
            <Button className="h-9" onClick={onDone}>
              Done
            </Button>
          </div>
          </div>
        </div>
      </div>
    </div>
  );
}
