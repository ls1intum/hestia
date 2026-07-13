import { useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, API_PREFIX } from "../api/client.ts";
import { useTheme } from "../theme/context.ts";
import ExtractionProgressModal from "../components/ExtractionProgressModal.tsx";
import iconLight from "../assets/logos/icon-light.svg";
import iconDark from "../assets/logos/icon-dark.svg";

// The upload endpoint runs everything through Apache Tika, which parses these
// out of the box. Kept in sync with the hint shown in the drop zone.
const ACCEPT = ".pdf,.docx,.pptx,.txt";
const ACCEPT_LABEL = "PDF, DOCX, PPTX, TXT";

// Models exposed by GWDG SAIA. An empty id falls back to the server default; any other
// value is passed through as ?model= so models can be A/B-compared without a restart.
const MODELS: { id: string; label: string }[] = [
  { id: "", label: "Default (openai-gpt-oss-120b)" },
  { id: "qwen3.6-35b-a3b", label: "qwen3.6-35b-a3b" },
  { id: "qwen3.5-122b-a10b", label: "qwen3.5-122b-a10b" },
  { id: "glm-4.7", label: "glm-4.7" },
  { id: "mistral-large-3-675b-instruct-2512", label: "mistral-large-3-675b-instruct-2512" },
  { id: "gemma-4-31b-it", label: "gemma-4-31b-it" },
];

/** Screen 2 — create a course, stage + upload its materials, then run the extraction (screen 3). */
export default function HomePage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { resolved } = useTheme();
  const flame = resolved === "dark" ? iconDark : iconLight;
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [name, setName] = useState("");
  const [files, setFiles] = useState<File[]>([]);
  const [isDragging, setIsDragging] = useState(false);
  const [model, setModel] = useState("");
  const [courseId, setCourseId] = useState<number | null>(null);
  const [progressOpen, setProgressOpen] = useState(false);

  // Accumulate across drops/picks, skipping files already staged (name + size).
  const addFiles = (incoming: FileList | null) => {
    if (!incoming || incoming.length === 0) return;
    setFiles((prev) => {
      const seen = new Set(prev.map((f) => `${f.name}:${f.size}`));
      const next = [...prev];
      for (const file of Array.from(incoming)) {
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
      const { data, error } = await api.POST("/api/courses/{courseId}/extract", {
        params: { path: { courseId: id }, query: model ? { model } : {} },
      });
      if (error || !data) throw new Error("Extraction failed.");
      return data;
    },
  });

  const create = useMutation({
    mutationFn: async () => {
      const { data, error } = await api.POST("/api/courses", {
        body: { name: name.trim() },
      });
      if (error || !data?.id) throw new Error("Could not create the course.");

      if (files.length > 0) {
        const formData = new FormData();
        files.forEach((file) => formData.append("files", file));
        const res = await fetch(`${API_PREFIX}/api/courses/${data.id}/documents`, {
          method: "POST",
          body: formData,
        });
        if (!res.ok) {
          throw new Error(`Course created, but the upload failed (HTTP ${res.status}).`);
        }
      }
      return data.id as number;
    },
    onSuccess: (id) => {
      setCourseId(id);
      // No materials → nothing to extract; land on the (empty) course straight away.
      if (files.length === 0) {
        navigate(`/courses/${id}`);
        return;
      }
      queryClient.setQueryData(["extract-status", id], null);
      setProgressOpen(true);
      extract.mutate(id);
    },
  });

  // Poll the live extraction progress while the (synchronous) extract POST is in flight.
  const statusQuery = useQuery({
    queryKey: ["extract-status", courseId],
    queryFn: async () => {
      const { data } = await api.GET("/api/courses/{courseId}/extract/status", {
        params: { path: { courseId: courseId as number } },
      });
      return data ?? null;
    },
    enabled: progressOpen && courseId != null && extract.isPending,
    refetchInterval: () => (extract.isPending ? 1000 : false),
  });

  const trimmed = name.trim();
  const busy = create.isPending || extract.isPending;

  return (
    <div className="mx-auto max-w-2xl">
      <div className="rounded-xl border border-hestia-border bg-hestia-surface p-6 shadow-sm sm:p-8">
        <div className="flex items-center gap-3">
          <img src={flame} alt="" className="h-9 w-9" />
          <div>
            <h1 className="text-2xl">Create course</h1>
            <p className="mt-0.5 text-sm text-hestia-text-muted">
              Upload your materials and we'll extract learning goals automatically.
            </p>
          </div>
        </div>

        <form
          className="mt-6 flex flex-col gap-5"
          onSubmit={(e) => {
            e.preventDefault();
            if (trimmed && !busy) create.mutate();
          }}
        >
          <div className="flex flex-col gap-1.5">
            <label htmlFor="course-name" className="text-sm font-medium text-hestia-text">
              Course title
            </label>
            <input
              id="course-name"
              className="rounded-md border border-hestia-border bg-hestia-bg px-3 py-2 text-sm text-hestia-text placeholder:text-hestia-text-muted focus:border-hestia-primary focus:outline-none"
              placeholder="e.g. Introduction to Data Science"
              value={name}
              onChange={(e) => setName(e.target.value)}
              autoFocus
            />
          </div>

          <div className="flex flex-col gap-1.5">
            <span className="text-sm font-medium text-hestia-text">Course materials</span>
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
              className={`flex cursor-pointer flex-col items-center justify-center rounded-lg border-2 border-dashed px-6 py-10 text-center transition ${
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
            </div>
          </div>

          {files.length > 0 && (
            <ul className="flex flex-col gap-2">
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

          <label className="flex flex-col gap-1.5 text-sm">
            <span className="font-medium text-hestia-text">Extraction model</span>
            <select
              value={model}
              onChange={(e) => setModel(e.target.value)}
              className="rounded-md border border-hestia-border bg-hestia-bg px-3 py-2 text-sm text-hestia-text focus:border-hestia-primary focus:outline-none"
            >
              {MODELS.map((m) => (
                <option key={m.id} value={m.id}>
                  {m.label}
                </option>
              ))}
            </select>
          </label>

          {create.isError && (
            <p className="text-sm text-hestia-danger">{create.error.message}</p>
          )}

          <div className="flex items-center justify-between gap-3 border-t border-hestia-border pt-5">
            <button
              type="button"
              onClick={() => navigate("/")}
              className="rounded-md px-4 py-2 text-sm font-semibold text-hestia-text-muted transition hover:text-hestia-text"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={!trimmed || busy}
              className="rounded-md bg-hestia-primary px-4 py-2 text-sm font-semibold text-white transition hover:bg-hestia-primary-hover disabled:cursor-not-allowed disabled:opacity-50"
            >
              {create.isPending ? "Creating…" : "Create course →"}
            </button>
          </div>
        </form>
      </div>

      <ExtractionProgressModal
        open={progressOpen}
        status={statusQuery.data ?? undefined}
        result={extract.isSuccess ? extract.data : null}
        error={extract.isError ? extract.error.message : null}
        onClose={() => {
          setProgressOpen(false);
          if (courseId != null) navigate(`/courses/${courseId}`);
        }}
      />
    </div>
  );
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
