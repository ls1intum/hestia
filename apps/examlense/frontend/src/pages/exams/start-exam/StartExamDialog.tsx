import { useEffect, useState, type ReactNode } from "react";
import { Clock } from "lucide-react";
import { useNavigate } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { parseExamPdf } from "@/lib/api/api-parse";
import { validateUploadFile } from "@/lib/parsing/pdf-precheck";
import { formatParseEstimate } from "@/lib/parsing/parse-estimate";
import { useSolverModels } from "@/lib/api/api-models";
import {
  resolveSelectableDefault,
  selectableModels,
} from "@/lib/exam/llm-models";
import { useToast } from "@/hooks/ui/use-toast";
import {
  createExam,
  deleteExam,
  uploadExamPdf,
  createSection,
  createBlock,
  createTask,
} from "@/lib/api/api-client";
import { WizardShell } from "./WizardShell";
import { UploadStep } from "./UploadStep";
import { SolverModelStep } from "./SolverModelStep";
import { MetadataStep, type ExamLanguage } from "./MetadataStep";
import { CoursePickerStep, CREATE_COURSE, NO_COURSE } from "./CoursePickerStep";
import { useCreateLghCourse } from "@/hooks/data/use-learning-goals";

export type StartExamMode = "pdf" | "manual";

type Step = "upload" | "solver" | "course" | "metadata";

const PDF_STEPS: Step[] = ["upload", "course", "solver"];
const MANUAL_STEPS: Step[] = ["metadata", "course", "solver"];

const SCAFFOLD_CONTEXT =
  "Enter the shared context for the tasks in this section here — instructions, definitions, code snippets, or any reference material the AI should consider when solving.";
const SCAFFOLD_TASK =
  "Example: Explain two levels of Bloom's Taxonomy in short terms and give one exam-question example for each level.";

/**
 * Guided, one-question-per-step exam creation. Driven by `mode`:
 * - "pdf":    Drop PDF (+ Fast Mode toggle) → link course → pick solver →
 *             lands on the parsing loading screen. Nothing is created until the
 *             final confirm: the exam is created, the PDF uploaded, and parsing
 *             fired only when the last step ("Open exam") is confirmed. The
 *             backend owns the parser model choice (no client-side model
 *             selection). One exam per PDF.
 * - "manual": Metadata → link course → pick solver → creates a scaffolded exam
 *             and drops the author into the editor.
 * Open when `mode` is non-null; `onClose` clears it in the parent.
 */
export const StartExamDialog = ({
  mode,
  onClose,
}: {
  mode: StartExamMode | null;
  onClose: () => void;
}) => {
  const navigate = useNavigate();
  const { toast } = useToast();
  const queryClient = useQueryClient();
  const onError = (msg: string) =>
    toast({ title: msg, variant: "destructive" });

  const createCourse = useCreateLghCourse();
  const { data: solverCatalog } = useSolverModels();
  const defaultSolverId = resolveSelectableDefault(
    selectableModels(solverCatalog?.models ?? []),
    solverCatalog?.defaultId,
  );

  const [stepIndex, setStepIndex] = useState(0);
  const [busy, setBusy] = useState(false);

  // Collected across steps.
  const [file, setFile] = useState<File | null>(null);
  const [parserFastMode, setParserFastMode] = useState(false);
  // Page count of the uploaded PDF, captured on the upload step's Continue and
  // used for the pre-parse time estimate. null = unknown (docx or pdfjs failed).
  const [pageCount, setPageCount] = useState<number | null>(null);
  const [solverId, setSolverId] = useState("");
  const [courseValue, setCourseValue] = useState(NO_COURSE);
  const [newCourseName, setNewCourseName] = useState("");
  const [title, setTitle] = useState("");
  const [language, setLanguage] = useState<ExamLanguage>("en");

  // Fresh state each time the dialog opens.
  useEffect(() => {
    if (!mode) return;
    setStepIndex(0);
    setBusy(false);
    setFile(null);
    setParserFastMode(false);
    setPageCount(null);
    setSolverId(defaultSolverId);
    setCourseValue(NO_COURSE);
    setNewCourseName("");
    setTitle("");
    setLanguage("en");
    // Only reset on open/mode change — not when the model catalog refreshes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mode]);

  const activeMode: StartExamMode = mode ?? "pdf";
  const steps = activeMode === "manual" ? MANUAL_STEPS : PDF_STEPS;
  const step = steps[stepIndex];
  const open = mode !== null;

  const goBack = () => setStepIndex((i) => Math.max(0, i - 1));

  // Resolve the LGH course id to link. When the author chose "create new", the
  // empty course is created in LGH now (once) and its id reused. Throws on
  // failure so callers can abort instead of silently linking nothing.
  const resolveCourseId = async (): Promise<number | null> => {
    if (courseValue === NO_COURSE) return null;
    if (courseValue === CREATE_COURSE) {
      const course = await createCourse.mutateAsync(newCourseName.trim());
      return course.id;
    }
    return Number(courseValue);
  };

  // PDF: create the exam (with the chosen solver), upload the PDF, and fire
  // parsing (the backend picks the parser model). Fired only on the final
  // confirm. Returns the new exam id on success (or null on failure, in which
  // case the exam is rolled back so the author can retry cleanly).
  const startParsing = async (): Promise<string | null> => {
    if (!file) return null;
    setBusy(true);

    let courseId: number | null;
    try {
      courseId = await resolveCourseId();
    } catch (e) {
      console.error("create LGH course failed", e);
      setBusy(false);
      onError("Couldn't create the LearningGoalHub course. Please try again.");
      return null;
    }

    const baseTitle = file.name.replace(/\.(pdf|docx)$/i, "");

    let examRow;
    try {
      examRow = await createExam({
        title: baseTitle,
        source: "pdf",
        status: "parsing",
        language: "en",
        solver_model: solverId,
        lgh_course_id: courseId,
      });
    } catch (e) {
      console.error("exam insert failed", e);
      setBusy(false);
      onError("Couldn't start parsing. Please try again.");
      return null;
    }

    let storagePath: string;
    try {
      const res = await uploadExamPdf(examRow.id, file);
      storagePath = res.storage_path;
    } catch (e) {
      console.error("upload failed", e);
      try {
        await deleteExam(examRow.id);
      } catch {
        /* best-effort */
      }
      setBusy(false);
      // Surface the server's specific message (e.g. a .docx conversion failure)
      // when present, falling back to the generic one.
      onError(
        (e as Error)?.message || "Couldn't start parsing. Please try again.",
      );
      return null;
    }

    parseExamPdf({
      examId: examRow.id,
      storagePath,
      fastMode: parserFastMode,
    }).catch((e) => console.error("invoke parse-exam-pdf failed", e));

    queryClient.invalidateQueries({ queryKey: ["exams-list"] });
    setBusy(false);
    return examRow.id;
  };

  // PDF: send the author to the parsing loading screen.
  const finishPdf = (id: string) => {
    queryClient.invalidateQueries({ queryKey: ["exams-list"] });
    toast({
      title: "Parsing started — we'll update your dashboard when it's ready.",
    });
    onClose();
    navigate(`/exams/${id}/edit`);
  };

  // Manual: create the exam now (course linked) + scaffold a starter section,
  // then open the editor.
  const finishManual = async () => {
    setBusy(true);
    let courseId: number | null;
    try {
      courseId = await resolveCourseId();
    } catch (e) {
      console.error("create LGH course failed", e);
      setBusy(false);
      onError("Couldn't create the LearningGoalHub course. Please try again.");
      return;
    }
    let exam;
    try {
      exam = await createExam({
        title: title.trim(),
        language,
        source: "manual",
        status: "draft",
        solver_model: solverId,
        lgh_course_id: courseId,
      });
    } catch {
      setBusy(false);
      onError("Could not create exam. Please try again.");
      return;
    }

    const examId = exam.id;
    // Sequential inserts (ascending position) so the backend's shift-on-insert
    // can't race them into a scrambled order.
    try {
      const sec = await createSection({
        exam_id: examId,
        position: 0,
        name: "Section 1",
      });
      await createBlock({
        exam_id: examId,
        section_id: sec.id,
        position: 0,
        kind: "context",
        content: SCAFFOLD_CONTEXT,
      });
      await createBlock({
        exam_id: examId,
        section_id: sec.id,
        position: 1,
        kind: "figure",
        content: "",
      });
      await createTask({
        exam_id: examId,
        section_id: sec.id,
        position: 2,
        type: "text",
        prompt: SCAFFOLD_TASK,
      });
    } catch (e) {
      console.error("manual scaffold failed", e);
    }

    onClose();
    navigate(`/exams/${examId}/edit`);
  };

  const handleNext = async () => {
    if (busy) return;
    if (activeMode === "pdf") {
      if (step === "upload") {
        // Completable pre-checks fire here, before any exam is created — so the
        // author gets immediate feedback instead of a server-side parse failure.
        // The same pass yields the page count for the final step's time estimate
        // (null for docx / unreadable PDFs → no estimate).
        if (file) {
          const { error, pageCount } = await validateUploadFile(file);
          if (error) return onError(error);
          setPageCount(pageCount);
        }
        return setStepIndex(1);
      }
      if (step === "course") {
        // No side effects here anymore — the exam is created and parsing fired
        // only on the final confirm.
        return setStepIndex(2);
      }
      if (step === "solver") {
        const id = await startParsing();
        if (id) finishPdf(id);
        return;
      }
    } else {
      if (step === "metadata") return setStepIndex(1);
      if (step === "course") return setStepIndex(2);
      if (step === "solver") return void finishManual();
    }
  };

  const meta = (): {
    heading: string;
    subtitle?: string;
    nextLabel: string;
    nextVariant?: "primary" | "muted";
    nextNote?: string;
    nextInfo?: ReactNode;
    disabled: boolean;
    body: ReactNode;
  } => {
    switch (step) {
      case "upload":
        return {
          heading: "Import an Exam",
          subtitle: "Upload an exam and we'll extract the tasks automatically.",
          nextLabel: "Continue",
          disabled: !file,
          body: (
            <UploadStep
              file={file}
              onChange={setFile}
              onError={onError}
              fastMode={parserFastMode}
              onFastModeChange={setParserFastMode}
            />
          ),
        };
      case "metadata":
        return {
          heading: "Exam Metadata",
          nextLabel: "Continue",
          disabled: !title.trim(),
          body: (
            <MetadataStep
              title={title}
              language={language}
              onTitleChange={setTitle}
              onLanguageChange={setLanguage}
              onSubmit={handleNext}
            />
          ),
        };
      case "solver": {
        // On the PDF flow this is the final confirm that actually starts parsing,
        // so show a rough page-count-based time estimate left of the button.
        const estimate =
          activeMode === "pdf" && pageCount
            ? formatParseEstimate(pageCount, parserFastMode)
            : null;
        return {
          heading: "Which LLM should solve the exam?",
          nextLabel: activeMode === "pdf" ? "Start Parsing" : "Create exam",
          nextInfo: estimate ? (
            <span className="inline-flex items-center gap-1">
              <Clock size={12} aria-hidden="true" />
              {`Est. ${estimate}`}
            </span>
          ) : undefined,
          disabled: !solverId,
          body: <SolverModelStep value={solverId} onChange={setSolverId} />,
        };
      }
      case "course": {
        // Nothing picked yet → the step is skippable, but skipping means no
        // learning-goal insights, so the primary button greys out into "Skip"
        // with a caveat instead of a confident "Continue".
        const skipping = courseValue === NO_COURSE;
        return {
          heading: "Connect to LearningGoalHub",
          nextLabel: skipping ? "Skip" : "Continue",
          nextVariant: skipping ? "muted" : "primary",
          nextNote: skipping
            ? "No learning goal insights without a course"
            : undefined,
          disabled: courseValue === CREATE_COURSE && !newCourseName.trim(),
          body: (
            <CoursePickerStep
              value={courseValue}
              onChange={setCourseValue}
              newCourseName={newCourseName}
              onNewCourseNameChange={setNewCourseName}
            />
          ),
        };
      }
    }
  };

  const m = meta();

  return (
    <WizardShell
      open={open}
      onOpenChange={(o) => {
        if (!o) onClose();
      }}
      title={m.heading}
      subtitle={m.subtitle}
      stepIndex={stepIndex}
      stepCount={steps.length}
      onBack={stepIndex === 0 ? undefined : goBack}
      onNext={handleNext}
      nextLabel={m.nextLabel}
      nextVariant={m.nextVariant}
      nextNote={m.nextNote}
      nextInfo={m.nextInfo}
      nextDisabled={m.disabled}
      busy={busy}
    >
      {m.body}
    </WizardShell>
  );
};
