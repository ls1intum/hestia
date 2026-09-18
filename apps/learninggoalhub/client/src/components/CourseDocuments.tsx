import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../api/client.ts";
import type { DocumentResponse } from "../api/client.ts";
import Button from "./Button.tsx";
import { RowAction } from "./GoalInlineEditing.tsx";
import { DOCUMENT_KIND_LABEL } from "../lib/documents.ts";
import type { DocumentKind } from "../lib/documents.ts";

/**
 * Inline list of a course's uploaded documents with inline rename and kind change. Rendered under
 * an expanded course row on the overview. The filename is immutable provenance (goal sources and the CSV
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

  const kindMutation = useMutation({
    mutationFn: async (vars: { documentId: number; kind: DocumentKind }) => {
      const { error } = await api.PATCH(
        "/api/courses/{courseId}/documents/{documentId}",
        {
          params: { path: { courseId, documentId: vars.documentId } },
          // displayName stays out of the body, so the server leaves it as it is.
          body: { kind: vars.kind },
        },
      );
      if (error) throw new Error("Could not change the document's kind.");
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["documents", courseId] }),
  });

  const documents = documentsQuery.data ?? [];

  return (
    // Sub-rows of the course row, indented to its name column, with the same shading the
    // competency table puts under an opened topic.
    <div className="border-t border-hestia-border/60 bg-hestia-bg/60 shadow-[inset_0_8px_10px_-10px_rgba(0,0,0,0.25)]">
      {documentsQuery.isLoading && (
        <p className="py-2.5 pl-11 text-sm text-hestia-text-muted">Loading…</p>
      )}
      {documentsQuery.isError && (
        <p className="py-2.5 pl-11 text-sm text-hestia-danger">
          {(documentsQuery.error as Error).message}
        </p>
      )}
      {!documentsQuery.isLoading && !documentsQuery.isError && documents.length === 0 && (
        <p className="py-2.5 pl-11 text-sm text-hestia-text-muted">
          No documents uploaded for this course.
        </p>
      )}
      {kindMutation.isError && (
        <p className="py-2 pl-11 text-sm text-hestia-danger">{(kindMutation.error as Error).message}</p>
      )}
      {documents.length > 0 && (
        <ul className="divide-y divide-hestia-border/60">
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
              kindBusy={kindMutation.isPending}
              onKindChange={(kind) => kindMutation.mutate({ documentId: doc.id!, kind })}
            />
          ))}
        </ul>
      )}
    </div>
  );
}

/**
 * One document: display name (or filename), its kind, rename pencil, and an inline edit form.
 */
function DocumentRow({
  document,
  editing,
  busy,
  error,
  onEdit,
  onCancel,
  onSave,
  kindBusy,
  onKindChange,
}: {
  document: DocumentResponse;
  editing: boolean;
  busy: boolean;
  error?: string;
  onEdit: () => void;
  onCancel: () => void;
  onSave: (displayName: string | null) => void;
  kindBusy: boolean;
  onKindChange: (kind: DocumentKind) => void;
}) {
  const shown = document.displayName ?? document.filename ?? "";
  const [draft, setDraft] = useState(shown);
  useEffect(() => {
    if (editing) setDraft(shown);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset the draft only when editing starts
  }, [editing]);

  const uploaded = document.uploadedAt
    ? new Date(document.uploadedAt).toLocaleDateString(undefined, {
        year: "numeric",
        month: "short",
        day: "numeric",
      })
    : null;
  const trimmed = draft.trim();
  const canSave = trimmed !== "" && trimmed !== shown && !busy;

  return (
    // Unless it is being renamed, a document is one line: the columns line the upload date and the
    // rename action up with the course row's "Created" column and its row actions.
    <li className={`group relative pl-11 pr-4 ${editing ? "py-3" : ""}`}>
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
              <Button variant="neutral" onClick={onCancel} disabled={busy}>
                Cancel
              </Button>
              <Button type="submit" disabled={!canSave}>
                {busy ? "Saving…" : "Save"}
              </Button>
            </div>
          </div>
        </form>
      ) : (
        <div className="grid h-10 grid-cols-[1fr_auto_7rem_3rem] items-center gap-4">
          <div className="flex min-w-0 items-center gap-2">
            <svg
              viewBox="0 0 20 20"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
              className="h-4 w-4 shrink-0 text-hestia-text-muted"
            >
              <path d="M11.5 2.5H5.5v15h9V5.5z" />
              <path d="M11.5 2.5v3h3" />
            </svg>
            <span className="truncate text-sm text-hestia-text" title={shown}>
              {shown}
            </span>
            {/* The filename stays visible as provenance once a display name covers it. */}
            {document.displayName && (
              <span
                className="truncate text-xs text-hestia-text-muted"
                title={document.filename}
              >
                {document.filename}
              </span>
            )}
          </div>
          {/* A document uploaded before kinds existed has none; it can be given one but not cleared. */}
          <select
            value={document.kind ?? ""}
            disabled={kindBusy}
            onChange={(e) => onKindChange(e.target.value as DocumentKind)}
            aria-label={`Kind of ${shown}`}
            title="Takes effect on the next extraction or competency tree rebuild"
            className="h-[22px] cursor-pointer rounded-md border border-hestia-border bg-hestia-surface px-1.5 text-xs font-medium text-hestia-text-muted transition hover:border-hestia-primary focus:border-hestia-primary focus:outline-none disabled:opacity-50"
          >
            {!document.kind && <option value="">No kind</option>}
            {(Object.keys(DOCUMENT_KIND_LABEL) as DocumentKind[]).map((kind) => (
              <option key={kind} value={kind}>
                {DOCUMENT_KIND_LABEL[kind]}
              </option>
            ))}
          </select>
          <span
            className="whitespace-nowrap text-right text-sm text-hestia-text-muted"
            title={uploaded ? `Uploaded ${uploaded}` : undefined}
          >
            {uploaded}
          </span>
          <span className="flex justify-end opacity-0 transition focus-within:opacity-100 group-hover:opacity-100">
            <span className="flex rounded-md border border-hestia-border bg-hestia-surface p-0.5 shadow-sm">
              <RowAction
                label={`Rename ${shown}`}
                onClick={onEdit}
                className="hover:bg-hestia-primary-muted hover:text-hestia-text"
              >
                <path d="M13.5 3.5l3 3L7 16l-3.7.7L4 13z" />
              </RowAction>
            </span>
          </span>
        </div>
      )}
    </li>
  );
}
