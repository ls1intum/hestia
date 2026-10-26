// @vitest-environment jsdom
import "../test/dom.ts";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";

const get = vi.fn();
vi.mock("../api/client.ts", () => ({
  API_PREFIX: "",
  api: { GET: (...args: unknown[]) => get(...args) },
}));

const { default: CoursePage } = await import("./CoursePage.tsx");

function renderAt(path: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/courses/:courseId" element={<CoursePage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("CoursePage", () => {
  beforeEach(() => get.mockReset());

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
});
