import { useState } from "react";
import { useFigureUrl } from "@/hooks/data/use-figure-url";
import { useSectionFigures } from "@/hooks/data/use-sections";
import type { SectionBlock, SectionFigure } from "@/lib/exam/exam-helpers";
import { BlockHeader } from "@/components/shared/exam-content/BlockHeader";

interface Props {
  block: SectionBlock;
  displayLabel?: string;
}

export const ReadOnlyFigureBlock = ({ block: _block, displayLabel }: Props) => {
  const [collapsed, setCollapsed] = useState(false);
  const { data: figures } = useSectionFigures(_block.id);
  const figure: SectionFigure | undefined = figures?.[0];
  const caption = displayLabel ?? "Figure";

  return (
    <article className="rounded-hestia-lg bg-hestia-primary-muted/25 px-hestia-3 py-hestia-2">
      <BlockHeader
        expanded={!collapsed}
        onToggle={() => setCollapsed((v) => !v)}
        label={caption}
        labelVariant="eyebrow"
      />
      {!collapsed && figure && (
        <div className="mt-hestia-2 pl-hestia-5">
          <FigureThumb figure={figure} />
        </div>
      )}
    </article>
  );
};

const FigureThumb = ({ figure }: { figure: SectionFigure }) => {
  const url = useFigureUrl(figure.id);

  return (
    <div className="overflow-hidden rounded-hestia-md border border-hestia-border bg-hestia-bg/40">
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
    </div>
  );
};
