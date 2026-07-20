/**
 * Rough, page-count-based estimate of how long PDF parsing will take, shown to
 * the author before they start parsing. Derived empirically from test exams:
 * normal parsing runs ~4.5 s/page, Fast Mode ~3 s/page.
 */

/** Seconds per page for normal parsing. */
export const SECONDS_PER_PAGE = 4.5;
/** Seconds per page with Fast Mode on. */
export const SECONDS_PER_PAGE_FAST = 3;

/**
 * Estimated parse time in seconds, rounded to a whole multiple of 5 (min 5) so
 * the UI never shows odd numbers like "43 sec".
 */
export function estimateParseSeconds(pages: number, fastMode: boolean): number {
  const perPage = fastMode ? SECONDS_PER_PAGE_FAST : SECONDS_PER_PAGE;
  const raw = pages * perPage;
  return Math.max(5, Math.round(raw / 5) * 5);
}

/**
 * Human-friendly duration for the estimate: seconds under a minute (already a
 * multiple of 5), otherwise whole minutes. Kept clean on purpose — this is a
 * rough guide, not a countdown.
 */
export function formatParseEstimate(pages: number, fastMode: boolean): string {
  const seconds = estimateParseSeconds(pages, fastMode);
  if (seconds < 60) return `~${seconds} sec`;
  const minutes = Math.round(seconds / 60);
  return `~${minutes} min`;
}

/**
 * Live parsing progress derived from elapsed time against the page-count
 * estimate. Used to drive a smooth in-progress bar + remaining-time countdown.
 * Always uses the normal (non-Fast-Mode) per-page rate: we don't persist the
 * fast-mode flag, so a Fast-Mode parse simply finishes a little early.
 *
 * `percent` is capped at 95 so the bar never claims completion before the status
 * actually flips; `overrun` marks that we've passed the estimate (the tail).
 */
export function parseCountdown(
  pages: number,
  elapsedSeconds: number,
): { percent: number; remainingSeconds: number; overrun: boolean } {
  const total = estimateParseSeconds(pages, false);
  const elapsed = Math.max(0, elapsedSeconds);
  const percent = Math.min(95, Math.round((elapsed / total) * 100));
  return {
    percent,
    remainingSeconds: Math.max(0, total - elapsed),
    overrun: elapsed >= total,
  };
}

/** Human-friendly "time left" for the parsing countdown. */
export function formatRemaining(remainingSeconds: number, overrun: boolean): string {
  if (overrun) return "Finishing up…";
  if (remainingSeconds <= 5) return "Almost done…";
  if (remainingSeconds < 60) {
    const secs = Math.max(5, Math.round(remainingSeconds / 5) * 5);
    return `~${secs} sec left`;
  }
  return `~${Math.round(remainingSeconds / 60)} min left`;
}
