import { useEffect, useState } from "react";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import {
  acknowledgeInactivityNotices,
  getInactivityNotices,
  type InactivityNotice,
} from "@/lib/api/api-client";

const date = (iso: string) => new Date(iso).toLocaleDateString("de-DE");

/**
 * After a login: if the account was warned for inactivity while the user was away, says so once.
 * The login itself has already reset the deletion period.
 */
export const InactivityNoticeDialog = () => {
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
};
