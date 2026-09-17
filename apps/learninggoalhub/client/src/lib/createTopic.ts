import { api } from "../api/client.ts";
import type { LearningGoal } from "../api/client.ts";

/**
 * Creates a topic an instructor named. By default only the topic itself is created; with
 * `generate`, an AI also writes skills, sub-skills and knowledge beneath it, without sources.
 * Shared by the table and the review so both report the same errors.
 */
export async function createTopic(
  courseId: number,
  text: string,
  generate: boolean,
): Promise<LearningGoal> {
  const result = generate
    ? await api.POST("/api/courses/{courseId}/learning-goals/terminal/generated", {
        params: { path: { courseId } },
        body: { text },
      })
    : await api.POST("/api/courses/{courseId}/learning-goals/terminal", {
        params: { path: { courseId } },
        body: { text },
      });
  if (!result.data) {
    throw new Error(
      result.response.status === 409
        ? "A topic with that wording already exists."
        : generate
          ? "Could not generate skills for this topic."
          : "Could not add the topic.",
    );
  }
  return result.data;
}
