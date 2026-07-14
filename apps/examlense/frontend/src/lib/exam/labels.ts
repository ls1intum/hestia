/**
 * English display labels for domain enums. These used to live in the i18n
 * catalog (`taskType.*`, `learningGoals.bloom.*`, `learningGoals.solo.*`,
 * `grading.source.*`); the app is English-only, so they are plain constants.
 */
import type { BloomLevel, SoloLevel } from "@/lib/learning-goals/learning-goals";
import type { TaskType } from "@/lib/exam/exam-helpers";

export const TASK_TYPE_LABELS: Record<TaskType, string> = {
  single_choice: "Single Choice",
  multiple_choice: "Multiple Choice",
  text: "Free Text",
};

export const BLOOM_LABELS: Record<BloomLevel, string> = {
  REMEMBER: "Remember",
  UNDERSTAND: "Understand",
  APPLY: "Apply",
  ANALYZE: "Analyze",
  EVALUATE: "Evaluate",
  CREATE: "Create",
};

export const SOLO_LABELS: Record<SoloLevel, string> = {
  PRESTRUCTURAL: "Prestructural",
  UNISTRUCTURAL: "Unistructural",
  MULTISTRUCTURAL: "Multistructural",
  RELATIONAL: "Relational",
  EXTENDED_ABSTRACT: "Extended abstract",
};

/** Grade source badge labels (auto / manual / pending). */
export const GRADE_SOURCE_LABELS: Record<string, string> = {
  auto: "Auto-graded",
  manual: "Manually graded",
  pending: "Needs grading",
};

/** Readable labels for the parser's effective PDF input mode (from `pdf_mode`). */
export const PDF_MODE_LABELS: Record<string, string> = {
  TEXT_ONLY: "Fast Mode",
  PDF_DIRECT: "Direct PDF",
  RASTERIZE: "Page images",
};

export const pdfModeLabel = (mode: string): string =>
  PDF_MODE_LABELS[mode] ?? "Unknown mode";
