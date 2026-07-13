import { useEffect, useState } from "react";
import { apiBaseUrl, getFigureSignedUrl } from "@/lib/api-client";

/**
 * Resolve a short-lived, directly-loadable `<img src>` URL for a figure.
 *
 * The backend issues an HMAC-signed, time-limited path (`/api/files/...`) that
 * the public file endpoint serves without an auth header — the local-FS
 * equivalent of a Supabase signed URL. We prefix it with the API base so the
 * image loads cross-origin in dev.
 */
export function useFigureUrl(figureId: string | undefined): string | null {
  const [url, setUrl] = useState<string | null>(null);

  useEffect(() => {
    if (!figureId) {
      setUrl(null);
      return;
    }
    let cancelled = false;
    getFigureSignedUrl(figureId)
      .then((res) => {
        if (!cancelled) setUrl(apiBaseUrl() + res.signed_url);
      })
      .catch(() => {
        if (!cancelled) setUrl(null);
      });
    return () => {
      cancelled = true;
    };
  }, [figureId]);

  return url;
}
