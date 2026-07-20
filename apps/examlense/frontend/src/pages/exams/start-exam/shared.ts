/** Max upload size for exam PDFs. Single source of truth in the parsing lib. */
export { MAX_PDF_BYTES as MAX_BYTES } from "@/lib/parsing/pdf-precheck";

export const formatBytes = (b: number) => {
  if (b < 1024) return `${b} B`;
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`;
  return `${(b / 1024 / 1024).toFixed(1)} MB`;
};

/**
 * Shorten a long filename for display while keeping the extension, so a huge
 * name (often without break points) can't blow out the layout. Keeps the start
 * of the name + an ellipsis + the extension; show the full name via `title`.
 */
export const truncateFilename = (name: string, max = 42): string => {
  if (name.length <= max) return name;
  const dot = name.lastIndexOf(".");
  const ext = dot > 0 ? name.slice(dot) : "";
  const stem = dot > 0 ? name.slice(0, dot) : name;
  const keep = Math.max(1, max - ext.length - 1); // 1 char for the ellipsis
  return `${stem.slice(0, keep)}…${ext}`;
};
