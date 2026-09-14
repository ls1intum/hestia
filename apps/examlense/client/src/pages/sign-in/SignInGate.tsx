import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ApiError, register } from "@/lib/api/api-client";
import { hasEverStoredToken, setToken } from "@/lib/api/token-store";
import { useHasToken, useMe } from "@/hooks/data/use-me";
import { gateState } from "./gate-state";
import wordmarkDark from "@/assets/hestia-wordmark-dark.svg";
import wordmarkLight from "@/assets/hestia-wordmark-light.svg";

/**
 * Gates the app on having a working session token, and gets one automatically for
 * a first-time visitor.
 *
 * Interim measure until TUM SAML is live, at which point this becomes a "Sign in
 * with TUM" redirect — the API contract does not change, only how a token is
 * obtained.
 *
 * A visitor with no token gets an account created for them, with nothing asked and
 * no screen shown. The access-key form appears only when a token was held and
 * rejected; see `gate-state.ts` for why those two cases must not be conflated.
 */
export const SignInGate = ({ children }: { children: React.ReactNode }) => {
  const me = useMe();
  const hasUsableToken = useHasToken();
  const [everHadToken] = useState(hasEverStoredToken);
  const [registerPending, setRegisterPending] = useState(false);
  const [registerError, setRegisterError] = useState<unknown>(null);

  const state = gateState({
    hasUsableToken,
    everHadToken,
    isSuccess: me.isSuccess,
    isLoading: me.isLoading,
    isError: me.isError,
    error: me.error,
    registerPending,
    registerError,
  });

  // At most one registration per mount. Without the guard, any re-render that
  // re-entered the "registering" state would mint another stray account.
  const registered = useRef(false);

  useEffect(() => {
    if (state !== "registering" || registered.current) return;
    registered.current = true;
    setRegisterPending(true);
    register()
      .then(() => me.refetch())
      .catch(setRegisterError)
      .finally(() => setRegisterPending(false));
    // Runs once, when the gate first decides an account is needed.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state]);

  switch (state) {
    case "authenticated":
      return <>{children}</>;
    case "registering":
    case "resolving":
      return <Splash>Setting things up…</Splash>;
    case "unreachable":
      return (
        <Splash>Can't reach the ExamLense server. Check your connection or VPN, then reload.</Splash>
      );
    default:
      return <AccessKeyCard reason={registerError} onSignedIn={() => me.refetch()} />;
  }
};

const Splash = ({ children }: { children: React.ReactNode }) => (
  <div className="flex min-h-dvh items-center justify-center bg-hestia-bg text-hestia-text">
    <p className="text-sm text-hestia-text-muted">{children}</p>
  </div>
);

const AccessKeyCard = ({
  reason,
  onSignedIn,
}: {
  reason: unknown;
  onSignedIn: () => void;
}) => {
  const [key, setKey] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  /**
   * When the server has already refused to create an account (registration
   * closed, or the per-IP cap reached), offering the button again would just
   * reproduce the same refusal — so show the reason instead and leave the access
   * key as the only route.
   */
  const refusal = reason instanceof ApiError ? reason.message : null;

  const signIn = (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setToken(key.trim());
    onSignedIn();
  };

  /**
   * Deliberate rather than automatic. Landing here means a token was held and is
   * no longer usable, and silently minting a replacement would hide the fact that
   * the previous account's exams are being left behind.
   */
  const startFresh = async () => {
    setError(null);
    setBusy(true);
    try {
      await register();
      onSignedIn();
    } catch (err) {
      setError(
        err instanceof ApiError
          ? (err.message ?? "Couldn't create an account.")
          : "Couldn't reach the server. Check your connection or VPN.",
      );
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex min-h-dvh items-center justify-center bg-hestia-bg px-hestia-5 py-hestia-10">
      <Card className="w-full max-w-md">
        <CardHeader className="space-y-hestia-3">
          <div>
            <img src={wordmarkLight} alt="HESTIA" className="h-8 w-auto dark:hidden" />
            <img src={wordmarkDark} alt="HESTIA" className="hidden h-8 w-auto dark:block" />
          </div>
          <CardTitle className="font-display">Sign in to ExamLense</CardTitle>
          <CardDescription>
            {refusal ??
              "Your previous session has ended. Paste your access key to pick up where you left off, or start with a new account."}
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-hestia-4">
          <form onSubmit={signIn} className="space-y-hestia-4">
            <div className="space-y-hestia-2">
              <Label htmlFor="access-key">Access key</Label>
              <Input
                id="access-key"
                value={key}
                onChange={(e) => setKey(e.target.value)}
                placeholder="exl_…"
                autoFocus
                autoCapitalize="none"
                autoCorrect="off"
                spellCheck={false}
                required
              />
              <p className="text-xs text-hestia-text-muted">
                Find it under Account → Access key in the browser you were using before.
              </p>
            </div>
            <Button type="submit" className="w-full" disabled={busy}>
              Sign in
            </Button>
          </form>

          {error && (
            <p role="alert" className="text-sm text-hestia-danger">
              {error}
            </p>
          )}

          {!refusal && (
            <>
              <div className="flex items-center gap-hestia-3">
                <span className="h-px flex-1 bg-hestia-border" />
                <span className="text-xs text-hestia-text-muted">or</span>
                <span className="h-px flex-1 bg-hestia-border" />
              </div>
              <div className="space-y-hestia-2">
                <Button
                  type="button"
                  variant="secondary"
                  className="w-full"
                  onClick={startFresh}
                  disabled={busy}
                >
                  {busy ? "Creating…" : "Start with a new account"}
                </Button>
                <p className="text-xs text-hestia-text-muted">
                  You'll get an empty account. Exams from a previous account stay with it and
                  won't appear here.
                </p>
              </div>
            </>
          )}
        </CardContent>
      </Card>
    </div>
  );
};
