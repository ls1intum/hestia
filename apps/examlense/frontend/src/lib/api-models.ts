/**
 * React Query hooks for the Spring Boot model-catalog endpoints.
 *
 * When `VITE_API_BASE_URL` is set, these hit /api/parser-models and
 * /api/solver-models. When it isn't (the backend isn't running, CI, offline
 * dev), they fall back to the hardcoded lists in `src/lib/llm-models.ts` so
 * the UI keeps working.
 *
 * Replace `PARSER_MODELS` / `SOLVER_MODELS` imports with `useParserModels()`
 * / `useSolverModels()` to opt a screen into the backend-driven catalog.
 */
import { useQuery } from "@tanstack/react-query";

import {
  apiRequest,
  ApiClientNotConfiguredError,
  isApiClientConfigured,
} from "@/lib/api-client";
import {
  DEFAULT_PARSER_MODEL_ID,
  DEFAULT_SOLVER_MODEL_ID,
  PARSER_MODELS,
  SOLVER_MODELS,
  type LlmModel,
} from "@/lib/llm-models";

interface ModelListResponse {
  models: Array<{ id: string; label: string; description?: string | null }>;
  defaultId: string;
}

export interface ResolvedModelCatalog {
  models: LlmModel[];
  defaultId: string;
  /** "backend" = served by Spring Boot, "fallback" = bundled in the frontend. */
  source: "backend" | "fallback";
}

async function fetchCatalog(
  path: string,
  fallback: LlmModel[],
  fallbackDefault: string,
  preserveFallbackModels = false,
): Promise<ResolvedModelCatalog> {
  if (!isApiClientConfigured()) {
    return { models: fallback, defaultId: fallbackDefault, source: "fallback" };
  }
  try {
    const resp = await apiRequest<ModelListResponse>(path);
    const backendModels = resp.models.map((m) => ({
      id: m.id,
      label: m.label,
      description: m.description ?? undefined,
    }));
    const models = preserveFallbackModels
      ? mergeWithFallbackModels(backendModels, fallback)
      : backendModels;
    const defaultId = preserveFallbackModels
      ? fallbackDefault
      : models.some((m) => m.id === resp.defaultId)
        ? resp.defaultId
        : fallbackDefault;
    return {
      models,
      defaultId,
      source: "backend",
    };
  } catch (err) {
    if (err instanceof ApiClientNotConfiguredError) {
      return { models: fallback, defaultId: fallbackDefault, source: "fallback" };
    }
    throw err;
  }
}

function mergeWithFallbackModels(
  backendModels: LlmModel[],
  fallbackModels: LlmModel[],
): LlmModel[] {
  const backendById = new Map(backendModels.map((m) => [m.id, m]));
  const merged = fallbackModels.map((fallback) => backendById.get(fallback.id) ?? fallback);
  for (const backendModel of backendModels) {
    if (!fallbackModels.some((fallback) => fallback.id === backendModel.id)) {
      merged.push(backendModel);
    }
  }
  return merged;
}

export function useParserModels() {
  return useQuery({
    queryKey: ["api", "parser-models"],
    queryFn: () =>
      fetchCatalog("/api/parser-models", PARSER_MODELS, DEFAULT_PARSER_MODEL_ID),
    staleTime: 5 * 60 * 1000,
    // The catalog rarely changes; keep the bundled list as initial data so
    // dropdowns render instantly even on a cold cache.
    initialData: {
      models: PARSER_MODELS,
      defaultId: DEFAULT_PARSER_MODEL_ID,
      source: "fallback" as const,
    },
  });
}

export function useSolverModels() {
  return useQuery({
    queryKey: ["api", "solver-models"],
    queryFn: () =>
      fetchCatalog(
        "/api/solver-models",
        SOLVER_MODELS,
        DEFAULT_SOLVER_MODEL_ID,
        true,
      ),
    staleTime: 5 * 60 * 1000,
    initialData: {
      models: SOLVER_MODELS,
      defaultId: DEFAULT_SOLVER_MODEL_ID,
      source: "fallback" as const,
    },
  });
}
