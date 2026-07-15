import { useEffect, useState, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { parseExamPdf } from "@/lib/api/api-parse";
import { useSolverModels } from "@/lib/api/api-models";
import { resolveSelectableDefault, selectableModels } from "@/lib/exam/llm-models";
import { useToast } from "@/hooks/ui/use-toast";
import {
  createExam,
  deleteExam,
  uploadExamPdf,
  patchExam,
  createSection,
  createBlock,
  createTask,
} from "@/lib/api/api-client";
import { WizardShell } from "./WizardShell";
import { UploadStep } from "./UploadStep";
import { ParserModelStep } from "./ParserModelStep";
import { SolverModelStep } from "./SolverModelStep";
import { MetadataStep, type ExamLanguage } from "./MetadataStep";
import { CoursePickerStep, CREATE_COURSE, NO_COURSE } from "./CoursePickerStep";
import { useCreateLghCourse } from "@/hooks/data/use-learning-goals";

export type StartExamMode = "pdf" | "manual";

type Step = "upload" | "parser" | "solver" | "course" | "metadata";

const PDF_STEPS: Step[] = ["upload", "course", "parser", "solver"];
const MANUAL_STEPS: Step[] = ["metadata", "course", "solver"];

const SCAFFOLD_CONTEXT =
  "Enter the shared context for the tasks in this section here — instructions, definitions, code snippets, or any reference material the AI should consider when solving.";
const SCAFFOLD_TASK =
  "Example: Explain two levels of Bloom's Taxonomy in short terms and give one exam-question example for each level.";

/**
 * Guided, one-question-per-step exam creation. Driven by `mode`:
 * - "pdf":    Drop PDF → link course → pick parser model(s) → pick solver →
 *             lands on the parsing loading screen. Parsing is fired eagerly the
 *             moment the parser step is confirmed (one exam per selected model).
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
  const [parserModelIds, setParserModelIds] = useState<string[]>([]);
  const [parserFastMode, setParserFastMode] = useState(false);
  const [solverId, setSolverId] = useState("");
  const [courseValue, setCourseValue] = useState(NO_COURSE);
  const [newCourseName, setNewCourseName] = useState("");
  const [title, setTitle] = useState("");
  const [language, setLanguage] = useState<ExamLanguage>("en");
  // PDF flow: exams created (and parsing) once the parser step is confirmed.
  const [examIds, setExamIds] = useState<string[]>([]);

  // Fresh state each time the dialog opens.
  useEffect(() => {
    if (!mode) return;
    setStepIndex(0);
    setBusy(false);
    setFile(null);
    setParserModelIds([]);
    setParserFastMode(false);
    setSolverId(defaultSolverId);
    setCourseValue(NO_COURSE);
    setNewCourseName("");
    setTitle("");
    setLanguage("en");
    setExamIds([]);
    // Only reset on open/mode change — not when the model catalog refreshes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mode]);

  const activeMode: StartExamMode = mode ?? "pdf";
  const steps = activeMode === "manual" ? MANUAL_STEPS : PDF_STEPS;
  const step = steps[stepIndex];
  const open = mode !== null;

  const goBack = () => setStepIndex((i) => Math.max(0, i - 1));

  // Resolve the LGH course id to link. When the author chose "create new", the
  // empty course is created in LGH now (once) and its id reused — so all the
  // PDF flow's per-parser exams share one course. Throws on failure so callers
  // can abort instead of silently linking nothing.
  const resolveCourseId = async (): Promise<number | null> => {
    if (courseValue === NO_COURSE) return null;
    if (courseValue === CREATE_COURSE) {
      const course = await createCourse.mutateAsync(newCourseName.trim());
      return course.id;
    }
    return Number(courseValue);
  };

  // PDF: create one exam per parser model, upload the PDF, and fire parsing.
  // Best-effort per model — a failed one is rolled back, the rest continue.
  const startParsing = async (): Promise<boolean> => {
    if (!file) return false;
    setBusy(true);

    let courseId: number | null;
    try {
      courseId = await resolveCourseId();
    } catch (e) {
      console.error("create LGH course failed", e);
      setBusy(false);
      onError("Couldn't create the LearningGoalHub course. Please try again.");
      return false;
    }

    const baseTitle = file.name.replace(/\.pdf$/i, "");

    const startOne = async (parserModel: string): Promise<string | null> => {
      let examRow;
      try {
        examRow = await createExam({
          title: baseTitle,
          source: "pdf",
          status: "parsing",
          language: "en",
          parser_model: parserModel,
          lgh_course_id: courseId,
        });
      } catch (e) {
        console.error("exam insert failed", parserModel, e);
        return null;
      }

      let storagePath: string;
      try {
        const res = await uploadExamPdf(examRow.id, file);
        storagePath = res.storage_path;
      } catch (e) {
        console.error("upload failed", parserModel, e);
        try {
          await deleteExam(examRow.id);
        } catch {
          /* best-effort */
        }
        return null;
      }

      parseExamPdf({
        examId: examRow.id,
        storagePath,
        parserModel,
        fastMode: parserFastMode,
      }).catch((e) =>
        console.error("invoke parse-exam-pdf failed", e),
      );
      return examRow.id;
    };

    const results = await Promise.all(parserModelIds.map(startOne));
    const ids = results.filter((id): id is string => id !== null);
    queryClient.invalidateQueries({ queryKey: ["exams-list"] });

    if (ids.length === 0) {
      setBusy(false);
      onError("Couldn't start parsing. Please try again.");
      return false;
    }
    setExamIds(ids);
    setBusy(false);
    return true;
  };

  // PDF: apply the chosen solver to every exam after parsing has already started.
  const patchSolver = async (): Promise<boolean> => {
    setBusy(true);
    await Promise.all(
      examIds.map((id) =>
        patchExam(id, { solver_model: solverId }).catch((e) =>
          console.error("patch solver failed", id, e),
        ),
      ),
    );
    setBusy(false);
    return true;
  };

  // PDF: send the author to the parsing loading screen.
  const finishPdf = async () => {
    queryClient.invalidateQueries({ queryKey: ["exams-list"] });
    toast({
      title:
        examIds.length > 1
          ? `Parsing started for ${examIds.length} exams — we'll update your dashboard as each is ready.`
          : "Parsing started — we'll update your dashboard when it's ready.",
    });
    onClose();
    navigate(`/exams/${examIds[0]}/edit`);
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
      if (step === "upload") return setStepIndex(1);
      if (step === "course") return setStepIndex(2);
      if (step === "parser") {
        if (await startParsing()) setStepIndex(3);
        return;
      }
      if (step === "solver") {
        if (await patchSolver()) return void finishPdf();
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
    disabled: boolean;
    body: ReactNode;
  } => {
    switch (step) {
      case "upload":
        return {
          heading: "Import a PDF",
          subtitle: "Upload an exam and we'll extract the tasks automatically.",
          nextLabel: "Continue",
          disabled: !file,
          body: <UploadStep file={file} onChange={setFile} onError={onError} />,
        };
      case "parser":
        return {
          heading: "Select your PDF parser model",
          nextLabel: "Start parsing",
          disabled: parserModelIds.length === 0,
          body: (
            <ParserModelStep
              selectedIds={parserModelIds}
              onChange={setParserModelIds}
              fastMode={parserFastMode}
              onFastModeChange={setParserFastMode}
            />
          ),
        };
      case "metadata":
        return {
          heading: "Exam metadata",
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
      case "solver":
        return {
          heading: "Which LLM should solve the exam?",
          nextLabel: activeMode === "pdf" ? "Open exam" : "Create exam",
          disabled: !solverId,
          body: <SolverModelStep value={solverId} onChange={setSolverId} />,
        };
      case "course": {
        // Nothing picked yet → the step is skippable, but skipping means no
        // learning-goal insights, so the primary button greys out into "Skip"
        // with a caveat instead of a confident "Continue".
        const skipping = courseValue === NO_COURSE;
        return {
          heading: "Connect a course from LearningGoalHub",
          nextLabel: skipping ? "Skip" : "Continue",
          nextVariant: skipping ? "muted" : "primary",
          nextNote: skipping
            ? "No learning-goal insights without a course"
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
      nextDisabled={m.disabled}
      busy={busy}
    >
      {m.body}
    </WizardShell>
  );
};
