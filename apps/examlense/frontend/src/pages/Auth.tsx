import { Navigate } from "react-router-dom";

/** Auth is disabled in single-user mode; this route just lands on the exam list. */
const Auth = () => <Navigate to="/exams" replace />;

export default Auth;
