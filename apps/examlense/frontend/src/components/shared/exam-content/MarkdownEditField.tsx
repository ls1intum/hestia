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
      className={readViewClassName}
    >
      <MarkdownView content={value} className={markdownClassName} />
    </div>
  );
};
