import { describe, it, expect } from "vitest";
import type { ExamListItem } from "./api-client";
import { examProgress, progressSortValue } from "./exam-progress";

const exam = (over: Partial<ExamListItem>): ExamListItem => ({
  id: "e1",
  title: "Exam",
  course: null,
  semester: null,
  instructor_name: null,
  total_points: null,
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

describe("progressSortValue", () => {
  it("sorts failed/empty below real progress", () => {
    expect(progressSortValue(exam({ status: "failed" }))).toBe(-1);
    expect(progressSortValue(exam({ status: "finished" }))).toBe(100);
    expect(
      progressSortValue(exam({ status: "grading", task_count: 2, graded_count: 1 })),
    ).toBe(50);
  });
});
