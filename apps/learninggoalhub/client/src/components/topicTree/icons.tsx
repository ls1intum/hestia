

export function InfoIcon() {
  return (
    <svg
      viewBox="0 0 20 20"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      aria-hidden="true"
      className="h-4 w-4 shrink-0"
    >
      <circle cx="10" cy="10" r="7.5" />
      <path d="M10 9v5M10 6.2v.01" />
    </svg>
  );
}

export function DocumentIcon() {
  return (
    <svg
      viewBox="0 0 20 20"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinejoin="round"
      aria-hidden="true"
      className="h-3.5 w-3.5 shrink-0"
    >
      <path d="M5 2.5h7l3.5 3.5v11.5H5z" />
      <path d="M8 10h5M8 13h5" />
    </svg>
  );
}

/** Two strands merging into one: something built from other goals rather than quoted. */
export function GeneratedIcon() {
  return (
    <svg
      viewBox="0 0 20 20"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      className="h-3.5 w-3.5 shrink-0"
    >
      <path d="M4 3v4a4 4 0 004 4h2M16 3v4a4 4 0 01-4 4h-2M10 11v6" />
    </svg>
  );
}

/** Icons for the layout switch. Sized to sit inline with the label text. */
export function DiagramIcon() {
  return (
    <svg
      viewBox="0 0 20 20"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      className="h-4 w-4"
    >
      <rect x="7.5" y="2.5" width="5" height="4" rx="1" />
      <rect x="2" y="13.5" width="5" height="4" rx="1" />
      <rect x="13" y="13.5" width="5" height="4" rx="1" />
      <path d="M10 6.5v3M10 9.5H4.5v4M10 9.5h5.5v4" />
    </svg>
  );
}

export function TableIcon() {
  return (
    <svg
      viewBox="0 0 20 20"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      className="h-4 w-4"
    >
      <rect x="3" y="4" width="14" height="12" rx="1.5" />
      <path d="M3 8h14M8 8v8" />
    </svg>
  );
}

/** Chevrons pointing apart (expand) or together (collapse), matching the button's current action. */
export function FoldIcon({ collapse }: { collapse: boolean }) {
  return (
    <svg
      viewBox="0 0 20 20"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      className="h-4 w-4"
    >
      {collapse ? (
        <path d="M6.5 3.5L10 7l3.5-3.5M6.5 16.5L10 13l3.5 3.5" />
      ) : (
        <path d="M6.5 6.5L10 3l3.5 3.5M6.5 13.5L10 17l3.5-3.5" />
      )}
    </svg>
  );
}

export function SlidersIcon() {
  return (
    <svg
      viewBox="0 0 20 20"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      className="h-4 w-4"
    >
      <path d="M17 4.5h-5.5M8 4.5H3M17 10h-7M6.5 10H3M17 15.5h-3.5M10 15.5H3M11.5 3v3M6.5 8.5v3M13.5 14v3" />
    </svg>
  );
}

export function CrossIcon() {
  return (
    <svg
      viewBox="0 0 20 20"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.5"
      strokeLinecap="round"
      aria-hidden="true"
      className="h-3 w-3"
    >
      <path d="M5 5l10 10M15 5L5 15" />
    </svg>
  );
}
