import ParsingMetricsPanel from "@/pages/admin/components/ParsingMetricsPanel";

const AdminDashboard = () => {
  return (
    <div className="min-h-dvh bg-hestia-bg text-hestia-text">
      <div className="mx-auto w-full max-w-[900px] px-hestia-5 py-hestia-8">
        <ParsingMetricsPanel />
      </div>
    </div>
  );
};

export default AdminDashboard;
