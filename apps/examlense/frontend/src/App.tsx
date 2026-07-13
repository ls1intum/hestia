import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter, Route, Routes } from "react-router-dom";
import { Toaster as Sonner } from "@/components/ui/sonner";
import { Toaster } from "@/components/ui/toaster";
import { TooltipProvider } from "@/components/ui/tooltip";
import { Navigate } from "react-router-dom";
import NotFound from "./pages/NotFound.tsx";
import Exams from "./pages/Exams.tsx";
import ExamEdit from "./pages/ExamEdit.tsx";
import GradeRoute from "./pages/GradeRoute.tsx";
import ExamResults from "./pages/ExamResults.tsx";
import AdminDashboard from "./pages/AdminDashboard.tsx";
import Auth from "./pages/Auth.tsx";
import ResetPassword from "./pages/ResetPassword.tsx";
import { RequireAuth } from "./components/RequireAuth";

const queryClient = new QueryClient();

// Vite's BASE_URL carries a trailing slash (e.g. "/examlense/"). React Router expects a
// basename WITHOUT one — with the slash, "/examlense" (no trailing slash) fails to match and
// no route renders, so the "/" → "/exams" redirect never fires. Strip it; fall back to "/".
const routerBasename = import.meta.env.BASE_URL.replace(/\/$/, "") || "/";

const AppRoutes = () => {
  return (
    <BrowserRouter basename={routerBasename}>
      <Routes>
        <Route path="/" element={<Navigate to="/exams" replace />} />
        <Route path="/auth" element={<Auth />} />
        <Route path="/reset-password" element={<ResetPassword />} />
        <Route path="/exams" element={<RequireAuth><Exams /></RequireAuth>} />
        <Route path="/exams/:id/edit" element={<RequireAuth><ExamEdit /></RequireAuth>} />
        <Route path="/exams/:id/grade" element={<RequireAuth><GradeRoute /></RequireAuth>} />
        <Route path="/exams/:id/results" element={<RequireAuth><ExamResults /></RequireAuth>} />
        <Route path="/admin" element={<RequireAuth><AdminDashboard /></RequireAuth>} />
        <Route path="/admin/feedback" element={<Navigate to="/admin" replace />} />
        {/* ADD ALL CUSTOM ROUTES ABOVE THE CATCH-ALL "*" ROUTE */}
        <Route path="*" element={<NotFound />} />
      </Routes>
    </BrowserRouter>
  );
};

const App = () => (
  <QueryClientProvider client={queryClient}>
    <TooltipProvider>
      <Toaster />
      <Sonner />
      <AppRoutes />
    </TooltipProvider>
  </QueryClientProvider>
);

export default App;
