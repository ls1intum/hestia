import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, API_PREFIX } from "../api/client.ts";
import type { LearningGoal } from "../api/client.ts";
import CompetencyTree from "../components/CompetencyTree.tsx";
import CompetencyGraph from "../components/CompetencyGraph.tsx";
import CompetencyGoalModal from "../components/CompetencyGoalModal.tsx";
import ConceptInfoDialog from "../components/ConceptInfoDialog.tsx";
import DocumentsDialog from "../components/DocumentsDialog.tsx";
import FilterPopover from "../components/FilterPopover.tsx";
import {
  LEVEL_META,
  buildCompetencyForest,
  groupGoalsByUnit,
  groupRelationships,
  levelOf,
  presentLevels,
  titleCase,
  tocLabel,
  type GoalGroup,
} from "../lib/goals.ts";

// The course page switches between two concepts: `list` is the extracted learning-goals review
// flow, while `map`/`table` are the synthesised skills concept's representations
// (focus-and-drill map, filterable tree-grid).
type GoalsView = "list" | "table" | "map";
type SkillsView = Extract<GoalsView, "table" | "map">;

// The list view filters like the tree-grid: each column is a multi-select set (empty = all),
// surfaced as a funnel popover + active-filter chips.
type ListFilterKey = "level" | "kind" | "status" | "bloom" | "solo";
const emptyFilters = (): Record<ListFilterKey, Set<string>> => ({
  level: new Set(),
  kind: new Set(),
  status: new Set(),
  bloom: new Set(),
  solo: new Set(),
});

const BLOOM_ORDER = [
  "REMEMBER",
  "UNDERSTAND",
  "APPLY",
  "ANALYZE",
  "EVALUATE",
  "CREATE",
];
const SOLO_ORDER = [
  "PRESTRUCTURAL",
  "UNISTRUCTURAL",
  "MULTISTRUCTURAL",
  "RELATIONAL",
  "EXTENDED_ABSTRACT",
];

export default function CoursePage() {
  const { courseId: courseIdParam } = useParams();
  const courseId = Number(courseIdParam);
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [confirmDelete, setConfirmDelete] = useState(false);
  const [documentsOpen, setDocumentsOpen] = useState(false);
  const [goalsView, setGoalsView] = useState<GoalsView>("table");
  const [lastSkillsView, setLastSkillsView] = useState<SkillsView>("table");
  const [conceptInfoOpen, setConceptInfoOpen] = useState(false);
  const [detailGoal, setDetailGoal] = useState<LearningGoal | null>(null);
  const [editGoal, setEditGoal] = useState<LearningGoal | null>(null);
  const [goalToDelete, setGoalToDelete] = useState<LearningGoal | null>(null);
  // Which session/unit the table-of-contents has selected — the list shows only this group.
  const [selectedGroupKey, setSelectedGroupKey] = useState<string | null>(null);

  const [search, setSearch] = useState("");
  const [filters, setFilters] =
    useState<Record<ListFilterKey, Set<string>>>(emptyFilters);
  const [openFilter, setOpenFilter] = useState<ListFilterKey | null>(null);

  const courseQuery = useQuery({
    queryKey: ["course", courseId],
    queryFn: async () => {
      const { data, error } = await api.GET("/api/courses/{id}", {
        params: { path: { id: courseId } },
      });
      if (error || !data) throw new Error("Could not load the course.");
      return data;
    },
  });

  const goalsQuery = useQuery({
    queryKey: ["goals", courseId],
    queryFn: async () => {
      const { data, error } = await api.GET(
        "/api/courses/{courseId}/learning-goals",
        {
          params: { path: { courseId }, query: { size: 500 } },
        },
      );
      if (error || !data) throw new Error("Could not load learning goals.");
      return data;
    },
  });

  const deleteMutation = useMutation({
    mutationFn: async () => {
      const { error } = await api.DELETE("/api/courses/{id}", {
        params: { path: { id: courseId } },
      });
      if (error) throw new Error("Could not delete the course.");
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["courses"] });
      navigate("/");
    },
  });

  const updateGoalMutation = useMutation({
    mutationFn: async (vars: {
      goalId: number;
      text?: string;
      status?: NonNullable<LearningGoal["status"]>;
      bloomLevel?: LearningGoal["bloomLevel"];
      soloLevel?: LearningGoal["soloLevel"];
    }) => {
      const { goalId, ...body } = vars;
      const { error } = await api.PATCH(
        "/api/courses/{courseId}/learning-goals/{goalId}",
        {
          params: { path: { courseId, goalId } },
          body,
        },
      );
      if (error) throw new Error("Could not update the learning goal.");
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["goals", courseId] });
      setEditGoal(null);
    },
  });

  const deleteGoalMutation = useMutation({
    mutationFn: async (goalId: number) => {
      const { error } = await api.DELETE(
        "/api/courses/{courseId}/learning-goals/{goalId}",
        { params: { path: { courseId, goalId } } },
      );
      if (error) throw new Error("Could not delete the learning goal.");
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["goals", courseId] });
      // goalCount on the course header and the course list comes from the server.
      await queryClient.invalidateQueries({ queryKey: ["course", courseId] });
      await queryClient.invalidateQueries({ queryKey: ["courses"] });
      setGoalToDelete(null);
    },
  });

  // In-modal edits (goal text, Bloom/SOLO dot clicks) go straight through the update mutation.
  const updateGoal = (
    goalId: number,
    changes: {
      text?: string;
      bloomLevel?: LearningGoal["bloomLevel"];
      soloLevel?: LearningGoal["soloLevel"];
    },
  ) => updateGoalMutation.mutate({ goalId, ...changes });

  const toggleApproved = (goal: LearningGoal) =>
    updateGoalMutation.mutate({
      goalId: goal.id!,
      status: goal.status === "APPROVED" ? "PENDING" : "APPROVED",
    });

  // Gap-analysis goals are hidden for now (backlog: dedicated gap review);
  // the pipeline still synthesises and stores them, only the client filters.
  const goals: LearningGoal[] = useMemo(
    () => (goalsQuery.data?.content ?? []).filter((g) => g.origin !== "GAP"),
    [goalsQuery.data],
  );

  // The competency-tree view only appears once the pipeline has synthesised one.
  const skillCount = useMemo(() => buildCompetencyForest(goals).length, [goals]);
  const competencyAvailable = skillCount > 0;
  // The competency views ignore the goal-level filter bar (filtering would prune tree nodes).
  const isCompetencyView = goalsView !== "list";

  // Fall back to the list if a competency view is active but none exists — only once the goals
  // have loaded, so the skills-first default survives the initial empty state.
  useEffect(() => {
    if (goalsQuery.data && isCompetencyView && !competencyAvailable)
      setGoalsView("list");
  }, [goalsQuery.data, isCompetencyView, competencyAvailable]);

  const showLearningGoals = () => setGoalsView("list");
  const showSkills = () => {
    if (competencyAvailable) setGoalsView(lastSkillsView);
  };
  const showSkillsView = (view: SkillsView) => {
    setLastSkillsView(view);
    setGoalsView(view);
  };

  // Only offer filter values that actually appear among the loaded goals, in a sensible order.
  const levels = useMemo(() => presentLevels(goals), [goals]);
  const bloomLevels = useMemo(
    () => BLOOM_ORDER.filter((l) => goals.some((g) => g.bloomLevel === l)),
    [goals],
  );
  const soloLevels = useMemo(
    () => SOLO_ORDER.filter((l) => goals.some((g) => g.soloLevel === l)),
    [goals],
  );
  const kindValues = useMemo(
    () => ["EXPLICIT", "IMPLICIT"].filter((k) => goals.some((g) => g.kind === k)),
    [goals],
  );
  const statusValues = useMemo(
    () =>
      ["PENDING", "APPROVED"].filter((s) =>
        goals.some((g) => (g.status ?? "PENDING") === s),
      ),
    [goals],
  );

  // One entry per filter column, mirroring the tree-grid: label, the values present (in a sensible
  // order) and how to render each. Drives both the funnel popovers and the active-filter chips.
  const filterDefs = useMemo(
    () => [
      {
        key: "level" as const,
        label: "Level",
        options: levels as string[],
        display: (v: string) => LEVEL_META[v as keyof typeof LEVEL_META].label,
      },
      { key: "kind" as const, label: "Kind", options: kindValues, display: titleCase },
      {
        key: "status" as const,
        label: "Status",
        options: statusValues,
        display: titleCase,
      },
      { key: "bloom" as const, label: "Bloom", options: bloomLevels, display: titleCase },
      { key: "solo" as const, label: "SOLO", options: soloLevels, display: titleCase },
    ],
    [levels, kindValues, statusValues, bloomLevels, soloLevels],
  );

  const filteredGoals = useMemo(() => {
    const needle = search.trim().toLowerCase();
    return goals.filter((g) => {
      if (filters.kind.size && !filters.kind.has(g.kind ?? "")) return false;
      if (filters.status.size && !filters.status.has(g.status ?? "PENDING"))
        return false;
      if (filters.level.size && !filters.level.has(levelOf(g))) return false;
      if (filters.bloom.size && !filters.bloom.has(g.bloomLevel ?? ""))
        return false;
      if (filters.solo.size && !filters.solo.has(g.soloLevel ?? ""))
        return false;
      if (needle && !(g.text ?? "").toLowerCase().includes(needle))
        return false;
      return true;
    });
  }, [goals, search, filters]);


  // Session/unit grouping drives both the list and the table of contents. Only the
  // lecture/exercise groups are shown — course-wide "Module goals" and "Ungrouped" goals are
  // dropped from this view.
  const groups = useMemo(
    () =>
      groupGoalsByUnit(filteredGoals).filter(
        (g) => g.level === "SESSION" || g.level === "EXERCISE",
      ),
    [filteredGoals],
  );

  // The list shows one group at a time; fall back to the first group when the
  // selection is unset or filtered away.
  const activeGroup = useMemo(
    () => groups.find((g) => g.key === selectedGroupKey) ?? groups[0] ?? null,
    [groups, selectedGroupKey],
  );

  const toggleFilterValue = (key: ListFilterKey, value: string) =>
    setFilters((prev) => {
      const next = new Set(prev[key]);
      if (next.has(value)) next.delete(value);
      else next.add(value);
      return { ...prev, [key]: next };
    });

  const clearFilter = (key: ListFilterKey) =>
    setFilters((prev) => ({ ...prev, [key]: new Set() }));

  const clearFilters = () => {
    setSearch("");
    setFilters(emptyFilters());
  };

  // Active filters as removable chips (search + each selected value), same as the tree-grid.
  const activeChips: { label: string; value: string; onRemove: () => void }[] =
    [];
  if (search.trim())
    activeChips.push({
      label: "Search",
      value: `“${search.trim()}”`,
      onRemove: () => setSearch(""),
    });
  for (const def of filterDefs) {
    for (const value of filters[def.key]) {
      activeChips.push({
        label: def.label,
        value: def.display(value),
        onRemove: () => toggleFilterValue(def.key, value),
      });
    }
  }

  const showToc = goalsView === "list" && groups.length > 0;
  const explainerText = isCompetencyView
    ? "What this course builds towards — skills with their sub-skills and knowledge, synthesised from the learning goals."
    : "What each session teaches — goals extracted from the uploaded materials. Review, edit and approve them here.";

  const courseName = courseQuery.data?.name ?? `Course #${courseId}`;
  return (
    <div className="flex flex-col gap-6">
      <div className="mx-auto flex w-full max-w-5xl flex-col gap-6">
        {/* Header — course identity stays separate from the concept switch below. */}
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
            <h1 className="text-2xl">{courseName}</h1>
            <CourseMenu
              exportHref={`${API_PREFIX}/api/courses/${courseId}/learning-goals/export.csv`}
              onManageDocuments={() => setDocumentsOpen(true)}
              onDelete={() => setConfirmDelete(true)}
            />
          </div>

          {goals.length > 0 && (
            <div className="flex flex-col gap-2">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div
                  role="tablist"
                  aria-label="Course concept"
                  className="inline-flex gap-0.5 rounded-[0.625rem] bg-[color-mix(in_srgb,var(--hestia-text)_7%,transparent)] p-0.5"
                >
                <button
                  type="button"
                  role="tab"
                  aria-selected={isCompetencyView}
                  aria-disabled={!competencyAvailable}
                  disabled={!competencyAvailable}
                  title={
                    competencyAvailable ? undefined : "No skills synthesised yet"
                  }
                  onClick={showSkills}
                  className={`inline-flex items-baseline gap-1.5 rounded-md px-3 py-1.5 text-sm transition ${
                    isCompetencyView
                      ? "bg-hestia-surface font-semibold text-hestia-text shadow-sm"
                      : competencyAvailable
                        ? "font-medium text-hestia-text-muted hover:text-hestia-text"
                        : "cursor-not-allowed font-medium text-hestia-text-muted opacity-60"
                  }`}
                >
                  Skills{" "}
                  <span
                    className={`text-xs tabular-nums ${
                      isCompetencyView
                        ? "text-hestia-primary"
                        : "text-hestia-text-muted"
                    }`}
                  >
                    {skillCount}
                  </span>
                </button>
                <button
                  type="button"
                  role="tab"
                  aria-selected={!isCompetencyView}
                  onClick={showLearningGoals}
                  className={`inline-flex items-baseline gap-1.5 rounded-md px-3 py-1.5 text-sm transition ${
                    !isCompetencyView
                      ? "bg-hestia-surface font-semibold text-hestia-text shadow-sm"
                      : "font-medium text-hestia-text-muted hover:text-hestia-text"
                  }`}
                >
                  Learning goals{" "}
                  <span
                    className={`text-xs tabular-nums ${
                      !isCompetencyView
                        ? "text-hestia-primary"
                        : "text-hestia-text-muted"
                    }`}
                  >
                    {goals.length}
                  </span>
                </button>
                </div>

                {isCompetencyView && (
                  <div
                    className="inline-flex items-center gap-1"
                    aria-label="Skills representation"
                  >
                  <button
                    type="button"
                    onClick={() => showSkillsView("table")}
                    className={`inline-flex items-center gap-1.5 rounded-md px-2.5 py-1.5 text-sm transition ${
                      goalsView === "table"
                        ? "bg-[color-mix(in_srgb,var(--hestia-text)_7%,transparent)] font-medium text-hestia-text"
                        : "text-hestia-text-muted hover:text-hestia-text"
                    }`}
                  >
                    <TableIcon />
                    Table
                  </button>
                  <button
                    type="button"
                    onClick={() => showSkillsView("map")}
                    className={`inline-flex items-center gap-1.5 rounded-md px-2.5 py-1.5 text-sm transition ${
                      goalsView === "map"
                        ? "bg-[color-mix(in_srgb,var(--hestia-text)_7%,transparent)] font-medium text-hestia-text"
                        : "text-hestia-text-muted hover:text-hestia-text"
                    }`}
                  >
                    <MapIcon />
                    Map
                  </button>
                  </div>
                )}
              </div>

              <p className="flex flex-wrap items-baseline gap-x-2 gap-y-1 text-sm text-hestia-text-muted">
                <span>{explainerText}</span>
                <button
                  type="button"
                  onClick={() => setConceptInfoOpen(true)}
                  className="text-sm text-hestia-primary underline decoration-[color-mix(in_srgb,var(--hestia-primary)_40%,transparent)] underline-offset-[3px] transition hover:decoration-hestia-primary"
                >
                  How do these relate?
                </button>
              </p>
            </div>
          )}
        </div>
        {deleteMutation.isError && (
          <p className="text-sm text-hestia-danger">
            {(deleteMutation.error as Error).message}
          </p>
        )}
        {/* Approve toggles run without a dialog, so their failures surface here. */}
        {updateGoalMutation.isError && !editGoal && (
          <p className="text-sm text-hestia-danger">
            {(updateGoalMutation.error as Error).message}
          </p>
        )}

        {confirmDelete && (
          <ConfirmDialog
            title="Delete course?"
            message={`This permanently removes "${courseName}" and all of its learning goals. This cannot be undone.`}
            confirmLabel={
              deleteMutation.isPending ? "Deleting…" : "Delete course"
            }
            busy={deleteMutation.isPending}
            onConfirm={() => deleteMutation.mutate()}
            onCancel={() => setConfirmDelete(false)}
          />
        )}

        {goalToDelete && (
          <ConfirmDialog
            title="Delete learning goal?"
            message={`This permanently removes "${goalToDelete.text}" together with its sources and relationships. This cannot be undone.`}
            confirmLabel={
              deleteGoalMutation.isPending ? "Deleting…" : "Delete goal"
            }
            busy={deleteGoalMutation.isPending}
            error={
              deleteGoalMutation.isError
                ? (deleteGoalMutation.error as Error).message
                : undefined
            }
            onConfirm={() => deleteGoalMutation.mutate(goalToDelete.id!)}
            onCancel={() => {
              deleteGoalMutation.reset();
              setGoalToDelete(null);
            }}
          />
        )}

        {editGoal && (
          <EditGoalDialog
            key={editGoal.id}
            goal={editGoal}
            busy={updateGoalMutation.isPending}
            error={
              updateGoalMutation.isError
                ? (updateGoalMutation.error as Error).message
                : undefined
            }
            onSave={(changes) =>
              updateGoalMutation.mutate({ goalId: editGoal.id!, ...changes })
            }
            onCancel={() => {
              updateGoalMutation.reset();
              setEditGoal(null);
            }}
          />
        )}

        {conceptInfoOpen && (
          <ConceptInfoDialog onClose={() => setConceptInfoOpen(false)} />
        )}

        {documentsOpen && (
          <DocumentsDialog
            courseId={courseId}
            onClose={() => setDocumentsOpen(false)}
          />
        )}

        {/* Filter bar — hidden on the competency views, which render the full synthesised tree.
            Filters the same way as the tree-grid: funnel popovers (multi-select) + active chips. */}
        {goals.length > 0 && !isCompetencyView && (
          <div className="flex flex-col gap-3">
            <div className="flex flex-wrap items-center gap-2 rounded-xl border border-hestia-border bg-hestia-surface p-3 shadow-sm">
              <label className="relative flex min-w-48 max-w-sm flex-1 items-center">
                <svg
                  viewBox="0 0 20 20"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  className="pointer-events-none absolute left-3 h-4 w-4 text-hestia-text-muted"
                >
                  <circle cx="9" cy="9" r="6" />
                  <path d="M14 14l4 4" />
                </svg>
                <input
                  type="search"
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  placeholder="Search learning goals…"
                  className="w-full rounded-sm border-[1.5px] border-hestia-border bg-hestia-surface py-1.5 pl-9 pr-3 text-sm text-hestia-text transition focus:border-hestia-primary focus:shadow-[0_0_0_3px_var(--hestia-primary-muted)] focus:outline-none"
                />
              </label>
              {filterDefs.map((def) => {
                if (def.options.length === 0) return null;
                const count = filters[def.key].size;
                return (
                  <div key={def.key} className="relative">
                    <button
                      type="button"
                      onClick={() =>
                        setOpenFilter((prev) =>
                          prev === def.key ? null : def.key,
                        )
                      }
                      aria-haspopup="true"
                      aria-expanded={openFilter === def.key}
                      className={`inline-flex items-center gap-1.5 rounded-md border px-2.5 py-1 text-sm font-medium transition ${
                        count > 0
                          ? "border-hestia-primary bg-hestia-primary-muted text-hestia-text"
                          : "border-hestia-border text-hestia-text-muted hover:bg-hestia-primary-muted hover:text-hestia-text"
                      }`}
                    >
                      {def.label}
                      {count > 0 && (
                        <span className="tabular-nums text-hestia-primary">
                          {count}
                        </span>
                      )}
                      <svg
                        viewBox="0 0 20 20"
                        fill={count > 0 ? "currentColor" : "none"}
                        stroke="currentColor"
                        strokeWidth="2"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        className={`h-3 w-3 ${count > 0 ? "text-hestia-primary" : "text-hestia-text-muted"}`}
                      >
                        <path d="M2.5 4h15l-6 7v5l-3 1.5V11z" />
                      </svg>
                    </button>
                    {openFilter === def.key && (
                      <FilterPopover
                        options={def.options}
                        selected={filters[def.key]}
                        display={def.display}
                        onToggle={(v) => toggleFilterValue(def.key, v)}
                        onClear={() => {
                          clearFilter(def.key);
                          setOpenFilter(null);
                        }}
                        onClose={() => setOpenFilter(null)}
                      />
                    )}
                  </div>
                );
              })}
            </div>

            {activeChips.length > 0 && (
              <div className="flex flex-wrap items-center gap-1.5">
                {activeChips.map((chip, i) => (
                  <span
                    key={i}
                    className="inline-flex items-center gap-1.5 rounded-full border border-[color-mix(in_srgb,var(--hestia-primary)_35%,transparent)] bg-hestia-primary-muted py-0.5 pl-2.5 pr-1.5 text-xs"
                  >
                    <span>
                      <b className="font-semibold">{chip.label}:</b> {chip.value}
                    </span>
                    <button
                      type="button"
                      onClick={chip.onRemove}
                      aria-label={`Remove filter ${chip.label} ${chip.value}`}
                      className="flex rounded-full text-hestia-text-muted transition hover:text-hestia-danger"
                    >
                      <svg
                        viewBox="0 0 20 20"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="2.5"
                        strokeLinecap="round"
                        aria-hidden="true"
                        className="h-3 w-3"
                      >
                        <path d="M5 5l10 10M15 5L5 15" />
                      </svg>
                    </button>
                  </span>
                ))}
                {activeChips.length > 1 && (
                  <button
                    type="button"
                    onClick={clearFilters}
                    className="text-xs text-hestia-text-muted underline transition hover:text-hestia-text"
                  >
                    Clear all
                  </button>
                )}
              </div>
            )}
          </div>
        )}

        {/* States */}
        {goalsQuery.isLoading && (
          <p className="text-sm text-hestia-text-muted">Loading…</p>
        )}
        {goalsQuery.isError && (
          <p className="text-sm text-hestia-danger">
            {(goalsQuery.error as Error).message}
          </p>
        )}
        {!goalsQuery.isLoading && goals.length === 0 && (
          <p className="rounded-xl border border-dashed border-hestia-border p-8 text-center text-sm text-hestia-text-muted">
            No learning goals yet for this course.
          </p>
        )}
      </div>

      {/* List view: table-of-contents on the left, the selected group's goals on the right. */}
      {goals.length > 0 && goalsView === "list" && (
        <div className="mx-auto w-full max-w-5xl">
          <div
            className={`grid gap-4 ${showToc ? "lg:grid-cols-[14rem_minmax(0,1fr)]" : ""}`}
          >
            {showToc && (
              <GoalTOC
                groups={groups}
                activeKey={activeGroup?.key ?? null}
                onSelect={setSelectedGroupKey}
              />
            )}
            <div className="min-w-0">
              {!activeGroup ? (
                <p className="rounded-xl border border-dashed border-hestia-border p-8 text-center text-sm text-hestia-text-muted">
                  No goals match the current filters.
                </p>
              ) : (
                <GoalCards
                  group={activeGroup}
                  onOpenDetail={setDetailGoal}
                  onToggleApproved={toggleApproved}
                  onEdit={setEditGoal}
                  onDelete={setGoalToDelete}
                />
              )}
            </div>
          </div>
        </div>
      )}

      {/* Competency table view: the forest as a filterable Excel-style tree-grid. */}
      {goals.length > 0 && goalsView === "table" && (
        <div className="mx-auto w-full max-w-5xl">
          <CompetencyTree
            goals={goals}
            onUpdate={updateGoal}
            onDelete={setGoalToDelete}
          />
        </div>
      )}

      {/* Competency map view: focus-and-drill graph, one layer at a time. The graph widens
          itself only while a skill is focused, so no width cap here. */}
      {goals.length > 0 && goalsView === "map" && (
        <CompetencyGraph
          goals={goals}
          onEdit={setEditGoal}
          onDelete={setGoalToDelete}
          onUpdate={updateGoal}
        />
      )}

      {/* The modal always gets the freshest goal for its id, so in-modal edits survive refetches. */}
      <CompetencyGoalModal
        goal={
          detailGoal
            ? (goals.find((g) => g.id === detailGoal.id) ?? detailGoal)
            : null
        }
        relationships={detailGoal ? groupRelationships(detailGoal) : []}
        onClose={() => setDetailGoal(null)}
        onUpdate={updateGoal}
        onDelete={setGoalToDelete}
      />
    </div>
  );
}

/** Cards for the goals of a single selected group — large index, text, a metadata row and the
 * review actions (approve / edit / delete). The card itself opens the detail modal, so it is a
 * focusable div rather than a button: buttons must not nest inside buttons. */
function GoalCards({
  group,
  onOpenDetail,
  onToggleApproved,
  onEdit,
  onDelete,
}: {
  group: GoalGroup;
  onOpenDetail: (goal: LearningGoal) => void;
  onToggleApproved: (goal: LearningGoal) => void;
  onEdit: (goal: LearningGoal) => void;
  onDelete: (goal: LearningGoal) => void;
}) {
  return (
    <div className="flex flex-col gap-4">
      {group.goals.map((goal, i) => {
        const rels = groupRelationships(goal);
        const hasMeta =
          Boolean(goal.bloomLevel || goal.soloLevel) || rels.length > 0;
        const approved = goal.status === "APPROVED";
        return (
          <div
            key={goal.id}
            role="button"
            tabIndex={0}
            onClick={() => onOpenDetail(goal)}
            onKeyDown={(e) => {
              if (e.key === "Enter" || e.key === " ") {
                e.preventDefault();
                onOpenDetail(goal);
              }
            }}
            className="group flex w-full cursor-pointer items-start gap-4 rounded-xl border border-hestia-border bg-hestia-surface p-4 text-left shadow-sm transition hover:border-hestia-primary hover:bg-hestia-bg"
          >
            <span className="w-6 shrink-0 text-base font-semibold tabular-nums text-hestia-text-muted">
              {i + 1}
            </span>
            <div className="min-w-0 flex-1">
              <p className="text-sm leading-relaxed text-hestia-text">
                {goal.text}
              </p>
              {hasMeta && (
                <div className="mt-2.5 flex flex-wrap items-center gap-x-2.5 gap-y-1.5 text-xs text-hestia-text-muted">
                  {goal.bloomLevel && (
                    <Badge variant="accent">{titleCase(goal.bloomLevel)}</Badge>
                  )}
                  {goal.soloLevel && (
                    <Badge variant="primary">{titleCase(goal.soloLevel)}</Badge>
                  )}
                  {(goal.bloomLevel || goal.soloLevel) && rels.length > 0 && (
                    <span
                      aria-hidden="true"
                      className="h-3.5 w-px bg-hestia-border"
                    />
                  )}
                  {rels.map((r) => (
                    <span key={r.type} className="whitespace-nowrap">
                      {r.phrase}{" "}
                      <span className="font-semibold tabular-nums text-hestia-text">
                        {r.count}
                      </span>{" "}
                      goal{r.count === 1 ? "" : "s"}
                    </span>
                  ))}
                </div>
              )}
            </div>
            <div className="flex shrink-0 items-center gap-1 self-center">
              <CardAction
                label={approved ? "Unapprove goal" : "Approve goal"}
                alwaysVisible={approved}
                onClick={() => onToggleApproved(goal)}
                className={
                  approved
                    ? "text-hestia-accent hover:bg-[color-mix(in_srgb,var(--hestia-accent)_15%,transparent)]"
                    : "text-hestia-text-muted hover:bg-hestia-primary-muted hover:text-hestia-text"
                }
              >
                <svg
                  viewBox="0 0 20 20"
                  fill={approved ? "currentColor" : "none"}
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  className="h-5 w-5"
                >
                  <circle cx="10" cy="10" r="7.5" />
                  <path
                    d="M6.5 10.5l2.5 2.5 4.5-5"
                    stroke={approved ? "var(--hestia-surface)" : "currentColor"}
                    fill="none"
                  />
                </svg>
              </CardAction>
              <CardAction
                label="Edit goal"
                onClick={() => onEdit(goal)}
                className="text-hestia-text-muted hover:bg-hestia-primary-muted hover:text-hestia-text"
              >
                <svg
                  viewBox="0 0 20 20"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  className="h-4 w-4"
                >
                  <path d="M13.5 3.5l3 3L7 16l-3.7.7L4 13z" />
                </svg>
              </CardAction>
              <CardAction
                label="Delete goal"
                onClick={() => onDelete(goal)}
                className="text-hestia-text-muted hover:bg-hestia-danger hover:text-white"
              >
                <svg
                  viewBox="0 0 20 20"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  className="h-4 w-4"
                >
                  <path d="M4 6h12M8 6V4h4v2M6 6l1 10h6l1-10" />
                </svg>
              </CardAction>
            </div>
          </div>
        );
      })}
    </div>
  );
}

/**
 * Icon button inside a goal card. Stops propagation so it doesn't also open the detail modal.
 * Edit/delete only fade in on card hover or focus; the approve check stays visible once approved.
 */
function CardAction({
  label,
  onClick,
  className,
  alwaysVisible,
  children,
}: {
  label: string;
  onClick: () => void;
  className: string;
  alwaysVisible?: boolean;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      title={label}
      aria-label={label}
      onClick={(e) => {
        e.stopPropagation();
        onClick();
      }}
      className={`flex h-8 w-8 items-center justify-center rounded-md transition focus-visible:opacity-100 ${
        alwaysVisible ? "" : "opacity-0 group-hover:opacity-100"
      } ${className}`}
    >
      {children}
    </button>
  );
}

/** Icons for the Skills representation toggle. Sized to sit inline with the label text. */
function MapIcon() {
  return (
    <svg
      viewBox="0 0 20 20"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      className="h-4 w-4"
    >
      <rect x="7.5" y="2.5" width="5" height="4" rx="1" />
      <rect x="2" y="13.5" width="5" height="4" rx="1" />
      <rect x="13" y="13.5" width="5" height="4" rx="1" />
      <path d="M10 6.5v3M10 9.5H4.5v4M10 9.5h5.5v4" />
    </svg>
  );
}

function TableIcon() {
  return (
    <svg
      viewBox="0 0 20 20"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      className="h-4 w-4"
    >
      <rect x="3" y="4" width="14" height="12" rx="1.5" />
      <path d="M3 8h14M8 8v8" />
    </svg>
  );
}

type BadgeVariant = "primary" | "accent";

const BADGE_VARIANTS: Record<BadgeVariant, string> = {
  primary: "bg-hestia-primary-muted text-hestia-primary",
  accent:
    "bg-[color-mix(in_srgb,var(--hestia-accent)_15%,transparent)] text-hestia-accent",
};

function Badge({
  children,
  variant,
}: {
  children: React.ReactNode;
  variant: BadgeVariant;
}) {
  return (
    <span
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-[0.7rem] font-semibold uppercase tracking-wide ${BADGE_VARIANTS[variant]}`}
    >
      {children}
    </span>
  );
}

/** Table of contents: selecting an entry shows only that group's goals in the list. */
function GoalTOC({
  groups,
  activeKey,
  onSelect,
}: {
  groups: GoalGroup[];
  activeKey: string | null;
  onSelect: (key: string) => void;
}) {
  return (
    <nav className="hidden lg:sticky lg:top-4 lg:block lg:self-start">
      <div className="rounded-xl border border-hestia-border bg-hestia-surface p-3 shadow-sm">
        <span className="mb-2 block text-xs font-semibold uppercase tracking-wide text-hestia-text-muted">
          Sessions
        </span>
        <ul className="flex flex-col gap-0.5">
          {groups.map((group) => {
            const isActive = group.key === activeKey;
            // Session/exercise entries sit visually under the course-wide "Module goals".
            const indented = group.level !== "MODULE";
            return (
              <li key={group.key}>
                <button
                  type="button"
                  onClick={() => onSelect(group.key)}
                  title={tocLabel(group)}
                  aria-current={isActive ? "true" : undefined}
                  className={`flex w-full items-start gap-2 border-l-2 py-1.5 pr-2 text-left text-sm leading-snug transition ${
                    indented ? "pl-5" : "pl-2"
                  } ${
                    isActive
                      ? "border-hestia-primary bg-hestia-primary-muted font-semibold text-hestia-text"
                      : "border-transparent text-hestia-text-muted hover:bg-hestia-bg hover:text-hestia-text"
                  }`}
                >
                  <span
                    className="mt-1 inline-block h-2 w-2 shrink-0 rounded-full"
                    style={{ backgroundColor: LEVEL_META[group.level].color }}
                  />
                  <span className="min-w-0 flex-1 line-clamp-2">
                    {tocLabel(group)}
                  </span>
                  <span className="mt-0.5 shrink-0 text-xs tabular-nums text-hestia-text-muted">
                    {group.goals.length}
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      </div>
    </nav>
  );
}

/** Kebab (⋮) overflow menu holding the course's Documents, Export and Delete actions. */
function CourseMenu({
  exportHref,
  onManageDocuments,
  onDelete,
}: {
  exportHref: string;
  onManageDocuments: () => void;
  onDelete: () => void;
}) {
  const [open, setOpen] = useState(false);
  const ref = useDismissable<HTMLDivElement>(open, () => setOpen(false));

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label="More actions"
        className="flex h-9 w-9 items-center justify-center rounded-md border border-hestia-border text-hestia-text transition hover:bg-hestia-primary-muted"
      >
        <svg viewBox="0 0 20 20" fill="currentColor" className="h-5 w-5">
          <circle cx="10" cy="4" r="1.6" />
          <circle cx="10" cy="10" r="1.6" />
          <circle cx="10" cy="16" r="1.6" />
        </svg>
      </button>
      {open && (
        <div
          role="menu"
          className="absolute right-0 top-full z-20 mt-1 w-44 overflow-hidden rounded-md border border-hestia-border bg-hestia-surface py-1 shadow-lg"
        >
          <button
            type="button"
            role="menuitem"
            onClick={() => {
              setOpen(false);
              onManageDocuments();
            }}
            className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-hestia-text transition hover:bg-hestia-bg"
          >
            <svg
              viewBox="0 0 20 20"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
              className="h-4 w-4 text-hestia-text-muted"
            >
              <path d="M5 2.5h6.5L15.5 6.5V17.5H5z" />
              <path d="M11.5 2.5v4h4" />
            </svg>
            Documents
          </button>
          <a
            href={exportHref}
            role="menuitem"
            onClick={() => setOpen(false)}
            className="flex items-center gap-2 px-3 py-2 text-sm text-hestia-text transition hover:bg-hestia-bg"
          >
            <svg
              viewBox="0 0 20 20"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
              className="h-4 w-4 text-hestia-text-muted"
            >
              <path d="M10 3v9m0 0l-3-3m3 3l3-3" />
              <path d="M4 15v2h12v-2" />
            </svg>
            Export CSV
          </a>
          <button
            type="button"
            role="menuitem"
            onClick={() => {
              setOpen(false);
              onDelete();
            }}
            className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-hestia-danger transition hover:bg-hestia-danger hover:text-white"
          >
            <svg
              viewBox="0 0 20 20"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
              className="h-4 w-4"
            >
              <path d="M4 6h12M8 6V4h4v2M6 6l1 10h6l1-10" />
            </svg>
            Delete course
          </button>
        </div>
      )}
    </div>
  );
}

/** Small centered confirmation overlay for destructive actions. */
function ConfirmDialog({
  title,
  message,
  confirmLabel,
  busy,
  error,
  onConfirm,
  onCancel,
}: {
  title: string;
  message: string;
  confirmLabel: string;
  busy?: boolean;
  error?: string;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onCancel();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onCancel]);

  return (
    <div
      onClick={onCancel}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
      role="dialog"
      aria-modal="true"
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-sm rounded-xl border border-hestia-border bg-hestia-surface p-6 shadow-xl"
      >
        <h3 className="text-lg text-hestia-text">{title}</h3>
        <p className="mt-2 text-sm leading-relaxed text-hestia-text-muted">
          {message}
        </p>
        {error && <p className="mt-3 text-sm text-hestia-danger">{error}</p>}
        <div className="mt-5 flex justify-end gap-2">
          <button
            onClick={onCancel}
            disabled={busy}
            className="rounded-md border border-hestia-border px-3 py-1.5 text-sm font-medium text-hestia-text transition hover:bg-hestia-primary-muted disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            onClick={onConfirm}
            disabled={busy}
            className="rounded-md bg-hestia-danger px-3 py-1.5 text-sm font-medium text-white transition hover:opacity-90 disabled:opacity-50"
          >
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}

/**
 * Modal for rewording a learning goal and correcting its Bloom/SOLO classification — rewording
 * often shifts the cognitive level (the verb anchors Bloom), and the instructor is the authority
 * over the LLM's initial classification. Levels can be set or changed, not cleared.
 */
function EditGoalDialog({
  goal,
  busy,
  error,
  onSave,
  onCancel,
}: {
  goal: LearningGoal;
  busy?: boolean;
  error?: string;
  onSave: (changes: {
    text?: string;
    bloomLevel?: LearningGoal["bloomLevel"];
    soloLevel?: LearningGoal["soloLevel"];
  }) => void;
  onCancel: () => void;
}) {
  const [text, setText] = useState(goal.text ?? "");
  const [bloom, setBloom] = useState(goal.bloomLevel ?? "");
  const [solo, setSolo] = useState(goal.soloLevel ?? "");
  const trimmed = text.trim();
  const textChanged = trimmed !== goal.text;
  const bloomChanged = bloom !== (goal.bloomLevel ?? "");
  const soloChanged = solo !== (goal.soloLevel ?? "");
  const canSave =
    trimmed !== "" && (textChanged || bloomChanged || soloChanged) && !busy;

  const save = () =>
    onSave({
      text: textChanged ? trimmed : undefined,
      bloomLevel: bloomChanged
        ? (bloom as LearningGoal["bloomLevel"])
        : undefined,
      soloLevel: soloChanged ? (solo as LearningGoal["soloLevel"]) : undefined,
    });

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onCancel();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onCancel]);

  return (
    <div
      onClick={onCancel}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
      role="dialog"
      aria-modal="true"
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-lg rounded-xl border border-hestia-border bg-hestia-surface p-6 shadow-xl"
      >
        <h3 className="text-lg text-hestia-text">Edit learning goal</h3>
        <textarea
          value={text}
          onChange={(e) => setText(e.target.value)}
          rows={4}
          autoFocus
          className="mt-3 w-full resize-y rounded-md border-[1.5px] border-hestia-border bg-hestia-surface p-2.5 text-sm leading-relaxed text-hestia-text transition focus:border-hestia-primary focus:outline-none"
        />
        <div className="mt-3 grid gap-3 sm:grid-cols-2">
          <LevelSelect
            label="Bloom"
            value={bloom}
            onChange={setBloom}
            options={BLOOM_ORDER}
          />
          <LevelSelect
            label="SOLO"
            value={solo}
            onChange={setSolo}
            options={SOLO_ORDER}
          />
        </div>
        {error && <p className="mt-2 text-sm text-hestia-danger">{error}</p>}
        <div className="mt-4 flex justify-end gap-2">
          <button
            onClick={onCancel}
            disabled={busy}
            className="rounded-md border border-hestia-border px-3 py-1.5 text-sm font-medium text-hestia-text transition hover:bg-hestia-primary-muted disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            onClick={save}
            disabled={!canSave}
            className="rounded-md bg-hestia-primary px-3 py-1.5 text-sm font-medium text-white transition hover:bg-hestia-primary-hover disabled:opacity-50"
          >
            {busy ? "Saving…" : "Save"}
          </button>
        </div>
      </div>
    </div>
  );
}

/** Labelled select for a taxonomy level in the edit dialog; shows "Not classified" while unset. */
function LevelSelect({
  label,
  value,
  onChange,
  options,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  options: string[];
}) {
  return (
    <label className="flex flex-col gap-1 text-xs font-medium uppercase tracking-wide text-hestia-text-muted">
      {label}
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="rounded-md border-[1.5px] border-hestia-border bg-hestia-surface px-2 py-1.5 text-sm font-normal normal-case tracking-normal text-hestia-text transition focus:border-hestia-primary focus:outline-none"
      >
        {value === "" && <option value="">Not classified</option>}
        {options.map((o) => (
          <option key={o} value={o}>
            {titleCase(o)}
          </option>
        ))}
      </select>
    </label>
  );
}


/**
 * Closes a popover when the user clicks outside the returned ref's element or presses Escape.
 * Returns a ref to attach to the popover's root.
 */
function useDismissable<T extends HTMLElement>(
  open: boolean,
  onClose: () => void,
) {
  const ref = useRef<T>(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;
  useEffect(() => {
    if (!open) return;
    const onPointer = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node))
        onCloseRef.current();
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onCloseRef.current();
    };
    window.addEventListener("mousedown", onPointer);
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("mousedown", onPointer);
      window.removeEventListener("keydown", onKey);
    };
  }, [open]);
  return ref;
}
