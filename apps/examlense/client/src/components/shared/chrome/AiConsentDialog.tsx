import { useCallback, useEffect, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogAction,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { MarkdownView } from "@/components/shared/exam-content/MarkdownView";
import { getAiConsent, grantAiConsent, setConsentHandler } from "@/lib/api/api-client";
import type { AiConsent } from "@/lib/api/api-client";
import { aiConsentKey } from "@/hooks/data/use-me";

/** The notice starts with its own title; the dialog already shows one. */
const withoutTitle = (markdown: string) => markdown.replace(/^#\s[^\n]*\n+/, "");

/**
 * Asks for the consent to external AI models when the server refuses a parse or a solve with
 * 428 CONSENT_REQUIRED. Shows the notice; "Zustimmen" records the consent and the refused request
 * is repeated, "Abbrechen" leaves everything as it was.
 */
export const AiConsentDialog = () => {
  const qc = useQueryClient();
  const [notice, setNotice] = useState<AiConsent | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const answer = useRef<((granted: boolean) => void) | null>(null);

  const finish = useCallback((granted: boolean) => {
    answer.current?.(granted);
    answer.current = null;
    setNotice(null);
    setError(null);
    setBusy(false);
  }, []);

  useEffect(() => {
    setConsentHandler(async () => {
      try {
        const current = await getAiConsent();
        if (current.granted) return true; // granted meanwhile (another tab): just repeat
        setNotice(current);
        return await new Promise<boolean>((resolve) => {
          answer.current = resolve;
        });
      } catch {
        return false;
      }
    });
    return () => setConsentHandler(null);
  }, []);

  const consent = async () => {
    setBusy(true);
    setError(null);
    try {
      const status = await grantAiConsent();
      qc.setQueryData(aiConsentKey, status);
      finish(true);
    } catch {
      setBusy(false);
      setError("Die Einwilligung konnte nicht gespeichert werden. Bitte versuchen Sie es erneut.");
    }
  };

  return (
    <AlertDialog open={notice !== null} onOpenChange={(open) => !open && !busy && finish(false)}>
      <AlertDialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-xl">
        <AlertDialogHeader>
          <AlertDialogTitle>Einwilligung: externe KI-Modelle</AlertDialogTitle>
          <AlertDialogDescription className="sr-only">
            Hinweis zur Nutzung externer KI-Modelle und Ihre Einwilligung dazu.
          </AlertDialogDescription>
        </AlertDialogHeader>
        {notice && <MarkdownView content={withoutTitle(notice.notice.text)} />}
        {error && <p className="text-sm text-destructive">{error}</p>}
        <AlertDialogFooter>
          <AlertDialogCancel disabled={busy}>Abbrechen</AlertDialogCancel>
          <AlertDialogAction
            disabled={busy}
            onClick={(e) => {
              e.preventDefault();
              void consent();
            }}
          >
            Zustimmen
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
};
