import { Zap } from "lucide-react";

import { Switch } from "@/components/ui/switch";
import { useParserModels } from "@/lib/api/api-models";
import { ModelPickerList } from "./ModelPickerList";

interface Props {
  selectedIds: string[];
  onChange: (ids: string[]) => void;
  fastMode: boolean;
  onFastModeChange: (enabled: boolean) => void;
}

/**
 * PDF flow step: pick one or more parser models. Each selected model parses the
 * same PDF into its own exam so the author can compare them on the dashboard.
 */
export const ParserModelStep = ({
  selectedIds,
  onChange,
  fastMode,
  onFastModeChange,
}: Props) => {
  const { data: parserCatalog } = useParserModels();

  const toggle = (id: string) =>
    onChange(
      selectedIds.includes(id)
        ? selectedIds.filter((m) => m !== id)
        : [...selectedIds, id],
    );

  return (
    <div className="space-y-hestia-3">
      <div className="flex items-start justify-between gap-hestia-3 rounded-hestia-md border border-hestia-border bg-hestia-bg/40 px-hestia-3 py-hestia-3">
        <div className="min-w-0">
          <div className="flex items-center gap-hestia-2">
            <Zap size={15} className="text-hestia-primary" />
            <span className="text-sm font-semibold text-hestia-text">
              Fast Mode
            </span>
          </div>
          <p className="mt-1 text-xs leading-relaxed text-hestia-text-muted">
            Faster parsing, but might cause structural issues in the parsed exam.
          </p>
        </div>
        <Switch
          checked={fastMode}
          onCheckedChange={onFastModeChange}
          aria-label="Toggle Fast Mode"
          className="mt-0.5 data-[state=checked]:bg-hestia-primary"
        />
      </div>

      <ModelPickerList
        models={parserCatalog?.models ?? []}
        selected={(id) => selectedIds.includes(id)}
        onSelect={toggle}
        emptyLabel="No parser models available."
      />
    </div>
  );
};
