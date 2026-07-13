import { describe, it, expect } from "vitest";
import { resilienceScore, resilienceTier } from "@/lib/resilience";

describe("resilienceScore", () => {
  it("inverts the AI percentage", () => {
    expect(resilienceScore(85)).toBe(15);
    expect(resilienceScore(0)).toBe(100);
    expect(resilienceScore(100)).toBe(0);
  });

  it("rounds and clamps to 0–100", () => {
    expect(resilienceScore(49.6)).toBe(50);
    expect(resilienceScore(120)).toBe(0);
    expect(resilienceScore(-5)).toBe(100);
  });
});

describe("resilienceTier", () => {
  it("is safe below 50% AI score", () => {
    expect(resilienceTier(0).tier).toBe("safe");
    expect(resilienceTier(49).tier).toBe("safe");
  });

  it("is endangered from 50% through 80% AI score", () => {
    expect(resilienceTier(50).tier).toBe("endangered");
    expect(resilienceTier(80).tier).toBe("endangered");
  });

  it("is critical above 80% AI score", () => {
    expect(resilienceTier(81).tier).toBe("critical");
    expect(resilienceTier(100).tier).toBe("critical");
  });

  it("states the rounded AI percentage in the blurb", () => {
    expect(resilienceTier(84.7).blurb).toContain("85%");
  });
});
