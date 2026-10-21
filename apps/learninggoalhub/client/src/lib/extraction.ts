import type { ExtractionStatus } from "../api/client.ts";

export type ExtractionPhase = NonNullable<ExtractionStatus["phase"]>;

/**
 * `label` is the full wording used in the modal's phase list; `short` is for the courses-list row,
 * whose status column is only 7rem wide and also has to fit the percentage.
 */
export const EXTRACTION_PHASES: { key: ExtractionPhase; label: string; short: string }[] = [
  { key: "DESCRIBING_FIGURES", label: "Understanding figures", short: "Figures" },
  { key: "EXTRACTING", label: "Extracting learning goals", short: "Extracting" },
  { key: "PERSISTING", label: "Saving learning goals", short: "Saving" },
  { key: "SYNTHESIZING", label: "Building competency tree", short: "Tree" },
];

export function extractionPhaseLabel(phase?: ExtractionPhase): string {
  return EXTRACTION_PHASES.find((item) => item.key === phase)?.label ?? "Starting extraction";
}

export function extractionPhaseShortLabel(phase?: ExtractionPhase): string {
  return EXTRACTION_PHASES.find((item) => item.key === phase)?.short ?? "Starting";
}
