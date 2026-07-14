import { useState } from "react";
import { Plus, FileText, Image as ImageIcon, ChevronDown } from "lucide-react";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { TASK_TYPES, type TaskType } from "@/lib/exam/exam-helpers";
import { TASK_TYPE_LABELS } from "@/lib/exam/labels";

export type AddBlockChoice =
  | { kind: "task"; type: TaskType }
  | { kind: "context" };

export const AddTaskInline = ({
  onAdd,
  onAddContext,
  onAddFigure,
  variant = "between",
}: {
  onAdd: (type: TaskType) => void;
  /** When provided, the popover also offers a "Context" entry. */
  onAddContext?: () => void;
  /** When provided, the popover also offers a "Figure" entry. */
  onAddFigure?: () => void;
  variant?: "between" | "empty";
}) => {
  const [open, setOpen] = useState(false);
  const hasExtras = !!(onAddContext || onAddFigure);

  const linkBtn =
    "inline-flex items-center gap-1.5 text-xs text-hestia-text-muted hover:text-hestia-primary";

  const taskPopover = (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button type="button" className={linkBtn}>
          <Plus size={14} />
          Add Task
          <ChevronDown size={12} />
        </button>
      </PopoverTrigger>
      <PopoverContent className="w-48 p-1">
        <div className="flex flex-col">
          {TASK_TYPES.map((tp) => (
            <button
              key={tp}
              onClick={() => {
                onAdd(tp);
                setOpen(false);
              }}
              className="rounded-hestia-sm px-hestia-3 py-hestia-2 text-left text-sm hover:bg-hestia-primary-muted/50"
            >
              {TASK_TYPE_LABELS[tp]}
            </button>
          ))}
        </div>
      </PopoverContent>
    </Popover>
  );

  // Between-blocks row with all three actions side-by-side.
  if (variant === "between" && hasExtras) {
    return (
      <div className="flex items-center justify-center gap-hestia-5">
        {onAddContext && (
          <button
            type="button"
            onClick={onAddContext}
            className={linkBtn}
          >
            <FileText size={14} />
            Add Context
          </button>
        )}
        {onAddFigure && (
          <button
            type="button"
            onClick={onAddFigure}
            className={linkBtn}
          >
            <ImageIcon size={14} />
            Add Figure
          </button>
        )}
        {taskPopover}
      </div>
    );
  }

  // Empty-state (no section yet) or task-only fallback: single popover trigger.
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          className={
            variant === "empty"
              ? "mx-auto inline-flex items-center gap-2 rounded-hestia-md border border-dashed border-hestia-border px-hestia-5 py-hestia-4 text-sm text-hestia-text-muted hover:border-hestia-primary hover:text-hestia-primary"
              : "group mx-auto flex items-center gap-2 text-xs text-hestia-text-muted hover:text-hestia-primary"
          }
        >
          <Plus size={14} />{" "}
          {variant === "empty"
            ? "+ Add your first task"
            : "+ Add Task"}
        </button>
      </PopoverTrigger>
      <PopoverContent className="w-48 p-1">
        <div className="flex flex-col">
          {TASK_TYPES.map((tp) => (
            <button
              key={tp}
              onClick={() => {
                onAdd(tp);
                setOpen(false);
              }}
              className="rounded-hestia-sm px-hestia-3 py-hestia-2 text-left text-sm hover:bg-hestia-primary-muted/50"
            >
              {TASK_TYPE_LABELS[tp]}
            </button>
          ))}
        </div>
      </PopoverContent>
    </Popover>
  );
};