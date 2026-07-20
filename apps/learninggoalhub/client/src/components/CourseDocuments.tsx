import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../api/client.ts";
import type { DocumentResponse } from "../api/client.ts";

/**
 * Inline list of a course's uploaded documents with inline rename. Rendered under an expanded
 * course row on the overview. The filename is immutable provenance (goal sources and the CSV
 * export cite it); renaming only sets a display name, and clearing it falls back to the filename.
 *
 * Mount this only when the row is expanded — the documents query fires on mount, so keeping it
 * unmounted while collapsed keeps the overview from firing one request per course up front.
 */
export default function CourseDocuments({ courseId }: { courseId: number }) {
  const queryClient = useQueryClient();
  const [editingId, setEditingId] = useState<number | null>(null);

  const documentsQuery = useQuery({
    queryKey: ["documents", courseId],
    queryFn: async () => {
      const { data, error } = await api.GET("/api/courses/{courseId}/documents", {
        params: { path: { courseId } },
      });
      if (error || !data) throw new Error("Could not load the documents.");
      return data;
    },
  });

  const renameMutation = useMutation({
    mutationFn: async (vars: { documentId: number; displayName: string | null }) => {
      const { error } = await api.PATCH(
        "/api/courses/{courseId}/documents/{documentId}",
        {
          params: { path: { courseId, documentId: vars.documentId } },
          body: { displayName: vars.displayName ?? undefined },
          // openapi-fetch drops undefined body keys, but clearing needs an explicit null.
          bodySerializer: (body) =>
            JSON.stringify({ displayName: body?.displayName ?? null }),
        },
      );
      if (error) throw new Error("Could not rename the document.");
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["documents", courseId] });
      setEditingId(null);
    },
  });

  const documents = documentsQuery.data ?? [];

  return (
    <div className="border-t border-hestia-border bg-hestia-bg/40 px-5 py-4 pl-14">
      {documentsQuery.isLoading && (
        <p className="text-sm text-hestia-text-muted">Loading…</p>
      )}
      {documentsQuery.isError && (
        <p className="text-sm text-hestia-danger">
          {(documentsQuery.error as Error).message}
        </p>
      )}
      {!documentsQuery.isLoading && !documentsQuery.isError && documents.length === 0 && (
        <p className="rounded-lg border border-dashed border-hestia-border p-4 text-center text-sm text-hestia-text-muted">
          No documents uploaded for this course.
        </p>
      )}
      {documents.length > 0 && (
        <ul className="flex flex-col gap-2">
          {documents.map((doc) => (
            <DocumentRow
              key={doc.id}
              document={doc}
              editing={editingId === doc.id}
              busy={renameMutation.isPending}
              error={
                editingId === doc.id && renameMutation.isError
                  ? (renameMutation.error as Error).message
                  : undefined
              }
              onEdit={() => {
                renameMutation.reset();
                setEditingId(doc.id!);
              }}
              onCancel={() => {
                renameMutation.reset();
                setEditingId(null);
              }}
              onSave={(displayName) =>
                renameMutation.mutate({ documentId: doc.id!, displayName })
              }
            />
          ))}
        </ul>
      )}
    </div>
  );
}

/** One document: display name (or filename), rename pencil, and an inline edit form. */
function DocumentRow({
  document,
  editing,
  busy,
  error,
  onEdit,
  onCancel,
  onSave,
}: {
  document: DocumentResponse;
  editing: boolean;
  busy: boolean;
  error?: string;
  onEdit: () => void;
  onCancel: () => void;
  onSave: (displayName: string | null) => void;
}) {
  const shown = document.displayName ?? document.filename ?? "";
  const [draft, setDraft] = useState(shown);
  useEffect(() => {
    if (editing) setDraft(shown);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset the draft only when editing starts
  }, [editing]);

  const uploaded = document.uploadedAt
    ? new Date(document.uploadedAt).toLocaleDateString()
    : null;
  const trimmed = draft.trim();
  const canSave = trimmed !== "" && trimmed !== shown && !busy;

  return (
    <li className="rounded-lg border border-hestia-border bg-hestia-surface p-3">
      {editing ? (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            if (canSave) onSave(trimmed);
          }}
          className="flex flex-col gap-2"
        >
          <input
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            autoFocus
            className="w-full rounded-sm border-[1.5px] border-hestia-border bg-hestia-surface px-2.5 py-1.5 text-sm text-hestia-text transition focus:border-hestia-primary focus:outline-none"
          />
          {error && <p className="text-sm text-hestia-danger">{error}</p>}
          <div className="flex items-center justify-between gap-2">
            {document.displayName ? (
              <button
                type="button"
                disabled={busy}
                onClick={() => onSave(null)}
                className="text-xs text-hestia-text-muted underline transition hover:text-hestia-text disabled:opacity-50"
              >
                Reset to filename
              </button>
            ) : (
              <span />
            )}
            <div className="flex gap-2">
              <button
                type="button"
                onClick={onCancel}
                disabled={busy}
                className="rounded-md border border-hestia-border px-2.5 py-1 text-sm font-medium text-hestia-text transition hover:bg-hestia-primary-muted disabled:opacity-50"
              >
                Cancel
              </button>
              <button
                type="submit"
                disabled={!canSave}
                className="rounded-md bg-hestia-primary px-2.5 py-1 text-sm font-medium text-white transition hover:bg-hestia-primary-hover disabled:opacity-50"
              >
                {busy ? "Saving…" : "Save"}
              </button>
            </div>
          </div>
        </form>
      ) : (
        <div className="group flex items-center gap-3">
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm text-hestia-text" title={shown}>
              {shown}
            </p>
            <p className="mt-0.5 truncate text-xs text-hestia-text-muted">
              {/* The filename stays visible as provenance once a display name covers it. */}
              {document.displayName ? document.filename : null}
              {document.displayName && uploaded ? " · " : null}
              {uploaded ? `Uploaded ${uploaded}` : null}
            </p>
          </div>
          <button
            type="button"
            title="Rename document"
            aria-label={`Rename ${shown}`}
            onClick={onEdit}
            className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md text-hestia-text-muted opacity-0 transition focus-visible:opacity-100 group-hover:opacity-100 hover:bg-hestia-primary-muted hover:text-hestia-text"
          >
            <svg
              viewBox="0 0 20 20"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
              className="h-4 w-4"
            >
              <path d="M13.5 3.5l3 3L7 16l-3.7.7L4 13z" />
            </svg>
          </button>
        </div>
      )}
    </li>
  );
}
