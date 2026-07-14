import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { parserModelLabel } from "@/lib/exam/llm-models";
import {
  useParseSurvey,
  useParseSurveyByModel,
  type ParseSurveyRow,
} from "@/hooks/data/use-parse-survey";

type Aspect = "speed" | "content_correctness" | "structure";

const modelLabel = (id: string) => parserModelLabel(id);

const average = (rows: ParseSurveyRow[], aspect: Aspect) => {
  const values = rows.map((r) => r[aspect]).filter((v): v is number => v != null);
  if (values.length === 0) return null;
  return values.reduce((s, v) => s + v, 0) / values.length;
};

const formatAvg = (avg: number | null) => (avg == null ? "—" : `${avg.toFixed(1)} / 10`);

const ParseSurveyPanel = () => {
  const { data: rows, isLoading } = useParseSurvey();
  const { data: byModel } = useParseSurveyByModel();

  if (isLoading || !rows) {
    return <p className="text-sm text-hestia-text-muted">Loading…</p>;
  }

  if (rows.length === 0) {
    return <p className="text-sm text-hestia-text-muted">No survey responses yet.</p>;
  }

  const stats = [
    { label: "Responses", value: rows.length.toLocaleString() },
    { label: "Avg speed", value: formatAvg(average(rows, "speed")) },
    { label: "Avg content correctness", value: formatAvg(average(rows, "content_correctness")) },
    { label: "Avg exam structure", value: formatAvg(average(rows, "structure")) },
  ];

  return (
    <div className="space-y-hestia-5">
      <div className="grid grid-cols-2 gap-hestia-3 lg:grid-cols-4">
        {stats.map((s) => (
          <Card key={s.label}>
            <CardContent className="p-hestia-4">
              <p className="text-xs font-medium text-hestia-text-muted">{s.label}</p>
              <p className="mt-1 font-body text-2xl font-bold tabular-nums text-hestia-text">{s.value}</p>
            </CardContent>
          </Card>
        ))}
      </div>

      {byModel && byModel.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle className="font-body text-lg">By model</CardTitle>
          </CardHeader>
          <CardContent>
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-hestia-border text-left text-xs text-hestia-text-muted">
                  <th className="pb-hestia-2 font-medium">Model</th>
                  <th className="pb-hestia-2 text-right font-medium">Responses</th>
                  <th className="pb-hestia-2 text-right font-medium">Avg speed</th>
                  <th className="pb-hestia-2 text-right font-medium">Avg content correctness</th>
                  <th className="pb-hestia-2 text-right font-medium">Avg exam structure</th>
                </tr>
              </thead>
              <tbody>
                {byModel.map((m) => (
                  <tr key={m.model_id} className="border-b border-hestia-border/40 last:border-0">
                    <td className="py-hestia-2 text-hestia-text">{modelLabel(m.model_id)}</td>
                    <td className="py-hestia-2 text-right tabular-nums text-hestia-text">{m.responses}</td>
                    <td className="py-hestia-2 text-right tabular-nums text-hestia-text">
                      {formatAvg(m.avg_speed)}
                    </td>
                    <td className="py-hestia-2 text-right tabular-nums text-hestia-text">
                      {formatAvg(m.avg_content_correctness)}
                    </td>
                    <td className="py-hestia-2 text-right tabular-nums text-hestia-text">
                      {formatAvg(m.avg_structure)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </CardContent>
        </Card>
      )}
    </div>
  );
};

export default ParseSurveyPanel;
