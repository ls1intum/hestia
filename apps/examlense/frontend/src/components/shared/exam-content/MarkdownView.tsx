import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import remarkMath from "remark-math";
import rehypeKatex from "rehype-katex";
import "katex/dist/katex.min.css";
import { cn } from "@/lib/utils/utils";

interface Props {
  content: string;
  className?: string;
}

// NOTE: --hestia-border embeds an alpha channel, so Tailwind slash-opacity
// modifiers (border-hestia-border/60) compile to invalid CSS — use the plain
// token. It is already lighter (0.15) than the input default (--input, 0.28).
export const markdownSurfaceClassName =
  "min-h-[80px] cursor-text rounded-hestia-md border border-hestia-border bg-hestia-surface px-hestia-3 py-hestia-2 text-sm text-hestia-text transition-colors hover:border-hestia-primary/45 focus:outline-none focus-visible:ring-2 focus-visible:ring-hestia-primary/40";

export const markdownTextareaClassName =
  "min-h-[80px] resize-y rounded-hestia-md border-hestia-border bg-hestia-surface px-hestia-3 py-hestia-2 font-body text-sm leading-relaxed text-hestia-text placeholder:text-hestia-text-muted/45 focus-visible:ring-hestia-primary/40 focus-visible:ring-offset-0";

export const MarkdownView = ({ content, className }: Props) => {
  return (
    <div className={cn("text-sm leading-relaxed text-hestia-text", className)}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm, remarkMath]}
        rehypePlugins={[rehypeKatex]}
        components={{
          p: ({ node, ...p }) => <p className="mb-2 last:mb-0 text-sm leading-relaxed" {...p} />,
          strong: ({ node, ...p }) => <strong className="font-semibold" {...p} />,
          em: ({ node, ...p }) => <em className="italic" {...p} />,
          del: ({ node, ...p }) => <del className="opacity-70" {...p} />,
          h1: ({ node, ...p }) => (
            <h1 className="mb-2 mt-3 font-body text-base font-semibold leading-snug first:mt-0" {...p} />
          ),
          h2: ({ node, ...p }) => (
            <h2 className="mb-2 mt-3 font-body text-base font-semibold leading-snug first:mt-0" {...p} />
          ),
          h3: ({ node, ...p }) => (
            <h3 className="mb-1.5 mt-2.5 font-body text-sm font-semibold leading-snug first:mt-0" {...p} />
          ),
          h4: ({ node, ...p }) => (
            <h4 className="mb-1.5 mt-2.5 font-body text-sm font-semibold leading-snug first:mt-0" {...p} />
          ),
          ul: ({ node, ...p }) => <ul className="mb-2 list-disc space-y-1 pl-5 last:mb-0" {...p} />,
          ol: ({ node, ...p }) => <ol className="mb-2 list-decimal space-y-1 pl-5 last:mb-0" {...p} />,
          li: ({ node, ...p }) => <li className="text-sm leading-relaxed" {...p} />,
          a: ({ node, ...p }) => (
            <a
              className="text-hestia-primary underline underline-offset-2 hover:opacity-80"
              target="_blank"
              rel="noreferrer noopener"
              {...p}
            />
          ),
          blockquote: ({ node, ...p }) => (
            <blockquote
              className="mb-2 border-l-2 border-hestia-border pl-3 italic text-hestia-text-muted last:mb-0"
              {...p}
            />
          ),
          hr: () => <hr className="my-3 border-hestia-border" />,
          code: ({ node, className: c, children, ...rest }) => {
            const text = Array.isArray(children)
              ? children.join("")
              : typeof children === "string"
                ? children
                : "";
            const isBlock = /language-/.test(c || "") || text.includes("\n");
            if (isBlock) {
              return (
                <code
                  className={cn(
                    "block bg-transparent font-mono text-sm leading-relaxed text-hestia-text whitespace-pre",
                    c,
                  )}
                  {...rest}
                >
                  {children}
                </code>
              );
            }
            return (
              <code
                className="rounded bg-hestia-text/[0.08] px-1.5 py-0.5 font-mono text-[0.875em] text-hestia-text break-words"
                {...rest}
              >
                {children}
              </code>
            );
          },
          pre: ({ node, ...p }) => (
            <pre
              className="mb-2 overflow-x-auto rounded-hestia-md border border-hestia-border bg-hestia-text/[0.06] p-hestia-3 text-sm leading-relaxed last:mb-0"
              {...p}
            />
          ),
          table: ({ node, ...p }) => (
            <div className="mb-2 overflow-x-auto last:mb-0">
              <table className="w-full border-collapse text-xs" {...p} />
            </div>
          ),
          th: ({ node, ...p }) => (
            <th
              className="border border-hestia-border bg-hestia-primary-muted/20 px-2 py-1 text-left font-semibold"
              {...p}
            />
          ),
          td: ({ node, ...p }) => (
            <td className="border border-hestia-border px-2 py-1" {...p} />
          ),
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  );
};
