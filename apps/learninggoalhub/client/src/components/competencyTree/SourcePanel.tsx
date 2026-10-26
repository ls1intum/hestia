import { lazy, Suspense, type RefObject } from "react";
import { API_PREFIX } from "../../api/client.ts";
import ErrorBoundary from "../ErrorBoundary.tsx";
import { SessionName } from "./GridRow.tsx";
import type { Row } from "./model.ts";

// Lazily loaded so the heavy pdf.js bundle only ships once a source is opened, not on first paint.
const SourcePdfPane = lazy(() => import("../SourcePdfPane.tsx"));

type GoalSource = NonNullable<Row["goal"]["sources"]>[number];

/** The PDF panel beside the grid, showing where the open goal's first source sits. */
export function SourcePanel({
  courseId,
  row,
  source,
  paneRef,
  onClose,
}: {
  courseId: number;
  row: Row | undefined;
  source: GoalSource;
  paneRef: RefObject<HTMLDivElement>;
  onClose: () => void;
}) {
  return (
    <div
      ref={paneRef}
      tabIndex={-1}
      className="w-full outline-none lg:sticky lg:top-4 lg:w-auto lg:shrink-0"
    >
      <ErrorBoundary
        resetKey={source.documentId}
        fallback={
          <div className="flex min-h-[32rem] w-full flex-col items-center justify-center gap-2 rounded-lg border border-hestia-border bg-hestia-surface text-center text-xs text-hestia-text-muted lg:w-[min(44vw,42rem)]">
            <p>Could not load the PDF preview.</p>
            {source.contentAvailable && (
              <a
                href={`${API_PREFIX}/api/courses/${courseId}/documents/${source.documentId}/content${source.page ? `#page=${source.page}` : ""}`}
                target="_blank"
                rel="noreferrer"
                className="font-medium text-hestia-primary underline underline-offset-2"
              >
                Open PDF in a new tab
              </a>
            )}
          </div>
        }
      >
        <Suspense
          fallback={
            <div className="flex min-h-[32rem] w-full items-center justify-center rounded-lg border border-hestia-border bg-hestia-surface text-xs text-hestia-text-muted lg:w-[min(44vw,42rem)]">
              Loading preview…
            </div>
          }
        >
          <SourcePdfPane
            courseId={courseId}
            source={source}
            onClose={onClose}
            headerExtra={
              row?.session ? (
                <SessionName
                  key={row.goal.hierarchy?.sessionId ?? row.session}
                  courseId={courseId}
                  sessionId={row.goal.hierarchy?.sessionId}
                  label={row.session}
                />
              ) : undefined
            }
          />
        </Suspense>
      </ErrorBoundary>
    </div>
  );
}
