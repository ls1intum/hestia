import type { ReactNode } from "react";

/** Muted single-line status message for admin panel loading / empty states. */
export const PanelMessage = ({ children }: { children: ReactNode }) => (
  <p className="text-sm text-hestia-text-muted">{children}</p>
);
