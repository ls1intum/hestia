import { useState } from "react";
import { ChevronDown, ChevronRight } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import { BLOOM_LABELS, SOLO_LABELS } from "@/lib/labels";
import type { Task } from "@/lib/exam-helpers";
import { MarkdownView } from "../MarkdownView";
import { BlockHeader } from "../BlockHeader";
import type { TaskGoalDisplay } from "./ReadOnlyTaskCard";

interface Props {
  task: Task;
  label: string;
  /** LGH-derived learning goals of this task, rendered read-only. */
  goals?: TaskGoalDisplay[];
}

/**
 * The static question shown in grading mode: prompt + learning goals (+ optional
 * reference answer for text tasks). It is non-editable reference material, so it
 * renders card-less — the warm-tinted flat look of ReadOnlyContextBlock — leaving
 * the gradable AI answer + score (TaskGradingPanel) as the only real "card" and
 * therefore the grader's single work object on the page.
 */
export const ReadOnlyQuestionBlock = ({ task, label, goals }: Props) => {
  const [collapsed, setCollapsed] = useState(false);
  const promptPreview = (task.prompt || "").trim();

  return (
    <article className="rounded-hestia-lg bg-hestia-primary-muted/25 px-hestia-3 py-hestia-2">
      <BlockHeader
        expanded={!collapsed}
        onToggle={() => setCollapsed((v) => !v)}
        label={label ? `${label})` : "Untitled task"}
        labelVariant="eyebrow"
      />
      {!collapsed && (
        <div className="mt-hestia-2 space-y-hestia-3 pl-hestia-5">
          {promptPreview ? (
            <MarkdownView content={promptPreview} />
          ) : (
            <p className="text-sm italic text-hestia-text-muted/70">Untitled task</p>
          )}

          {(goals?.length ?? 0) > 0 && (
            <Collapsible>
              <CollapsibleTrigger className="group inline-flex items-center gap-hestia-1 rounded-hestia-sm py-0.5 text-hestia-text-muted transition-colors hover:text-hestia-text focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-hestia-primary focus-visible:ring-offset-1 focus-visible:ring-offset-hestia-bg">
                <ChevronRight
                  size={12}
                  className="shrink-0 group-data-[state=open]:hidden"
                  aria-hidden
                />
                <ChevronDown
                  size={12}
                  className="hidden shrink-0 group-data-[state=open]:block"
                  aria-hidden
                />
                <span className="hestia-eyebrow">Learning goals</span>
                <span className="hestia-eyebrow tabular-nums">({goals!.length})</span>
              </CollapsibleTrigger>
              <CollapsibleContent className="mt-hestia-2 flex flex-wrap items-center gap-hestia-1">
                {goals!.map((g) => (
                  <span key={g.id} className="inline-flex items-center gap-hestia-1">
                    <Badge
                      variant="secondary"
                      className="max-w-[360px] bg-hestia-primary-muted/40 text-xs font-normal text-hestia-text"
                    >
                      <span className="truncate">{g.text || `Goal #${g.id}`}</span>
                    </Badge>
                    {g.bloomLevel && (
                      <Badge variant="outline" className="text-[10px] text-hestia-text-muted">
                        {BLOOM_LABELS[g.bloomLevel]}
                      </Badge>
                    )}
                    {g.soloLevel && (
                      <Badge variant="outline" className="text-[10px] text-hestia-text-muted">
                        {SOLO_LABELS[g.soloLevel]}
                      </Badge>
                    )}
                  </span>
                ))}
              </CollapsibleContent>
            </Collapsible>
          )}

          {task.type === "text" && task.reference_answer && (
            <div className="rounded-hestia-sm border border-hestia-border/60 bg-hestia-bg/40 p-hestia-2">
              <div className="mb-1 hestia-eyebrow text-hestia-text-muted">
                Reference answer (optional)
              </div>
              <MarkdownView content={task.reference_answer} />
            </div>
          )}
        </div>
      )}
    </article>
  );
};
