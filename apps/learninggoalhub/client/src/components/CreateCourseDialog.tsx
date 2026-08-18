import { useEffect, useRef, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, API_PREFIX } from "../api/client.ts";
import Button from "./Button.tsx";

// The upload endpoint runs everything through Apache Tika, which parses these
// out of the box. Kept in sync with the hint shown in the drop zone.
const ACCEPT = ".pdf,.docx,.pptx,.txt";
const ACCEPT_LABEL = "PDF, DOCX, PPTX, TXT";

/**
 * Steps between hitting "Create course" and the extraction taking over in the courses list. Only
 * "uploading" has a meaningful percentage — the rest are single server round trips, so they show an
 * indeterminate bar. "processing" is the tail of the same upload request: once the bytes are on the
 * wire the server still parses every file through Tika/PDFBox, which is most of the wait.
 */
type Step = "creating" | "uploading" | "processing" | "starting";

const STEP_LABEL: Record<Step, string> = {
  creating: "Creating course…",
  uploading: "Uploading materials…",
  processing: "Processing materials…",
  starting: "Starting analysis…",
};

/**
 * Create a course, stage + upload its materials, then start extraction before returning to the
 * courses list. The list owns live extraction progress and review.
 */
export default function CreateCourseDialog({ onClose }: { onClose: () => void }) {
  const queryClient = useQueryClient();
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [name, setName] = useState("");
  const [files, setFiles] = useState<File[]>([]);
  const [isDragging, setIsDragging] = useState(false);
  // "" = detect from the uploaded materials.
  const [outputLanguage, setOutputLanguage] = useState<"" | "de" | "en">("");
  const [figuresEnabled, setFiguresEnabled] = useState(false);
  const [step, setStep] = useState<Step | null>(null);
  const [uploadPercent, setUploadPercent] = useState(0);

  // Accumulate across drops/picks, skipping files already staged (name + size).
  const addFiles = (incoming: FileList | null) => {
    if (!incoming || incoming.length === 0) return;
    // Snapshot before updating state: the file input is reset right after this call, which empties
    // the live FileList before React gets around to running the updater below.
    const picked = Array.from(incoming);
    setFiles((prev) => {
      const seen = new Set(prev.map((f) => `${f.name}:${f.size}`));
      const next = [...prev];
      for (const file of picked) {
        const key = `${file.name}:${file.size}`;
        if (!seen.has(key)) {
          seen.add(key);
          next.push(file);
        }
      }
      return next;
    });
  };

  const removeFile = (index: number) =>
    setFiles((prev) => prev.filter((_, i) => i !== index));

  const extract = useMutation({
    mutationFn: async (id: number) => {
      const result = await api.POST("/api/courses/{courseId}/extract", {
        params: { path: { courseId: id }, query: {} },
      });
      if (result.error || !result.data) {
        const message = result.response.status === 409
          ? "Another extraction is already running. Please wait."
          : "Could not start extraction.";
        throw new Error(message);
      }
      return result.data;
    },
  });

  const create = useMutation({
    mutationFn: async () => {
      setStep("creating");
      const { data, error } = await api.POST("/api/courses", {
        body: {
          name: name.trim(),
          outputLanguage: outputLanguage === "" ? undefined : outputLanguage,
          figuresEnabled,
        },
      });
      if (error || !data?.id) throw new Error("Could not create the course.");

      if (files.length > 0) {
        setUploadPercent(0);
        setStep("uploading");
        await uploadDocuments(data.id, files, setUploadPercent, () => setStep("processing"));
      }
      return data.id as number;
    },
    onSuccess: (id) => {
      queryClient.invalidateQueries({ queryKey: ["courses"] });
      // No materials → nothing to extract, so hand the list back straight away.
      if (files.length === 0) {
        onClose();
        return;
      }
      setStep("starting");
      // Closing is the dialog owner's job: the courses list renders this dialog and it is itself
      // routed at "/", so navigating there would not dismiss anything. The list picks the run up
      // from its own polling and shows progress inline on the course's row.
      extract.mutate(id, {
        onSuccess: () => onClose(),
        onError: () => setStep(null),
      });
    },
    onError: () => setStep(null),
  });

  const trimmed = name.trim();
  const totalBytes = files.reduce((sum, file) => sum + file.size, 0);
  const busy = create.isPending || extract.isPending;
  const formError = extract.isError
    ? extract.error.message
    : create.isError
      ? create.error.message
      : null;

  // Escape closes the dialog, but not mid-flight (an in-progress upload/extraction must not be
  // abandoned by a stray key).
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !busy) onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [busy, onClose]);

  return (
    <div
      className="fixed inset-0 z-50"
      role="dialog"
      aria-modal="true"
      aria-label="Create course"
    >
      {/* An opaque scrim rather than the goal modal's backdrop-blur: blurring the courses list
          through this layer costs a repaint of the whole table on every frame, which measured out
          at ~30fps while typing here (16 of 45 frames over 32ms, against 2 of 65 without it). */}
      <div aria-hidden="true" className="absolute inset-0 bg-hestia-bg/90" />
      <div
        onClick={busy ? undefined : onClose}
        className="absolute inset-0 flex items-start justify-center overflow-y-auto p-4 sm:p-8"
      >
        {/* One animation for the whole panel rather than per tile: every element animating over
            the backdrop-filter keeps the browser from caching the blurred layer. */}
        <form
          onClick={(e) => e.stopPropagation()}
          onSubmit={(e) => {
            e.preventDefault();
            if (trimmed && !busy) create.mutate();
          }}
          className="comp-unfold flex w-full max-w-5xl flex-col gap-3.5 sm:mt-[6vh]"
        >
          {/* The header spans both columns, so the close button never rides on one of them. */}
          <div className="flex items-start justify-between gap-2">
            <div className="flex min-w-0 flex-col gap-1">
              <span className="text-xs font-semibold uppercase tracking-wider text-hestia-text">
                Create course
              </span>
              <p className="text-xs text-hestia-text-muted">
                Upload your materials and we'll extract learning goals automatically.
              </p>
            </div>
            <Button
              variant="ghost"
              size="icon-sm"
              onClick={onClose}
              disabled={busy}
              aria-label="Close"
            >
              <svg
                viewBox="0 0 20 20"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                className="h-4 w-4"
              >
                <path d="M5 5l10 10M15 5L5 15" />
              </svg>
            </Button>
          </div>

          {/* What the course IS on the left, what it is MADE OF on the right. The staged files are
              the part that grows, so keeping them in their own column stops them from pushing the
              settings out of view. Below `lg` everything stacks. */}
          <div className="flex w-full flex-col gap-3.5 lg:flex-row lg:items-stretch">
            <div className="flex w-full min-w-0 flex-col gap-3.5 lg:max-w-md lg:shrink-0">
              <div className="flex flex-col gap-2 rounded-lg border border-hestia-border bg-hestia-surface p-4 shadow-lg">
                <label
                  htmlFor="course-name"
                  className="text-xs font-semibold uppercase tracking-wider text-hestia-text-muted"
                >
                  Course title
                </label>
                <input
                  id="course-name"
                  className="w-full rounded-sm border-[1.5px] border-hestia-border bg-hestia-bg px-2.5 py-2 text-sm text-hestia-text transition placeholder:text-hestia-text-muted focus:border-hestia-primary focus:shadow-[0_0_0_3px_var(--hestia-primary-muted)] focus:outline-none"
                  placeholder="e.g. Introduction to Data Science"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  autoFocus
                />
              </div>

              <div className="flex flex-col gap-2 rounded-lg border border-hestia-border bg-hestia-surface p-4 shadow-lg">
                <label
                  htmlFor="output-language"
                  className="text-xs font-semibold uppercase tracking-wider text-hestia-text-muted"
                >
                  Output language
                </label>
                <select
                  id="output-language"
                  value={outputLanguage}
                  onChange={(e) => setOutputLanguage(e.target.value as "" | "de" | "en")}
                  className="w-full rounded-sm border-[1.5px] border-hestia-border bg-hestia-bg px-2.5 py-2 text-sm text-hestia-text transition focus:border-hestia-primary focus:shadow-[0_0_0_3px_var(--hestia-primary-muted)] focus:outline-none"
                >
                  <option value="">Same as the materials</option>
                  <option value="de">Deutsch</option>
                  <option value="en">English</option>
                </select>
                <span className="text-xs text-hestia-text-muted">
                  Learning goals are written in the language of your uploaded materials unless you
                  pick one here.
                </span>
              </div>

              <div className="flex flex-col gap-2 rounded-lg border border-hestia-border bg-hestia-surface p-4 shadow-lg">
                <span className="text-xs font-semibold uppercase tracking-wider text-hestia-text-muted">
                  Slide images
                </span>
                <label className="flex cursor-pointer items-start gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={figuresEnabled}
                    onChange={(e) => setFiguresEnabled(e.target.checked)}
                    className="mt-0.5 h-3.5 w-3.5 shrink-0 accent-hestia-primary"
                  />
                  <span className="flex flex-col gap-1.5">
                    <span className="font-medium text-hestia-text">
                      Analyse slide images and diagrams
                    </span>
                    <span className="text-xs text-hestia-text-muted">
                      This makes extraction noticeably slower.
                    </span>
                  </span>
                </label>
              </div>

              <div className="flex flex-col gap-4 rounded-lg border border-hestia-border bg-hestia-surface p-4 shadow-lg">
                {step && (
                  <div>
                    <div className="flex items-center justify-between gap-3 text-sm text-hestia-text-muted">
                      <span aria-live="polite">{STEP_LABEL[step]}</span>
                      {step === "uploading" && (
                        <span className="tabular-nums">{uploadPercent}%</span>
                      )}
                    </div>
                    <div
                      className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-hestia-primary-muted"
                      role="progressbar"
                      aria-label={STEP_LABEL[step]}
                      aria-valuemin={step === "uploading" ? 0 : undefined}
                      aria-valuemax={step === "uploading" ? 100 : undefined}
                      aria-valuenow={step === "uploading" ? uploadPercent : undefined}
                    >
                      {step === "uploading" ? (
                        <div
                          className="h-full rounded-full bg-hestia-primary transition-[width] duration-300 ease-out"
                          style={{ width: `${uploadPercent}%` }}
                        />
                      ) : (
                        <div className="h-full w-1/3 animate-pulse rounded-full bg-hestia-primary" />
                      )}
                    </div>
                  </div>
                )}
                {formError && <p className="text-sm text-hestia-danger">{formError}</p>}
                <div className="flex items-center justify-between gap-3">
                  <Button variant="ghost" size="lg" onClick={onClose} disabled={busy}>
                    Cancel
                  </Button>
                  <Button type="submit" size="lg" disabled={!trimmed || busy}>
                    {busy ? "Creating…" : "Create course →"}
                  </Button>
                </div>
              </div>
            </div>

            {/* Taken out of flow at `lg`: the staged files then cannot stretch the panel, so it is
                the same size with thirteen files as with none. The wrapper carries the width and
                takes its height from the settings column; the card fills it and the list scrolls. */}
            <div className="w-full min-w-0 lg:relative lg:flex-1">
              <div className="flex w-full min-w-0 flex-col gap-2 rounded-lg border border-hestia-border bg-hestia-surface p-4 shadow-lg lg:absolute lg:inset-0 lg:min-h-0">
              <div className="flex items-center justify-between gap-2">
                <span className="text-xs font-semibold uppercase tracking-wider text-hestia-text-muted">
                  Course materials
                </span>
                {files.length > 0 && (
                  <span className="shrink-0 text-xs tabular-nums text-hestia-text-muted">
                    {files.length} file{files.length === 1 ? "" : "s"} · {formatSize(totalBytes)}
                  </span>
                )}
              </div>
              <div
                role="button"
                tabIndex={0}
                onClick={() => fileInputRef.current?.click()}
                onKeyDown={(e) => {
                  if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault();
                    fileInputRef.current?.click();
                  }
                }}
                onDragOver={(e) => {
                  e.preventDefault();
                  setIsDragging(true);
                }}
                onDragLeave={() => setIsDragging(false)}
                onDrop={(e) => {
                  e.preventDefault();
                  setIsDragging(false);
                  addFiles(e.dataTransfer.files);
                }}
                className={`flex cursor-pointer flex-col items-center justify-center rounded-lg border-2 border-dashed px-6 py-8 text-center transition ${
                  // Nothing staged yet: the zone fills the column instead of leaving it empty.
                  files.length > 0 ? "shrink-0" : "lg:flex-1"
                } ${
                  isDragging
                    ? "border-hestia-primary bg-hestia-primary-muted"
                    : "border-hestia-border hover:border-hestia-primary"
                }`}
              >
                <svg
                  className="h-8 w-8 text-hestia-text-muted"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.5"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  aria-hidden
                >
                  <path d="M12 16V4m0 0L8 8m4-4 4 4" />
                  <path d="M4 14v4a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-4" />
                </svg>
                <p className="mt-3 text-sm font-medium text-hestia-text">
                  Drag &amp; drop your course materials here
                </p>
                <p className="text-sm text-hestia-text-muted">
                  or <span className="font-medium text-hestia-primary">browse files</span>
                </p>
                <p className="mt-2 text-xs text-hestia-text-muted">
                  Supported: {ACCEPT_LABEL} · Max 100 MB per file
                </p>
              </div>
              {/* Deliberately a sibling of the drop zone, not a child: the zone's onClick calls
                  click() on this input, and a click dispatched on a descendant bubbles straight
                  back into that same handler — which re-opens the picker forever and blows the
                  stack. Keeping it outside breaks the cycle. */}
              <input
                ref={fileInputRef}
                type="file"
                multiple
                accept={ACCEPT}
                className="hidden"
                onChange={(e) => {
                  addFiles(e.target.files);
                  if (fileInputRef.current) fileInputRef.current.value = "";
                }}
              />

              {/* The list is the only part that grows, so it absorbs the column's spare height and
                  scrolls inside it — the count above stays visible while it does. Stacked below `lg`
                  there is no shared height to fill, so a viewport cap stands in. */}
              {files.length > 0 && (
                <ul className="mt-1 flex max-h-[50vh] min-h-0 flex-1 flex-col gap-2 overflow-y-auto lg:max-h-none">
                  {files.map((file, index) => (
                    <li
                      key={`${file.name}:${file.size}`}
                      className="flex items-center gap-3 rounded-md border border-hestia-border bg-hestia-bg px-3 py-2"
                    >
                      <span aria-hidden className="text-base">📄</span>
                      <span className="min-w-0 flex-1 truncate text-sm text-hestia-text">
                        {file.name}
                      </span>
                      <span className="shrink-0 text-xs tabular-nums text-hestia-text-muted">
                        {formatSize(file.size)}
                      </span>
                      <button
                        type="button"
                        onClick={() => removeFile(index)}
                        aria-label={`Remove ${file.name}`}
                        className="shrink-0 rounded-md px-1.5 text-lg leading-none text-hestia-text-muted transition hover:text-hestia-danger"
                      >
                        ×
                      </button>
                    </li>
                  ))}
                </ul>
              )}
              </div>
            </div>
          </div>
        </form>
      </div>
    </div>
  );
}

/**
 * Uploads the staged materials in one request. XHR rather than fetch: only XHR reports how many
 * bytes have gone out, which is the one part of this wait we can measure. `onTransferred` fires when
 * the last byte is sent — everything after that is the server parsing, with no progress to report.
 */
function uploadDocuments(
  courseId: number,
  files: File[],
  onPercent: (percent: number) => void,
  onTransferred: () => void,
): Promise<void> {
  const formData = new FormData();
  files.forEach((file) => formData.append("files", file));

  return new Promise((resolve, reject) => {
    const request = new XMLHttpRequest();
    request.open("POST", `${API_PREFIX}/api/courses/${courseId}/documents`);
    request.upload.onprogress = (event) => {
      if (event.lengthComputable) {
        onPercent(Math.round((event.loaded / event.total) * 100));
      }
    };
    request.upload.onload = () => onTransferred();
    request.onload = () => {
      if (request.status >= 200 && request.status < 300) resolve();
      else reject(new Error(`Course created, but the upload failed (HTTP ${request.status}).`));
    };
    request.onerror = () =>
      reject(new Error("Course created, but the upload failed. Please check your connection."));
    request.send(formData);
  });
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
