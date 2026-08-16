import type { ReactNode } from "react";

/** Labeled field wrapper matching the dialog's form styling. */
export const Field = ({
  label,
  children,
}: {
  label: string;
  children: ReactNode;
}) => (
  <label className="block">
    <span className="mb-1 block text-xs font-medium text-hestia-text-muted">
      {label}
    </span>
    {children}
  </label>
);
