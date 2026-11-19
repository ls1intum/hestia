import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest, ApiError, loginUrl } from "@/lib/api/api-client";

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
