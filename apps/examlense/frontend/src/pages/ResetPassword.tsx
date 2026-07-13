import { Navigate } from "react-router-dom";

/** No password reset in single-user mode; redirect to the exam list. */
const ResetPassword = () => <Navigate to="/exams" replace />;

export default ResetPassword;
