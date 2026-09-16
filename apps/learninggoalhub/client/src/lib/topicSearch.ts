import { api } from "../api/client.ts";
import type { LearningGoal, Schemas } from "../api/client.ts";

export type PagePlan = Schemas["PagePlan"];
export type PageRange = Schemas["PageRange"];
export type TopicProposal = Schemas["ProposalResponse"];
export type ProposedSkill = Schemas["ProposedSkill"];
export type ProposedSubSkill = Schemas["ProposedSubSkill"];

/** The server's `{code, message}` reason for a failed call, falling back to `fallback`. */
function reason(error: unknown, fallback: string): string {
  const message = (error as { message?: unknown } | undefined)?.message;
  return typeof message === "string" && message !== "" ? message : fallback;
}

/** Proposes the page runs of the course's documents that mention `text`. */
export async function findTopicPages(courseId: number, text: string): Promise<PagePlan> {
  const result = await api.POST("/api/courses/{courseId}/learning-goals/topic-search/pages", {
    params: { path: { courseId } },
    body: { text },
  });
  if (!result.data) throw new Error("Could not search the slides.");
  return result.data;
}

/** Reads the chosen pages into a proposal the server holds for thirty minutes. */
export async function readTopicPages(
  courseId: number,
  text: string,
  ranges: PageRange[],
): Promise<TopicProposal> {
  const result = await api.POST("/api/courses/{courseId}/learning-goals/topic-search", {
    params: { path: { courseId } },
    body: { text, ranges },
  });
  if (!result.data) {
    throw new Error(reason(result.error, "Could not read the chosen pages."));
  }
  return result.data;
}

/** Creates the topic with the sub-skills kept from the proposal. */
export async function createTopicFromSlides(
  courseId: number,
  proposalId: string,
  subSkillKeys: string[],
): Promise<LearningGoal> {
  const result = await api.POST(
    "/api/courses/{courseId}/learning-goals/topic-search/{proposalId}",
    { params: { path: { courseId, proposalId } }, body: { subSkillKeys } },
  );
  if (!result.data) {
    const status = result.response.status;
    throw new Error(
      status === 404
        ? "This search has expired. Search the slides again."
        : status === 409
          ? "A topic with that wording already exists."
          : reason(result.error, "Could not create the topic."),
    );
  }
  return result.data;
}
