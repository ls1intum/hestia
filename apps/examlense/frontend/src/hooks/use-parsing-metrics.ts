import { useQuery } from "@tanstack/react-query";

import { fetchParsingMetrics } from "@/lib/parsing-metrics-api";

export const parsingMetricsKey = ["admin-parsing-metrics"] as const;

/**
 * Admin parsing metrics. Backed by the mock today; see `parsing-metrics-api.ts`
 * for the swap point onto a live aggregation endpoint.
 */
export function useParsingMetrics() {
  return useQuery({
    queryKey: parsingMetricsKey,
    queryFn: fetchParsingMetrics,
    staleTime: 5 * 60 * 1000,
  });
}
