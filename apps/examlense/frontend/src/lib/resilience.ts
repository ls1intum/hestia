/**
 * AI-resilience framing for the results Overview.
 *
 * ExamLense has an AI take the exam, so the "AI score" (percentage of points the
 * AI earned) is a direct measure of how easily the exam can be cheated on. We
 * flip it into a "resilience score" (100 − AI%) so higher = better = the exam
 * holds up, and bucket the AI score into three verdict tiers.
 */

export type ResilienceTier = "safe" | "endangered" | "critical";

export interface ResilienceVerdict {
  tier: ResilienceTier;
  /** Short badge label, e.g. "Critically endangered". */
  tierLabel: string;
  /** Advisory headline for the banner. */
  headline: string;
  /** One-line explainer that states the AI %. */
  blurb: string;
}

/** Resilience score shown to the user: 100 − AI%, clamped to 0–100. */
export const resilienceScore = (aiPct: number): number =>
  Math.max(0, Math.min(100, Math.round(100 - aiPct)));

/**
 * Bucket the AI's score into a verdict. Thresholds (by AI %, higher = worse):
 *   AI < 50   → safe
 *   50–80     → endangered
 *   AI > 80   → critically endangered
 */
export const resilienceTier = (aiPct: number): ResilienceVerdict => {
  const ai = Math.round(aiPct);

  if (ai > 80) {
    return {
      tier: "critical",
      tierLabel: "Critically endangered",
      headline: "This exam is highly vulnerable to AI assistance",
      blurb: `An AI scored ${ai}% of the available points — most questions can be answered by AI alone.`,
    };
  }

  if (ai < 50) {
    return {
      tier: "safe",
      tierLabel: "Safe",
      headline: "This exam holds up well against AI",
      blurb: `An AI scored only ${ai}% of the available points — most of your questions resist AI assistance.`,
    };
  }

  return {
    tier: "endangered",
    tierLabel: "Endangered",
    headline: "This exam is partially vulnerable to AI assistance",
    blurb: `An AI scored ${ai}% of the available points — a meaningful share of questions can be answered by AI.`,
  };
};
