import { useCallback, useMemo } from "react";
import {
  Background,
  Controls,
  Handle,
  MarkerType,
  Position,
  ReactFlow,
  type Edge,
  type Node,
  type NodeProps,
  type NodeTypes,
} from "@xyflow/react";
import dagre from "@dagrejs/dagre";
import "@xyflow/react/dist/style.css";
import type { LearningGoal } from "../api/client.ts";
import { useTheme } from "../theme/context.ts";
import { LEVEL_META, levelOf, presentLevels, type GoalLevel } from "../lib/goals.ts";

type RelationshipType = "CONTRIBUTES_TO" | "PREREQUISITE_OF" | "OVERLAPS_WITH";

// Edge appearance per relationship type. Strokes use HESTIA tokens so they track the
// active theme; OVERLAPS_WITH is symmetric → dashed, no arrowhead.
const EDGE_STYLE: Record<
  RelationshipType,
  { stroke: string; dashed: boolean; directed: boolean; label: string }
> = {
  PREREQUISITE_OF: {
    stroke: "var(--hestia-accent)",
    dashed: false,
    directed: true,
    label: "Prerequisite",
  },
  CONTRIBUTES_TO: {
    stroke: "var(--hestia-primary)",
    dashed: false,
    directed: true,
    label: "Contributes",
  },
  OVERLAPS_WITH: {
    stroke: "var(--hestia-text-muted)",
    dashed: true,
    directed: false,
    label: "Overlaps",
  },
};

const NODE_SIZE = 128;

type GoalNodeData = { goal: LearningGoal; level: GoalLevel };
type GoalNode = Node<GoalNodeData, "goal">;

function GoalGraphNode({ data, selected }: NodeProps<GoalNode>) {
  const { goal, level } = data;
  const color = LEVEL_META[level].color;
  return (
    <div
      className="flex items-center justify-center rounded-full border-2 p-3 text-center shadow-sm transition"
      style={{
        width: NODE_SIZE,
        height: NODE_SIZE,
        borderColor: color,
        backgroundColor: `color-mix(in srgb, ${color} 14%, var(--hestia-surface))`,
        boxShadow: selected ? `0 0 0 3px ${color}` : undefined,
      }}
    >
      <Handle type="target" position={Position.Top} className="!opacity-0" />
      <span className="line-clamp-4 text-[0.62rem] font-medium leading-tight text-hestia-text">
        {goal.text}
      </span>
      <Handle type="source" position={Position.Bottom} className="!opacity-0" />
    </div>
  );
}

const NODE_TYPES: NodeTypes = { goal: GoalGraphNode };

/**
 * Builds React Flow nodes + edges from the goals' relationships, then runs a dagre layered
 * layout. Only directed relationships (PREREQUISITE_OF / CONTRIBUTES_TO) drive the ranking so
 * the graph reads top-down by dependency; OVERLAPS_WITH edges are added afterwards as dashed
 * cross-links and don't influence the layout. Nodes are positioned once per goal set; selection
 * styling is applied separately so re-selecting doesn't re-run the layout.
 */
function buildLayout(goals: LearningGoal[]): { nodes: GoalNode[]; edges: Edge[] } {
  const goalsById = new Map<number, LearningGoal>();
  goals.forEach((g) => {
    if (g.id != null) goalsById.set(g.id, g);
  });

  const edges: Edge[] = [];
  const seen = new Set<string>();
  goals.forEach((goal) => {
    const sourceId = goal.id;
    if (sourceId == null) return;
    (goal.relationships ?? []).forEach((rel) => {
      const targetId = rel.targetGoalId;
      const type = rel.type as RelationshipType | undefined;
      if (targetId == null || type == null || !goalsById.has(targetId)) return;
      const style = EDGE_STYLE[type];
      const key = style.directed
        ? `${type}:${sourceId}->${targetId}`
        : `${type}:${Math.min(sourceId, targetId)}-${Math.max(sourceId, targetId)}`;
      if (seen.has(key)) return;
      seen.add(key);
      edges.push({
        id: key,
        source: String(sourceId),
        target: String(targetId),
        style: {
          stroke: style.stroke,
          strokeWidth: 1.5,
          strokeDasharray: style.dashed ? "5 4" : undefined,
        },
        markerEnd: style.directed
          ? { type: MarkerType.ArrowClosed, color: style.stroke }
          : undefined,
      });
    });
  });

  const g = new dagre.graphlib.Graph();
  g.setGraph({ rankdir: "TB", nodesep: 50, ranksep: 80, marginx: 16, marginy: 16 });
  g.setDefaultEdgeLabel(() => ({}));
  goals.forEach((goal) => {
    if (goal.id != null) g.setNode(String(goal.id), { width: NODE_SIZE, height: NODE_SIZE });
  });
  edges.forEach((edge) => {
    // Only directed edges shape the ranking; dashed overlaps would create cycles.
    if (edge.markerEnd) g.setEdge(edge.source, edge.target);
  });
  dagre.layout(g);

  const nodes: GoalNode[] = goals
    .filter((goal): goal is LearningGoal & { id: number } => goal.id != null)
    .map((goal) => {
      const { x, y } = g.node(String(goal.id));
      return {
        id: String(goal.id),
        type: "goal",
        // dagre reports the node centre; React Flow positions by top-left corner.
        position: { x: x - NODE_SIZE / 2, y: y - NODE_SIZE / 2 },
        data: { goal, level: levelOf(goal) },
      };
    });

  return { nodes, edges };
}

export default function GoalGraph({
  goals,
  selectedGoalId,
  onSelect,
}: {
  goals: LearningGoal[];
  selectedGoalId: number | null;
  onSelect: (id: number | null) => void;
}) {
  const { resolved } = useTheme();
  const layout = useMemo(() => buildLayout(goals), [goals]);

  // Layout is computed once per goal set; only the `selected` flag changes on (de)selection.
  const nodes = useMemo(
    () =>
      layout.nodes.map((n) => ({ ...n, selected: n.id === String(selectedGoalId) })),
    [layout.nodes, selectedGoalId],
  );

  const onNodeClick = useCallback(
    (_: unknown, node: Node) => onSelect(Number(node.id)),
    [onSelect],
  );

  const levels = presentLevels(goals);

  return (
    <div className="flex flex-col gap-3">
      <div className="h-[600px] overflow-hidden rounded-xl border border-hestia-border bg-hestia-bg shadow-sm">
        <ReactFlow
          nodes={nodes}
          edges={layout.edges}
          nodeTypes={NODE_TYPES}
          onNodeClick={onNodeClick}
          onPaneClick={() => onSelect(null)}
          nodesDraggable={false}
          colorMode={resolved}
          fitView
          minZoom={0.1}
          proOptions={{ hideAttribution: true }}
        >
          <Background gap={20} className="!bg-hestia-bg" />
          <Controls showInteractive={false} />
        </ReactFlow>
      </div>

      {/* Legend (mockup screen 5): level colours + relationship line styles. */}
      <div className="flex flex-wrap items-center gap-x-6 gap-y-2 rounded-xl border border-hestia-border bg-hestia-surface px-4 py-3 text-xs text-hestia-text-muted shadow-sm">
        <div className="flex flex-wrap items-center gap-3">
          <span className="font-medium text-hestia-text">Levels</span>
          {levels.map((l) => (
            <span key={l} className="inline-flex items-center gap-1.5">
              <span
                className="inline-block h-2.5 w-2.5 rounded-full"
                style={{ backgroundColor: LEVEL_META[l].color }}
              />
              {LEVEL_META[l].label}
            </span>
          ))}
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <span className="font-medium text-hestia-text">Relationships</span>
          {(Object.keys(EDGE_STYLE) as RelationshipType[]).map((t) => (
            <span key={t} className="inline-flex items-center gap-1.5">
              <span
                className="inline-block h-0 w-5 border-t-2"
                style={{
                  borderColor: EDGE_STYLE[t].stroke,
                  borderStyle: EDGE_STYLE[t].dashed ? "dashed" : "solid",
                }}
              />
              {EDGE_STYLE[t].label}
            </span>
          ))}
        </div>
      </div>
    </div>
  );
}
