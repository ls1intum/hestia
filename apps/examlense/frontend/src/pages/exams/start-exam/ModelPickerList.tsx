import { Check } from "lucide-react";

import { ModelLogo } from "@/components/shared/ModelLogo";
import type { LlmModel } from "@/lib/exam/llm-models";
import { MODEL_META, MODEL_ORDER } from "@/lib/exam/model-meta";
import { cn } from "@/lib/utils/utils";

interface ModelPickerListProps {
  /** Raw catalog from `useParserModels()` / `useSolverModels()`. */
  models: LlmModel[];
  /** Whether a given model id is currently selected. */
  selected: (id: string) => boolean;
  /** Called when a model option is activated. */
  onSelect: (id: string) => void;
  /** Message shown when no known models are available. */
  emptyLabel: string;
}

/**
 * Shared model-option list for the create-exam parser (multi-select) and solver
 * (single-select) steps. Selection-shape-agnostic: callers decide what
 * "selected" means and what activating an option does. Filters to models with
 * display metadata and sorts by the canonical `MODEL_ORDER`.
 */
export const ModelPickerList = ({
  models,
  selected,
  onSelect,
  emptyLabel,
}: ModelPickerListProps) => {
  const known = models
    .filter((m) => MODEL_META[m.id])
    .sort((a, b) => MODEL_ORDER.indexOf(a.id) - MODEL_ORDER.indexOf(b.id));

  if (known.length === 0) {
    return (
      <p className="rounded-hestia-md border border-hestia-border bg-hestia-bg/40 px-hestia-3 py-hestia-3 text-xs text-hestia-text-muted">
        {emptyLabel}
      </p>
    );
  }

  return (
    <div className="grid gap-hestia-2">
      {known.map((m) => (
        <ModelOptionButton
          key={m.id}
          model={m}
          selected={selected(m.id)}
          onSelect={() => onSelect(m.id)}
        />
      ))}
    </div>
  );
};

function ModelOptionButton({
  model,
  selected,
  onSelect,
}: {
  model: LlmModel;
  selected: boolean;
  onSelect: () => void;
}) {
  const meta = MODEL_META[model.id] ?? {
    provider: "Model",
    name: model.label,
    logoSrc: "",
  };

  return (
    <button
      type="button"
      aria-pressed={selected}
      onClick={onSelect}
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
          <ModelLogo modelId={model.id} />
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
}
