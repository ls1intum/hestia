import { PenLine } from "lucide-react";
import { Textarea } from "@/components/ui/textarea";
import { cn } from "@/lib/utils/utils";
import {
  MarkdownView,
  markdownSurfaceClassName,
  markdownTextareaClassName,
} from "@/components/shared/exam-content/MarkdownView";
import type { InlineTextEdit } from "@/hooks/ui/use-inline-text-edit";

interface Props {
  field: InlineTextEdit;
  placeholder: string;
  /** aria-label for the read-view click target. */
  ariaLabel: string;
  rows?: number;
  /** Extra classes appended to the textarea's markdown class. */
  textareaClassName?: string;
  /** Class for the read-view click target (defaults to the bordered surface). */
  readViewClassName?: string;
  /** Class forwarded to MarkdownView in the read view. */
  markdownClassName?: string;
  hint?: string;
}

/**
 * The textarea ↔ MarkdownView toggle shared by the editable task and context
 * cards. Editing (or a blank value) shows the textarea plus a "Markdown
 * supported" hint; otherwise a click-to-edit rendered-markdown surface.
 */
export const MarkdownEditField = ({
  field,
  placeholder,
  ariaLabel,
  rows = 2,
  textareaClassName,
  readViewClassName = markdownSurfaceClassName,
  markdownClassName,
  hint = "Code blocks and snippets (Markdown) supported",
}: Props) => {
  const { editing, isEmpty, enterEdit, textareaRef, textareaProps, value } = field;

  if (editing || isEmpty) {
    return (
      <>
        <Textarea
          ref={textareaRef}
          {...textareaProps}
          placeholder={placeholder}
          rows={rows}
          className={cn(markdownTextareaClassName, textareaClassName)}
        />
        <p className="mt-1 text-xs text-hestia-text-muted">{hint}</p>
      </>
    );
  }

  return (
    <div
      role="button"
      tabIndex={0}
      onClick={enterEdit}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          enterEdit();
        }
      }}
      aria-label={ariaLabel}
      className={cn("group flex items-start gap-2", readViewClassName)}
    >
      {/* Box is exactly one line of body text tall; centering the icon in it
          keeps the pencil aligned to the first row as the field grows. */}
      <span className="flex h-[1.42rem] shrink-0 items-center" aria-hidden>
        <PenLine
          size={13}
          className="text-hestia-text-muted/50 transition-colors group-hover:text-hestia-text-muted"
        />
      </span>
      <div className="min-w-0 flex-1">
        <MarkdownView content={value} className={markdownClassName} />
      </div>
    </div>
  );
};
