import { useEffect, useState } from "react";
import { Route, Routes } from "react-router-dom";
import Layout from "./components/Layout.tsx";
import CoursesPage from "./pages/CoursesPage.tsx";
import CoursePage from "./pages/CoursePage.tsx";

export default function App() {
  const [isAuthenticated, setIsAuthenticated] = useState(true);

  useEffect(() => {
    const handleUnauthorized = () => setIsAuthenticated(false);
    window.addEventListener("lgh:unauthorized", handleUnauthorized);
    return () => window.removeEventListener("lgh:unauthorized", handleUnauthorized);
  }, []);

  if (!isAuthenticated) {
    return (
      <Layout>
        <div className="flex h-full flex-col items-center justify-center pt-24 text-center">
          <h1 className="mb-2 text-2xl font-bold">Welcome to LearningGoalHub</h1>
          <p className="mb-6 text-hestia-text-muted">Please log in to view and manage your courses.</p>
          <a
            href="/learninggoalhub/saml2/authenticate/tum"
            className="inline-flex items-center rounded-md bg-hestia-primary px-4 py-2 text-sm font-medium text-hestia-on-primary hover:bg-hestia-primary-muted transition-colors"
          >
            Log in with TUM
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
      </Routes>
    </Layout>
  );
}
