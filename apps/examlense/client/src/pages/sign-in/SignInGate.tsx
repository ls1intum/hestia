import { ApiError } from "@/lib/api/api-client";
import { useMe } from "@/hooks/data/use-me";

export const SignInGate = ({ children }: { children: React.ReactNode }) => {
  const me = useMe();

  if (me.isSuccess) return <>{children}</>;
  if (me.isError) {
    if (me.error instanceof ApiError && me.error.status === 401) {
      // apiRequest is already sending the browser to the TUM login.
      return <Splash>Redirecting to the TUM login…</Splash>;
    }
    return (
      <Splash>Can't reach the ExamLense server. Check your connection or VPN, then reload.</Splash>
    );
  }
  return <Splash>Setting things up…</Splash>;
};

const Splash = ({ children }: { children: React.ReactNode }) => (
  <div className="flex min-h-dvh items-center justify-center bg-hestia-bg text-hestia-text">
    <p className="text-sm text-hestia-text-muted">{children}</p>
  </div>
);
