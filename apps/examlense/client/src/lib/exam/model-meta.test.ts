import { describe, expect, it } from "vitest";

import { MODEL_META } from "./model-meta";

describe("MODEL_META", () => {
  it("uses bundled assets for every mapped model logo", () => {
    for (const meta of Object.values(MODEL_META)) {
      expect(meta.logoSrc).toBeTruthy();
      expect(meta.logoSrc).not.toMatch(/^https?:\/\//);
    }
  });

  it("reuses the Gemini asset across model versions", () => {
    expect(MODEL_META["gemini-3.5-flash"].logoSrc).toBe(
      MODEL_META["gemini-2.5-flash"].logoSrc,
    );
  });
});
