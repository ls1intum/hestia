import { useEffect, useMemo, useState } from "react";
import { ReadOnlyContextBlock } from "@/components/shared/exam-content/read-only/ReadOnlyContextBlock";
import { ReadOnlyFigureBlock } from "@/components/shared/exam-content/read-only/ReadOnlyFigureBlock";
import { ReadOnlyTaskCard } from "@/components/shared/exam-content/read-only/ReadOnlyTaskCard";
import { MarkdownView } from "@/components/shared/exam-content/MarkdownView";
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
}

export const AllTasksList = ({
  tasks,
  sections,
  blocks,
  answersById,
  gradesById,
}: Props) => {
  const grouped = useMemo(() => {
    const sortedSections = sections.slice().sort((a, b) => a.position - b.position);
    const all: (Section | null)[] = [...sortedSections, null];
    const sectionIndexById = new Map<string, number>();
    sortedSections.forEach((s, i) => sectionIndexById.set(s.id, i));
    return all
      .map((sec) => {
        const sId = sec?.id ?? null;
        const secTasks = tasks
          .filter((tk) => (tk.section_id ?? null) === sId)
          .sort((a, b) => a.position - b.position);
        const secBlocks: SectionBlock[] = sec
          ? blocks.filter((b) => b.section_id === sec.id)
          : [];
        const slug = sec
          ? `section-${(sectionIndexById.get(sec.id) ?? 0) + 1}`
          : "section-unassigned";
        const title =
          sec?.name?.trim() ||
          (sec ? "Untitled section" : "Unassigned tasks");
        const { earned, max } = scoreRollup(secTasks, gradesById, answersById);
        return {
          slug,
          title,
          tasks: secTasks,
          items: mergeSectionItems(secTasks, secBlocks),
          earned,
          max,
        };
      })
      .filter((g) => g.items.length > 0);
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
    if (typeof window === "undefined") return "";
    return window.location.hash.replace(/^#/, "");
  });
  useEffect(() => {
    const validIds = new Set(grouped.map((g) => g.slug));
    if (!currentSlug || !validIds.has(currentSlug)) {
      setCurrentSlug(grouped[0]?.slug ?? "");
    }
  }, [grouped, currentSlug]);

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
              const hasTextAnswer =
                task.type === "text" && !!answer?.answer_text;
              const hasReasoning = !!answer?.reasoning;
              const panel = (
                <div className="space-y-hestia-2">
                  {(hasTextAnswer || hasReasoning) && (
                    <details className="rounded-hestia-sm border border-hestia-border/60 bg-hestia-bg/40 px-hestia-2 py-1.5">
                      <summary className="cursor-pointer select-none hestia-eyebrow text-hestia-text-muted">
                        AI answer
                      </summary>
                      <div className="mt-hestia-2 space-y-hestia-2">
                        {hasTextAnswer && (
                          <div className="rounded-hestia-sm border border-hestia-border/60 bg-hestia-surface px-hestia-2 py-hestia-2">
                            <MarkdownView content={answer!.answer_text ?? ""} />
                          </div>
                        )}
                        {hasReasoning && (
                          <details className="text-xs text-hestia-text-muted">
                            <summary className="cursor-pointer select-none">
                              Show reasoning
                            </summary>
                            <div className="mt-1">
                              <MarkdownView
                                content={answer!.reasoning ?? ""}
                                className="text-hestia-text-muted"
                              />
                            </div>
                          </details>
                        )}
                      </div>
                    </details>
                  )}
                  <div className="rounded-hestia-sm border border-hestia-border/60 bg-hestia-bg/40 px-hestia-2 py-1.5">
                    <div className="flex items-center justify-between gap-hestia-2 text-xs text-hestia-text-muted">
                      <span className="hestia-eyebrow">Score</span>
                      <span className="tabular-nums">
                        <span className="font-semibold text-hestia-text">
                          {eff.score != null ? Number(eff.score.toFixed(2)) : "—"}
                        </span>
                        {" / "}
                        {maxPoints}
                      </span>
                    </div>
                    <ScoreBar pct={pct * 100} tone="tier" className="mt-1" />
                  </div>
                </div>
              );
              return (
                <ReadOnlyTaskCard
                  key={`t-${task.id}`}
                  task={task}
                  label={letterById.get(task.id) ?? ""}
                  graded={eff.score != null}
                  answer={answer}
                  gradingPanel={panel}
                />
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
        currentSectionId={currentSlug}
        onSelectSection={setCurrentSlug}
      />
      <div className="relative flex min-w-0 flex-1 flex-col">
        <div className="flex-1 overflow-y-auto">
          <div className="mx-auto w-full max-w-[900px] px-hestia-6 pb-hestia-8 pt-hestia-5">
            <SectionCarousel
              slides={slides}
              currentId={currentSlug}
              onChange={setCurrentSlug}
            />
          </div>
        </div>
      </div>
    </div>
  );
};
