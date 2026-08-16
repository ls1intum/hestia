import { useState } from "react";
import { Bot, ChevronDown } from "lucide-react";
import { cn } from "@/lib/utils/utils";
import type { Task } from "@/lib/exam/exam-helpers";
import type { TaskAnswer } from "@/lib/grading/grading";
import { MarkdownView } from "@/components/shared/exam-content/MarkdownView";
import { ReadOnlyOptionList } from "./ReadOnlyOptionList";

interface Props {
  task: Task;
  answer: TaskAnswer;
}

/**
 * The LLM's answer to a task: choice tasks show the picked/correct option list;
 * text tasks show the answer markdown (collapsible — free-text can be long) plus
 * a "Show reasoning" disclosure. Shared by grading and results so the AI answer
 * looks identical in both.
 */
export const AiAnswerBlock = ({ task, answer }: Props) => {
  const [open, setOpen] = useState(true);
  const isText = task.type === "text";

  return (
    <div className="space-y-hestia-2">
      {isText ? (
        <button
          type="button"
          onClick={() => setOpen((o) => !o)}
          aria-expanded={open}
          className="flex w-full items-center gap-hestia-2 text-left"
        >
          <ChevronDown
            size={14}
            aria-hidden
            className={cn(
              "shrink-0 text-hestia-text-muted transition-transform",
              !open && "-rotate-90",
            )}
          />
          <Bot size={12} className="text-hestia-grading" />
          <span className="hestia-eyebrow text-hestia-text-muted">AI answer</span>
        </button>
      ) : (
        <div className="flex items-center gap-hestia-2">
          <Bot size={12} className="text-hestia-grading" />
          <span className="hestia-eyebrow text-hestia-text-muted">AI answer</span>
        </div>
      )}

      {!isText ? (
        <ReadOnlyOptionList task={task} answer={answer} />
      ) : open ? (
        <>
          {/*
            Rendering is already math-capable: MarkdownView runs remark-math +
            rehype-katex (KaTeX CSS loaded). If formulas show as raw text, the
            solver emitted plain-text notation instead of $…$ LaTeX — that is a
            backend solver-prompt fix, tracked separately, not a rendering bug.
          */}
          <MarkdownView
            content={answer.answer_text ?? ""}
            className="text-hestia-text/90"
          />
          {answer.reasoning && (
            <details className="text-xs text-hestia-text-muted">
              <summary className="cursor-pointer select-none">Show reasoning</summary>
              <div className="mt-1">
                <MarkdownView
                  content={answer.reasoning}
                  className="text-hestia-text-muted"
                />
              </div>
            </details>
          )}
        </>
      ) : (
        <button
          type="button"
          onClick={() => setOpen(true)}
          className="line-clamp-1 w-full text-left text-sm text-hestia-text-muted"
        >
          {answer.answer_text?.trim() || "Show answer"}
        </button>
      )}
    </div>
  );
};
