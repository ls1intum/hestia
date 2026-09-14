import ParsingMetricsPanel from "@/pages/admin/components/ParsingMetricsPanel";
import { UserAccessPanel } from "@/pages/admin/components/UserAccessPanel";

const AdminDashboard = () => {
  return (
    <div className="min-h-dvh bg-hestia-bg text-hestia-text">
      <div className="mx-auto w-full max-w-[900px] space-y-hestia-8 px-hestia-5 py-hestia-8">
        <UserAccessPanel />
        <ParsingMetricsPanel />
      </div>
    </div>
  );
};

export default AdminDashboard;
