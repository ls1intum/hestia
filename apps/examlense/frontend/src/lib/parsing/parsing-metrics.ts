/**
 * Types + bundled mock for the admin Parsing Metrics dashboard.
 *
 * `byModel` is keyed by the parser model ids in `src/lib/llm-models.ts` so the
 * panel can resolve human labels. The mock lives here; the data access (swap
 * point onto a real backend) is in `parsing-metrics-api.ts`.
 */
import { PARSER_MODELS } from "@/lib/exam/llm-models";

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

const MOCK_BY_MODEL: ModelParsingStat[] = [
  { modelId: PARSER_MODELS[0].id, pdfMode: "PDF_DIRECT", total: 142, succeeded: 134, failed: 8, avgMsPerPage: 4_600, avgTokensPerPage: 2_300 },
  { modelId: PARSER_MODELS[1].id, pdfMode: "PDF_DIRECT", total: 63, succeeded: 61, failed: 2, avgMsPerPage: 6_975, avgTokensPerPage: 3_100 },
  { modelId: PARSER_MODELS[2].id, pdfMode: "PDF_DIRECT", total: 38, succeeded: 33, failed: 5, avgMsPerPage: 3_800, avgTokensPerPage: 2_025 },
  { modelId: PARSER_MODELS[2].id, pdfMode: "TEXT_ONLY", total: 24, succeeded: 24, failed: 0, avgMsPerPage: 900, avgTokensPerPage: 1_100 },
  { modelId: PARSER_MODELS[3].id, pdfMode: "RASTERIZE", total: 21, succeeded: 20, failed: 1, avgMsPerPage: 7_875, avgTokensPerPage: 3_700 },
  { modelId: PARSER_MODELS[4].id, pdfMode: "RASTERIZE", total: 57, succeeded: 56, failed: 1, avgMsPerPage: 1_700, avgTokensPerPage: 1_350 },
  { modelId: PARSER_MODELS[4].id, pdfMode: "TEXT_ONLY", total: 31, succeeded: 31, failed: 0, avgMsPerPage: 620, avgTokensPerPage: 780 },
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
