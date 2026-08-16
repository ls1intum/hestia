import { BotMessageSquare } from "lucide-react";

/**
 * Ties a question block to its AI answer card below it. Shared by grading and
 * results so the pairing reads the same in both.
 */
export const QuestionAnswerConnector = () => (
  <div className="flex flex-col items-center" aria-hidden>
    <span className="h-hestia-2 w-px bg-hestia-border" />
    <span className="flex h-7 w-7 items-center justify-center rounded-full border border-hestia-border bg-hestia-surface text-hestia-text-muted shadow-hestia-sm">
      <BotMessageSquare size={14} />
    </span>
    <span className="h-hestia-2 w-px bg-hestia-border" />
  </div>
);
