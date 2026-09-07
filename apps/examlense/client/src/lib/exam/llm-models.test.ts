import { describe, expect, it } from "vitest";

import {
  PARSER_MODELS,
  SOLVER_MODELS,
  parserModelLabel,
  solverModelLabel,
} from "./llm-models";
import { MODEL_META } from "./model-meta";

/** Every GWDG id ExamLense ever shipped. Their endpoint has been removed. */
const RETIRED_GWDG_IDS = [
  "qwen3.6-35b-a3b",
  "mistral-large-3-675b-instruct-2512",
  "gemma-4-31b-it",
  "qwen3.5-397b-a17b",
];

describe("GWDG model removal", () => {
  it("offers no GWDG model for parsing or solving", () => {
    const offered = [...PARSER_MODELS, ...SOLVER_MODELS].map((m) => m.id);
    for (const id of RETIRED_GWDG_IDS) {
      expect(offered).not.toContain(id);
    }
  });

  // The admin Parsing Metrics table groups by the raw parser_model on each row,
  // and those rows outlive the catalog. Losing the label turns history into slugs.
  it("still names a retired GWDG model rather than echoing its id", () => {
    expect(parserModelLabel("qwen3.6-35b-a3b")).toBe("Qwen 3.6 35B A3B (GWDG)");
    expect(solverModelLabel("qwen3.6-35b-a3b")).toBe("Qwen 3.6 35B A3B (GWDG)");
    for (const id of RETIRED_GWDG_IDS) {
      expect(parserModelLabel(id)).not.toBe(id);
      expect(solverModelLabel(id)).not.toBe(id);
    }
  });

  it("keeps display metadata so old exams still render a provider logo", () => {
    expect(MODEL_META["qwen3.6-35b-a3b"]?.provider).toBe("GWDG");
    expect(MODEL_META["mistral-large-3-675b-instruct-2512"]?.provider).toBe("GWDG");
  });

  it("falls back to the raw id for a model it has never heard of", () => {
    expect(parserModelLabel("some-future-model")).toBe("some-future-model");
    expect(solverModelLabel("some-future-model")).toBe("some-future-model");
  });
});
