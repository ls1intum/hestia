import { api } from "../api/client.ts";
import type { LearningGoal } from "../api/client.ts";

const PAGE_SIZE = 500;

/**
 * Fetches every learning goal of a course, following pagination.
 *
 * Terminal skills are written last by tree synthesis, so they sit at the very end of the list. A
 * single fixed-size request silently drops them once a course grows past that size, which makes the
 * competency tree look like it was never built and leaves the skill review empty. Shared by every
 * caller of the `["goals", courseId]` query so they cannot disagree about what "all goals" means.
 */
export async function fetchAllGoals(courseId: number): Promise<LearningGoal[]> {
  const first = await api.GET("/api/courses/{courseId}/learning-goals", {
    params: { path: { courseId }, query: { size: PAGE_SIZE } },
  });
  if (first.error || !first.data) throw new Error("Could not load learning goals.");

  const total = first.data.page?.totalElements ?? first.data.content?.length ?? 0;
  const goals = [...(first.data.content ?? [])];
  for (let page = 1; goals.length < total; page += 1) {
    const next = await api.GET("/api/courses/{courseId}/learning-goals", {
      params: { path: { courseId }, query: { size: PAGE_SIZE, page } },
    });
    if (next.error || !next.data?.content?.length) break;
    goals.push(...next.data.content);
  }
  return goals;
}
