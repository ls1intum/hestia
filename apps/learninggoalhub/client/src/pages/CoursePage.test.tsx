// @vitest-environment jsdom
import "../test/dom.ts";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ThemeProvider } from "../theme/ThemeProvider.tsx";

const get = vi.fn();
const post = vi.fn();
vi.mock("../api/client.ts", () => ({
  API_PREFIX: "",
  api: {
    GET: (...args: unknown[]) => get(...args),
    POST: (...args: unknown[]) => post(...args),
  },
}));

const { default: CoursePage } = await import("./CoursePage.tsx");

function renderAt(path: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <ThemeProvider>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/courses/:courseId" element={<CoursePage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </ThemeProvider>,
  );
}

describe("CoursePage", () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    // jsdom has no matchMedia, which the theme reads for the OS preference.
    window.matchMedia = vi.fn().mockReturnValue({
      matches: false,
      addEventListener: () => {},
      removeEventListener: () => {},
    });
  });

  it("shows not found for an id that is not a number, without asking the server", () => {
    renderAt("/courses/abc");

    expect(screen.getByRole("heading", { name: "Not found" })).toBeTruthy();
    expect(screen.getByText("There is no such course.")).toBeTruthy();
    expect(get).not.toHaveBeenCalled();
  });

  it("shows not found for a course the server does not know", async () => {
    get.mockResolvedValue({
      error: { code: "NOT_FOUND", message: "Course not found: 999" },
      response: new Response(null, { status: 404 }),
    });

    renderAt("/courses/999");

    expect(await screen.findByText("This course does not exist or was deleted.")).toBeTruthy();
    expect(get).toHaveBeenCalledWith("/api/courses/{id}", { params: { path: { id: 999 } } });
  });

  it("reports a server failure as an error, not as a missing course", async () => {
    get.mockResolvedValue({
      error: { code: "INTERNAL_SERVER_ERROR", message: "Unexpected server error" },
      response: new Response(null, { status: 500 }),
    });

    renderAt("/courses/7");

    expect(await screen.findByText("Could not load the course.")).toBeTruthy();
    expect(screen.queryByRole("heading", { name: "Not found" })).toBeNull();
  });

  it("offers to start the analysis of a course whose materials were never analysed", async () => {
    const ok = (data: unknown) => ({ data, response: new Response(null, { status: 200 }) });
    get.mockImplementation((path: string) => {
      if (path === "/api/courses/{id}") {
        return ok({ id: 7, name: "Algorithms", documentCount: 2, goalCount: 0, topicCount: 0 });
      }
      if (path === "/api/courses/{courseId}/learning-goals") {
        return ok({ content: [], page: { totalElements: 0 } });
      }
      return ok(null);
    });
    post.mockResolvedValue({ data: { status: "RUNNING" }, response: new Response(null, { status: 202 }) });
    const user = userEvent.setup();

    renderAt("/courses/7");

    expect(await screen.findByText("2 documents uploaded, not analysed yet.")).toBeTruthy();
    await user.click(screen.getByRole("button", { name: "Start analysis" }));
    expect(post).toHaveBeenCalledWith("/api/courses/{courseId}/extract", {
      params: { path: { courseId: 7 }, query: {} },
    });
  });

  it("does not offer to start an analysis for a course that already has one", async () => {
    get.mockImplementation((path: string) => ({
      data: path === "/api/courses/{id}"
        ? { id: 7, name: "Algorithms", documentCount: 2, goalCount: 0, extractionStatus: "RUNNING" }
        : { content: [], page: { totalElements: 0 } },
      response: new Response(null, { status: 200 }),
    }));

    renderAt("/courses/7");

    expect(await screen.findByRole("heading", { name: "Algorithms" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Start analysis" })).toBeNull();
  });
});
