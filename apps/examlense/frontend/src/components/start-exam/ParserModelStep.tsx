import { Check, Zap } from "lucide-react";

import { Switch } from "@/components/ui/switch";
import { useParserModels } from "@/lib/api-models";
import { cn } from "@/lib/utils";

interface Props {
  selectedIds: string[];
  onChange: (ids: string[]) => void;
  fastMode: boolean;
  onFastModeChange: (enabled: boolean) => void;
}

const MODEL_META: Record<
  string,
  {
    provider: string;
    name: string;
    logoSrc: string;
  }
> = {
  "gemini-2.5-flash": {
    provider: "Google",
    name: "Gemini Flash",
    logoSrc:
      "https://commons.wikimedia.org/wiki/Special:Redirect/file/Google_Gemini_icon_2025.svg",
  },
  "claude-opus-4-8": {
    provider: "Anthropic",
    name: "Claude",
    logoSrc:
      "https://commons.wikimedia.org/wiki/Special:Redirect/file/Claude_AI_symbol.svg",
  },
  "gpt-5.5": {
    provider: "OpenAI",
    name: "GPT",
    logoSrc:
      "https://commons.wikimedia.org/wiki/Special:Redirect/file/OpenAI_logo_2025_%28symbol%29.svg",
  },
  "qwen3.6-35b-a3b": {
    provider: "GWDG",
    name: "Qwen 3.6",
    logoSrc:
      "https://commons.wikimedia.org/wiki/Special:Redirect/file/Qwen_logo.svg",
  },
  "mistral-large-3-675b-instruct-2512": {
    provider: "GWDG",
    name: "Mistral Large",
    logoSrc:
      "https://commons.wikimedia.org/wiki/Special:Redirect/file/Mistral_AI_logo_%282025%E2%80%93%29.svg",
  },
};

const MODEL_ORDER = [
  "gemini-2.5-flash",
  "gpt-5.5",
  "claude-opus-4-8",
  "mistral-large-3-675b-instruct-2512",
  "qwen3.6-35b-a3b",
];

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
  const parserModels = (parserCatalog?.models ?? [])
    .filter((m) => MODEL_META[m.id])
    .sort((a, b) => MODEL_ORDER.indexOf(a.id) - MODEL_ORDER.indexOf(b.id));

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

      {parserModels.length === 0 ? (
        <p className="rounded-hestia-md border border-hestia-border bg-hestia-bg/40 px-hestia-3 py-hestia-3 text-xs text-hestia-text-muted">
          No parser models available.
        </p>
      ) : (
        <div className="grid gap-hestia-2">
          {parserModels.map((m) => {
            const selected = selectedIds.includes(m.id);
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
                onClick={() => toggle(m.id)}
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
                    {meta.logoSrc ? (
                      <img
                        src={meta.logoSrc}
                        alt=""
                        className={cn(
                          "max-h-full max-w-full object-contain",
                          m.id === "gpt-5.5" ? "dark:brightness-0 dark:invert" : "",
                        )}
                        loading="lazy"
                        referrerPolicy="no-referrer"
                      />
                    ) : (
                      <span className="text-xs font-bold text-hestia-primary">
                        {meta.name.slice(0, 1)}
                      </span>
                    )}
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
