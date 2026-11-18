import { useEffect, useState } from "react";
import { Route, Routes, useLocation, useNavigate } from "react-router-dom";
import Layout from "./components/Layout.tsx";
import CoursesPage from "./pages/CoursesPage.tsx";
import CoursePage from "./pages/CoursePage.tsx";
import IntakePage from "./pages/IntakePage.tsx";
import NotFoundPage from "./pages/NotFoundPage.tsx";
import { INTAKE_PATH, rememberReturn, takeReturn } from "./lib/intake.ts";

export default function App() {
  const [isAuthenticated, setIsAuthenticated] = useState(true);
  const location = useLocation();
  const navigate = useNavigate();
  const onIntake = location.pathname.startsWith(INTAKE_PATH);

  useEffect(() => {
    const handleUnauthorized = () => setIsAuthenticated(false);
    window.addEventListener("lgh:unauthorized", handleUnauthorized);
    return () => window.removeEventListener("lgh:unauthorized", handleUnauthorized);
  }, []);

  // The SAML login always returns to the start page; go on to where the login started.
  useEffect(() => {
    const target = takeReturn();
    if (target && location.pathname === "/") navigate(target, { replace: true });
    // Only on the first render after the login round trip.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (!isAuthenticated) {
    return (
      <Layout>
        <div className="flex h-full flex-col items-center justify-center pt-24 text-center">
          {onIntake ? (
            <>
              <h1 className="mb-2 text-2xl font-bold">Die nächste Veranstaltung beginnt mit der letzten</h1>
              <p className="mb-6 max-w-md text-hestia-text-muted">
                Melden Sie sich an, um die Unterlagen Ihrer letzten Durchführung hochzuladen.
              </p>
            </>
          ) : (
            <>
              <h1 className="mb-2 text-2xl font-bold">Welcome to LearningGoalHub</h1>
              <p className="mb-6 text-hestia-text-muted">Please log in to view and manage your courses.</p>
            </>
          )}
          <a
            href="/learninggoalhub/saml2/authenticate/tum"
            onClick={() => rememberReturn(location.pathname)}
            className="inline-flex items-center rounded-md bg-hestia-primary px-4 py-2 text-sm font-medium text-hestia-on-primary hover:bg-hestia-primary-muted transition-colors"
          >
            {onIntake ? "Mit TUM-Kennung anmelden" : "Log in with TUM"}
          </a>
        </div>
      </Layout>
    );
  }

  return (
    <Layout>
      <Routes>
        <Route path="/" element={<CoursesPage />} />
        <Route path="/courses/:courseId" element={<CoursePage />} />
        <Route path={INTAKE_PATH} element={<IntakePage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Routes>
    </Layout>
  );
}
