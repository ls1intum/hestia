import { describe, it, expect } from "vitest";
import type { ExamListItem } from "@/lib/api/api-client";
import { examProgress, examJourney, progressSortValue } from "./exam-progress";
import { isParseFailure } from "./exam-helpers";

const exam = (over: Partial<ExamListItem>): ExamListItem => ({
  id: "e1",
  title: "Exam",
  course: null,
  language: "en",
  source: "manual",
  source_file_url: null,
  status: "draft",
  parse_error: null,
  parse_phase: null,
  parser_model: null,
  solver_model: null,
  lgh_course_id: null,
  created_at: "2026-01-01T00:00:00Z",
  updated_at: "2026-01-01T00:00:00Z",
  task_count: 0,
  scored_count: 0,
  answered_count: 0,
  graded_count: 0,
  section_count: 0,
  confirmed_section_count: 0,
  ...over,
});

describe("examProgress", () => {
  it("uses scored tasks for editing statuses", () => {
    expect(examProgress(exam({ status: "draft", task_count: 4, scored_count: 3 })).percent).toBe(75);
    expect(examProgress(exam({ status: "ready", task_count: 2, scored_count: 1 })).percent).toBe(50);
  });

  it("uses graded tasks for grading, solved tasks for evaluating", () => {
    expect(examProgress(exam({ status: "grading", task_count: 4, graded_count: 1 })).percent).toBe(25);
    expect(examProgress(exam({ status: "evaluating", task_count: 4, answered_count: 2 })).percent).toBe(50);
  });

  it("reports 100% for finished and an error state for failed", () => {
    expect(examProgress(exam({ status: "finished" })).percent).toBe(100);
    const failed = examProgress(exam({ status: "failed" }));
    expect(failed.percent).toBeNull();
    expect(failed.state).toBe("error");
  });

  it("derives parsing progress from the parse phase", () => {
    expect(examProgress(exam({ status: "parsing", parse_phase: "extracting" })).percent).toBe(75);
  });

  it("returns null percent when an exam has no tasks yet", () => {
    expect(examProgress(exam({ status: "draft", task_count: 0 })).percent).toBeNull();
  });
});

describe("examJourney", () => {
  it("tracks confirmed sections in draft/ready with a remaining count", () => {
    const j = examJourney(
      exam({ status: "draft", task_count: 4, section_count: 4, confirmed_section_count: 2 }),
    );
    expect(j.kind).toBe("steps");
    expect(j.score).toEqual({ state: "active", value: 50, remaining: 2, total: 4 });
    expect(j.grade.state).toBe("pending");
    expect(j.aiSolving).toBe(false);
    expect(j.primary).toBe("2 to confirm");
    expect(j.detail).toBe(
      "2 of 4 sections confirmed — confirm the remaining sections to start solving.",
    );
  });

  it("singularises the remaining section count", () => {
    const j = examJourney(
      exam({ status: "draft", task_count: 3, section_count: 3, confirmed_section_count: 2 }),
    );
    expect(j.primary).toBe("1 to confirm");
    expect(j.detail).toBe(
      "2 of 3 sections confirmed — confirm the remaining section to start solving.",
    );
  });

  it("reads 'ready to solve' once every section is confirmed", () => {
    const j = examJourney(
      exam({ status: "ready", task_count: 2, section_count: 2, confirmed_section_count: 2 }),
    );
    expect(j.score).toEqual({ state: "active", value: 100, remaining: 0, total: 2 });
    expect(j.primary).toBe("Ready to solve");
    expect(j.detail).toBe("All sections confirmed — start the LLM solving.");
  });

  it("marks the score step done and flags AI solving while evaluating", () => {
    const j = examJourney(
      exam({ status: "evaluating", task_count: 4, answered_count: 1 }),
    );
    expect(j.kind).toBe("evaluating");
    expect(j.score.state).toBe("done");
    expect(j.grade.state).toBe("pending");
    expect(j.aiSolving).toBe(true);
    expect(j.primary).toBe("Solving task 1 of 4…");
    expect(j.detail).toBe("AI is solving — 1 of 4 answered");
  });

  it("grades in grading mode with a remaining count; finish stays pending", () => {
    const j = examJourney(exam({ status: "grading", task_count: 4, graded_count: 1 }));
    expect(j.score.state).toBe("done");
    expect(j.grade).toEqual({ state: "active", value: 25, remaining: 3, total: 4 });
    expect(j.finish.state).toBe("pending");
    expect(j.primary).toBe("3 to grade");
    expect(j.detail).toBe(
      "1 of 4 graded — grade the answers for the remaining tasks.",
    );
  });

  it("checks the grade step once every answer is graded (still grading)", () => {
    const j = examJourney(exam({ status: "grading", task_count: 4, graded_count: 4 }));
    expect(j.grade.state).toBe("done");
    expect(j.finish.state).toBe("pending");
    expect(j.primary).toBe("All graded");
    expect(j.detail).toBe("All answers graded — ready for the final results.");
  });

  it("completes all three steps when finished", () => {
    const j = examJourney(exam({ status: "finished", task_count: 2 }));
    expect(j.score.state).toBe("done");
    expect(j.grade.state).toBe("done");
    expect(j.finish.state).toBe("done");
    expect(j.primary).toBe("Complete");
  });

  it("routes failed / parsing / task-less exams to their own kinds", () => {
    expect(examJourney(exam({ status: "failed" })).kind).toBe("error");
    expect(examJourney(exam({ status: "parsing" })).kind).toBe("parsing");
    // A truly blank exam (no sections, no tasks) shows the dash.
    expect(examJourney(exam({ status: "draft", task_count: 0, section_count: 0 })).kind).toBe("empty");
  });

  it("treats sections-without-tasks as incomplete, not a blank dash", () => {
    const j = examJourney(
      exam({ status: "draft", task_count: 0, section_count: 2 }),
    );
    expect(j.kind).toBe("incomplete");
    expect(j.primary).toBe("No tasks present");
  });

  it("treats a task-containing exam with no sections as incomplete, not ready", () => {
    const j = examJourney(
      exam({ status: "draft", task_count: 15, section_count: 0 }),
    );
    expect(j.kind).toBe("incomplete");
    expect(j.primary).toBe("No sections found");
    // Regression guard: this used to fall through to "Ready to solve".
    expect(j.primary).not.toBe("Ready to solve");
    // A `ready`-status exam with orphaned tasks is likewise incomplete.
    expect(
      examJourney(exam({ status: "ready", task_count: 3, section_count: 0 })).kind,
    ).toBe("incomplete");
  });

  it("still renders the two-step journey when sections exist", () => {
    expect(
      examJourney(
        exam({ status: "draft", task_count: 4, section_count: 2, confirmed_section_count: 1 }),
      ).kind,
    ).toBe("steps");
  });

  it("labels the failure origin (parse vs evaluation) in the tooltip", () => {
    // A PDF that never finalized parsing (no parsed_at) = parse failure — even if
    // tasks were committed before the failure/cancel.
    expect(
      examJourney(exam({ status: "failed", source: "pdf", parsed_at: null, task_count: 4 })).detail,
    ).toBe("Parsing failed");
    // A PDF that parsed (parsed_at set) then failed = evaluation failure.
    expect(
      examJourney(exam({ status: "failed", source: "pdf", parsed_at: "2026-01-01T00:00:00Z" })).detail,
    ).toBe("Evaluation failed");
    // A manual exam can never be a parse failure.
    expect(
      examJourney(exam({ status: "failed", source: "manual" })).detail,
    ).toBe("Evaluation failed");
  });
});

describe("isParseFailure", () => {
  it("is true only for a failed PDF import that never finalized parsing", () => {
    // Never parsed (no parsed_at) — a parse failure even with tasks committed.
    expect(isParseFailure(exam({ status: "failed", source: "pdf", parsed_at: null, task_count: 3 }))).toBe(true);
    // Parsed, then failed while solving = evaluation failure.
    expect(isParseFailure(exam({ status: "failed", source: "pdf", parsed_at: "2026-01-01T00:00:00Z" }))).toBe(false);
    // Manual exams and non-failed exams are never parse failures.
    expect(isParseFailure(exam({ status: "failed", source: "manual" }))).toBe(false);
    expect(isParseFailure(exam({ status: "draft", source: "pdf" }))).toBe(false);
  });
});

describe("progressSortValue", () => {
  it("sorts failed/empty below real progress", () => {
    expect(progressSortValue(exam({ status: "failed" }))).toBe(-1);
    expect(progressSortValue(exam({ status: "finished" }))).toBe(100);
    expect(
      progressSortValue(exam({ status: "grading", task_count: 2, graded_count: 1 })),
    ).toBe(50);
  });
});
