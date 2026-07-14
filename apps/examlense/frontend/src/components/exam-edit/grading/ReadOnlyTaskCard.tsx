import { useState } from "react";
import { Check, ChevronDown, ChevronRight } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import { cn } from "@/lib/utils";
import { BLOOM_LABELS, SOLO_LABELS } from "@/lib/labels";
import type { Task } from "@/lib/exam-helpers";
import type { TaskAnswer } from "@/lib/grading";
import type { BloomLevel, SoloLevel } from "@/lib/learning-goals";
import { MarkdownView } from "../MarkdownView";
import { BlockHeader } from "../BlockHeader";
import { BlockCard } from "../BlockCard";

/** A resolved (or placeholder, when LGH is unreachable) learning goal to display. */
export interface TaskGoalDisplay {
  id: number;
  text?: string | null;
  bloomLevel?: BloomLevel | null;
  soloLevel?: SoloLevel | null;
}

interface Props {
  task: Task;
  label: string;
  /** True when this task has a grade (auto or manual). Drives the leading dot. */
  graded?: boolean;
  answer: TaskAnswer | undefined;
  /** LGH-derived learning goals of this task, rendered read-only. */
  goals?: TaskGoalDisplay[];
  /** Optional grading panel rendered below the task body. */
  gradingPanel?: React.ReactNode;
}

export const ReadOnlyTaskCard = ({
  task,
  label,
  graded,
  answer,
  goals,
  gradingPanel,
}: Props) => {
  const [collapsed, setCollapsed] = useState(false);
  const promptPreview = (task.prompt || "").trim();

  const labelText = (
    <span className="inline-flex min-w-0 items-center gap-hestia-2">
      <span
        aria-hidden
        className={cn(
          "h-1.5 w-1.5 shrink-0 rounded-full",
          graded ? "bg-hestia-success" : "bg-hestia-border",
        )}
      />
      <span className="min-w-0 truncate tabular-nums">
        {label ? `${label})` : "Untitled task"}
      </span>
    </span>
  );

  const correctIds = new Set(
    (task.options ?? []).filter((o) => o.is_correct).map((o) => o.id),
  );
  const pickedIds = new Set(answer?.selected_option_ids ?? []);

  const header = (
    <BlockHeader
      expanded={!collapsed}
      onToggle={() => setCollapsed((v) => !v)}
      label={labelText}
    />
  );

  const body = collapsed ? (
    promptPreview ? (
      <p className="text-sm leading-relaxed text-hestia-text-muted line-clamp-2">
        {promptPreview}
      </p>
    ) : (
      <p className="text-sm italic text-hestia-text-muted/70">
        Untitled task
      </p>
    )
  ) : (
    <div>
      {promptPreview && (
        <div className="mb-hestia-3">
          <MarkdownView content={promptPreview} />
        </div>
      )}

      {(goals?.length ?? 0) > 0 && (
        <Collapsible className="mb-hestia-3">
          <CollapsibleTrigger className="group inline-flex items-center gap-hestia-1 rounded-hestia-sm py-0.5 text-hestia-text-muted transition-colors hover:text-hestia-text focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-hestia-primary focus-visible:ring-offset-1 focus-visible:ring-offset-hestia-bg">
            <ChevronRight
              size={12}
              className="shrink-0 transition-transform group-data-[state=open]:hidden"
              aria-hidden
            />
            <ChevronDown
              size={12}
              className="hidden shrink-0 transition-transform group-data-[state=open]:block"
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

      {task.type !== "text" && (task.options?.length ?? 0) > 0 && (
        <ul className="space-y-1.5 opacity-75">
          {task.options!.map((o) => {
            const isCorrect = correctIds.has(o.id);
            const isPicked = pickedIds.has(o.id);
            return (
              <li
                key={o.id}
                className={cn(
                  "flex items-start gap-hestia-2 rounded-hestia-sm border border-hestia-border/60 px-hestia-2 py-1.5 text-sm",
                  isPicked && "border-hestia-primary/60 bg-hestia-primary-muted/20",
                  isCorrect && "ring-1 ring-hestia-success/40",
                )}
              >
                <span
                  aria-hidden
                  className={cn(
                    "mt-0.5 inline-flex h-4 w-4 shrink-0 items-center justify-center rounded-full border",
                    isPicked
                      ? "border-hestia-primary bg-hestia-primary text-white"
                      : "border-hestia-border text-transparent",
                  )}
                >
                  {isPicked && <Check size={12} />}
                </span>
                <span className="min-w-0 flex-1 break-words text-hestia-text">
                  {o.text || <span className="italic text-hestia-text-muted">—</span>}
                </span>
              </li>
            );
          })}
        </ul>
      )}

      {task.type === "text" && task.reference_answer && (
        <div className="mt-hestia-3 rounded-hestia-sm border border-hestia-border/60 bg-hestia-bg/40 p-hestia-2 opacity-75">
          <div className="mb-1 hestia-eyebrow text-hestia-text-muted">
            Reference answer (optional)
          </div>
          <MarkdownView content={task.reference_answer} />
        </div>
      )}

      {gradingPanel && <div className="mt-hestia-3">{gradingPanel}</div>}
    </div>
  );

  return <BlockCard variant="primary" header={header} body={body} />;
};
