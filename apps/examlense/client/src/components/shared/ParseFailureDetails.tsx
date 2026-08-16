import { useState } from "react";

/**
 * Body of a parse-failure toast: slim by default (just a "View details" link),
 * expanding in place to the full error message on click. Rendered inside the
 * toast's ToastDescription, so it inherits the tonal-red destructive styling.
 */
export function ParseFailureDetails({ message }: { message: string }) {
  const [expanded, setExpanded] = useState(false);

  if (expanded) {
    return <p className="leading-relaxed">{message}</p>;
  }
  return (
    <button
      type="button"
      onClick={() => setExpanded(true)}
      className="font-medium underline underline-offset-2 hover:opacity-80"
    >
      View details
    </button>
  );
}
