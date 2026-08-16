import { AlertTriangle } from "lucide-react";

/**
 * Inline amber warning banner used across the editor cards to flag missing
 * content (empty task prompt, blank context, figure with no image, …). Left
 * accent bar + soft warning tint, matching the app's warning tokens.
 */
export const WarningBanner = ({ text }: { text: string }) => (
  <div className="flex items-start gap-2 rounded-hestia-sm border-l-4 border-hestia-warning bg-hestia-warning/10 px-hestia-3 py-2 text-xs text-hestia-text">
    <AlertTriangle size={14} className="mt-0.5 text-hestia-warning shrink-0" />
    <span>{text}</span>
  </div>
);
