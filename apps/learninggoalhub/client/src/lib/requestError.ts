/**
 * The error a failed write throws: the client's own wording, followed by the server's
 * `{code, message}` reason when the server rejected the request (4xx) — that reason names the field
 * or conflict. A 5xx reason is the generic "Unexpected server error", which adds nothing.
 */
export function requestError(fallback: string, result: { error?: unknown; response: Response }): Error {
  const message = (result.error as { message?: unknown } | undefined)?.message;
  const status = result.response.status;
  if (status < 400 || status >= 500 || typeof message !== "string" || message.trim() === "") {
    return new Error(fallback);
  }
  const reason = message.trim();
  return new Error(`${fallback} ${reason}${/[.!?]$/.test(reason) ? "" : "."}`);
}
