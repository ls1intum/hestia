import { useSolverModels } from "@/lib/api/api-models";
import { ModelPickerList } from "./ModelPickerList";

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

  return (
    <div className="space-y-hestia-2">
      <ModelPickerList
        models={solverCatalog?.models ?? []}
        selected={(id) => id === value}
        onSelect={onChange}
        emptyLabel="No solver models available."
      />
    </div>
  );
};
