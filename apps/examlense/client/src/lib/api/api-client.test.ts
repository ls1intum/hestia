import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest, ApiError, loginUrl, setConsentHandler } from "@/lib/api/api-client";

function mockFetch(status: number, body: unknown = {}) {
  const fn = vi.fn().mockResolvedValue(
    new Response(status === 204 ? null : JSON.stringify(body), {
      status,
      headers: { "content-type": "application/json" },
    }),
  );
  vi.stubGlobal("fetch", fn);
  return fn;
}

describe("apiRequest", () => {
  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=abc%3D123; path=/";
  });
  afterEach(() => {
    document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
    vi.unstubAllGlobals();
  });

  it("sends the session cookie but no CSRF header on GET", async () => {
    const fetchMock = mockFetch(200, []);
    await apiRequest("/api/exams");
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect(init.credentials).toBe("include");
    expect((init.headers as Record<string, string>)["X-XSRF-TOKEN"]).toBeUndefined();
    expect((init.headers as Record<string, string>).Authorization).toBeUndefined();
  });

  it("echoes the XSRF-TOKEN cookie on writes", async () => {
    const fetchMock = mockFetch(200, {});
    await apiRequest("/api/exams", { method: "POST", json: { title: "x" } });
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect((init.headers as Record<string, string>)["X-XSRF-TOKEN"]).toBe("abc=123");
  });

  it("sends the browser to the SAML login on 401", async () => {
    mockFetch(401, { error: "unauthorized" });
    const assign = vi.fn();
    vi.stubGlobal("location", { ...window.location, assign });
    await expect(apiRequest("/api/me")).rejects.toBeInstanceOf(ApiError);
    expect(assign).toHaveBeenCalledWith(loginUrl());
    expect(loginUrl()).toMatch(/\/saml2\/authenticate\/tum$/);
  });
});

describe("apiRequest consent handling", () => {
  afterEach(() => {
    setConsentHandler(null);
    vi.unstubAllGlobals();
  });

  const consentRequired = () =>
    new Response(JSON.stringify({ error: "Consent required", code: "CONSENT_REQUIRED" }), {
      status: 428,
      headers: { "content-type": "application/json" },
    });
  const ok = () =>
    new Response(JSON.stringify({ ok: true }), { status: 200, headers: { "content-type": "application/json" } });

  it("asks for consent on 428 CONSENT_REQUIRED and repeats the request once it is given", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(consentRequired()).mockResolvedValueOnce(ok());
    vi.stubGlobal("fetch", fetchMock);
    const handler = vi.fn().mockResolvedValue(true);
    setConsentHandler(handler);

    await expect(apiRequest("/api/solve-exam", { method: "POST", json: {} })).resolves.toEqual({ ok: true });

    expect(handler).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("fails with the 428 when the user declines", async () => {
    vi.stubGlobal("fetch", vi.fn().mockImplementation(async () => consentRequired()));
    setConsentHandler(async () => false);

    await expect(apiRequest("/api/parse-exam-pdf", { method: "POST", json: {} })).rejects.toMatchObject({
      status: 428,
    });
  });

  it("does not ask again when the repeated request is refused too", async () => {
    const fetchMock = vi.fn().mockImplementation(async () => consentRequired());
    vi.stubGlobal("fetch", fetchMock);
    const handler = vi.fn().mockResolvedValue(true);
    setConsentHandler(handler);

    await expect(apiRequest("/api/solve-exam", { method: "POST", json: {} })).rejects.toBeInstanceOf(ApiError);
    expect(handler).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("leaves other 428 answers alone", async () => {
    vi.stubGlobal("fetch", vi.fn().mockImplementation(async () => new Response("{}", { status: 428 })));
    const handler = vi.fn().mockResolvedValue(true);
    setConsentHandler(handler);

    await expect(apiRequest("/api/x")).rejects.toBeInstanceOf(ApiError);
    expect(handler).not.toHaveBeenCalled();
  });
});
