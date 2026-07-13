import { useEffect, useRef, useState, type CSSProperties, type HTMLAttributes } from "react";
import {
  GripVertical,
  MoreVertical,
  Trash2,
  AlertTriangle,
  ChevronDown,
  ChevronRight,
  Check,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Checkbox } from "@/components/ui/checkbox";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
  DropdownMenuSub,
  DropdownMenuSubTrigger,
  DropdownMenuSubContent,
  DropdownMenuPortal,
  DropdownMenuSeparator,
} from "@/components/ui/dropdown-menu";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { TASK_TYPE_LABELS } from "@/lib/labels";
import { useAutosizeTextarea } from "@/hooks/use-autosize-textarea";
import { useDebouncedCallback } from "@/hooks/use-debounced-callback";
import { cn } from "@/lib/utils";
import {
  MarkdownView,
  markdownSurfaceClassName,
  markdownTextareaClassName,
} from "./MarkdownView";
import { BlockHeader } from "./BlockHeader";
import { BlockCard } from "./BlockCard";
import {
  TASK_TYPES,
  mcWarning,
  newOption,
  type Task,
  type TaskOption,
  type TaskType,
} from "@/lib/exam-helpers";

const preventNumberWheelChange = (event: React.WheelEvent<HTMLInputElement>) => {
  event.currentTarget.blur();
};

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

  // Local mirror for fast typing; flush via onPatch on change
  const [prompt, setPrompt] = useState(task.prompt);
  useEffect(() => setPrompt(task.prompt), [task.id, task.prompt]);
  const promptRef = useAutosizeTextarea<HTMLTextAreaElement>(prompt);
  const [editingPrompt, setEditingPrompt] = useState(() => (task.prompt ?? "").trim() === "");
  const justEnteredPromptEdit = useRef(false);

  useEffect(() => {
    if (editingPrompt && justEnteredPromptEdit.current) {
      justEnteredPromptEdit.current = false;
      const el = promptRef.current;
      if (el) {
        el.focus();
        const len = el.value.length;
        try {
          el.setSelectionRange(len, len);
        } catch {
          /* noop */
        }
      }
    }
  }, [editingPrompt, promptRef]);

  const enterPromptEdit = () => {
    justEnteredPromptEdit.current = true;
    setEditingPrompt(true);
  };

  // Debounce text-field patches so each keystroke does NOT cascade into a
  // full editor re-render (which would recompute a/b/c labels for every task).
  const debouncedPatchPrompt = useDebouncedCallback(
    (value: string) => onPatch({ prompt: value }),
    250,
  );

  const hasEmptyPrompt = (task.prompt ?? "").trim() === "";

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

  const header = (
    <BlockHeader
        expanded={!collapsed}
        onToggle={onToggleCollapsed}
        label={
          <span>
            {label ? (
              <span className="tabular-nums">{label})</span>
            ) : (
              <span className="italic text-hestia-text-muted">
                Untitled task
              </span>
            )}
          </span>
        }
        rightMeta={
          <label className="flex items-center gap-hestia-2 hestia-eyebrow text-hestia-text-muted">
            Points
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
                task.points == null || task.points <= 0
                  ? "border-hestia-danger animate-pulse-danger"
                  : "border-hestia-border",
              )}
            />
          </label>
        }
        badge={
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
                  className="cursor-pointer bg-hestia-primary-muted/40 text-hestia-text hover:bg-hestia-primary-muted/60"
                >
                  {TASK_TYPE_LABELS[task.type]}
                </Badge>
              </button>
            </PopoverTrigger>
            <PopoverContent align="end" className="w-56 p-1">
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
        }
        actionsMenu={
          <TaskActionsMenu {...{ task, onDuplicate, onConvert, setConfirmDelete }} />
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

      {editingPrompt || prompt.trim() === "" ? (
        <>
          <Textarea
            ref={promptRef}
            value={prompt}
            onChange={(e) => {
              setPrompt(e.target.value);
              debouncedPatchPrompt(e.target.value);
            }}
            onBlur={() => {
              debouncedPatchPrompt.flush();
              if (prompt !== task.prompt) onPatch({ prompt });
              if (prompt.trim() !== "") setEditingPrompt(false);
            }}
            placeholder="Enter the task question…"
            rows={2}
            className={cn(markdownTextareaClassName, "max-h-[550px] overflow-y-auto")}
          />
          <p className="mt-1 text-xs text-hestia-text-muted">
            Code blocks and snippets (Markdown) supported
          </p>
        </>
      ) : (
        <div
          role="button"
          tabIndex={0}
          onClick={enterPromptEdit}
          onKeyDown={(e) => {
            if (e.key === "Enter" || e.key === " ") {
              e.preventDefault();
              enterPromptEdit();
            }
          }}
          aria-label="Enter the task question…"
          className={markdownSurfaceClassName}
        >
          <MarkdownView content={prompt} />
        </div>
      )}

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
        setNodeRef={setNodeRef}
        style={style}
        isDragging={isDragging}
        header={header}
        body={body}
      />

      <AlertDialog open={confirmDelete} onOpenChange={setConfirmDelete}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete this task?</AlertDialogTitle>
            <AlertDialogDescription>This action cannot be undone.</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={onDelete}
              className="bg-hestia-danger text-white hover:bg-hestia-danger/90"
            >
              Delete
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <AlertDialog
        open={pendingConvert !== null}
        onOpenChange={(open) => {
          if (!open) setPendingConvert(null);
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Change task type?</AlertDialogTitle>
            <AlertDialogDescription>
              {pendingConvert === "text"
                ? "Switching to a free-text task will remove all answer options and correctness settings. This cannot be undone."
                : "Switching from a free-text task to a choice task will discard the current free-text answer setup. This cannot be undone."}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                if (pendingConvert) onConvert(pendingConvert);
                setPendingConvert(null);
              }}
              className="bg-hestia-danger text-white hover:bg-hestia-danger/90"
            >
              Change type
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
};

const TaskActionsMenu = ({
  task,
  onDuplicate,
  onConvert,
  setConfirmDelete,
}: {
  task: Task;
  onDuplicate: () => void;
  onConvert: (toType: TaskType) => void;
  setConfirmDelete: (v: boolean) => void;
}) => (
  <DropdownMenu>
    <DropdownMenuTrigger
      aria-label="Task actions"
      className="rounded-hestia-sm p-1 text-hestia-text-muted hover:bg-hestia-primary-muted/40 hover:text-hestia-text"
    >
      <MoreVertical size={16} />
    </DropdownMenuTrigger>
    <DropdownMenuContent align="end">
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
      <DropdownMenuSeparator />
      <DropdownMenuItem
        onClick={() => setConfirmDelete(true)}
        className="text-hestia-danger focus:text-hestia-danger"
      >
        Delete
      </DropdownMenuItem>
    </DropdownMenuContent>
  </DropdownMenu>
);

const WarningBanner = ({ text }: { text: string }) => (
  <div className="mt-hestia-3 flex items-start gap-2 rounded-hestia-sm border-l-4 border-hestia-warning bg-hestia-warning/10 px-hestia-3 py-2 text-xs text-hestia-text">
    <AlertTriangle size={14} className="mt-0.5 text-hestia-warning shrink-0" />
    <span>{text}</span>
  </div>
);

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

