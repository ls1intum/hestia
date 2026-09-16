import type { DocumentResponse } from "../api/client.ts";

/** Lecture or exercise, chosen at upload. Documents uploaded before the choice have none. */
export type DocumentKind = NonNullable<DocumentResponse["kind"]>;

export const DOCUMENT_KIND_LABEL: Record<DocumentKind, string> = {
  LECTURE: "Lecture",
  EXERCISE: "Exercise",
};
