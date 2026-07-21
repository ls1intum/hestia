import { useState, type CSSProperties, type HTMLAttributes } from "react";
import {
  GripVertical,
  Trash2,
  ArrowRight,
  ChevronDown,
  ChevronRight,
  Check,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Input } from "@/components/ui/input";
import { Checkbox } from "@/components/ui/checkbox";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import {
  DropdownMenuItem,
  DropdownMenuSub,
  DropdownMenuSubTrigger,
  DropdownMenuSubContent,
  DropdownMenuPortal,
} from "@/components/ui/dropdown-menu";
import { TASK_TYPE_LABELS } from "@/lib/exam/labels";
import { useInlineTextEdit } from "@/hooks/ui/use-inline-text-edit";
import { cn, preventNumberWheelChange } from "@/lib/utils/utils";
import { MarkdownEditField } from "@/components/shared/exam-content/MarkdownEditField";
import { WarningBanner } from "@/components/shared/exam-content/WarningBanner";
import { WayfindingPill } from "@/components/shared/WayfindingPill";
import { BlockHeader } from "@/components/shared/exam-content/BlockHeader";
import { BlockCard } from "@/components/shared/exam-content/BlockCard";
import { BlockActionsMenu } from "@/components/shared/exam-content/BlockActionsMenu";
import { ConfirmDeleteDialog } from "@/components/shared/exam-content/ConfirmDeleteDialog";
import {
  TASK_TYPES,
  isTextEmpty,
  mcWarning,
  newOption,
  taskMissingScore,
  type Task,
  type TaskOption,
  type TaskType,
} from "@/lib/exam/exam-helpers";

interface Props {
  task: Task;
  /** Letter label within the section (e.g. "a", "b"). */
  label: string;
  collapsed: boolean;
  onToggleCollapsed: () => void;
  onPatch: (patch: Partial<Task>) => void;
  onDelete: () => void;
  onDuplicate: () => void;
  onConvert: (toType: TaskType) => void;
  /** Drag-and-drop wiring (optional). */
  dragHandleProps?: HTMLAttributes<HTMLButtonElement>;
  setNodeRef?: (el: HTMLElement | null) => void;
  style?: CSSProperties;
  isDragging?: boolean;
}

export const TaskCard = ({
  task,
  label,
  collapsed,
  onToggleCollapsed,
  onPatch,
  onDelete,
  onDuplicate,
  onConvert,
  dragHandleProps,
  setNodeRef,
  style,
  isDragging,
}: Props) => {
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [pendingConvert, setPendingConvert] = useState<TaskType | null>(null);

  // Local mirror + click-to-edit machine for the prompt. Debounced patches
  // keep each keystroke from cascading into a full editor re-render (which
  // would recompute a/b/c labels for every task).
  const field = useInlineTextEdit({
    value: task.prompt,
    onCommit: (prompt) => onPatch({ prompt }),
  });

  const hasEmptyPrompt = isTextEmpty(task.prompt);
  const noScore = taskMissingScore(task);

  const updateOption = (id: string, patch: Partial<TaskOption>) => {
    const next = (task.options ?? []).map((o) => (o.id === id ? { ...o, ...patch } : o));
    onPatch({ options: next });
  };
  const removeOption = (id: string) => {
    onPatch({ options: (task.options ?? []).filter((o) => o.id !== id) });
  };
  const addOption = () => {
    onPatch({ options: [...(task.options ?? []), newOption()] });
  };
  const setSCCorrect = (id: string) => {
    onPatch({
      options: (task.options ?? []).map((o) => ({ ...o, is_correct: o.id === id })),
    });
  };

  const typeSelector = (
    <Popover>
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label="Change task type"
          title="Change task type"
          className="rounded-full focus:outline-none focus-visible:ring-2 focus-visible:ring-hestia-primary/40"
        >
          <Badge
            variant="secondary"
            className="cursor-pointer gap-1 bg-hestia-primary-muted/40 text-hestia-text hover:bg-hestia-primary-muted/60"
          >
            {TASK_TYPE_LABELS[task.type]}
            <ChevronDown size={12} className="opacity-70" />
          </Badge>
        </button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-56 p-1">
        <div className="px-hestia-2 py-1 hestia-eyebrow text-hestia-text-muted">
          Change task type
        </div>
        {TASK_TYPES.map((tp) => {
          const isCurrent = tp === task.type;
          return (
            <button
              key={tp}
              type="button"
              onClick={() => {
                if (isCurrent) return;
                const isTextSwitch =
                  (task.type === "text" && tp !== "text") ||
                  (tp === "text" && task.type !== "text");
                if (isTextSwitch) {
                  setPendingConvert(tp);
                } else {
                  onConvert(tp);
                }
              }}
              className={cn(
                "flex w-full items-center justify-between rounded-hestia-sm px-hestia-2 py-1.5 text-sm text-hestia-text hover:bg-hestia-primary-muted/40",
                isCurrent && "bg-hestia-primary-muted/30",
              )}
            >
              <span>{TASK_TYPE_LABELS[tp]}</span>
              {isCurrent && <Check size={14} className="text-hestia-primary" />}
            </button>
          );
        })}
      </PopoverContent>
    </Popover>
  );

  const header = (
    <BlockHeader
        expanded={!collapsed}
        onToggle={onToggleCollapsed}
        labelVariant="eyebrow"
        quietControls
        dragAlwaysVisible
        label={
          label ? (
            <span className="tabular-nums">Question {label})</span>
          ) : (
            <span className="italic">Untitled task</span>
          )
        }
        actionsMenu={
          <BlockActionsMenu
            ariaLabel="Task actions"
            onDelete={() => setConfirmDelete(true)}
          >
            <DropdownMenuItem onClick={onDuplicate}>Duplicate</DropdownMenuItem>
            <DropdownMenuSub>
              <DropdownMenuSubTrigger>Convert type</DropdownMenuSubTrigger>
              <DropdownMenuPortal>
                <DropdownMenuSubContent>
                  {TASK_TYPES.filter((tp) => tp !== task.type).map((tp) => (
                    <DropdownMenuItem key={tp} onClick={() => onConvert(tp)}>
                      {TASK_TYPE_LABELS[tp]}
                    </DropdownMenuItem>
                  ))}
                </DropdownMenuSubContent>
              </DropdownMenuPortal>
            </DropdownMenuSub>
          </BlockActionsMenu>
        }
        dragHandleProps={dragHandleProps}
      />
  );

  const body = !collapsed ? (
    <div>
      {task.parse_confidence === "low" && (
        <WarningBanner text="Low confidence — please review this task carefully." />
      )}
      {hasEmptyPrompt && (
        <WarningBanner text="No task description added." />
      )}

      <MarkdownEditField
        field={field}
        placeholder="Enter the task question…"
        ariaLabel="Enter the task question…"
        rows={2}
        textareaClassName="max-h-[550px] overflow-y-auto"
        readViewClassName="-mx-hestia-2 cursor-text rounded-hestia-sm px-hestia-2 py-1 transition-colors hover:bg-hestia-primary-muted/25 focus:outline-none focus-visible:ring-2 focus-visible:ring-hestia-primary/40"
        markdownClassName="text-hestia-text/90"
      />

      <div className="my-hestia-3 -mx-hestia-3 border-t border-hestia-border/60" />

      <div className="flex items-center justify-between gap-hestia-2">
        <div className="flex items-center gap-hestia-2">
          <span className="hestia-eyebrow text-hestia-text-muted">Task Type:</span>
          {typeSelector}
        </div>
        <div className="relative flex items-center gap-hestia-2">
          {noScore && (
            <WayfindingPill
              tone="warning"
              label="Score needs to be set"
              icon={<ArrowRight size={12} className="shrink-0" />}
              iconSide="end"
              className="pointer-events-none absolute right-full top-1/2 mr-hestia-2 -translate-y-1/2 motion-safe:animate-pulse"
            />
          )}
          <label className="flex items-center gap-hestia-2 hestia-eyebrow text-hestia-text-muted">
            Max score
            <Input
              id={`score-input-${task.id}`}
              type="number"
              min={0}
              step={0.5}
              value={task.points ?? ""}
              onWheel={preventNumberWheelChange}
              onChange={(e) => {
                const v = e.target.value;
                onPatch({ points: v === "" ? null : Number(v) });
              }}
              className={cn(
                "h-7 w-20 bg-hestia-surface text-sm",
                noScore
                  ? "border-hestia-danger animate-pulse-danger"
                  : "border-hestia-border",
              )}
            />
          </label>
        </div>
      </div>

      {task.type === "text" ? null : task.type === "single_choice" ? (
        <div className="mt-hestia-3">
          <RadioGroup
            value={(task.options ?? []).find((o) => o.is_correct)?.id ?? ""}
            onValueChange={setSCCorrect}
            className="space-y-2"
          >
            {(task.options ?? []).map((o) => (
              <OptionRow key={o.id}>
                <RadioGroupItem value={o.id} id={o.id} />
                <Input
                  value={o.text}
                  onChange={(e) => updateOption(o.id, { text: e.target.value })}
                  placeholder="Option text"
                  className="flex-1 border-hestia-border bg-hestia-surface"
                />
                <RemoveOptionButton onClick={() => removeOption(o.id)} />
              </OptionRow>
            ))}
          </RadioGroup>
          <AddOptionButton onClick={addOption} />
        </div>
      ) : (
        <div className="mt-hestia-3 space-y-2">
          {(task.options ?? []).map((o) => (
            <OptionRow key={o.id}>
              <Checkbox
                checked={o.is_correct}
                onCheckedChange={(v) => updateOption(o.id, { is_correct: !!v })}
              />
              <Input
                value={o.text}
                onChange={(e) => updateOption(o.id, { text: e.target.value })}
                placeholder="Option text"
                className="flex-1 border-hestia-border bg-hestia-surface"
              />
              <RemoveOptionButton onClick={() => removeOption(o.id)} />
            </OptionRow>
          ))}
          <AddOptionButton onClick={addOption} />
        </div>
      )}
    </div>
  ) : null;

  return (
    <>
      <BlockCard
        variant="primary"
        bodyDivider={false}
        setNodeRef={setNodeRef}
        style={style}
        isDragging={isDragging}
        header={header}
        body={body}
      />

      <ConfirmDeleteDialog
        open={confirmDelete}
        onOpenChange={setConfirmDelete}
        title="Delete this task?"
        description="This action cannot be undone."
        onConfirm={onDelete}
      />

      <ConfirmDeleteDialog
        open={pendingConvert !== null}
        onOpenChange={(open) => {
          if (!open) setPendingConvert(null);
        }}
        title="Change task type?"
        description={
          pendingConvert === "text"
            ? "Switching to a free-text task will remove all answer options and correctness settings. This cannot be undone."
            : "Switching from a free-text task to a choice task will discard the current free-text answer setup. This cannot be undone."
        }
        onConfirm={() => {
          if (pendingConvert) onConvert(pendingConvert);
          setPendingConvert(null);
        }}
        confirmLabel="Change type"
      />
    </>
  );
};

const OptionRow = ({ children }: { children: React.ReactNode }) => (
  <div className="group flex items-center gap-2">{children}</div>
);

const RemoveOptionButton = ({ onClick }: { onClick: () => void }) => (
  <button
    type="button"
    onClick={onClick}
    aria-label="Remove option"
    className="opacity-0 group-hover:opacity-100 text-hestia-text-muted hover:text-hestia-danger transition-opacity"
  >
    <Trash2 size={14} />
  </button>
);

const AddOptionButton = ({ onClick }: { onClick: () => void }) => {
  return (
    <button
      type="button"
      onClick={onClick}
      className="mt-2 text-xs text-hestia-text-muted hover:text-hestia-primary"
    >
      + Add option
    </button>
  );
};

