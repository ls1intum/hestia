import { ReactNode } from "react";

export function Alert({ children }: { children: ReactNode }) {
  return (
    <div
      role="status"
      className="flex items-start gap-2.5 rounded-hestia-md border border-hestia-accent bg-hestia-accent-muted px-4 py-3 text-sm leading-relaxed text-hestia-text"
    >
      <svg
        className="mt-0.5 shrink-0 text-hestia-accent"
        width="16"
        height="16"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        <circle cx="12" cy="12" r="10" />
        <path d="M12 16v-4" />
        <path d="M12 8h.01" />
      </svg>
      <span>{children}</span>
    </div>
  );
}
