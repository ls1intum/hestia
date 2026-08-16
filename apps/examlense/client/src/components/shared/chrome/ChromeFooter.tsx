import type { ReactNode } from "react";

interface Props {
  left?: ReactNode;
  center?: ReactNode;
  right?: ReactNode;
}

/** Generic sticky footer chrome shared by exam-edit and grading views. */
export const ChromeFooter = ({ left, center, right }: Props) => (
  <footer className="sticky bottom-0 z-30 border-t border-hestia-border bg-hestia-surface/95 backdrop-blur">
    <div className="mx-auto flex w-full max-w-[1280px] items-center gap-hestia-3 px-hestia-5 py-hestia-2">
      <div className="flex flex-1 items-center justify-start gap-hestia-2">
        {left}
      </div>
      {center != null && (
        <div className="hidden flex-1 items-center justify-center sm:inline-flex">
          {center}
        </div>
      )}
      <div className="flex flex-1 items-center justify-end gap-hestia-2">
        {right}
      </div>
    </div>
  </footer>
);