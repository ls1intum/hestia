import { useCallback, useEffect, useId, useRef, useState } from "react";
import Button from "./Button.tsx";
import MarkdownText from "./MarkdownText.tsx";
import { useFocusTrap } from "../lib/useFocusTrap.ts";
import {
  acknowledgeInactivityNotices,
  confirmUploads,
  getInactivityNotices,
  setUploadConfirmationAsker,
  type InactivityNotice,
  type UploadConfirmation,
} from "../lib/privacy.ts";

/** Centered overlay above everything else (also above the create-course panel). */
function Overlay({ titleId, onCancel, children }: { titleId: string; onCancel?: () => void; children: React.ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  useFocusTrap(ref);
  useEffect(() => {
    if (!onCancel) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onCancel();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onCancel]);
  return (
    <div
      ref={ref}
      tabIndex={-1}
      role="dialog"
      aria-modal="true"
      aria-labelledby={titleId}
      className="fixed inset-0 z-[60] flex items-center justify-center bg-black/50 p-4 focus:outline-none"
    >
      <div className="max-h-[85vh] w-full max-w-lg overflow-y-auto rounded-xl border border-hestia-border bg-hestia-surface p-6 shadow-lg">
        {children}
      </div>
    </div>
  );
}

/**
 * Asked once per user before the first upload: the user ticks that uploads contain no personal data
 * of others; the confirmation is recorded in core and counts for all three tools.
 */
export function UploadConfirmationDialog() {
  const titleId = useId();
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
    setUploadConfirmationAsker((current) => {
      setNotice(current);
      return new Promise<boolean>((resolve) => {
        answer.current = resolve;
      });
    });
    return () => setUploadConfirmationAsker(null);
  }, []);

  if (!notice) return null;

  const confirm = async () => {
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
    <Overlay titleId={titleId} onCancel={busy ? undefined : () => finish(false)}>
      <h3 id={titleId} className="text-lg text-hestia-text">Keine personenbezogenen Daten hochladen</h3>
      <div className="mt-3 text-sm leading-relaxed text-hestia-text-muted">
        <MarkdownText text={notice.text} />
      </div>
      <label className="mt-4 flex items-start gap-2 text-sm text-hestia-text">
        <input
          type="checkbox"
          className="mt-0.5 h-4 w-4 accent-hestia-primary"
          checked={checked}
          onChange={(e) => setChecked(e.target.checked)}
          disabled={busy}
        />
        <span>{notice.checkboxLabel}</span>
      </label>
      {error && <p className="mt-3 text-sm text-hestia-danger">{error}</p>}
      <div className="mt-6 flex justify-end gap-2">
        <Button variant="neutral" onClick={() => finish(false)} disabled={busy}>
          Abbrechen
        </Button>
        <Button variant="primary" onClick={() => void confirm()} disabled={busy || !checked}>
          Bestätigen und hochladen
        </Button>
      </div>
    </Overlay>
  );
}

const date = (iso: string) => new Date(iso).toLocaleDateString("de-DE");

/**
 * After a login: if the account was warned for inactivity while the user was away, says so once.
 * The login itself has already reset the deletion period.
 */
export function InactivityNoticeDialog() {
  const titleId = useId();
  const [notice, setNotice] = useState<InactivityNotice | null>(null);

  useEffect(() => {
    getInactivityNotices()
      .then((notices) => setNotice(notices[0] ?? null))
      .catch(() => setNotice(null));
  }, []);

  if (!notice) return null;

  const close = () => {
    setNotice(null);
    void acknowledgeInactivityNotices().catch(() => undefined);
  };

  return (
    <Overlay titleId={titleId} onCancel={close}>
      <h3 id={titleId} className="text-lg text-hestia-text">Willkommen zurück</h3>
      <p className="mt-2 text-sm leading-relaxed text-hestia-text-muted">
        Sie hatten sich seit dem {date(notice.lastSeenAt)} nicht mehr angemeldet. Am {date(notice.warnedAt)} haben
        wir Ihr Konto zur Löschung vorgemerkt, weil Konten nach {notice.deleteAfterDays} Tagen ohne Anmeldung mit
        ihren Inhalten gelöscht werden. Mit dieser Anmeldung beginnt die Frist neu; Ihr Konto und Ihre Inhalte
        bleiben erhalten.
      </p>
      <div className="mt-6 flex justify-end">
        <Button variant="primary" onClick={close}>
          Verstanden
        </Button>
      </div>
    </Overlay>
  );
}
