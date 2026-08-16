import { useEffect, useMemo, useState } from "react";
import { ReadOnlyContextBlock } from "@/components/shared/exam-content/read-only/ReadOnlyContextBlock";
import { ReadOnlyFigureBlock } from "@/components/shared/exam-content/read-only/ReadOnlyFigureBlock";
import { ReadOnlyQuestionBlock } from "@/components/shared/exam-content/read-only/ReadOnlyQuestionBlock";
import { QuestionAnswerConnector } from "@/components/shared/exam-content/read-only/QuestionAnswerConnector";
import { AnswerCard } from "@/components/shared/exam-content/read-only/AnswerCard";
import { AiAnswerBlock } from "@/components/shared/exam-content/read-only/AiAnswerBlock";
import { SectionLayout } from "@/components/shared/exam-content/SectionLayout";
import {
  SectionCarousel,
  type CarouselSlide,
} from "@/components/shared/exam-content/SectionCarousel";
import {
  SectionSidebar,
  useGradingSectionEntries,
} from "@/components/shared/exam-content/SectionSidebar";
import {
  figureLabelsForBlocks,
  letterLabel,
  mergeSectionItems,
  type Section,
  type SectionBlock,
  type Task,
} from "@/lib/exam/exam-helpers";
import {
  effectiveScore,
  scoreRollup,
  type TaskAnswer,
  type TaskGrade,
} from "@/lib/grading/grading";
import { ScoreBar } from "./ScoreBar";

interface Props {
  tasks: Task[];
  sections: Section[];
  blocks: SectionBlock[];
  answersById: Map<string, TaskAnswer>;
  gradesById: Map<string, TaskGrade>;
  /** When set (e.g. deep-linked from the breakdown table), open this task's
   *  section and scroll to it on mount. */
  scrollToTaskId?: string;
}

export const AllTasksList = ({
  tasks,
  sections,
  blocks,
  answersById,
  gradesById,
  scrollToTaskId,
}: Props) => {
  const grouped = useMemo(() => {
    const sortedSections = sections.slice().sort((a, b) => a.position - b.position);
    const all: (Section | null)[] = [...sortedSections, null];
    const sectionIndexById = new Map<string, number>();
    sortedSections.forEach((s, i) => sectionIndexById.set(s.id, i));
    return all.flatMap((sec) => {
      const sId = sec?.id ?? null;
      const secTasks = tasks
        .filter((tk) => (tk.section_id ?? null) === sId)
        .sort((a, b) => a.position - b.position);
      const secBlocks: SectionBlock[] = sec
        ? blocks.filter((b) => b.section_id === sec.id)
        : [];
      const items = mergeSectionItems(secTasks, secBlocks);
      if (items.length === 0) return [];
      const slug = sec
        ? `section-${(sectionIndexById.get(sec.id) ?? 0) + 1}`
        : "section-unassigned";
      const title =
        sec?.name?.trim() || (sec ? "Untitled section" : "Unassigned tasks");
      const { earned, max } = scoreRollup(secTasks, gradesById, answersById);
      return [{ slug, title, tasks: secTasks, items, earned, max }];
    });
  }, [tasks, sections, blocks, gradesById, answersById]);

  const figureLabels = useMemo(
    () => figureLabelsForBlocks(sections, blocks),
    [sections, blocks],
  );

  const pendingByTaskId = useMemo(() => {
    const m = new Map<string, boolean>();
    for (const task of tasks) {
      const eff = effectiveScore(task, gradesById.get(task.id), answersById.get(task.id));
      m.set(task.id, eff.score == null);
    }
    return m;
  }, [tasks, gradesById, answersById]);

  const gradingEntries = useGradingSectionEntries(
    sections,
    tasks,
    pendingByTaskId,
    gradesById,
    answersById,
  );
  // Results is read-only: drop the grading-only hover swap (name → "X / Y graded")
  // so rows just show the section name + score without a hover state change.
  const sectionEntries = useMemo(
    () => gradingEntries.map((e) => ({ ...e, taskProgressLabel: undefined })),
    [gradingEntries],
  );

  const [currentSlug, setCurrentSlug] = useState<string>(() => {
    // Deep-linked task wins: open its section directly.
    if (scrollToTaskId) {
      const g = grouped.find((gr) => gr.tasks.some((t) => t.id === scrollToTaskId));
      if (g) return g.slug;
    }
    if (typeof window === "undefined") return "";
    return window.location.hash.replace(/^#/, "");
  });

  // After the target section's slide is in the DOM, scroll to the task. Double
  // rAF lets the carousel commit the slide before we measure it.
  useEffect(() => {
    if (!scrollToTaskId) return;
    const raf = requestAnimationFrame(() =>
      requestAnimationFrame(() => {
        document
          .getElementById(`task-${scrollToTaskId}`)
          ?.scrollIntoView({ behavior: "smooth", block: "center" });
      }),
    );
    return () => cancelAnimationFrame(raf);
  }, [scrollToTaskId]);
  // Resolve the effective section during render rather than syncing state via an
  // effect: fall back to the first section whenever the stored slug is empty or
  // points at a section that no longer exists.
  const activeSlug = grouped.some((g) => g.slug === currentSlug)
    ? currentSlug
    : grouped[0]?.slug ?? "";

  const slides: CarouselSlide[] = grouped.map((g) => {
    const letterById = new Map<string, string>();
    g.tasks.forEach((tk, i) => letterById.set(tk.id, letterLabel(i)));
    const scoreMeta = (
      <span className="shrink-0 text-sm tabular-nums text-hestia-text-muted">
        <span className="font-semibold text-hestia-text">
          {Number(g.earned.toFixed(2))}
        </span>
        {" / "}
        {g.max}
      </span>
    );
    return {
      id: g.slug,
      content: (
        <section id={g.slug} className="scroll-mt-12">
          <SectionLayout
            status="confirmed"
            title={
              <h2 className="truncate font-body text-base font-semibold text-hestia-text">
                {g.title}
              </h2>
            }
            headerAction={scoreMeta}
          >
            {g.items.map((item) => {
              if (item.kind === "context") {
                return (
                  <ReadOnlyContextBlock
                    key={`c-${item.block.id}`}
                    block={item.block}
                  />
                );
              }
              if (item.kind === "figure") {
                return (
                  <ReadOnlyFigureBlock
                    key={`f-${item.block.id}`}
                    block={item.block}
                    displayLabel={
                      figureLabels.get(item.block.id) ?? "Figure"
                    }
                  />
                );
              }
              const task = item.task;
              const answer = answersById.get(task.id);
              const eff = effectiveScore(
                task,
                gradesById.get(task.id),
                answer,
              );
              const maxPoints = task.points ?? 0;
              const pct =
                maxPoints > 0 && eff.score != null ? eff.score / maxPoints : 0;
              return (
                <div
                  key={`t-${task.id}`}
                  id={`task-${task.id}`}
                  className="scroll-mt-12"
                >
                  <ReadOnlyQuestionBlock
                    task={task}
                    label={letterById.get(task.id) ?? ""}
                  />
                  <QuestionAnswerConnector />
                  <AnswerCard>
                    {answer ? (
                      <AiAnswerBlock task={task} answer={answer} />
                    ) : (
                      <p className="text-sm text-hestia-text-muted">
                        No answer was generated for this task.
                      </p>
                    )}
                    <div className="mt-hestia-3 border-t border-hestia-border pt-hestia-3">
                      <div className="flex items-center justify-between gap-hestia-2">
                        <span className="hestia-eyebrow text-hestia-text-muted">Score</span>
                        <span className="text-sm tabular-nums">
                          <span className="font-semibold text-hestia-text">
                            {eff.score != null ? Number(eff.score.toFixed(2)) : "—"}
                          </span>
                          <span className="text-hestia-text-muted"> / {maxPoints}</span>
                        </span>
                      </div>
                      <ScoreBar pct={pct * 100} tone="tier" className="mt-hestia-2" />
                    </div>
                  </AnswerCard>
                </div>
              );
            })}
          </SectionLayout>
        </section>
      ),
    };
  });

  if (grouped.length === 0) {
    return (
      <p className="py-hestia-10 text-center text-sm text-hestia-text-muted">
        No tasks.
      </p>
    );
  }

  return (
    <div className="flex min-h-0 min-w-0 flex-1">
      <SectionSidebar
        entries={sectionEntries}
        currentSectionId={activeSlug}
        onSelectSection={setCurrentSlug}
      />
      <div className="relative flex min-w-0 flex-1 flex-col">
        <div className="flex-1 overflow-y-auto">
          <div className="mx-auto w-full max-w-[900px] px-hestia-6 pb-hestia-8 pt-hestia-5">
            <SectionCarousel
              slides={slides}
              currentId={activeSlug}
              onChange={setCurrentSlug}
            />
          </div>
        </div>
      </div>
    </div>
  );
};
