import { type ReactNode } from "react";
import { Badge } from "@/components/ui/badge";
import { BlockRow } from "@/pages/exam-edit/components/BlockRow";
import { SortableItem } from "@/pages/exam-edit/components/SortableItem";
import { TaskCard } from "@/pages/exam-edit/components/TaskCard";
import { ContextBlockCard } from "@/pages/exam-edit/components/ContextBlockCard";
import { FigureBlockCard } from "@/pages/exam-edit/components/FigureBlockCard";
import { TASK_TYPE_LABELS } from "@/lib/exam/labels";
import {
  blockDomId,
  convertTaskType,
  itemId,
  taskMissingScore,
  type BlockItem as BlockItemType,
  type SectionBlock,
  type Task,
} from "@/lib/exam/exam-helpers";

interface RowDescriptor {
  label: ReactNode;
  subtitle: ReactNode;
  missingScore: boolean;
  badgeText: string;
}

/**
 * Pure description of a block's collapsed TOC row (label / subtitle / badge),
 * keyed off the block kind.
 */
function blockRowDescriptor(
  item: BlockItemType,
  {
    figureLabels,
    taskLetterById,
  }: { figureLabels: Map<string, string>; taskLetterById: Map<string, string> },
): RowDescriptor {
  if (item.kind === "task") {
    const letter = taskLetterById.get(item.task.id) ?? "";
    const prompt = item.task.prompt?.trim() ?? "";
    return {
      label: letter ? `Question ${letter})` : "Untitled task",
      subtitle: prompt ? (
        <p className="text-sm leading-relaxed text-hestia-text-muted line-clamp-2">
          {prompt}
        </p>
      ) : (
        <p className="text-sm italic text-hestia-text-muted/70">
          Enter the task question…
        </p>
      ),
      missingScore: taskMissingScore(item.task),
      badgeText: TASK_TYPE_LABELS[item.task.type],
    };
  }
  if (item.kind === "figure") {
    return {
      label: figureLabels.get(item.block.id) ?? "Figure",
      subtitle: null,
      missingScore: false,
      badgeText: "Figure",
    };
  }
  return {
    label: "Context",
    subtitle: null,
    missingScore: false,
    badgeText: "Context",
  };
}

interface Props {
  item: BlockItemType;
  collapseApi: {
    isCollapsed: (id: string) => boolean;
    toggle: (id: string) => void;
  };
  figureLabels: Map<string, string>;
  taskLetterById: Map<string, string>;
  examId: string;
  onPatchBlock: (blockId: string, patch: Partial<SectionBlock>) => void;
  onDeleteBlock: (blockId: string) => void;
  onPatchTask: (taskId: string, patch: Partial<Task>) => void;
  onDeleteTask: (taskId: string) => void;
  onDuplicateTask: (task: Task) => void;
}

/**
 * A single sortable block. Collapsed → lightweight TOC row; expanded → the full
 * editor card. Both share the same SortableItem so DnD works in either state.
 */
export const BlockItem = ({
  item,
  collapseApi,
  figureLabels,
  taskLetterById,
  examId,
  onPatchBlock,
  onDeleteBlock,
  onPatchTask,
  onDeleteTask,
  onDuplicateTask,
}: Props) => {
  const id = itemId(item);
  const expanded = !collapseApi.isCollapsed(id);
  const onToggle = () => collapseApi.toggle(id);
  const scrollTargetId = blockDomId(item);

  return (
    <div id={scrollTargetId}>
      <SortableItem id={id}>
        {({ setNodeRef, style, isDragging, dragHandleProps }) => {
          if (!expanded) {
            const row = blockRowDescriptor(item, { figureLabels, taskLetterById });
            return (
              <BlockRow
                kind={item.kind}
                label={row.label}
                subtitle={row.subtitle}
                missingScore={row.missingScore}
                badge={
                  <Badge
                    variant="secondary"
                    className="bg-hestia-primary-muted/30 text-hestia-text-muted"
                  >
                    {row.badgeText}
                  </Badge>
                }
                onToggle={onToggle}
                setNodeRef={setNodeRef}
                style={style}
                isDragging={isDragging}
                dragHandleProps={dragHandleProps}
              />
            );
          }

          // Expanded: render the full editor card. Its own header chevron
          // toggles back to the collapsed row via the shared collapseApi.
          if (item.kind === "context") {
            return (
              <ContextBlockCard
                block={item.block}
                onToggleCollapsed={onToggle}
                onPatch={(patch) => onPatchBlock(item.block.id, patch)}
                onDelete={() => onDeleteBlock(item.block.id)}
                setNodeRef={setNodeRef}
                style={style}
                isDragging={isDragging}
                dragHandleProps={dragHandleProps}
              />
            );
          }
          if (item.kind === "figure") {
            return (
              <FigureBlockCard
                block={item.block}
                examId={examId}
                displayLabel={figureLabels.get(item.block.id) ?? "Figure"}
                onToggleCollapsed={onToggle}
                onDelete={() => onDeleteBlock(item.block.id)}
                setNodeRef={setNodeRef}
                style={style}
                isDragging={isDragging}
                dragHandleProps={dragHandleProps}
              />
            );
          }
          return (
            <TaskCard
              task={item.task}
              label={taskLetterById.get(item.task.id) ?? ""}
              collapsed={false}
              onToggleCollapsed={onToggle}
              onPatch={(patch) => onPatchTask(item.task.id, patch)}
              onDelete={() => onDeleteTask(item.task.id)}
              onDuplicate={() => onDuplicateTask(item.task)}
              onConvert={(toType) =>
                onPatchTask(item.task.id, convertTaskType(item.task, toType))
              }
              setNodeRef={setNodeRef}
              style={style}
              isDragging={isDragging}
              dragHandleProps={dragHandleProps}
            />
          );
        }}
      </SortableItem>
    </div>
  );
};
