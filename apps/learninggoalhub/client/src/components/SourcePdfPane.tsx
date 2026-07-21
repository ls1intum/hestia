import { useEffect, useRef, useState } from "react";
import * as pdfjsLib from "pdfjs-dist";
import { API_PREFIX, type GoalSource } from "../api/client.ts";

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

export default function SourcePdfPane({
  courseId,
  source,
  onClose,
}: {
  courseId: number | string;
  source: GoalSource;
  onClose: () => void;
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
    let cancelled = false;
    let loadedDocument: PdfDocument | null = null;
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
    const loadPage = async () => {
      try {
        loadedDocument = await loadingTask.promise;
        const page = await loadedDocument.getPage(pageNumber);
        const textContent = await page.getTextContent();
        if (cancelled) {
          await loadedDocument.destroy();
          return;
        }
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
      if (loadedDocument) {
        void loadedDocument.destroy();
      } else {
        void loadingTask.destroy();
      }
    };
  }, [contentUrl, pageNumber]);

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
    setRenderedPage({
      width: viewport.width,
      height: viewport.height,
      highlights: findHighlightRects(
        loadedPage.textItems,
        source.snippet,
        viewport,
      ),
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
  }, [loadedPage, paneWidth, source.snippet]);

  useEffect(() => {
    if (renderedPage?.highlights.length) {
      highlightRef.current?.scrollIntoView({ block: "center", behavior: "smooth" });
    }
  }, [renderedPage]);

  return (
    <section className="flex min-h-[32rem] max-h-[76vh] min-w-0 w-full flex-col overflow-hidden rounded-lg border border-hestia-border bg-hestia-surface shadow-lg lg:w-[min(44vw,42rem)]">
      <header className="flex shrink-0 items-center justify-between gap-3 border-b border-hestia-border px-3.5 py-2.5">
        <div className="min-w-0">
          <p className="truncate text-xs font-semibold text-hestia-text">
            {source.displayName ?? source.filename ?? "Source document"}
          </p>
          <p className="text-[0.65rem] text-hestia-text-muted">p. {pageNumber}</p>
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
