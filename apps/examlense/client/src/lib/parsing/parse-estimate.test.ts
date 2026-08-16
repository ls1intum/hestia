import { describe, it, expect } from "vitest";
import {
  estimateParseSeconds,
  parseCountdown,
  formatRemaining,
} from "./parse-estimate";

describe("estimateParseSeconds", () => {
  it("rounds to a multiple of 5 with a floor of 5", () => {
    expect(estimateParseSeconds(10, false)).toBe(45); // 45
    expect(estimateParseSeconds(10, true)).toBe(30); // 30
    expect(estimateParseSeconds(1, true)).toBe(5); // 3 → floored to 5
  });
});

describe("parseCountdown", () => {
  it("is 0% with full remaining at the start", () => {
    const r = parseCountdown(10, 0); // total 45
    expect(r.percent).toBe(0);
    expect(r.remainingSeconds).toBe(45);
    expect(r.overrun).toBe(false);
  });

  it("tracks elapsed time linearly", () => {
    const r = parseCountdown(10, 22.5); // half of 45
    expect(r.percent).toBe(50);
    expect(r.remainingSeconds).toBe(22.5);
    expect(r.overrun).toBe(false);
  });

  it("caps percent at 95 before completion", () => {
    const r = parseCountdown(10, 44); // 97.7% → capped
    expect(r.percent).toBe(95);
    expect(r.overrun).toBe(false);
  });

  it("marks overrun and floors remaining at 0 past the estimate", () => {
    const r = parseCountdown(10, 100);
    expect(r.percent).toBe(95);
    expect(r.remainingSeconds).toBe(0);
    expect(r.overrun).toBe(true);
  });

  it("clamps negative elapsed to 0", () => {
    const r = parseCountdown(10, -30);
    expect(r.percent).toBe(0);
    expect(r.remainingSeconds).toBe(45);
  });
});

describe("formatRemaining", () => {
  it("shows the finishing tail when overrun", () => {
    expect(formatRemaining(0, true)).toBe("Finishing up…");
  });

  it("shows an almost-done message under 5s", () => {
    expect(formatRemaining(3, false)).toBe("Almost done…");
    expect(formatRemaining(5, false)).toBe("Almost done…");
  });

  it("rounds seconds to a multiple of 5", () => {
    expect(formatRemaining(22.5, false)).toBe("~25 sec left");
    expect(formatRemaining(45, false)).toBe("~45 sec left");
  });

  it("switches to whole minutes at/above a minute", () => {
    expect(formatRemaining(90, false)).toBe("~2 min left");
    expect(formatRemaining(120, false)).toBe("~2 min left");
  });
});
