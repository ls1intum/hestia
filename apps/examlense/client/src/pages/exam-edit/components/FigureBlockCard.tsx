import { useRef, useState, type CSSProperties, type HTMLAttributes } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { ImagePlus, Loader2, Trash2 } from "lucide-react";
import { uploadFigure, deleteFigure, patchFigure } from "@/lib/api/api-client";
import { useFigureUrl } from "@/hooks/data/use-figure-url";
import { useSectionFigures, figuresKey } from "@/hooks/data/use-sections";
import { useToast } from "@/hooks/ui/use-toast";
import { useInlineTextEdit } from "@/hooks/ui/use-inline-text-edit";
import { MarkdownEditField } from "@/components/shared/exam-content/MarkdownEditField";
import { cn } from "@/lib/utils/utils";
import type { SectionBlock, SectionFigure } from "@/lib/exam/exam-helpers";
import { BlockHeader } from "@/components/shared/exam-content/BlockHeader";
import { BlockCard } from "@/components/shared/exam-content/BlockCard";
import { BlockActionsMenu } from "@/components/shared/exam-content/BlockActionsMenu";
import { ConfirmDeleteDialog } from "@/components/shared/exam-content/ConfirmDeleteDialog";
import { WarningBanner } from "@/components/shared/exam-content/WarningBanner";
import { Badge } from "@/components/ui/badge";

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
      const uploaded = await uploadFigure(block.id, file, 0);
      if (figure) {
        // The caption describes the figure, not the file, so replacing the
        // image must not silently discard what the author wrote.
        if (figure.caption) {
          try {
            await patchFigure(uploaded.id, { caption: figure.caption });
          } catch {
            /* the image is what matters; a lost caption can be retyped */
          }
        }
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
      // Flag crops taken from the PDF. They are usually right and occasionally
      // wrong, and the editor is where the author is already checking the parse —
      // the one moment a cue to look actually costs nothing.
      badge={
        figure?.source === "pdf" ? (
          <Badge variant="secondary">Auto-extracted</Badge>
        ) : undefined
      }
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
        <>
          <FigureThumb
            figure={figure}
            onRemove={removeFigure}
            onReplace={() => inputRef.current?.click()}
          />
          <FigureCaption
            key={figure.id}
            figure={figure}
            onCommit={async (caption) => {
              try {
                // Stored untrimmed: the committed value flows straight back into
                // the field, and trimming it would eat the space the author just
                // typed mid-sentence. Blank means "no caption".
                await patchFigure(figure.id, {
                  caption: caption.trim() ? caption : null,
                });
                refresh();
              } catch {
                toast({
                  title: "Could not save this caption. Please try again.",
                  variant: "destructive",
                });
              }
            }}
          />
        </>
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
          <span className="text-[10px] text-hestia-text-muted">
            PNG, JPEG, WEBP or GIF · max 5 MB
          </span>
        </button>
        </>
      )}
      <input
        ref={inputRef}
        type="file"
        aria-label="Upload figure image"
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

/**
 * The figure's caption — seeded by the parser, optional, and part of what the
 * solver is shown for this block. Keyed by figure id at the call site so
 * replacing the image remounts it with the carried-over text.
 */
const FigureCaption = ({
  figure,
  onCommit,
}: {
  figure: SectionFigure;
  onCommit: (caption: string) => void;
}) => {
  const field = useInlineTextEdit({
    value: figure.caption ?? "",
    onCommit,
    optional: true,
  });

  return (
    <div className="mt-hestia-2">
      <MarkdownEditField
        field={field}
        optional
        rows={1}
        placeholder="Add a caption…"
        ariaLabel="Figure caption"
        readViewClassName="-mx-hestia-2 cursor-text rounded-hestia-sm px-hestia-2 py-1 transition-colors hover:bg-hestia-primary-muted/25 focus:outline-none focus-visible:ring-2 focus-visible:ring-hestia-primary/40"
        markdownClassName="text-sm text-hestia-text-muted"
      />
    </div>
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
