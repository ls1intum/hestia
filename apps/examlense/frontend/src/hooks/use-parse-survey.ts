import { useQuery } from "@tanstack/react-query";

import { adminSurvey, adminSurveyByModel } from "@/lib/api-client";

export interface ParseSurveyRow {
  id: string;
  exam_id: string;
  speed: number | null;
  content_correctness: number | null;
  structure: number | null;
  created_at: string;
}

/** One row per parser model: average survey scores + how many responses backed them. */
export interface ParseSurveyModelRow {
  model_id: string;
  responses: number;
  avg_speed: number | null;
  avg_content_correctness: number | null;
  avg_structure: number | null;
}

export const parseSurveyKey = ["admin-parse-survey"] as const;
export const parseSurveyByModelKey = ["admin-parse-survey-by-model"] as const;

/**
 * Admin parsing-quality survey responses, served by the backend admin endpoint
 * (`GET /api/admin/survey`).
 */
export function useParseSurvey() {
  return useQuery<ParseSurveyRow[]>({
    queryKey: parseSurveyKey,
    queryFn: () => adminSurvey() as Promise<ParseSurveyRow[]>,
    staleTime: 5 * 60 * 1000,
  });
}

/**
 * Survey scores rolled up per parser model (`GET /api/admin/survey-by-model`) —
 * the "which model does the job best" view.
 */
export function useParseSurveyByModel() {
  return useQuery<ParseSurveyModelRow[]>({
    queryKey: parseSurveyByModelKey,
    queryFn: () => adminSurveyByModel() as Promise<ParseSurveyModelRow[]>,
    staleTime: 5 * 60 * 1000,
  });
}
