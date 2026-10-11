import { useEffect, useRef, useState, type ReactNode } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import * as pdfjsLib from "pdfjs-dist";
import { API_PREFIX, api, type GoalSource } from "../api/client.ts";

pdfjsLib.GlobalWorkerOptions.workerSrc = new URL(
  "pdfjs-dist/build/pdf.worker.min.mjs",
  import.meta.url,
).toString();

type PdfDocument = Awaited<ReturnType<typeof pdfjsLib.getDocument>["promise"]>;
type PdfPage = Awaited<ReturnType<PdfDocument["getPage"]>>;
type PdfViewport = ReturnType<PdfPage["getViewport"]>;
type PdfRenderTask = ReturnType<PdfPage["render"]>;
type Transform = [number, number, number, number, number, number];

type PdfTextItem = {
  str: string;
  transform: Transform;
  width: number;
  height: number;
};

type HighlightRect = {
  left: number;
  top: number;
  width: number;
  height: number;
};

type LoadedPage = {
  page: PdfPage;
  textItems: PdfTextItem[];
};

type RenderedPage = {
  width: number;
  height: number;
  highlights: HighlightRect[];
};

const normalizeWhitespace = (text: string) => text.replace(/\s+/g, " ").trim();

function itemRect(item: PdfTextItem, viewport: PdfViewport): HighlightRect {
  const transform = pdfjsLib.Util.transform(viewport.transform, item.transform);
  const height = Math.hypot(transform[2], transform[3]);
  const angle = Math.atan2(transform[1], transform[0]);
  const ascent = height * 0.8;
  const width = item.width * viewport.scale;
  const unitX = { x: Math.cos(angle), y: Math.sin(angle) };
  const unitUp = { x: Math.sin(angle), y: -Math.cos(angle) };
  const topLeft = {
    x: transform[4] + unitUp.x * ascent,
    y: transform[5] + unitUp.y * ascent,
  };
  const bottomLeft = {
    x: transform[4] - unitUp.x * (height - ascent),
    y: transform[5] - unitUp.y * (height - ascent),
  };
  const points = [
    topLeft,
    { x: topLeft.x + unitX.x * width, y: topLeft.y + unitX.y * width },
    bottomLeft,
    { x: bottomLeft.x + unitX.x * width, y: bottomLeft.y + unitX.y * width },
  ];
  const xs = points.map((point) => point.x);
  const ys = points.map((point) => point.y);
  const left = Math.min(...xs);
  const top = Math.min(...ys);

  return {
    left,
    top,
    width: Math.max(2, Math.max(...xs) - left),
    height: Math.max(2, Math.max(...ys) - top),
  };
}

function findHighlightRects(
  items: PdfTextItem[],
  snippet: string | undefined,
  viewport: PdfViewport,
) {
  const needle = normalizeWhitespace(snippet ?? "");
  if (!needle) return [];

  let cursor = 0;
  const entries = items
    .map((item) => {
      const text = normalizeWhitespace(item.str);
      if (!text) return null;
      const start = cursor;
      cursor += text.length + 1;
      return { item, start, end: start + text.length };
    })
    .filter((entry): entry is NonNullable<typeof entry> => entry !== null);
  const pageText = entries.map((entry) => normalizeWhitespace(entry.item.str)).join(" ");
  const matchStart = pageText.indexOf(needle);
  if (matchStart < 0) return [];

  const matchEnd = matchStart + needle.length;
  return entries
    .filter((entry) => entry.start < matchEnd && entry.end > matchStart)
    .map((entry) => itemRect(entry.item, viewport));
}

function convertHighlightRects(
  rects: GoalSource["highlightRects"],
  viewport: PdfViewport,
): HighlightRect[] {
  return (rects ?? []).flatMap((rect) => {
    if (
      rect.x == null ||
      rect.y == null ||
      rect.width == null ||
      rect.height == null
    ) {
      return [];
    }
    const [x1, y1, x2, y2] = viewport.convertToViewportRectangle([
      rect.x,
      rect.y,
      rect.x + rect.width,
      rect.y + rect.height,
    ]);
    const left = Math.min(x1, x2);
    const top = Math.min(y1, y2);
    return [
      {
        left,
        top,
        width: Math.max(2, Math.abs(x2 - x1)),
        height: Math.max(2, Math.abs(y2 - y1)),
      },
    ];
  });
}

export default function SourcePdfPane({
  courseId,
  source,
  onClose,
  headerExtra,
}: {
  courseId: number | string;
  source: GoalSource;
  onClose: () => void;
  /** Extra lines under the document name and page, such as the source's session. */
  headerExtra?: ReactNode;
}) {
  const contentUrl =
    source.documentId == null
      ? null
      : `${API_PREFIX}/api/courses/${courseId}/documents/${source.documentId}/content`;
  const pageNumber = source.page && source.page > 0 ? source.page : 1;
  const externalUrl = contentUrl ? `${contentUrl}#page=${pageNumber}` : null;
  const paneRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const highlightRef = useRef<HTMLDivElement>(null);
  const [paneWidth, setPaneWidth] = useState(0);
  const [pdfDocument, setPdfDocument] = useState<PdfDocument | null>(null);
  // The page on show; it starts on the source's page and the arrows move it through the document.
  const [viewedPage, setViewedPage] = useState(pageNumber);
  const [loadedPage, setLoadedPage] = useState<LoadedPage | null>(null);
  const [renderedPage, setRenderedPage] = useState<RenderedPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const pane = paneRef.current;
    if (!pane) return;
    const updateWidth = () => setPaneWidth(pane.clientWidth);
    updateWidth();
    const observer = new ResizeObserver(updateWidth);
    observer.observe(pane);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    setViewedPage(pageNumber);
  }, [contentUrl, pageNumber, source.snippet]);

  // The document loads once per file; paging through it only fetches and renders another page.
  useEffect(() => {
    let cancelled = false;
    let loadedDocument: PdfDocument | null = null;
    setPdfDocument(null);
    setLoadedPage(null);
    setRenderedPage(null);
    setLoading(true);
    setError(null);

    if (!contentUrl) {
      setLoading(false);
      setError("This source document is not available for preview.");
      return;
    }

    const loadingTask = pdfjsLib.getDocument({ url: contentUrl });
    const loadDocument = async () => {
      try {
        loadedDocument = await loadingTask.promise;
        if (cancelled) {
          await loadedDocument.destroy();
          return;
        }
        setPdfDocument(loadedDocument);
      } catch {
        if (!cancelled) {
          setLoading(false);
          setError("Could not load this PDF.");
        }
      }
    };
    void loadDocument();

    return () => {
      cancelled = true;
      if (loadedDocument) {
        void loadedDocument.destroy();
      } else {
        void loadingTask.destroy();
      }
    };
  }, [contentUrl]);

  useEffect(() => {
    if (!pdfDocument) return;
    let cancelled = false;
    const loadPage = async () => {
      try {
        const page = await pdfDocument.getPage(
          Math.min(Math.max(viewedPage, 1), pdfDocument.numPages),
        );
        const textContent = await page.getTextContent();
        if (cancelled) return;
        const textItems = textContent.items.filter((item) => "str" in item) as unknown as
          PdfTextItem[];
        setLoadedPage({ page, textItems });
        setLoading(false);
      } catch {
        if (!cancelled) {
          setLoading(false);
          setError("Could not load this PDF.");
        }
      }
    };
    void loadPage();
    return () => {
      cancelled = true;
    };
  }, [pdfDocument, viewedPage]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || !loadedPage) return;
    let cancelled = false;
    let renderTask: PdfRenderTask | null = null;
    const baseViewport = loadedPage.page.getViewport({ scale: 1 });
    const availableWidth = Math.max(240, (paneWidth || 600) - 24);
    const scale = Math.max(0.5, availableWidth / baseViewport.width);
    const viewport = loadedPage.page.getViewport({ scale });
    const outputScale = window.devicePixelRatio || 1;
    const context = canvas.getContext("2d");

    if (!context) {
      setError("Could not render this PDF.");
      return;
    }

    canvas.width = Math.floor(viewport.width * outputScale);
    canvas.height = Math.floor(viewport.height * outputScale);
    canvas.style.width = `${viewport.width}px`;
    canvas.style.height = `${viewport.height}px`;
    // Only the source's own page carries the highlight.
    const highlights =
      loadedPage.page.pageNumber !== pageNumber || source.evidenceKind === "FIGURE"
        ? []
        : source.highlightRects?.length
          ? convertHighlightRects(source.highlightRects, viewport)
          : findHighlightRects(loadedPage.textItems, source.snippet, viewport);
    setRenderedPage({
      width: viewport.width,
      height: viewport.height,
      highlights,
    });

    renderTask = loadedPage.page.render({
      canvasContext: context,
      viewport,
      transform:
        outputScale !== 1
          ? [outputScale, 0, 0, outputScale, 0, 0]
          : undefined,
    });
    void renderTask.promise.catch((renderError: unknown) => {
      if (
        !cancelled &&
        !(renderError instanceof Error &&
          renderError.name === "RenderingCancelledException")
      ) {
        setError("Could not render this PDF.");
      }
    });

    return () => {
      cancelled = true;
      renderTask?.cancel();
    };
  }, [loadedPage, paneWidth, pageNumber, source.evidenceKind, source.highlightRects, source.snippet]);

  useEffect(() => {
    if (renderedPage?.highlights.length) {
      highlightRef.current?.scrollIntoView({ block: "center", behavior: "smooth" });
    } else if (paneRef.current) {
      paneRef.current.scrollTop = 0;
    }
  }, [renderedPage]);

  const pageCount = pdfDocument?.numPages ?? null;
  const goToPage = (page: number) => {
    if (pageCount != null && page >= 1 && page <= pageCount) setViewedPage(page);
  };

  return (
    <section className="flex min-h-[32rem] max-h-[76vh] min-w-0 w-full flex-col overflow-hidden rounded-lg border border-hestia-border bg-hestia-surface shadow-lg lg:w-[min(44vw,42rem)]">
      <header className="flex shrink-0 items-start justify-between gap-3 border-b border-hestia-border px-3.5 py-2.5">
        <div className="min-w-0">
          <DocumentName
            courseId={courseId}
            documentId={source.documentId}
            displayName={source.displayName}
            filename={source.filename}
          />
          {headerExtra}
          {source.evidenceKind === "FIGURE" && (
            <div className="mt-1.5 max-w-[22rem]">
              <span className="inline-flex rounded-full border border-hestia-primary/40 bg-hestia-primary-muted px-1.5 py-0.5 text-[10px] font-medium text-hestia-primary">
                Figure-derived (AI description)
              </span>
              {source.figureDescription && (
                // The description is the whole evidence for a figure source, so it is shown in full;
                // a long one scrolls rather than pushing the page off the pane.
                <p className="mt-1 max-h-28 overflow-y-auto text-xs leading-snug text-hestia-text-muted">
                  {source.figureDescription}
                </p>
              )}
            </div>
          )}
        </div>
        {/* The page controls sit beside the close button, so the name and session keep the left. */}
        <div className="flex shrink-0 items-center gap-2">
            <div className="flex items-center gap-1 text-xs text-hestia-text-muted">
              <PageArrow
                label="Previous page"
                disabled={pageCount == null || viewedPage <= 1}
                onClick={() => goToPage(viewedPage - 1)}
                path="M12 5l-5 5 5 5"
              />
              <span className="tabular-nums">
                p. {viewedPage}
                {pageCount != null && ` of ${pageCount}`}
              </span>
              <PageArrow
                label="Next page"
                disabled={pageCount == null || viewedPage >= pageCount}
                onClick={() => goToPage(viewedPage + 1)}
                path="M8 5l5 5-5 5"
              />
              {viewedPage !== pageNumber && (
                <button
                  type="button"
                  onClick={() => setViewedPage(pageNumber)}
                  className="ml-1 whitespace-nowrap font-medium text-hestia-primary underline-offset-2 hover:underline"
                >
                  Back to p. {pageNumber}
                </button>
              )}
            </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close PDF preview"
            className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md text-hestia-text-muted transition hover:bg-hestia-text/10 hover:text-hestia-text"
          >
            <svg
              viewBox="0 0 20 20"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              className="h-4 w-4"
            >
              <path d="M5 5l10 10M15 5L5 15" />
            </svg>
          </button>
        </div>
      </header>
      <div ref={paneRef} className="min-h-0 flex-1 overflow-auto p-3">
        {loading ? (
          <div className="flex h-full min-h-40 items-center justify-center gap-2 text-xs text-hestia-text-muted">
            <span className="h-4 w-4 animate-spin rounded-full border-2 border-hestia-border border-t-hestia-primary" />
            Loading PDF…
          </div>
        ) : error ? (
          <div className="flex h-full min-h-40 flex-col items-center justify-center gap-2 text-center text-xs text-hestia-text-muted">
            <p>{error}</p>
            {externalUrl && (
              <a
                href={externalUrl}
                target="_blank"
                rel="noreferrer"
                className="font-medium text-hestia-primary underline underline-offset-2"
              >
                Open PDF in a new tab
              </a>
            )}
          </div>
        ) : loadedPage ? (
          <div
            className="relative mx-auto"
            style={
              renderedPage
                ? { width: renderedPage.width, height: renderedPage.height }
                : undefined
            }
          >
            <canvas ref={canvasRef} className="block shadow-md" />
            {renderedPage?.highlights.map((highlight, index) => (
              <div
                key={`${highlight.left}-${highlight.top}-${index}`}
                ref={index === 0 ? highlightRef : undefined}
                aria-hidden="true"
                className="pointer-events-none absolute rounded-sm bg-yellow-300/40 ring-1 ring-yellow-500/60"
                style={{
                  left: highlight.left,
                  top: highlight.top,
                  width: highlight.width,
                  height: highlight.height,
                }}
              />
            ))}
          </div>
        ) : null}
      </div>
    </section>
  );
}

function PageArrow({
  label,
  disabled,
  onClick,
  path,
}: {
  label: string;
  disabled: boolean;
  onClick: () => void;
  path: string;
}) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      disabled={disabled}
      onClick={onClick}
      className="flex h-5 w-5 items-center justify-center rounded text-hestia-text-muted transition hover:bg-hestia-primary-muted hover:text-hestia-text disabled:pointer-events-none disabled:opacity-40"
    >
      <svg
        viewBox="0 0 20 20"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
        className="h-3.5 w-3.5"
      >
        <path d={path} />
      </svg>
    </button>
  );
}

/**
 * The document's name with an inline rename, like the session name under it. Renaming sets the
 * display name only; the filename stays as provenance, and clearing the name falls back to it.
 */
function DocumentName({
  courseId,
  documentId,
  displayName,
  filename,
}: {
  courseId: number | string;
  documentId: number | undefined;
  displayName: string | undefined;
  filename: string | undefined;
}) {
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState<string | null>(null);
  const shown = displayName || filename || "Source document";
  const rename = useMutation({
    mutationFn: async (next: string | null) => {
      const { error } = await api.PATCH("/api/courses/{courseId}/documents/{documentId}", {
        params: { path: { courseId: Number(courseId), documentId: documentId! } },
        body: { displayName: next ?? undefined },
        // openapi-fetch drops undefined body keys, but clearing needs an explicit null.
        bodySerializer: (body) => JSON.stringify({ displayName: body?.displayName ?? null }),
      });
      if (error) throw new Error("Could not rename the document.");
    },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["goals", Number(courseId)] }),
        queryClient.invalidateQueries({ queryKey: ["documents", Number(courseId)] }),
      ]);
      setDraft(null);
    },
  });
  const cancel = () => {
    rename.reset();
    setDraft(null);
  };

  if (draft == null) {
    return (
      <p className="flex min-w-0 items-center gap-1">
        <span className="truncate text-xs font-semibold text-hestia-text" title={shown}>
          {shown}
        </span>
        {documentId != null && (
          <button
            type="button"
            aria-label={`Rename document ${shown}`}
            title="Rename this document"
            onClick={() => {
              rename.reset();
              setDraft(shown);
            }}
            className="flex h-5 w-5 shrink-0 items-center justify-center rounded text-hestia-text-muted transition hover:bg-hestia-primary-muted hover:text-hestia-text"
          >
            <svg
              viewBox="0 0 20 20"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
              className="h-3.5 w-3.5"
            >
              <path d="M13.5 3.5l3 3L7 16l-3.7.7L4 13z" />
            </svg>
          </button>
        )}
      </p>
    );
  }
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        const trimmed = draft.trim();
        if (trimmed === "" || trimmed === shown) cancel();
        else if (!rename.isPending) rename.mutate(trimmed);
      }}
      className="flex flex-col gap-1"
    >
      <input
        value={draft}
        autoFocus
        disabled={rename.isPending}
        aria-label="Document name"
        onChange={(e) => setDraft(e.target.value)}
        onKeyDown={(e) => {
          // Escape ends the rename, not the panel around it.
          if (e.key === "Escape") {
            e.preventDefault();
            e.stopPropagation();
            cancel();
          }
        }}
        className="w-full rounded-sm border-[1.5px] border-hestia-primary bg-hestia-bg px-2 py-1 text-xs text-hestia-text outline-none"
      />
      <p
        className={`text-xs leading-snug ${rename.isError ? "text-hestia-danger" : "text-hestia-text-muted"}`}
      >
        {rename.isError ? (
          (rename.error as Error).message
        ) : rename.isPending ? (
          "Saving…"
        ) : (
          <>
            Enter saves, Esc cancels.
            {displayName && filename && (
              <>
                {" "}
                <button
                  type="button"
                  onClick={() => rename.mutate(null)}
                  className="underline transition hover:text-hestia-text"
                >
                  Reset to {filename}
                </button>
              </>
            )}
          </>
        )}
      </p>
    </form>
  );
}
