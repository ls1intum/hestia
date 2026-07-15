import { parserModelLabel } from "@/lib/exam/llm-models";
import {
  useParseSurvey,
  useParseSurveyByModel,
  type ParseSurveyRow,
} from "@/hooks/data/use-parse-survey";
import { StatCardGrid } from "./StatCardGrid";
import { PanelMessage } from "./PanelMessage";
import { MetricsTable, type MetricsColumn } from "./MetricsTable";

type Aspect = "speed" | "content_correctness" | "structure";

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
    return <PanelMessage>Loading…</PanelMessage>;
  }

  if (rows.length === 0) {
    return <PanelMessage>No survey responses yet.</PanelMessage>;
  }

  const stats = [
    { label: "Responses", value: rows.length.toLocaleString() },
    { label: "Avg speed", value: formatAvg(average(rows, "speed")) },
    { label: "Avg content correctness", value: formatAvg(average(rows, "content_correctness")) },
    { label: "Avg exam structure", value: formatAvg(average(rows, "structure")) },
  ];

  type ByModelRow = NonNullable<typeof byModel>[number];
  const columns: MetricsColumn<ByModelRow>[] = [
    { header: "Model", cell: (m) => parserModelLabel(m.model_id) },
    { header: "Responses", align: "right", cell: (m) => m.responses },
    { header: "Avg speed", align: "right", cell: (m) => formatAvg(m.avg_speed) },
    {
      header: "Avg content correctness",
      align: "right",
      cell: (m) => formatAvg(m.avg_content_correctness),
    },
    { header: "Avg exam structure", align: "right", cell: (m) => formatAvg(m.avg_structure) },
  ];

  return (
    <div className="space-y-hestia-5">
      <StatCardGrid stats={stats} className="grid-cols-2 lg:grid-cols-4" />

      {byModel && byModel.length > 0 && (
        <MetricsTable
          title="By model"
          columns={columns}
          rows={byModel}
          getRowKey={(m) => m.model_id}
        />
      )}
    </div>
  );
};

export default ParseSurveyPanel;
