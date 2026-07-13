import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import ParsingMetricsPanel from "@/components/admin/ParsingMetricsPanel";
import ParseSurveyPanel from "@/components/admin/ParseSurveyPanel";

const AdminDashboard = () => {
  return (
    <div className="min-h-screen bg-hestia-bg text-hestia-text">
      <div className="mx-auto w-full max-w-[900px] px-hestia-5 py-hestia-8">
        <Tabs defaultValue="survey">
          <TabsList className="mb-hestia-5">
            <TabsTrigger value="survey">Parsing Survey</TabsTrigger>
            <TabsTrigger value="parsing-metrics">Parsing Metrics</TabsTrigger>
          </TabsList>
          <TabsContent value="survey">
            <ParseSurveyPanel />
          </TabsContent>
          <TabsContent value="parsing-metrics">
            <ParsingMetricsPanel />
          </TabsContent>
        </Tabs>
      </div>
    </div>
  );
};

export default AdminDashboard;
