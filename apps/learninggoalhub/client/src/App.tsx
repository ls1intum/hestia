import { Route, Routes } from "react-router-dom";
import Layout from "./components/Layout.tsx";
import CoursesPage from "./pages/CoursesPage.tsx";
import CoursePage from "./pages/CoursePage.tsx";
import NotFoundPage from "./pages/NotFoundPage.tsx";

export default function App() {
  return (
    <Layout>
      <Routes>
        <Route path="/" element={<CoursesPage />} />
        <Route path="/courses/:courseId" element={<CoursePage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Routes>
    </Layout>
  );
}
