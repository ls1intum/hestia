import { useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { ImagePlus, Trash2, Loader2 } from "lucide-react";
import { uploadFigure, patchFigure, deleteFigure } from "@/lib/api/api-client";
import { useFigureUrl } from "@/hooks/data/use-figure-url";
import { useSectionFigures, figuresKey } from "@/hooks/data/use-sections";
import type { SectionFigure } from "@/lib/exam/exam-helpers";
import { useToast } from "@/hooks/ui/use-toast";

const MAX_BYTES = 5 * 1024 * 1024;
const MIME = ["image/png", "image/jpeg", "image/webp", "image/gif"];

export const SectionFigures = ({
  examId,
  blockId,
}: {
  examId: string;
  blockId: string;
}) => {
  const { data: figures } = useSectionFigures(blockId);
  const qc = useQueryClient();
  const inputRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const { toast } = useToast();

  const refresh = () =>
    qc.invalidateQueries({ queryKey: figuresKey(blockId) });

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
      await uploadFigure(blockId, file, figures?.length ?? 0);
      refresh();
    } catch (err) {
      console.error("figure upload failed", err);
      toast({
        title: "Could not upload this figure. Please try again.",
        variant: "destructive",
      });
    } finally {
      setUploading(false);
      if (inputRef.current) inputRef.current.value = "";
    }
  };

  return (
    <div className="mt-hestia-3">
      <div className="mb-2 flex items-center justify-between">
        <span className="hestia-eyebrow text-hestia-text-muted">
          Figures
        </span>
        <button
          type="button"
          onClick={() => inputRef.current?.click()}
          disabled={uploading}
          className="inline-flex items-center gap-1 text-xs text-hestia-text-muted hover:text-hestia-primary disabled:opacity-50"
        >
          {uploading ? <Loader2 size={12} className="animate-spin" /> : <ImagePlus size={12} />}
          Add figure
        </button>
        <input
          ref={inputRef}
          type="file"
          accept={MIME.join(",")}
          className="sr-only"
          onChange={(e) => handleFile(e.target.files?.[0])}
        />
      </div>
      {figures && figures.length > 0 ? (
        <div className="grid grid-cols-2 gap-hestia-2 sm:grid-cols-3 md:grid-cols-4">
          {figures.map((f) => (
            <FigureThumb
              key={f.id}
              figure={f}
              onChanged={refresh}
            />
          ))}
        </div>
      ) : (
        <p className="text-xs italic text-hestia-text-muted/70">
          No figures yet.
        </p>
      )}
    </div>
  );
};

const FigureThumb = ({
  figure,
  onChanged,
}: {
  figure: SectionFigure;
  onChanged: () => void;
}) => {
  const url = useFigureUrl(figure.id);
  const [caption, setCaption] = useState(figure.caption ?? "");

  const remove = async () => {
    await deleteFigure(figure.id);
    onChanged();
  };

  const saveCaption = async (next: string) => {
    if (next === (figure.caption ?? "")) return;
    await patchFigure(figure.id, { caption: next || null });
    onChanged();
  };

  return (
    <div className="group relative overflow-hidden rounded-hestia-md border border-hestia-border bg-hestia-bg/40">
      {url ? (
        <img
          src={url}
          alt={caption || "Figure"}
          className="aspect-video w-full object-cover"
          loading="lazy"
        />
      ) : (
        <div className="flex aspect-video w-full items-center justify-center text-xs text-hestia-text-muted">
          …
        </div>
      )}
      <button
        type="button"
        onClick={remove}
        aria-label="Delete figure"
        className="absolute right-1 top-1 rounded-hestia-sm bg-hestia-bg/80 p-1 text-hestia-text-muted opacity-0 transition-opacity hover:text-hestia-danger group-hover:opacity-100"
      >
        <Trash2 size={12} />
      </button>
      <input
        value={caption}
        onChange={(e) => setCaption(e.target.value)}
        onBlur={(e) => saveCaption(e.target.value.trim())}
        placeholder="Caption (optional)"
        className="w-full border-t border-hestia-border bg-transparent px-2 py-1 text-xs text-hestia-text placeholder:text-hestia-text-muted/60 focus:outline-none"
      />
    </div>
  );
};