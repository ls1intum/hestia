import type { CSSProperties, ReactNode } from "react";
import { useSortable } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";

interface Props {
  id: string;
  children: (args: {
    setNodeRef: (el: HTMLElement | null) => void;
    style: CSSProperties;
    isDragging: boolean;
    dragHandleProps: Record<string, unknown>;
  }) => ReactNode;
}

/**
 * Render-prop wrapper around dnd-kit's useSortable so card components can
 * stay agnostic of the dnd library and just receive a drag handle + ref.
 */
export const SortableItem = ({ id, children }: Props) => {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id });

  const style: CSSProperties = {
    transform: CSS.Translate.toString(transform),
    transition,
    zIndex: isDragging ? 10 : undefined,
  };

  return (
    <>
      {children({
        setNodeRef,
        style,
        isDragging,
        dragHandleProps: { ...attributes, ...listeners },
      })}
    </>
  );
};
