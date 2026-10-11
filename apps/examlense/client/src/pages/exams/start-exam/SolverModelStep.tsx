import { useSolverModels } from "@/lib/api/api-models";
import { thinkingLabel } from "@/lib/exam/llm-models";
import { ModelPickerList } from "./ModelPickerList";

interface Props {
  value: string;
  onChange: (id: string) => void;
}

/**
 * Shared step (both flows): choose the LLM that will solve the exam. Locked in
 * for the run once the exam is created.
 *
 * <p>Reasoning depth is not selectable — every solve runs at the model's own
 * default. It is shown here because the evaluation run records it, so a result
 * can be read back later knowing how the model was configured.
 */
export const SolverModelStep = ({ value, onChange }: Props) => {
  const { data: solverCatalog } = useSolverModels();

  return (
    <div className="space-y-hestia-2">
      <ModelPickerList
        models={solverCatalog?.models ?? []}
        selected={(id) => id === value}
        onSelect={onChange}
        emptyLabel="No solver models available."
      />
      {value && (
        <p className="text-xs text-hestia-text-muted">
          Thinking: {thinkingLabel(value)}
        </p>
      )}
    </div>
  );
};
