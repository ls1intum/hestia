import { useCallback, useEffect, useRef, useState } from "react";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Checkbox } from "@/components/ui/checkbox";
import { MarkdownView } from "@/components/shared/exam-content/MarkdownView";
import {
  confirmUploads,
  getUploadConfirmation,
  setUploadConfirmationHandler,
  type UploadConfirmation,
} from "@/lib/api/api-client";

/**
 * Asked once per user, when the server refuses an upload with 428 UPLOAD_CONFIRMATION_REQUIRED:
 * the user ticks that uploads contain no personal data of others, the confirmation is recorded and
 * the refused upload is repeated. "Abbrechen" leaves everything as it was.
 */
export const UploadConfirmationDialog = () => {
  const [notice, setNotice] = useState<UploadConfirmation | null>(null);
  const [checked, setChecked] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const answer = useRef<((confirmed: boolean) => void) | null>(null);

  const finish = useCallback((confirmed: boolean) => {
    answer.current?.(confirmed);
    answer.current = null;
    setNotice(null);
    setChecked(false);
    setError(null);
    setBusy(false);
  }, []);

  useEffect(() => {
    setUploadConfirmationHandler(async () => {
      try {
        const current = await getUploadConfirmation();
        if (current.confirmed) return true; // confirmed meanwhile (another tab or tool)
        setNotice(current);
        return await new Promise<boolean>((resolve) => {
          answer.current = resolve;
        });
      } catch {
        return false;
      }
    });
    return () => setUploadConfirmationHandler(null);
  }, []);

  const confirm = async () => {
    if (!notice) return;
    setBusy(true);
    setError(null);
    try {
      await confirmUploads(notice.noticeVersion);
      finish(true);
    } catch {
      setBusy(false);
      setError("Die Bestätigung konnte nicht gespeichert werden. Bitte versuchen Sie es erneut.");
    }
  };

  return (
    <AlertDialog open={notice !== null} onOpenChange={(open) => !open && !busy && finish(false)}>
      <AlertDialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-xl">
        <AlertDialogHeader>
          <AlertDialogTitle>Keine personenbezogenen Daten hochladen</AlertDialogTitle>
          <AlertDialogDescription className="sr-only">
            Bestätigung vor dem ersten Upload, dass er keine personenbezogenen Daten Dritter enthält.
          </AlertDialogDescription>
        </AlertDialogHeader>
        {notice && <MarkdownView content={notice.text} />}
        {notice && (
          <label className="flex items-start gap-2 text-sm">
            <Checkbox
              className="mt-0.5"
              checked={checked}
              onCheckedChange={(value) => setChecked(value === true)}
              disabled={busy}
            />
            <span>{notice.checkboxLabel}</span>
          </label>
        )}
        {error && <p className="text-sm text-destructive">{error}</p>}
        <AlertDialogFooter>
          <AlertDialogCancel disabled={busy}>Abbrechen</AlertDialogCancel>
          <AlertDialogAction
            disabled={busy || !checked}
            onClick={(e) => {
              e.preventDefault();
              void confirm();
            }}
          >
            Bestätigen und hochladen
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
};
