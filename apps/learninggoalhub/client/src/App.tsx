import { Route, Routes } from "react-router-dom";
import Layout from "./components/Layout.tsx";
import CoursesPage from "./pages/CoursesPage.tsx";
import HomePage from "./pages/HomePage.tsx";
import CoursePage from "./pages/CoursePage.tsx";

export default function App() {
  return (
    <Layout>
      <Routes>
        <Route path="/" element={<CoursesPage />} />
        <Route path="/courses/new" element={<HomePage />} />
        <Route path="/courses/:courseId" element={<CoursePage />} />
      </Routes>
    </Layout>
  );
}
