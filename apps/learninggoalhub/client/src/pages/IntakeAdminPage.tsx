import { useState } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import Button from "../components/Button.tsx";
import ConfirmDialog from "../components/ConfirmDialog.tsx";
import { deleteSubmission, fetchAll, fileUrl, formatBytes, formatDate } from "../lib/intake.ts";
import type { IntakeSubmission } from "../lib/intake.ts";

/** All submissions for the platform admins: who, what they accepted, and the files to download. */
export default function IntakeAdminPage() {
  const queryClient = useQueryClient();
  const all = useQuery({ queryKey: ["intake", "all"], queryFn: fetchAll });
  const [toDelete, setToDelete] = useState<IntakeSubmission | null>(null);
  const remove = useMutation({
    mutationFn: (id: string) => deleteSubmission(id),
    onSuccess: () => {
      setToDelete(null);
      void queryClient.invalidateQueries({ queryKey: ["intake"] });
    },
  });

  return (
    <div className="mx-auto max-w-5xl">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-2xl font-bold">Alle Einreichungen</h1>
        <Link to="/einreichen" className="text-sm text-hestia-primary hover:underline">
          Zur Einreichung
        </Link>
      </div>
      {all.isPending && <p className="text-sm text-hestia-text-muted">Wird geladen …</p>}
      {all.error && <p className="text-sm text-hestia-danger">{all.error.message}</p>}
      {all.data?.length === 0 && <p className="text-sm text-hestia-text-muted">Noch keine Einreichungen.</p>}
      <ul className="space-y-3">
        {all.data?.map((s) => (
          <li key={s.id} className="rounded-lg border border-hestia-border bg-hestia-surface p-4 text-sm">
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <div>
                <span className="font-semibold">{s.submitterId}</span>
                {s.contactEmail && (
                  <>
                    {" · "}
                    <a href={`mailto:${s.contactEmail}`} className="text-hestia-primary hover:underline">
                      {s.contactEmail}
                    </a>
                  </>
                )}
              </div>
              <span className="text-hestia-text-muted">{formatDate(s.createdAt)}</span>
            </div>
            <div className="mt-1 text-xs text-hestia-text-muted">
              Bedingungen {s.termsVersion} (SHA-256 {s.termsSha256?.slice(0, 12)}…) · Externe KI:{" "}
              {s.consentExternalAi ? "ja" : "nein"} · Veröffentlichung: {s.consentPublication ? "ja" : "nein"}
            </div>
            {s.note && <p className="mt-2 whitespace-pre-wrap">{s.note}</p>}
            <ul className="mt-2 space-y-0.5">
              {s.files?.map((f) => (
                <li key={f.id}>
                  <a href={fileUrl(s.id!, f.id!)} className="text-hestia-primary hover:underline">
                    {f.name}
                  </a>{" "}
                  <span className="text-hestia-text-muted">({formatBytes(f.sizeBytes)})</span>
                </li>
              ))}
            </ul>
            <div className="mt-3">
              <Button variant="neutral" size="sm" onClick={() => setToDelete(s)}>
                Löschen
              </Button>
            </div>
          </li>
        ))}
      </ul>
      {remove.error && <p className="mt-3 text-sm text-hestia-danger">{remove.error.message}</p>}
      {toDelete && (
        <ConfirmDialog
          title="Einreichung löschen?"
          message={`Die Einreichung von ${toDelete.submitterId} und ihre ${toDelete.files?.length ?? 0} Dateien werden endgültig gelöscht.`}
          confirmLabel="Löschen"
          tone="danger"
          busy={remove.isPending}
          onConfirm={() => remove.mutate(toDelete.id!)}
          onCancel={() => setToDelete(null)}
        />
      )}
    </div>
  );
}
