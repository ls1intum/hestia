import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, API_PREFIX } from "../api/client.ts";
import type { LearningGoal } from "../api/client.ts";
import GoalGraph from "../components/GoalGraph.tsx";
import CompetencyTree from "../components/CompetencyTree.tsx";
import CompetencyGraph from "../components/CompetencyGraph.tsx";
import GoalDetailPanel from "../components/GoalDetailPanel.tsx";
import GoalDetailModal from "../components/GoalDetailModal.tsx";
import {
  LEVEL_META,
  groupGoalsByUnit,
  groupRelationships,
  hasCompetencyTree,
  levelOf,
  presentLevels,
  sourceFilenames,
  tocLabel,
  type GoalGroup,
  type RelationshipGroup,
} from "../lib/goals.ts";

type GoalsView = "list" | "graph" | "tree" | "map";

/** Display labels for the view toggle. */
const VIEW_LABELS: Record<GoalsView, string> = {
  list: "List",
  graph: "Graph",
  tree: "Tree",
  map: "Map",
};
type KindFilter = "ALL" | "EXPLICIT" | "IMPLICIT";
type GoalStatus = NonNullable<LearningGoal["status"]>;
type StatusFilter = "ALL" | GoalStatus;

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
  const [goalsView, setGoalsView] = useState<GoalsView>("list");
  const [selectedGoalId, setSelectedGoalId] = useState<number | null>(null);
  const [detailGoal, setDetailGoal] = useState<LearningGoal | null>(null);
  const [editGoal, setEditGoal] = useState<LearningGoal | null>(null);
  const [goalToDelete, setGoalToDelete] = useState<LearningGoal | null>(null);
  // Which session/unit the table-of-contents has selected — the list shows only this group.
  const [selectedGroupKey, setSelectedGroupKey] = useState<string | null>(null);
  // Map view: clicking the approved/unapproved counter dims the non-matching nodes.
  const [mapHighlight, setMapHighlight] = useState<
    "approved" | "unapproved" | null
  >(null);

  const [search, setSearch] = useState("");
  const [kindFilter, setKindFilter] = useState<KindFilter>("ALL");
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("ALL");
  const [levelFilter, setLevelFilter] = useState("ALL");
  const [bloomFilter, setBloomFilter] = useState("ALL");
  const [soloFilter, setSoloFilter] = useState("ALL");

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
      status?: GoalStatus;
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

  const toggleApproved = (goal: LearningGoal) =>
    updateGoalMutation.mutate({
      goalId: goal.id!,
      status: goal.status === "APPROVED" ? "PENDING" : "APPROVED",
    });

  const goals: LearningGoal[] = useMemo(
    () => goalsQuery.data?.content ?? [],
    [goalsQuery.data],
  );

  // The competency-tree view only appears once the pipeline has synthesised one.
  const competencyAvailable = useMemo(() => hasCompetencyTree(goals), [goals]);
  const views: GoalsView[] = competencyAvailable
    ? ["list", "graph", "tree", "map"]
    : ["list", "graph"];
  // The competency views ignore the goal-level filter bar (filtering would prune tree nodes).
  const isCompetencyView = goalsView === "tree" || goalsView === "map";

  // Fall back to the list if a competency view is active but none exists (yet).
  useEffect(() => {
    if (isCompetencyView && !competencyAvailable) setGoalsView("list");
  }, [isCompetencyView, competencyAvailable]);

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

  const filteredGoals = useMemo(() => {
    const needle = search.trim().toLowerCase();
    return goals.filter((g) => {
      if (kindFilter !== "ALL" && g.kind !== kindFilter) return false;
      if (statusFilter !== "ALL" && (g.status ?? "PENDING") !== statusFilter)
        return false;
      if (levelFilter !== "ALL" && levelOf(g) !== levelFilter) return false;
      if (bloomFilter !== "ALL" && g.bloomLevel !== bloomFilter) return false;
      if (soloFilter !== "ALL" && g.soloLevel !== soloFilter) return false;
      if (needle && !(g.text ?? "").toLowerCase().includes(needle))
        return false;
      return true;
    });
  }, [
    goals,
    search,
    kindFilter,
    statusFilter,
    levelFilter,
    bloomFilter,
    soloFilter,
  ]);

  const selectedGoal = useMemo(
    () => filteredGoals.find((g) => g.id === selectedGoalId) ?? null,
    [filteredGoals, selectedGoalId],
  );

  // Session/unit grouping drives both the list and the table of contents.
  const groups = useMemo(
    () => groupGoalsByUnit(filteredGoals),
    [filteredGoals],
  );

  // The list shows one group at a time; fall back to the first group when the
  // selection is unset or filtered away.
  const activeGroup = useMemo(
    () => groups.find((g) => g.key === selectedGroupKey) ?? groups[0] ?? null,
    [groups, selectedGroupKey],
  );

  const hasActiveFilter =
    search.trim() !== "" ||
    kindFilter !== "ALL" ||
    statusFilter !== "ALL" ||
    levelFilter !== "ALL" ||
    bloomFilter !== "ALL" ||
    soloFilter !== "ALL";

  const clearFilters = () => {
    setSearch("");
    setKindFilter("ALL");
    setStatusFilter("ALL");
    setLevelFilter("ALL");
    setBloomFilter("ALL");
    setSoloFilter("ALL");
  };

  const showToc = goalsView === "list" && groups.length > 0;

  const courseName = courseQuery.data?.name ?? `Course #${courseId}`;
  const totalGoals = courseQuery.data?.goalCount ?? goals.length;
  const approvedGoals = useMemo(
    () => goals.filter((g) => g.status === "APPROVED").length,
    [goals],
  );
  const unapprovedGoals = goals.length - approvedGoals;

  return (
    <div className="flex flex-col gap-6">
      {/* Header — title + count and the view toggle / menu on a single line */}
      <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
        <div className="flex flex-wrap items-baseline gap-x-2">
          <h1 className="text-2xl">{courseName}</h1>
          {goalsView === "map" ? (
            <span className="flex items-center gap-1.5 text-sm text-hestia-text-muted">
              <span>·</span>
              <CountPill
                count={approvedGoals}
                label="approved"
                active={mapHighlight === "approved"}
                onClick={() =>
                  setMapHighlight((h) => (h === "approved" ? null : "approved"))
                }
              />
              <CountPill
                count={unapprovedGoals}
                label="unapproved"
                active={mapHighlight === "unapproved"}
                onClick={() =>
                  setMapHighlight((h) =>
                    h === "unapproved" ? null : "unapproved",
                  )
                }
              />
            </span>
          ) : (
            <span className="text-sm text-hestia-text-muted">
              · {totalGoals} learning goal{totalGoals === 1 ? "" : "s"}
            </span>
          )}
        </div>
        <div className="flex items-center gap-3">
          <div className="flex rounded-md border border-hestia-border p-0.5 text-sm">
            {views.map((view) => (
              <button
                key={view}
                onClick={() => setGoalsView(view)}
                className={`rounded-[0.3rem] px-3 py-1 font-medium transition ${
                  goalsView === view
                    ? "bg-hestia-primary text-white"
                    : "text-hestia-text-muted hover:text-hestia-text"
                }`}
              >
                {VIEW_LABELS[view]}
              </button>
            ))}
          </div>
          <CourseMenu
            exportHref={`${API_PREFIX}/api/courses/${courseId}/learning-goals/export.csv`}
            onDelete={() => setConfirmDelete(true)}
          />
        </div>
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

      {/* Filter bar — hidden on the competency views, which render the full synthesised tree. */}
      {goals.length > 0 && !isCompetencyView && (
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
              className="w-full rounded-md border-[1.5px] border-hestia-border bg-hestia-surface py-1.5 pl-9 pr-3 text-sm text-hestia-text transition focus:border-hestia-primary focus:outline-none"
            />
          </label>
          <FilterDropdown
            label="Level"
            info={LEVEL_INFO}
            value={levelFilter}
            onChange={setLevelFilter}
            options={[
              { value: "ALL", label: "All" },
              ...levels.map((l) => ({ value: l, label: LEVEL_META[l].label })),
            ]}
          />
          <FilterDropdown
            label="Kind"
            info={KIND_INFO}
            value={kindFilter}
            onChange={(v) => setKindFilter(v as KindFilter)}
            options={[
              { value: "ALL", label: "All" },
              { value: "EXPLICIT", label: "Explicit" },
              { value: "IMPLICIT", label: "Implicit" },
            ]}
          />
          <FilterDropdown
            label="Status"
            info={STATUS_INFO}
            value={statusFilter}
            onChange={(v) => setStatusFilter(v as StatusFilter)}
            options={[
              { value: "ALL", label: "All" },
              { value: "PENDING", label: "Pending" },
              { value: "APPROVED", label: "Approved" },
            ]}
          />
          <FilterDropdown
            label="Bloom"
            info={BLOOM_INFO}
            value={bloomFilter}
            onChange={setBloomFilter}
            options={[
              { value: "ALL", label: "All" },
              ...bloomLevels.map((l) => ({ value: l, label: titleCase(l) })),
            ]}
          />
          <FilterDropdown
            label="SOLO"
            info={SOLO_INFO}
            value={soloFilter}
            onChange={setSoloFilter}
            options={[
              { value: "ALL", label: "All" },
              ...soloLevels.map((l) => ({ value: l, label: titleCase(l) })),
            ]}
          />
          {hasActiveFilter && (
            <button
              onClick={clearFilters}
              className="ml-auto inline-flex items-center gap-1.5 text-sm font-medium text-hestia-primary transition hover:text-hestia-primary-hover"
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
                <path d="M3 10a7 7 0 1 1 2 4.9" />
                <path d="M3 15v-4h4" />
              </svg>
              Clear filters
            </button>
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

      {/* List view: table-of-contents on the left, the selected group's goals on the right. */}
      {goals.length > 0 && goalsView === "list" && (
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
      )}

      {/* Graph view: graph on the left, persistent detail panel on the right. */}
      {goals.length > 0 && goalsView === "graph" && (
        <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_20rem]">
          <div className="min-w-0">
            {filteredGoals.length === 0 ? (
              <p className="rounded-xl border border-dashed border-hestia-border p-8 text-center text-sm text-hestia-text-muted">
                No goals match the current filters.
              </p>
            ) : (
              <GoalGraph
                goals={filteredGoals}
                selectedGoalId={selectedGoalId}
                onSelect={setSelectedGoalId}
              />
            )}
          </div>
          <aside className="lg:sticky lg:top-4 lg:self-start">
            <GoalDetailPanel
              goal={selectedGoal}
              onViewInList={
                selectedGoal ? () => setGoalsView("list") : undefined
              }
            />
          </aside>
        </div>
      )}

      {/* Competency tree view: the full synthesised tree, terminal → sub-skill → knowledge/gap. */}
      {goals.length > 0 && goalsView === "tree" && (
        <CompetencyTree goals={goals} onOpenDetail={setDetailGoal} />
      )}

      {/* Competency map view: focus-and-drill graph, one layer at a time. */}
      {goals.length > 0 && goalsView === "map" && (
        <CompetencyGraph
          goals={goals}
          highlight={mapHighlight}
          onOpenDetail={setDetailGoal}
          onToggleApproved={toggleApproved}
          onEdit={setEditGoal}
          onDelete={setGoalToDelete}
        />
      )}

      <GoalDetailModal goal={detailGoal} onClose={() => setDetailGoal(null)} />
    </div>
  );
}

/** A clickable count in the map header that toggles dimming of the non-matching nodes. When active
 * it is filled in; clicking again clears the highlight. */
function CountPill({
  count,
  label,
  active,
  onClick,
}: {
  count: number;
  label: string;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      title={
        active
          ? `Showing all — click to clear`
          : `Highlight ${label} goals, dim the rest`
      }
      className={`rounded-full border px-2 py-0.5 font-medium tabular-nums transition ${
        active
          ? "border-hestia-primary bg-hestia-primary text-white"
          : "border-hestia-border text-hestia-text-muted hover:border-hestia-primary hover:text-hestia-text"
      }`}
    >
      {count} {label}
    </button>
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
        const sources = sourceFilenames(goal);
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
                {sources.map((name, s) => (
                  <SourceChip key={s} name={name} />
                ))}
              </p>
              {hasMeta && (
                <div className="mt-2.5 flex flex-wrap items-center gap-x-2.5 gap-y-1.5 text-xs text-hestia-text-muted">
                  {goal.bloomLevel && (
                    <Tooltip
                      content={
                        <TaxonomyTip
                          taxonomy="Bloom"
                          level={titleCase(goal.bloomLevel)}
                          desc={BLOOM_DESC[titleCase(goal.bloomLevel)]}
                        />
                      }
                    >
                      <Badge variant="accent">
                        {titleCase(goal.bloomLevel)}
                      </Badge>
                    </Tooltip>
                  )}
                  {goal.soloLevel && (
                    <Tooltip
                      content={
                        <TaxonomyTip
                          taxonomy="SOLO"
                          level={titleCase(goal.soloLevel)}
                          desc={SOLO_DESC[titleCase(goal.soloLevel)]}
                        />
                      }
                    >
                      <Badge variant="primary">
                        {titleCase(goal.soloLevel)}
                      </Badge>
                    </Tooltip>
                  )}
                  {(goal.bloomLevel || goal.soloLevel) && rels.length > 0 && (
                    <span
                      aria-hidden="true"
                      className="h-3.5 w-px bg-hestia-border"
                    />
                  )}
                  {rels.map((r) => (
                    <Tooltip
                      key={r.type}
                      content={<RelationshipTip group={r} />}
                    >
                      <span className="cursor-help whitespace-nowrap decoration-hestia-border decoration-dotted underline-offset-2 hover:underline">
                        {r.phrase}{" "}
                        <span className="font-semibold tabular-nums text-hestia-text">
                          {r.count}
                        </span>{" "}
                        goal{r.count === 1 ? "" : "s"}
                      </span>
                    </Tooltip>
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

/**
 * Wraps a card trigger (badge / relationship text) and reveals a HESTIA-styled tooltip above it on
 * hover. Hover-only (no focusable child) because triggers live inside the clickable goal card button.
 */
function Tooltip({
  content,
  children,
}: {
  content: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <span className="group/tip relative inline-flex items-center">
      {children}
      <span
        role="tooltip"
        className="pointer-events-none absolute bottom-full left-1/2 z-30 mb-2 hidden w-60 max-w-[calc(100vw-2rem)] -translate-x-1/2 rounded-lg border border-hestia-border border-l-[3px] border-l-hestia-primary bg-hestia-surface p-2.5 text-left text-xs font-normal normal-case leading-snug tracking-normal shadow-lg group-hover/tip:block"
      >
        {content}
      </span>
    </span>
  );
}

/** Tooltip body for a Bloom/SOLO badge: which taxonomy it is, the level, and what the level means. */
function TaxonomyTip({
  taxonomy,
  level,
  desc,
}: {
  taxonomy: string;
  level: string;
  desc?: string;
}) {
  return (
    <>
      <span className="block text-[0.65rem] font-semibold uppercase tracking-wide text-hestia-primary">
        {taxonomy}
      </span>
      <span className="mt-0.5 block font-semibold text-hestia-text">
        {level}
      </span>
      {desc && (
        <span className="mt-0.5 block text-hestia-text-muted">{desc}</span>
      )}
    </>
  );
}

/** Tooltip body for a relationship summary: the phrase plus the exact linked goals. */
function RelationshipTip({ group }: { group: RelationshipGroup }) {
  return (
    <>
      <span className="block text-[0.65rem] font-semibold uppercase tracking-wide text-hestia-primary">
        {group.phrase} {group.count} goal{group.count === 1 ? "" : "s"}
      </span>
      {group.targets.length > 0 && (
        <span className="mt-1 flex flex-col gap-1">
          {group.targets.map((t, i) => (
            <span key={i} className="flex gap-1.5 text-hestia-text">
              <span className="text-hestia-text-muted">•</span>
              <span className="line-clamp-2">{t}</span>
            </span>
          ))}
        </span>
      )}
    </>
  );
}

/** A compact source chip rendered inline after the goal text, ChatGPT-citation style. */
function SourceChip({ name }: { name: string }) {
  return (
    <span
      title={name}
      className="ml-1 inline-flex max-w-[10rem] items-center gap-0.5 rounded border border-hestia-border bg-hestia-surface px-1 py-px align-middle text-[0.65rem] font-normal leading-none text-hestia-text-muted"
    >
      <span className="shrink-0">
        <DocIcon />
      </span>
      <span className="truncate">{name}</span>
    </span>
  );
}

function DocIcon() {
  return (
    <svg
      viewBox="0 0 20 20"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      className="h-3 w-3"
    >
      <path d="M6 3h5l3 3v11H6z" />
      <path d="M11 3v3h3" />
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

/** Kebab (⋮) overflow menu holding the course's Export and Delete actions. */
function CourseMenu({
  exportHref,
  onDelete,
}: {
  exportHref: string;
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

/** Title-cases an ALL-CAPS enum value (e.g. "EXTENDED_ABSTRACT" → "Extended Abstract"). */
function titleCase(value: string): string {
  return value
    .toLowerCase()
    .split("_")
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(" ");
}

/**
 * Compact filter control: a single button showing the active value that opens a popover of
 * pill options. Keeps the whole filter bar to one row instead of a chip group per facet.
 */
function FilterDropdown({
  label,
  info,
  value,
  onChange,
  options,
}: {
  label: string;
  info?: FilterInfo;
  value: string;
  onChange: (value: string) => void;
  options: { value: string; label: string }[];
}) {
  const [open, setOpen] = useState(false);
  const ref = useDismissable<HTMLDivElement>(open, () => setOpen(false));
  if (options.length <= 1) return null;

  const active = options.find((o) => o.value === value) ?? options[0];
  const isFiltered = value !== "ALL";

  return (
    <div ref={ref} className="relative inline-flex items-center gap-1">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="menu"
        aria-expanded={open}
        className={`inline-flex items-center gap-1.5 rounded-md border px-2.5 py-1 text-sm font-medium transition ${
          isFiltered
            ? "border-hestia-primary bg-hestia-primary-muted text-hestia-text"
            : "border-hestia-border text-hestia-text-muted hover:bg-hestia-primary-muted hover:text-hestia-text"
        }`}
      >
        <span className="text-hestia-text-muted">{label}</span>
        <span
          className={isFiltered ? "text-hestia-primary" : "text-hestia-text"}
        >
          {active.label}
        </span>
        <svg
          viewBox="0 0 20 20"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          className={`h-3.5 w-3.5 text-hestia-text-muted transition-transform ${
            open ? "rotate-180" : ""
          }`}
        >
          <path d="M5 7l5 6 5-6" />
        </svg>
      </button>
      {open && (
        <div
          role="menu"
          className="absolute left-0 top-full z-20 mt-1 rounded-md border border-hestia-border bg-hestia-surface p-1.5 shadow-lg"
        >
          <div className="flex max-w-[16rem] flex-wrap gap-1.5">
            {options.map((o) => {
              const isActive = o.value === value;
              return (
                <button
                  key={o.value}
                  type="button"
                  role="menuitemradio"
                  aria-checked={isActive}
                  onClick={() => {
                    onChange(o.value);
                    setOpen(false);
                  }}
                  className={`whitespace-nowrap rounded-md px-2.5 py-1 text-sm font-medium transition ${
                    isActive
                      ? "bg-hestia-primary text-white"
                      : "border border-hestia-border text-hestia-text-muted hover:bg-hestia-primary-muted hover:text-hestia-text"
                  }`}
                >
                  {o.label}
                </button>
              );
            })}
          </div>
        </div>
      )}
      {info && <InfoTip title={label} info={info} />}
    </div>
  );
}

/** Structured explanation shown in a filter's info tooltip. */
type FilterInfo = {
  intro: string;
  items?: { term: string; desc: string }[];
};

const LEVEL_INFO: FilterInfo = {
  intro: "The hierarchy level a goal applies to.",
  items: [
    { term: "Module", desc: "Course-wide outcome spanning the whole course." },
    { term: "Session", desc: "Tied to a single lecture or document." },
    { term: "Exercise", desc: "Tied to a specific exercise or tutorial." },
  ],
};

const KIND_INFO: FilterInfo = {
  intro: "Where the goal comes from.",
  items: [
    { term: "Explicit", desc: "Stated directly in the source material." },
    { term: "Implicit", desc: "Inferred by the model from the content." },
  ],
};

const STATUS_INFO: FilterInfo = {
  intro: "The review state of a goal.",
  items: [
    { term: "Pending", desc: "Extracted but not yet reviewed." },
    { term: "Approved", desc: "Accepted by an instructor." },
  ],
};

const BLOOM_INFO: FilterInfo = {
  intro:
    "Bloom's taxonomy — the cognitive demand, from lower- to higher-order thinking.",
  items: [
    { term: "Remember", desc: "Recall facts and basic concepts." },
    { term: "Understand", desc: "Explain ideas or concepts." },
    { term: "Apply", desc: "Use knowledge in new situations." },
    { term: "Analyze", desc: "Break ideas apart and draw connections." },
    { term: "Evaluate", desc: "Justify a stance or judgement." },
    { term: "Create", desc: "Produce new or original work." },
  ],
};

const SOLO_INFO: FilterInfo = {
  intro:
    "SOLO taxonomy — how structurally complex the expected understanding is.",
  items: [
    { term: "Prestructural", desc: "Misses the point; no real grasp." },
    { term: "Unistructural", desc: "Grasps one relevant aspect." },
    { term: "Multistructural", desc: "Several aspects, but in isolation." },
    { term: "Relational", desc: "Integrates aspects into a coherent whole." },
    { term: "Extended Abstract", desc: "Generalises beyond to new contexts." },
  ],
};

/** Level → description lookups (keyed by title-cased term) for the badge tooltips. */
const BLOOM_DESC: Record<string, string> = Object.fromEntries(
  (BLOOM_INFO.items ?? []).map((i) => [i.term, i.desc]),
);
const SOLO_DESC: Record<string, string> = Object.fromEntries(
  (SOLO_INFO.items ?? []).map((i) => [i.term, i.desc]),
);

/** A muted "i" icon that reveals a HESTIA-styled explanatory tooltip on hover/focus. */
function InfoTip({ title, info }: { title: string; info: FilterInfo }) {
  return (
    <span className="group/tip relative inline-flex" tabIndex={0}>
      <svg
        viewBox="0 0 20 20"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
        className="h-4 w-4 cursor-help text-hestia-text-muted transition hover:text-hestia-primary"
      >
        <circle cx="10" cy="10" r="7.5" />
        <path d="M10 9v4" />
        <path d="M10 6.5h.01" />
      </svg>
      <span
        role="tooltip"
        className="pointer-events-none absolute left-1/2 top-full z-30 mt-2 hidden w-72 max-w-[calc(100vw-2rem)] -translate-x-1/2 rounded-lg border border-hestia-border border-l-[3px] border-l-hestia-primary bg-hestia-surface p-3 text-left shadow-lg group-hover/tip:block group-focus/tip:block"
      >
        <span className="block text-xs font-semibold uppercase tracking-wide text-hestia-primary">
          {title}
        </span>
        <span className="mt-1 block text-xs leading-snug text-hestia-text-muted">
          {info.intro}
        </span>
        {info.items && (
          <span className="mt-2 flex flex-col gap-1 border-t border-hestia-border pt-2">
            {info.items.map((it) => (
              <span key={it.term} className="block text-xs leading-snug">
                <span className="font-semibold text-hestia-text">
                  {it.term}
                </span>
                <span className="text-hestia-text-muted"> — {it.desc}</span>
              </span>
            ))}
          </span>
        )}
      </span>
    </span>
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
