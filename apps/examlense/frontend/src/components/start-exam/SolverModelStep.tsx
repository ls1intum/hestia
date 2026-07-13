import { Check } from "lucide-react";

import { useSolverModels } from "@/lib/api-models";
import { cn } from "@/lib/utils";
import { ModelLogo } from "@/components/ModelLogo";
import {
  SOLVER_MODEL_META as MODEL_META,
  SOLVER_MODEL_ORDER as MODEL_ORDER,
} from "@/lib/solver-model-meta";

interface Props {
  value: string;
  onChange: (id: string) => void;
}

/**
 * Shared step (both flows): choose the LLM that will solve the exam. Locked in
 * for the run once the exam is created.
 */
export const SolverModelStep = ({ value, onChange }: Props) => {
  const { data: solverCatalog } = useSolverModels();
  const solverModels = (solverCatalog?.models ?? [])
    .filter((m) => MODEL_META[m.id])
    .sort((a, b) => MODEL_ORDER.indexOf(a.id) - MODEL_ORDER.indexOf(b.id));

  return (
    <div className="space-y-hestia-2">
      {solverModels.length === 0 ? (
        <p className="rounded-hestia-md border border-hestia-border bg-hestia-bg/40 px-hestia-3 py-hestia-3 text-xs text-hestia-text-muted">
          No solver models available.
        </p>
      ) : (
        <div className="grid gap-hestia-2">
          {solverModels.map((m) => {
            const selected = value === m.id;
            const meta = MODEL_META[m.id] ?? {
              provider: "Model",
              name: m.label,
              logoSrc: "",
            };

            return (
              <button
                key={m.id}
                type="button"
                aria-pressed={selected}
                onClick={() => onChange(m.id)}
                className={cn(
                  "group relative flex min-h-[56px] w-full items-center justify-between gap-hestia-3 rounded-hestia-md border bg-hestia-surface px-hestia-3 py-hestia-2 text-left shadow-hestia-sm transition-all hover:border-hestia-primary/60 hover:shadow-hestia-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-hestia-primary focus-visible:ring-offset-2 focus-visible:ring-offset-hestia-bg",
                  selected
                    ? "border-hestia-primary bg-hestia-primary-muted/20 ring-1 ring-hestia-primary/35"
                    : "border-hestia-border",
                )}
              >
                <span className="flex min-w-0 items-center gap-hestia-2">
                  <span
                    className={cn(
                      "inline-flex h-9 w-12 shrink-0 items-center justify-center rounded-hestia-md border border-hestia-border bg-hestia-bg/70 px-1.5 py-1",
                      selected ? "ring-1 ring-hestia-primary/30" : "",
                    )}
                    aria-hidden="true"
                  >
                    <ModelLogo modelId={m.id} />
                  </span>
                  <span className="block min-w-0">
                    <span className="block truncate text-sm font-semibold text-hestia-text">
                      {meta.name}
                    </span>
                    <span className="mt-0.5 block text-xs font-medium text-hestia-text-muted">
                      {meta.provider}
                    </span>
                  </span>
                </span>
                <span
                  className={cn(
                    "inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-hestia-full border transition-colors",
                    selected
                      ? "border-hestia-primary bg-hestia-primary text-white"
                      : "border-hestia-border text-transparent group-hover:text-hestia-text-muted",
                  )}
                  aria-hidden="true"
                >
                  <Check size={14} />
                </span>
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
};
