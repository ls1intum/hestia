import { describe, expect, it } from "vitest";

import { goalReviewReasonLabel } from "@/lib/learning-goals/learning-goals";
import { toGoalDisplay } from "@/lib/learning-goals/task-goals";

describe("task goal review", () => {
  it("names the reason of a marked goal", () => {
    expect(goalReviewReasonLabel("GOAL_CHANGED")).toBe("Lernziel geändert");
    expect(goalReviewReasonLabel("GOAL_REMOVED")).toBe("Lernziel entfernt");
    expect(goalReviewReasonLabel(null)).toBeNull();
  });

  it("carries the review marking of the link into the display goal", () => {
    const display = toGoalDisplay({
      id: "g1",
      text: "Apply recursion",
      bloom_level: "APPLY",
      solo_level: null,
      course_id: "c1",
      review: "GOAL_CHANGED",
      review_link_id: "l1",
      removed: false,
    });
    expect(display).toMatchObject({ id: "g1", review: "GOAL_CHANGED", reviewLinkId: "l1", courseId: "c1" });
  });
});
