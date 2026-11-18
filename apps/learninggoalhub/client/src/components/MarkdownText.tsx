import type { ReactNode } from "react";

/**
 * Renders the small Markdown subset the terms of use use: headings (#, ##), numbered and bulleted
 * lists, paragraphs and **bold**. HTML comments are dropped. No HTML from the text is rendered.
 */
export default function MarkdownText({ text }: { text: string }) {
  const lines = text.replace(/<!--[\s\S]*?-->/g, "").split(/\r?\n/);
  const blocks: ReactNode[] = [];
  let paragraph: string[] = [];
  let list: { ordered: boolean; items: string[] } | null = null;

  const flushParagraph = () => {
    if (paragraph.length > 0) {
      blocks.push(
        <p key={blocks.length} className="mb-3">
          {inline(paragraph.join(" "))}
        </p>,
      );
      paragraph = [];
    }
  };
  const flushList = () => {
    if (list) {
      const items = list.items.map((item, i) => <li key={i}>{inline(item)}</li>);
      blocks.push(
        list.ordered ? (
          <ol key={blocks.length} className="mb-3 list-decimal space-y-1 pl-6">
            {items}
          </ol>
        ) : (
          <ul key={blocks.length} className="mb-3 list-disc space-y-1 pl-6">
            {items}
          </ul>
        ),
      );
      list = null;
    }
  };

  for (const raw of lines) {
    const line = raw.trim();
    const heading = /^(#{1,3})\s+(.*)$/.exec(line);
    const ordered = /^\d+\.\s+(.*)$/.exec(line);
    const bullet = /^[-*]\s+(.*)$/.exec(line);
    if (line === "") {
      flushParagraph();
      flushList();
    } else if (heading) {
      flushParagraph();
      flushList();
      const level = heading[1].length;
      blocks.push(
        level === 1 ? (
          <h2 key={blocks.length} className="mb-2 text-lg font-bold">
            {inline(heading[2])}
          </h2>
        ) : (
          <h3 key={blocks.length} className="mb-2 mt-4 font-semibold">
            {inline(heading[2])}
          </h3>
        ),
      );
    } else if (ordered || bullet) {
      flushParagraph();
      const isOrdered = ordered != null;
      if (!list || list.ordered !== isOrdered) {
        flushList();
        list = { ordered: isOrdered, items: [] };
      }
      list.items.push((ordered ?? bullet)![1]);
    } else if (list && raw.startsWith("  ")) {
      // A continuation line of the last list item.
      list.items[list.items.length - 1] += " " + line;
    } else {
      flushList();
      paragraph.push(line);
    }
  }
  flushParagraph();
  flushList();
  return <div className="text-sm leading-relaxed">{blocks}</div>;
}

function inline(text: string): ReactNode[] {
  return text.split(/(\*\*[^*]+\*\*)/g).map((part, i) =>
    part.startsWith("**") && part.endsWith("**") ? <strong key={i}>{part.slice(2, -2)}</strong> : part,
  );
}
