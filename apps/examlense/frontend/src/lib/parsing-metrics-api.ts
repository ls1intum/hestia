/**
 * Data access for admin Parsing Metrics — the single swap point.
 *
 * When the Spring Boot backend is configured (`VITE_API_BASE_URL` set) we read
 * live aggregates from `GET /api/parse-metrics`; otherwise we fall back to the
 * bundled mock so previews still render. Callers only depend on
 * `fetchParsingMetrics()` returning `Promise<ParsingMetrics>`.
 */
import { apiRequest, isApiClientConfigured } from "@/lib/api-client";
import { MOCK_PARSING_METRICS, type ParsingMetrics } from "@/lib/parsing-metrics";

export async function fetchParsingMetrics(): Promise<ParsingMetrics> {
  if (isApiClientConfigured()) {
    return apiRequest<ParsingMetrics>("/api/parse-metrics");
  }
  return MOCK_PARSING_METRICS;
}
