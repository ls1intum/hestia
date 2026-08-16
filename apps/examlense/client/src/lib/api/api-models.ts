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
} from "@/lib/api/api-client";
import {
  DEFAULT_SOLVER_MODEL_ID,
  SOLVER_MODELS,
  type LlmModel,
} from "@/lib/exam/llm-models";

interface ModelListResponse {
  models: Array<{ id: string; label: string; description?: string | null }>;
  defaultId: string;
}

export interface ResolvedModelCatalog {
  models: LlmModel[];
  defaultId: string;
  /** "server" = served by Spring Boot, "fallback" = bundled in the client. */
  source: "server" | "fallback";
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
    const serverModels = resp.models.map((m) => ({
      id: m.id,
      label: m.label,
      description: m.description ?? undefined,
    }));
    const models = preserveFallbackModels
      ? mergeWithFallbackModels(serverModels, fallback)
      : serverModels;
    const defaultId = preserveFallbackModels
      ? fallbackDefault
      : models.some((m) => m.id === resp.defaultId)
        ? resp.defaultId
        : fallbackDefault;
    return {
      models,
      defaultId,
      source: "server",
    };
  } catch (err) {
    if (err instanceof ApiClientNotConfiguredError) {
      return { models: fallback, defaultId: fallbackDefault, source: "fallback" };
    }
    throw err;
  }
}

function mergeWithFallbackModels(
  serverModels: LlmModel[],
  fallbackModels: LlmModel[],
): LlmModel[] {
  const serverById = new Map(serverModels.map((m) => [m.id, m]));
  const merged = fallbackModels.map((fallback) => serverById.get(fallback.id) ?? fallback);
  for (const serverModel of serverModels) {
    if (!fallbackModels.some((fallback) => fallback.id === serverModel.id)) {
      merged.push(serverModel);
    }
  }
  return merged;
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
