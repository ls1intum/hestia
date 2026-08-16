import { useState } from "react";
import type { SectionBlock } from "@/lib/exam/exam-helpers";
import { MarkdownView } from "../MarkdownView";
import { BlockHeader } from "@/components/shared/exam-content/BlockHeader";

interface Props {
  block: SectionBlock;
}

export const ReadOnlyContextBlock = ({ block }: Props) => {
  const [collapsed, setCollapsed] = useState(false);
  const hasContent = block.content.trim().length > 0;

  return (
    <article className="rounded-hestia-lg bg-hestia-primary-muted/25 px-hestia-3 py-hestia-2">
      <BlockHeader
        expanded={!collapsed}
        onToggle={() => setCollapsed((v) => !v)}
        label="Context"
        labelVariant="eyebrow"
      />
      {!collapsed && hasContent && (
        <div className="mt-hestia-2 pl-hestia-5">
          <MarkdownView content={block.content} className="text-hestia-text/90" />
        </div>
      )}
    </article>
  );
};
