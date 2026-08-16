import { parserModelLabel } from "@/lib/exam/llm-models";
import { pdfModeLabel } from "@/lib/exam/labels";
import { useParsingMetrics } from "@/hooks/data/use-parsing-metrics";
import { StatCardGrid } from "./StatCardGrid";
import { PanelMessage } from "./PanelMessage";
import { MetricsTable, type MetricsColumn } from "./MetricsTable";

const formatDuration = (ms: number) => {
  if (ms < 1000) return `${ms} ms`;
  const s = ms / 1000;
  return s < 60 ? `${s.toFixed(1)} s` : `${Math.floor(s / 60)}m ${Math.round(s % 60)}s`;
};

const rate = (succeeded: number, total: number) =>
  total === 0 ? "—" : `${Math.round((succeeded / total) * 100)}%`;

const formatTokens = (n: number) =>
  n === 0 ? "—" : n >= 1000 ? `${(n / 1000).toFixed(1)}k` : `${n}`;

const ParsingMetricsPanel = () => {
  const { data: metrics, isLoading } = useParsingMetrics();

  if (isLoading || !metrics) {
    return <PanelMessage>Loading…</PanelMessage>;
  }

  const stats = [
    { label: "Total parses", value: metrics.total.toLocaleString() },
    { label: "Success rate", value: rate(metrics.succeeded, metrics.total) },
    { label: "Failed", value: metrics.failed.toLocaleString() },
    { label: "Avg duration", value: formatDuration(metrics.avgDurationMs) },
    { label: "P95 duration", value: formatDuration(metrics.p95DurationMs) },
  ];

  type ByModelRow = (typeof metrics.byModel)[number];
  const columns: MetricsColumn<ByModelRow>[] = [
    {
      header: "Model",
      cell: (m) => (
        <>
          {parserModelLabel(m.modelId)}
          <span className="mt-0.5 block text-xs text-hestia-text-muted">
            {pdfModeLabel(m.pdfMode)}
          </span>
        </>
      ),
    },
    { header: "Total", align: "right", cell: (m) => m.total },
    { header: "Success rate", align: "right", cell: (m) => rate(m.succeeded, m.total) },
    { header: "Time / page", align: "right", cell: (m) => formatDuration(m.avgMsPerPage) },
    { header: "Tokens / page", align: "right", cell: (m) => formatTokens(m.avgTokensPerPage) },
  ];

  return (
    <div className="space-y-hestia-5">
      <StatCardGrid stats={stats} className="grid-cols-2 sm:grid-cols-3 lg:grid-cols-5" />

      <MetricsTable
        title="By model"
        columns={columns}
        rows={metrics.byModel}
        getRowKey={(m) => `${m.modelId}:${m.pdfMode}`}
      />
    </div>
  );
};

export default ParsingMetricsPanel;
