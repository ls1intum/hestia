import { useEffect } from "react";
import { useNavigate } from "react-router-dom";

/**
 * Legacy /evaluate route — the "How do you want to start?" flow now lives in a
 * modal opened from the dashboard. We redirect any direct hits to /exams with a
 * flag so the dashboard auto-opens the modal.
 */
const Evaluate = () => {
  const navigate = useNavigate();
  useEffect(() => {
    navigate("/exams?new=1", { replace: true });
  }, [navigate]);
  return null;
};

export default Evaluate;
