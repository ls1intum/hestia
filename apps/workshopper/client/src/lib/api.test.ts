import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, deleteSession, downloadName, errorMessage, listSessions, safeFileName } from "./api";

describe("download names", () => {
  it("use the title as shown", () => {
    expect(downloadName.slides("Intro to ML")).toBe("Intro to ML - Slides.pptx");
    expect(downloadName.timetable("Données & Co.")).toBe("Données & Co. - Timetable.pdf");
    expect(downloadName.course("Machine Learning WS26")).toBe("Machine Learning WS26.zip");
  });

  it("remove only what file systems forbid", () => {
    expect(safeFileName("Part 1: Basics / Advanced?")).toBe("Part 1 Basics Advanced");
    expect(safeFileName('a\\b"c<d>e|f*g')).toBe("a b c d e f g");
    expect(safeFileName("line\r\nbreak")).toBe("line break");
    expect(safeFileName(".hidden")).toBe("hidden");
  });

  it("fall back for empty titles and limit the length", () => {
    expect(downloadName.slides(undefined)).toBe("Workshop - Slides.pptx");
    expect(downloadName.course("   ")).toBe("Course.zip");
    expect(safeFileName("x".repeat(300))).toHaveLength(100);
  });
});

describe("errorMessage", () => {
  it("shows the server's message for request errors", () => {
    expect(errorMessage(new ApiError(404, "This session no longer exists."), "fallback")).toBe("This session no longer exists.");
    expect(errorMessage(new ApiError(429, "Too many AI requests."), "fallback")).toBe("Too many AI requests.");
  });

  it("uses the fallback for server failures and unknown errors", () => {
    expect(errorMessage(new ApiError(500, "The request failed on the server."), "Please try again.")).toBe("Please try again.");
    expect(errorMessage("weird", "Please try again.")).toBe("Please try again.");
    expect(errorMessage(new Error("Network down"), "fallback")).toBe("Network down");
  });
});

describe("requests", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  function stubFetch() {
    const fetchMock = vi.fn(async () => new Response("[]", { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    return fetchMock;
  }

  function lastInit(fetchMock: ReturnType<typeof stubFetch>) {
    const call = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    return { ...call[1], headers: new Headers(call[1].headers) };
  }

  it("send the CSRF token from the cookie with every write and the session cookie always", async () => {
    vi.stubGlobal("document", { cookie: "other=1; XSRF-TOKEN=abc123" });
    const fetchMock = stubFetch();
    await deleteSession("s1");
    const init = lastInit(fetchMock);
    expect(init.headers.get("X-XSRF-TOKEN")).toBe("abc123");
    expect(init.credentials).toBe("same-origin");
  });

  it("do not send the CSRF token with reads", async () => {
    vi.stubGlobal("document", { cookie: "other=1; XSRF-TOKEN=abc123" });
    const fetchMock = stubFetch();
    await listSessions();
    const init = lastInit(fetchMock);
    expect(init.headers.has("X-XSRF-TOKEN")).toBe(false);
    expect(init.credentials).toBe("same-origin");
  });
});
