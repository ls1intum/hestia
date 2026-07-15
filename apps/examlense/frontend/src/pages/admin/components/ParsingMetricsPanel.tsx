import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { parserModelLabel } from "@/lib/exam/llm-models";
import { pdfModeLabel } from "@/lib/exam/labels";
import { useParsingMetrics } from "@/hooks/data/use-parsing-metrics";

const modelLabel = (id: string) => parserModelLabel(id);

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
    return <p className="text-sm text-hestia-text-muted">Loading…</p>;
  }

  const stats = [
    { label: "Total parses", value: metrics.total.toLocaleString() },
    { label: "Success rate", value: rate(metrics.succeeded, metrics.total) },
    { label: "Failed", value: metrics.failed.toLocaleString() },
    { label: "Avg duration", value: formatDuration(metrics.avgDurationMs) },
    { label: "P95 duration", value: formatDuration(metrics.p95DurationMs) },
  ];

  return (
    <div className="space-y-hestia-5">
      <div className="grid grid-cols-2 gap-hestia-3 sm:grid-cols-3 lg:grid-cols-5">
        {stats.map((s) => (
          <Card key={s.label}>
            <CardContent className="p-hestia-4">
              <p className="text-xs font-medium text-hestia-text-muted">{s.label}</p>
              <p className="mt-1 font-body text-2xl font-bold tabular-nums text-hestia-text">{s.value}</p>
            </CardContent>
          </Card>
        ))}
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="font-body text-lg">By model</CardTitle>
        </CardHeader>
        <CardContent>
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-hestia-border text-left text-xs text-hestia-text-muted">
                <th className="pb-hestia-2 font-medium">Model</th>
                <th className="pb-hestia-2 text-right font-medium">Total</th>
                <th className="pb-hestia-2 text-right font-medium">Success rate</th>
                <th className="pb-hestia-2 text-right font-medium">Time / page</th>
                <th className="pb-hestia-2 text-right font-medium">Tokens / page</th>
              </tr>
            </thead>
            <tbody>
              {metrics.byModel.map((m) => (
                <tr key={`${m.modelId}:${m.pdfMode}`} className="border-b border-hestia-border/40 last:border-0">
                  <td className="py-hestia-2 text-hestia-text">
                    {modelLabel(m.modelId)}
                    <span className="mt-0.5 block text-xs text-hestia-text-muted">{pdfModeLabel(m.pdfMode)}</span>
                  </td>
                  <td className="py-hestia-2 text-right tabular-nums text-hestia-text">{m.total}</td>
                  <td className="py-hestia-2 text-right tabular-nums text-hestia-text">
                    {rate(m.succeeded, m.total)}
                  </td>
                  <td className="py-hestia-2 text-right tabular-nums text-hestia-text">
                    {formatDuration(m.avgMsPerPage)}
                  </td>
                  <td className="py-hestia-2 text-right tabular-nums text-hestia-text">
                    {formatTokens(m.avgTokensPerPage)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </CardContent>
      </Card>
    </div>
  );
};

export default ParsingMetricsPanel;
