import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { setToken } from "@/lib/api/token-store";
import { useHasToken, useMe } from "@/hooks/data/use-me";
import { gateState } from "./gate-state";
import wordmarkDark from "@/assets/hestia-wordmark-dark.svg";
import wordmarkLight from "@/assets/hestia-wordmark-light.svg";

export const SignInGate = ({ children }: { children: React.ReactNode }) => {
  const me = useMe();
  const hasUsableToken = useHasToken();

  useEffect(() => {
    // Parse SAML token from URL fragment
    const hash = window.location.hash;
    if (hash.startsWith("#token=")) {
      const token = hash.replace("#token=", "");
      setToken(token);
      window.history.replaceState(null, "", window.location.pathname + window.location.search);
      me.refetch();
    }
  }, [me]);

  const state = gateState({
    hasUsableToken,
    everHadToken: false, // We no longer use Access Keys, so we don't care about past tokens
    isSuccess: me.isSuccess,
    isLoading: me.isLoading,
    isError: me.isError,
    error: me.error,
    registerPending: false,
    registerError: null,
  });

  switch (state) {
    case "authenticated":
      return <>{children}</>;
    case "resolving":
      return <Splash>Setting things up…</Splash>;
    case "unreachable":
      return (
        <Splash>Can't reach the ExamLense server. Check your connection or VPN, then reload.</Splash>
      );
    default:
      return <LoginCard />;
  }
};

const Splash = ({ children }: { children: React.ReactNode }) => (
  <div className="flex min-h-dvh items-center justify-center bg-hestia-bg text-hestia-text">
    <p className="text-sm text-hestia-text-muted">{children}</p>
  </div>
);

const LoginCard = () => {
  return (
    <div className="flex min-h-dvh items-center justify-center bg-hestia-bg px-hestia-5 py-hestia-10">
      <Card className="w-full max-w-md">
        <CardHeader className="space-y-hestia-3 text-center">
          <div className="flex justify-center">
            <img src={wordmarkLight} alt="HESTIA" className="h-8 w-auto dark:hidden" />
            <img src={wordmarkDark} alt="HESTIA" className="hidden h-8 w-auto dark:block" />
          </div>
          <CardTitle className="font-display mt-4">Welcome to ExamLense</CardTitle>
          <CardDescription>
            Log in with your TUM account to create and review exams with AI assistance.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-hestia-4">
          <Button 
            className="w-full h-12 text-base rounded-xl" 
            onClick={() => window.location.href = import.meta.env.BASE_URL + "saml2/authenticate/tum"}
          >
            Log in with TUM
          </Button>
        </CardContent>
      </Card>
    </div>
  );
};

