import { Fragment, useCallback, useEffect, useRef, useState, type ReactNode } from "react";
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
import {
  acknowledgeInactivityNotices,
  confirmUploads,
  getInactivityNotices,
  getUploadConfirmation,
  setUploadConfirmationHandler,
  type InactivityNotice,
  type UploadConfirmation,
} from "@/lib/api";

/** The notice's paragraphs; **bold** is the only Markdown it uses. */
function NoticeText({ text }: { text: string }) {
  return (
    <div className="space-y-2 text-sm text-muted-foreground">
      {text.split(/\n\s*\n/).map((paragraph, i) => (
        <p key={i}>
          {paragraph.split(/\*\*(.+?)\*\*/).map((part, j): ReactNode =>
            j % 2 === 1 ? <strong key={j}>{part}</strong> : <Fragment key={j}>{part}</Fragment>
          )}
        </p>
      ))}
    </div>
  );
}

/**
 * Asked once per user, when the server refuses an upload (slide template) with 428
 * UPLOAD_CONFIRMATION_REQUIRED: the user ticks that uploads contain no personal data of others, the
 * confirmation is recorded and the upload is repeated. "Abbrechen" leaves everything as it was.
 */
export function UploadConfirmationDialog() {
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
        {notice && <NoticeText text={notice.text} />}
        {notice && (
          <label className="flex items-start gap-2 text-sm">
            <input
              type="checkbox"
              className="mt-0.5 h-4 w-4 accent-primary"
              checked={checked}
              onChange={(e) => setChecked(e.target.checked)}
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
}

const date = (iso: string) => new Date(iso).toLocaleDateString("de-DE");

/**
 * After a login: if the account was warned for inactivity while the user was away, says so once.
 * The login itself has already reset the deletion period.
 */
export function InactivityNoticeDialog() {
  const [notice, setNotice] = useState<InactivityNotice | null>(null);

  useEffect(() => {
    getInactivityNotices()
      .then((notices) => setNotice(notices[0] ?? null))
      .catch(() => setNotice(null));
  }, []);

  const close = () => {
    setNotice(null);
    void acknowledgeInactivityNotices().catch(() => undefined);
  };

  return (
    <AlertDialog open={notice !== null} onOpenChange={(open) => !open && close()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Willkommen zurück</AlertDialogTitle>
          <AlertDialogDescription>
            {notice &&
              `Sie hatten sich seit dem ${date(notice.lastSeenAt)} nicht mehr angemeldet. ` +
                `Am ${date(notice.warnedAt)} haben wir Ihr Konto zur Löschung vorgemerkt, weil Konten nach ` +
                `${notice.deleteAfterDays} Tagen ohne Anmeldung mit ihren Inhalten gelöscht werden. ` +
                "Mit dieser Anmeldung beginnt die Frist neu; Ihr Konto und Ihre Inhalte bleiben erhalten."}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogAction onClick={close}>Verstanden</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

/** Both privacy dialogs; mounted once in each view of the app. */
export function PrivacyDialogs() {
  return (
    <>
      <UploadConfirmationDialog />
      <InactivityNoticeDialog />
    </>
  );
}
