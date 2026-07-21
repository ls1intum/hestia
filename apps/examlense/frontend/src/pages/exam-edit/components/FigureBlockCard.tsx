import { useRef, useState, type CSSProperties, type HTMLAttributes } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { ImagePlus, Loader2, Trash2 } from "lucide-react";
import { uploadFigure, deleteFigure } from "@/lib/api/api-client";
import { useFigureUrl } from "@/hooks/data/use-figure-url";
import { useSectionFigures, figuresKey } from "@/hooks/data/use-sections";
import { useToast } from "@/hooks/ui/use-toast";
import { cn } from "@/lib/utils/utils";
import type { SectionBlock, SectionFigure } from "@/lib/exam/exam-helpers";
import { BlockHeader } from "@/components/shared/exam-content/BlockHeader";
import { BlockCard } from "@/components/shared/exam-content/BlockCard";
import { BlockActionsMenu } from "@/components/shared/exam-content/BlockActionsMenu";
import { ConfirmDeleteDialog } from "@/components/shared/exam-content/ConfirmDeleteDialog";
import { WarningBanner } from "@/components/shared/exam-content/WarningBanner";

const MAX_BYTES = 5 * 1024 * 1024;
const MIME = ["image/png", "image/jpeg", "image/webp", "image/gif"];

interface Props {
  block: SectionBlock;
  examId: string;
  /** Auto-derived display name (e.g. "Figure 1.2"). */
  displayLabel: string;
  onToggleCollapsed: () => void;
  onDelete: () => void;
  dragHandleProps?: HTMLAttributes<HTMLButtonElement>;
  setNodeRef?: (el: HTMLElement | null) => void;
  style?: CSSProperties;
  isDragging?: boolean;
}

export const FigureBlockCard = ({
  block,
  examId,
  displayLabel,
  onToggleCollapsed,
  onDelete,
  dragHandleProps,
  setNodeRef,
  style,
  isDragging,
}: Props) => {
  const { data: figures } = useSectionFigures(block.id);
  const qc = useQueryClient();
  const inputRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const [dragActive, setDragActive] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const { toast } = useToast();

  const figure: SectionFigure | undefined = figures?.[0];

  const refresh = () =>
    qc.invalidateQueries({ queryKey: figuresKey(block.id) });

  const handleFile = async (file: File | null | undefined) => {
    if (!file) return;
    if (!MIME.includes(file.type)) {
      toast({
        title: "Only PNG, JPEG, WEBP or GIF images are supported.",
        variant: "destructive",
      });
      return;
    }
    if (file.size > MAX_BYTES) {
      toast({
        title: "Image is too large. Maximum size is 5 MB.",
        variant: "destructive",
      });
      return;
    }
    setUploading(true);
    try {
      // Upload the replacement first, then drop the previous figure so the
      // block keeps its single-figure invariant. The backend assigns the
      // storage path + id.
      await uploadFigure(block.id, file, 0);
      if (figure) {
        try {
          await deleteFigure(figure.id);
        } catch {
          /* best-effort cleanup of the old figure */
        }
      }
      refresh();
    } catch {
      toast({
        title: "Could not upload this figure. Please try again.",
        variant: "destructive",
      });
    } finally {
      setUploading(false);
      if (inputRef.current) inputRef.current.value = "";
    }
  };

  const removeFigure = async () => {
    if (!figure) return;
    await deleteFigure(figure.id);
    refresh();
  };

  const header = (
    <BlockHeader
      expanded
      onToggle={onToggleCollapsed}
      label={displayLabel}
      labelVariant="eyebrow"
      quietControls
      dragAlwaysVisible
      actionsMenu={
        <BlockActionsMenu
          ariaLabel="Figure actions"
          onDelete={() => setConfirmDelete(true)}
          deleteLabel="Delete figure block"
        />
      }
      dragHandleProps={dragHandleProps}
    />
  );

  const body = (
    <div
      onDragOver={(e) => {
        e.preventDefault();
        if (!dragActive) setDragActive(true);
      }}
      onDragLeave={() => setDragActive(false)}
      onDrop={(e) => {
        e.preventDefault();
        setDragActive(false);
        if (uploading) return;
        void handleFile(e.dataTransfer.files?.[0]);
      }}
      className={cn(
        "rounded-hestia-md transition-colors",
        dragActive &&
          "ring-2 ring-hestia-primary ring-offset-1 ring-offset-hestia-bg bg-hestia-primary-muted/20",
      )}
    >
      {figure ? (
        <FigureThumb
          figure={figure}
          onRemove={removeFigure}
          onReplace={() => inputRef.current?.click()}
        />
      ) : (
        <>
        <WarningBanner text="Missing figure; upload it or simply take a screenshot of the original and drop it here" />
        <button
          type="button"
          onClick={() => inputRef.current?.click()}
          disabled={uploading}
          className="flex w-full flex-col items-center justify-center gap-1 rounded-hestia-md border border-dashed border-hestia-border-strong px-hestia-4 py-hestia-5 text-sm text-hestia-text-muted hover:border-hestia-primary hover:text-hestia-primary disabled:opacity-50"
        >
          {uploading ? <Loader2 size={16} className="animate-spin" /> : <ImagePlus size={16} />}
          <span>Upload an image</span>
          <span className="text-[10px] text-hestia-text-muted/70">
            PNG, JPEG, WEBP or GIF · max 5 MB
          </span>
        </button>
        </>
      )}
      <input
        ref={inputRef}
        type="file"
        accept={MIME.join(",")}
        className="sr-only"
        onChange={(e) => handleFile(e.target.files?.[0])}
      />
    </div>
  );

  return (
    <>
      <BlockCard
        variant="muted"
        setNodeRef={setNodeRef}
        style={style}
        isDragging={isDragging}
        header={header}
        body={body}
      />

      <ConfirmDeleteDialog
        open={confirmDelete}
        onOpenChange={setConfirmDelete}
        title="Delete this figure block?"
        description="The image attached to it will also be removed."
        onConfirm={onDelete}
      />
    </>
  );
};

const FigureThumb = ({
  figure,
  onRemove,
  onReplace,
}: {
  figure: SectionFigure;
  onRemove: () => void;
  onReplace: () => void;
}) => {
  const url = useFigureUrl(figure.id);

  return (
    <div className="group relative w-fit max-w-full overflow-hidden rounded-hestia-md border border-hestia-border">
      {url ? (
        <img
          src={url}
          alt={figure.caption ?? "Figure"}
          className="max-h-72 w-full object-contain"
          loading="lazy"
        />
      ) : (
        <div className="flex h-32 w-full items-center justify-center text-xs text-hestia-text-muted">
          …
        </div>
      )}
      <div className="absolute right-1 top-1 flex gap-1 opacity-0 transition-opacity group-hover:opacity-100">
        <button
          type="button"
          onClick={onReplace}
          aria-label="Replace image"
          title="Replace image"
          className="rounded-hestia-sm bg-hestia-bg/80 p-1 text-hestia-text-muted hover:text-hestia-primary"
        >
          <ImagePlus size={12} />
        </button>
        <button
          type="button"
          onClick={onRemove}
          aria-label="Delete figure"
          title="Delete figure"
          className="rounded-hestia-sm bg-hestia-bg/80 p-1 text-hestia-text-muted hover:text-hestia-danger"
        >
          <Trash2 size={12} />
        </button>
      </div>
    </div>
  );
};
