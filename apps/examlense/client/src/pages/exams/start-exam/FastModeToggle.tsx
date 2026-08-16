import { Zap } from "lucide-react";

import { Switch } from "@/components/ui/switch";

interface Props {
  checked: boolean;
  onChange: (enabled: boolean) => void;
}

/**
 * Fast Mode toggle for PDF parsing. When on, the parser runs in a faster,
 * text-only mode at the cost of possible structural issues in the parsed exam.
 * Surfaced in the upload step of the create-exam wizard.
 */
export const FastModeToggle = ({ checked, onChange }: Props) => (
  <div className="flex items-start justify-between gap-hestia-3 rounded-hestia-md border border-hestia-border bg-hestia-bg/40 px-hestia-3 py-hestia-3">
    <div className="min-w-0">
      <div className="flex items-center gap-hestia-2">
        <Zap size={15} className="text-hestia-primary" />
        <span className="text-sm font-semibold text-hestia-text">Fast Mode</span>
      </div>
      <p className="mt-1 text-xs leading-relaxed text-hestia-text-muted">
        ~25% faster, but may parse slightly less accurately. Turn it on if normal
        parsing takes too long or if the exam is larger than 20 pages.
      </p>
    </div>
    <Switch
      checked={checked}
      onCheckedChange={onChange}
      aria-label="Toggle Fast Mode"
      className="mt-0.5 data-[state=checked]:bg-hestia-primary"
    />
  </div>
);
