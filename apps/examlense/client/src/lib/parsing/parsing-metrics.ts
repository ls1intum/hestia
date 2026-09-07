/**
 * Types + bundled mock for the admin Parsing Metrics dashboard.
 *
 * `byModel` is keyed by the parser model ids in `src/lib/exam/llm-models.ts` so
 * the panel can resolve human labels. The mock lives here; the data access (swap
 * point onto a real backend) is in `parsing-metrics-api.ts`.
 */

export interface ModelParsingStat {
  modelId: string;
  /** Effective PDF input mode (`PDF_DIRECT` | `RASTERIZE` | `TEXT_ONLY` | `unknown`). */
  pdfMode: string;
  total: number;
  succeeded: number;
  failed: number;
  /** Pooled Σ duration / Σ pages across this model's parses. */
  avgMsPerPage: number;
  /** Pooled Σ total tokens / Σ pages across this model's parses. */
  avgTokensPerPage: number;
}

export interface ParsingMetrics {
  total: number;
  succeeded: number;
  failed: number;
  avgDurationMs: number;
  p50DurationMs: number;
  p95DurationMs: number;
  byModel: ModelParsingStat[];
}

// Ids are spelled out rather than indexed off PARSER_MODELS: real metric rows
// outlive the catalog, so the mock has to be able to name a retired model
// (qwen3.6, from the removed GWDG endpoint) that the active list no longer holds.
const MOCK_BY_MODEL: ModelParsingStat[] = [
  { modelId: "gemini-3.5-flash", pdfMode: "PDF_DIRECT", total: 142, succeeded: 134, failed: 8, avgMsPerPage: 4_600, avgTokensPerPage: 2_300 },
  { modelId: "claude-opus-4-8", pdfMode: "PDF_DIRECT", total: 38, succeeded: 33, failed: 5, avgMsPerPage: 3_800, avgTokensPerPage: 2_025 },
  { modelId: "gpt-5.5", pdfMode: "PDF_DIRECT", total: 63, succeeded: 61, failed: 2, avgMsPerPage: 6_975, avgTokensPerPage: 3_100 },
  { modelId: "qwen3.6-35b-a3b", pdfMode: "RASTERIZE", total: 57, succeeded: 56, failed: 1, avgMsPerPage: 1_700, avgTokensPerPage: 1_350 },
  { modelId: "mistral-large-3-675b-instruct-2512", pdfMode: "RASTERIZE", total: 31, succeeded: 31, failed: 0, avgMsPerPage: 620, avgTokensPerPage: 780 },
];

export const MOCK_PARSING_METRICS: ParsingMetrics = {
  total: MOCK_BY_MODEL.reduce((s, m) => s + m.total, 0),
  succeeded: MOCK_BY_MODEL.reduce((s, m) => s + m.succeeded, 0),
  failed: MOCK_BY_MODEL.reduce((s, m) => s + m.failed, 0),
  avgDurationMs: 18_900,
  p50DurationMs: 16_200,
  p95DurationMs: 42_700,
  byModel: MOCK_BY_MODEL,
};
