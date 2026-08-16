import { describe, expect, it } from "vitest";

import { ModelLogo } from "./ModelLogo";

describe("ModelLogo", () => {
  it("falls back to the model id initial when metadata is unavailable", () => {
    const fallback = ModelLogo({ modelId: "unknown-model" });

    expect(fallback.type).toBe("span");
    expect(fallback.props.children).toBe("u");
  });
});
