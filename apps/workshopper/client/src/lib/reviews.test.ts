import { describe, expect, it } from "vitest";
import { reviewReasonLabel, reviewTargetKind, reviewTargetName } from "./reviews";

describe("review labels", () => {
  it("names the reason", () => {
    expect(reviewReasonLabel("GOAL_CHANGED")).toBe("Learning goal changed");
    expect(reviewReasonLabel("GOAL_REMOVED")).toBe("Learning goal removed");
  });

  it("names the kind of the target by module and type", () => {
    expect(reviewTargetKind({ module: "TEACHING", targetType: "SESSION" })).toBe("Session");
    expect(reviewTargetKind({ module: "ASSESSMENT", targetType: "TASK" })).toBe("Exam task");
    expect(reviewTargetKind({ module: "MATERIALS", targetType: "DOCUMENT" })).toBe("Document");
  });

  it("uses the label when there is one and the kind otherwise", () => {
    expect(reviewTargetName({ module: "TEACHING", targetType: "SESSION", targetLabel: "Intro" })).toBe("Session: Intro");
    expect(reviewTargetName({ module: "MATERIALS", targetType: "DOCUMENT", targetLabel: null })).toBe("Document");
  });
});
