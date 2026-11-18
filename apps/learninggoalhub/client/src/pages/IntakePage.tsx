import { useEffect, useRef, useState } from "react";
import type { DragEvent, FormEvent, ReactNode } from "react";
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
 * First step of the workflow: the teacher uploads the material of a past course. Shows the terms
 * of use, the required confirmation and the two research consents (research on by default, the
 * replication package off by default).
 */
export default function IntakePage() {
  const queryClient = useQueryClient();
  const terms = useQuery({ queryKey: ["intake", "terms"], queryFn: fetchTerms });
  const me = useQuery({ queryKey: ["intake", "me"], queryFn: fetchMe });
  const mine = useQuery({ queryKey: ["intake", "mine"], queryFn: fetchMine });

  const [files, setFiles] = useState<File[]>([]);
  const [courseTitle, setCourseTitle] = useState("");
  const [confirmedTerms, setConfirmedTerms] = useState(false);
  const [consentResearch, setConsentResearch] = useState(true);
  const [consentReplicationPackage, setConsentReplicationPackage] = useState(false);
  const [contactEmail, setContactEmail] = useState("");
  const [emailTouched, setEmailTouched] = useState(false);
  const [note, setNote] = useState("");
  const [fileError, setFileError] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);
  const [termsOpen, setTermsOpen] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);

  // Suggest the address from the login until the user types their own.
  const suggestedEmail = me.data?.suggestedEmail;
  useEffect(() => {
    if (!emailTouched && suggestedEmail) setContactEmail(suggestedEmail);
  }, [suggestedEmail, emailTouched]);

  const upload = useMutation({
    mutationFn: submit,
    onSuccess: () => {
      setFiles([]);
      if (fileInput.current) fileInput.current.value = "";
      setCourseTitle("");
      setNote("");
      setConfirmedTerms(false);
      setConsentResearch(true);
      setConsentReplicationPackage(false);
      void queryClient.invalidateQueries({ queryKey: ["intake", "mine"] });
    },
  });

  const addFiles = (list: FileList | null) => {
    const chosen = [...files, ...Array.from(list ?? [])].filter(
      (f, i, all) => all.findIndex((g) => g.name === f.name && g.size === f.size) === i,
    );
    const wrongType = chosen.find((f) => !hasAcceptedExtension(f.name));
    const tooLarge = chosen.find((f) => f.size > MAX_FILE_BYTES);
    if (wrongType) setFileError(`Dieser Dateityp wird nicht angenommen: ${wrongType.name}`);
    else if (tooLarge) setFileError(`Diese Datei ist größer als 50 MB: ${tooLarge.name}`);
    else if (chosen.length > MAX_FILES) setFileError(`Höchstens ${MAX_FILES} Dateien auf einmal.`);
    else setFileError(null);
    setFiles(chosen);
  };

  const removeFile = (file: File) => {
    const rest = files.filter((f) => f !== file);
    setFiles(rest);
    if (rest.every(hasAcceptedFile) && rest.length <= MAX_FILES) setFileError(null);
  };

  const onDrop = (event: DragEvent) => {
    event.preventDefault();
    setDragging(false);
    addFiles(event.dataTransfer.files);
  };

  const canSubmit = terms.data != null && files.length > 0 && fileError == null && confirmedTerms && !upload.isPending;

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    if (!canSubmit || !terms.data) return;
    upload.mutate({
      terms: terms.data,
      files,
      confirmedTerms,
      consentResearch,
      consentReplicationPackage,
      courseTitle,
      contactEmail,
      note,
    });
  };

  return (
    <div className="mx-auto max-w-3xl">
      <header className="mb-8 pt-4 text-center">
        <p className="mb-2 text-xs font-semibold uppercase tracking-wider text-hestia-primary">Schritt 1</p>
        <h1 className="font-display text-3xl font-bold">Die nächste Veranstaltung beginnt mit der letzten</h1>
        <p className="mx-auto mt-3 max-w-xl text-hestia-text-muted">
          Laden Sie die Unterlagen Ihrer letzten Durchführung hoch: Folien, Modulbeschreibung, Klausur. Wir werten
          sie aus und melden uns mit den Ergebnissen bei Ihnen.
        </p>
      </header>

      {upload.isSuccess ? (
        <section className="rounded-lg border border-hestia-border bg-hestia-surface p-8 text-center" role="status">
          <h2 className="font-display text-2xl font-bold">Vielen Dank!</h2>
          <p className="mx-auto mt-2 max-w-md text-hestia-text-muted">
            Ihr Material ist angekommen. Wir melden uns, sobald die Auswertung vorliegt.
          </p>
          <Button variant="secondary" size="lg" className="mt-6" onClick={() => upload.reset()}>
            Weiteres Material hochladen
          </Button>
        </section>
      ) : (
        <form onSubmit={onSubmit} className="space-y-6 rounded-lg border border-hestia-border bg-hestia-surface p-6">
          <div>
            <label htmlFor="intake-course" className="mb-1 block text-sm font-semibold">
              Welche Veranstaltung? <span className="font-normal text-hestia-text-muted">(optional)</span>
            </label>
            <input
              id="intake-course"
              value={courseTitle}
              maxLength={300}
              onChange={(e) => setCourseTitle(e.target.value)}
              placeholder="z. B. Einführung in die Softwaretechnik, WS 2025/26"
              className="w-full rounded-md border border-hestia-border bg-hestia-bg px-3 py-2 text-sm"
            />
          </div>

          <div>
            <span className="mb-1 block text-sm font-semibold">Ihre Unterlagen</span>
            <div
              onDragOver={(e) => {
                e.preventDefault();
                setDragging(true);
              }}
              onDragLeave={() => setDragging(false)}
              onDrop={onDrop}
              className={`flex flex-col items-center justify-center rounded-lg border-2 border-dashed px-6 py-10 text-center transition-colors ${
                dragging ? "border-hestia-primary bg-hestia-primary-muted" : "border-hestia-border"
              }`}
            >
              <p className="text-sm">Dateien hierher ziehen oder</p>
              <Button variant="secondary" size="md" className="mt-2" onClick={() => fileInput.current?.click()}>
                Dateien auswählen
              </Button>
              <input
                ref={fileInput}
                id="intake-files"
                type="file"
                multiple
                accept={ACCEPTED_EXTENSIONS.join(",")}
                onChange={(e) => addFiles(e.target.files)}
                className="sr-only"
                aria-label="Dateien auswählen"
              />
              <p className="mt-3 text-xs text-hestia-text-muted">
                Bis zu {MAX_FILES} Dateien, je höchstens 50 MB. PDF, PowerPoint, Word, Excel, OpenDocument, ZIP, Text.
              </p>
            </div>
            {files.length > 0 && (
              <ul className="mt-3 divide-y divide-hestia-border rounded-md border border-hestia-border text-sm">
                {files.map((f) => (
                  <li key={f.name + f.size} className="flex items-center justify-between gap-3 px-3 py-2">
                    <span className="truncate">
                      {f.name} <span className="text-hestia-text-muted">({formatBytes(f.size)})</span>
                    </span>
                    <Button variant="ghost" size="sm" onClick={() => removeFile(f)} aria-label={`${f.name} entfernen`}>
                      Entfernen
                    </Button>
                  </li>
                ))}
              </ul>
            )}
            {fileError && <p className="mt-2 text-sm text-hestia-danger">{fileError}</p>}
          </div>

          <fieldset className="space-y-3">
            <legend className="mb-1 text-sm font-semibold">Forschung</legend>
            <Check checked={consentResearch} onChange={setConsentResearch}>
              <strong>Für die Forschung zur Verfügung stellen.</strong> HESTIA ist ein Forschungsvorhaben; Ihr Material
              hilft uns, das Werkzeug zu verbessern. Ergebnisse veröffentlichen wir nur so, dass Sie nicht erkennbar
              sind.
            </Check>
            <Check checked={consentReplicationPackage} onChange={setConsentReplicationPackage}>
              <strong>Anonymisiert in einem Replikationspaket veröffentlichen.</strong> Damit andere unsere Ergebnisse
              nachprüfen können, dürfen anonymisierte Daten aus Ihrem Material Teil eines veröffentlichten
              Replikationspakets werden.
            </Check>
          </fieldset>

          <div className="grid gap-4 sm:grid-cols-2">
            <div>
              <label htmlFor="intake-email" className="mb-1 block text-sm font-semibold">
                E-Mail für die Auswertung
              </label>
              <input
                id="intake-email"
                type="email"
                value={contactEmail}
                onChange={(e) => {
                  setEmailTouched(true);
                  setContactEmail(e.target.value);
                }}
                className="w-full rounded-md border border-hestia-border bg-hestia-bg px-3 py-2 text-sm"
              />
            </div>
            <div>
              <label htmlFor="intake-note" className="mb-1 block text-sm font-semibold">
                Was interessiert Sie? <span className="font-normal text-hestia-text-muted">(optional)</span>
              </label>
              <input
                id="intake-note"
                value={note}
                maxLength={4000}
                onChange={(e) => setNote(e.target.value)}
                placeholder="z. B. Wie KI-fest ist meine Klausur?"
                className="w-full rounded-md border border-hestia-border bg-hestia-bg px-3 py-2 text-sm"
              />
            </div>
          </div>

          <div className="rounded-md border border-hestia-border bg-hestia-bg p-4">
            <Check checked={confirmedTerms} onChange={setConfirmedTerms}>
              Ich akzeptiere die{" "}
              <button
                type="button"
                onClick={() => setTermsOpen((open) => !open)}
                className="text-hestia-primary underline"
                aria-expanded={termsOpen}
                aria-controls="intake-terms"
              >
                Nutzungsbedingungen
              </button>
              . Ich habe die nötigen Rechte am Material, und es enthält keine personenbezogenen Daten von Studierenden.
            </Check>
            {termsOpen && (
              <div
                id="intake-terms"
                className="mt-3 max-h-80 overflow-y-auto border-t border-hestia-border pt-3 pr-2"
                tabIndex={0}
                aria-label="Nutzungsbedingungen"
              >
                {terms.isPending && <p className="text-sm text-hestia-text-muted">Wird geladen …</p>}
                {terms.error && <p className="text-sm text-hestia-danger">{terms.error.message}</p>}
                {terms.data && <MarkdownText text={terms.data.text ?? ""} />}
              </div>
            )}
          </div>

          {upload.error && <p className="text-sm text-hestia-danger">{upload.error.message}</p>}

          <div className="flex justify-end">
            <Button type="submit" size="lg" disabled={!canSubmit}>
              {upload.isPending ? "Wird hochgeladen …" : "Hochladen"}
            </Button>
          </div>
        </form>
      )}

      {mine.data && mine.data.length > 0 && (
        <section className="mt-10">
          <h2 className="mb-2 text-lg font-semibold">Bisher hochgeladen</h2>
          <ul className="space-y-2">
            {mine.data.map((s) => (
              <li key={s.id} className="rounded-md border border-hestia-border bg-hestia-surface px-4 py-2 text-sm">
                <div className="flex justify-between gap-3">
                  <span className="font-medium">{s.courseTitle ?? "Ohne Titel"}</span>
                  <span className="text-hestia-text-muted">{formatDate(s.createdAt)}</span>
                </div>
                <div className="text-hestia-text-muted">{s.files?.map((f) => f.name).join(", ")}</div>
              </li>
            ))}
          </ul>
        </section>
      )}
      <p className="mt-6 text-center text-xs text-hestia-text-muted">
        Zustimmung widerrufen oder Material löschen lassen: eine formlose Nachricht an die im Impressum genannte
        Kontaktadresse genügt.
      </p>
    </div>
  );
}

function hasAcceptedFile(file: File): boolean {
  return hasAcceptedExtension(file.name) && file.size <= MAX_FILE_BYTES;
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
    <label className="flex items-start gap-3 text-sm leading-relaxed">
      <input
        type="checkbox"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
        className="mt-1 h-4 w-4 shrink-0 accent-[var(--hestia-primary)]"
      />
      <span>{children}</span>
    </label>
  );
}
