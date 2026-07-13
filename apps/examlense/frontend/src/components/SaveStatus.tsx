import { createContext, ReactNode, useCallback, useContext, useRef, useState } from "react";

type Status = "idle" | "saving" | "saved" | "error";

interface Ctx {
  status: Status;
  setSaving: () => void;
  setSaved: () => void;
  setError: () => void;
}

const SaveStatusContext = createContext<Ctx | null>(null);

export const SaveStatusProvider = ({ children }: { children: ReactNode }) => {
  const [status, setStatus] = useState<Status>("idle");
  const inflight = useRef(0);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const setSaving = useCallback(() => {
    inflight.current += 1;
    setStatus("saving");
    if (timer.current) clearTimeout(timer.current);
  }, []);

  const setSaved = useCallback(() => {
    inflight.current = Math.max(0, inflight.current - 1);
    if (inflight.current === 0) {
      setStatus("saved");
      if (timer.current) clearTimeout(timer.current);
      timer.current = setTimeout(() => setStatus("idle"), 1500);
    }
  }, []);

  const setError = useCallback(() => {
    inflight.current = Math.max(0, inflight.current - 1);
    setStatus("error");
  }, []);

  return (
    <SaveStatusContext.Provider value={{ status, setSaving, setSaved, setError }}>
      {children}
    </SaveStatusContext.Provider>
  );
};

export const useSaveStatus = () => {
  const ctx = useContext(SaveStatusContext);
  if (!ctx) throw new Error("useSaveStatus must be inside SaveStatusProvider");
  return ctx;
};

export const SaveIndicator = () => {
  const { status } = useSaveStatus();
  if (status === "idle") return <span aria-hidden className="text-xs opacity-0">·</span>;
  const text =
    status === "saving" ? "Saving…" :
    status === "saved" ? "Saved" :
    "—";
  return (
    <span className="text-xs text-hestia-text-muted transition-opacity duration-300">
      {text}
    </span>
  );
};