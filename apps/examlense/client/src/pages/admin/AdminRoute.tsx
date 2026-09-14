import { Navigate } from "react-router-dom";
import { useMe } from "@/hooks/data/use-me";
import AdminDashboard from "./AdminDashboard";

/**
 * Keeps non-admins out of the admin dashboard. Cosmetic only — the server gates
 * `GET /api/parse-metrics` on `ROLE_ADMIN`, so this just avoids rendering a page
 * that would only fill with 403s.
 */
export const AdminRoute = () => {
  const { data: me, isLoading } = useMe();

  if (isLoading) return null;
  if (!me?.is_admin) return <Navigate to="/exams" replace />;
  return <AdminDashboard />;
};
