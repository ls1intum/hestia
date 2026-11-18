import { useEffect, useState } from "react";
import type { FormEvent, ReactNode } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import Button from "../components/Button.tsx";
import MarkdownText from "../components/MarkdownText.tsx";
import {
  ACCEPTED_EXTENSIONS,
  MAX_FILE_BYTES,
  MAX_FILES,
  fetchMe,
  fetchMine,
  fetchTerms,
  formatBytes,
  formatDate,
  hasAcceptedExtension,
  submit,
} from "../lib/intake.ts";

/**
 * Material intake: shows the terms of use, asks for the two confirmations and the two optional
 * consents, and uploads the files. Below it the submitter's own earlier submissions.
 */
export default function IntakePage() {
  const queryClient = useQueryClient();
  const terms = useQuery({ queryKey: ["intake", "terms"], queryFn: fetchTerms });
  const me = useQuery({ queryKey: ["intake", "me"], queryFn: fetchMe });
  const mine = useQuery({ queryKey: ["intake", "mine"], queryFn: fetchMine });

  const [files, setFiles] = useState<File[]>([]);
  const [confirmedRights, setConfirmedRights] = useState(false);
  const [confirmedNoStudentData, setConfirmedNoStudentData] = useState(false);
  const [consentPublication, setConsentPublication] = useState(false);
  const [consentExternalAi, setConsentExternalAi] = useState(false);
  const [contactEmail, setContactEmail] = useState("");
  const [emailTouched, setEmailTouched] = useState(false);
  const [note, setNote] = useState("");
  const [fileError, setFileError] = useState<string | null>(null);
  const [inputKey, setInputKey] = useState(0);

  // Suggest the address from the login until the user types their own.
  const suggestedEmail = me.data?.suggestedEmail;
  useEffect(() => {
    if (!emailTouched && suggestedEmail) setContactEmail(suggestedEmail);
  }, [suggestedEmail, emailTouched]);

  const upload = useMutation({
    mutationFn: submit,
    onSuccess: () => {
      setFiles([]);
      setInputKey((k) => k + 1);
      setNote("");
      setConfirmedRights(false);
      setConfirmedNoStudentData(false);
      setConsentPublication(false);
      setConsentExternalAi(false);
      void queryClient.invalidateQueries({ queryKey: ["intake", "mine"] });
    },
  });

  const chooseFiles = (list: FileList | null) => {
    const chosen = Array.from(list ?? []);
    const wrongType = chosen.find((f) => !hasAcceptedExtension(f.name));
    const tooLarge = chosen.find((f) => f.size > MAX_FILE_BYTES);
    if (wrongType) setFileError(`Dieser Dateityp wird nicht angenommen: ${wrongType.name}`);
    else if (tooLarge) setFileError(`Diese Datei ist größer als 50 MB: ${tooLarge.name}`);
    else if (chosen.length > MAX_FILES) setFileError(`Höchstens ${MAX_FILES} Dateien pro Einreichung.`);
    else setFileError(null);
    setFiles(chosen);
  };

  const canSubmit =
    terms.data != null &&
    files.length > 0 &&
    fileError == null &&
    confirmedRights &&
    confirmedNoStudentData &&
    !upload.isPending;

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    if (!canSubmit || !terms.data) return;
    upload.mutate({
      terms: terms.data,
      files,
      confirmedRights,
      confirmedNoStudentData,
      consentPublication,
      consentExternalAi,
      contactEmail,
      note,
    });
  };

  return (
    <div className="mx-auto max-w-3xl">
      <div className="mb-6 flex items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold">Material einreichen</h1>
          <p className="mt-1 text-sm text-hestia-text-muted">
            Folien, Klausuren und Modulbeschreibungen für das Forschungsvorhaben HESTIA.
          </p>
        </div>
        {me.data?.admin && (
          <Link to="/einreichen/admin" className="text-sm text-hestia-primary hover:underline">
            Alle Einreichungen
          </Link>
        )}
      </div>

      <section className="mb-6 rounded-lg border border-hestia-border bg-hestia-surface p-5">
        {terms.isPending && <p className="text-sm text-hestia-text-muted">Nutzungsbedingungen werden geladen …</p>}
        {terms.error && <p className="text-sm text-hestia-danger">{terms.error.message}</p>}
        {terms.data && (
          <div className="max-h-[28rem] overflow-y-auto pr-2" tabIndex={0} aria-label="Nutzungsbedingungen">
            <MarkdownText text={terms.data.text ?? ""} />
          </div>
        )}
      </section>

      <form onSubmit={onSubmit} className="space-y-5 rounded-lg border border-hestia-border bg-hestia-surface p-5">
        <div>
          <label htmlFor="intake-files" className="mb-1 block text-sm font-semibold">
            Dateien
          </label>
          <input
            key={inputKey}
            id="intake-files"
            type="file"
            multiple
            accept={ACCEPTED_EXTENSIONS.join(",")}
            onChange={(e) => chooseFiles(e.target.files)}
            className="block w-full text-sm"
          />
          <p className="mt-1 text-xs text-hestia-text-muted">
            Bis zu {MAX_FILES} Dateien, je höchstens 50 MB: PDF, PowerPoint, Word, Excel, OpenDocument, ZIP, Text.
          </p>
          {files.length > 0 && (
            <ul className="mt-2 space-y-0.5 text-sm">
              {files.map((f) => (
                <li key={f.name + f.size}>
                  {f.name} <span className="text-hestia-text-muted">({formatBytes(f.size)})</span>
                </li>
              ))}
            </ul>
          )}
          {fileError && <p className="mt-1 text-sm text-hestia-danger">{fileError}</p>}
        </div>

        <fieldset className="space-y-2">
          <legend className="mb-1 text-sm font-semibold">Bestätigungen (erforderlich)</legend>
          <Check checked={confirmedRights} onChange={setConfirmedRights}>
            Ich habe die Nutzungsbedingungen gelesen und akzeptiere sie. Ich darf das Material für diesen Zweck
            weitergeben (Abschnitt 2 und 3).
          </Check>
          <Check checked={confirmedNoStudentData} onChange={setConfirmedNoStudentData}>
            Das Material enthält keine personenbezogenen Daten von Studierenden (keine Namen, Matrikelnummern,
            Bearbeitungen oder Noten).
          </Check>
        </fieldset>

        <fieldset className="space-y-2">
          <legend className="mb-1 text-sm font-semibold">Freiwillige Zustimmungen</legend>
          <Check checked={consentExternalAi} onChange={setConsentExternalAi}>
            Externe KI-Dienste: Für die Auswertung dürfen auch kommerzielle Anbieter wie OpenAI, Google oder
            Anthropic genutzt werden. Dabei können Inhalte in die USA übermittelt werden (Abschnitt 5).
          </Check>
          <Check checked={consentPublication} onChange={setConsentPublication}>
            Veröffentlichung im Sinne von Open Science: Das Material darf als Teil eines Forschungsdatensatzes
            veröffentlicht werden (Abschnitt 4).
          </Check>
        </fieldset>

        <div>
          <label htmlFor="intake-email" className="mb-1 block text-sm font-semibold">
            E-Mail für die Auswertung (optional)
          </label>
          <input
            id="intake-email"
            type="email"
            value={contactEmail}
            onChange={(e) => {
              setEmailTouched(true);
              setContactEmail(e.target.value);
            }}
            className="w-full rounded-md border border-hestia-border bg-hestia-bg px-3 py-1.5 text-sm"
          />
        </div>

        <div>
          <label htmlFor="intake-note" className="mb-1 block text-sm font-semibold">
            Hinweise zum Material (optional)
          </label>
          <textarea
            id="intake-note"
            value={note}
            maxLength={4000}
            rows={3}
            onChange={(e) => setNote(e.target.value)}
            placeholder="z. B. Lehrveranstaltung, Semester, was Sie interessiert"
            className="w-full rounded-md border border-hestia-border bg-hestia-bg px-3 py-1.5 text-sm"
          />
        </div>

        {upload.error && <p className="text-sm text-hestia-danger">{upload.error.message}</p>}
        {upload.isSuccess && (
          <p className="text-sm font-semibold text-hestia-accent" role="status">
            Vielen Dank! Ihr Material ist eingegangen.
          </p>
        )}

        <Button type="submit" size="lg" disabled={!canSubmit}>
          {upload.isPending ? "Wird hochgeladen …" : "Material einreichen"}
        </Button>
      </form>

      <section className="mt-8">
        <h2 className="mb-2 text-lg font-semibold">Ihre Einreichungen</h2>
        {mine.error && <p className="text-sm text-hestia-danger">{mine.error.message}</p>}
        {mine.data?.length === 0 && <p className="text-sm text-hestia-text-muted">Noch keine.</p>}
        <ul className="space-y-2">
          {mine.data?.map((s) => (
            <li key={s.id} className="rounded-md border border-hestia-border bg-hestia-surface px-4 py-2 text-sm">
              <div className="font-medium">{formatDate(s.createdAt)}</div>
              <div className="text-hestia-text-muted">{s.files?.map((f) => f.name).join(", ")}</div>
            </li>
          ))}
        </ul>
        <p className="mt-3 text-xs text-hestia-text-muted">
          Löschen oder Widerruf: eine formlose Nachricht an die im Impressum genannte Kontaktadresse genügt.
        </p>
      </section>
    </div>
  );
}

function Check({
  checked,
  onChange,
  children,
}: {
  checked: boolean;
  onChange: (value: boolean) => void;
  children: ReactNode;
}) {
  return (
    <label className="flex items-start gap-2 text-sm">
      <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} className="mt-1" />
      <span>{children}</span>
    </label>
  );
}
