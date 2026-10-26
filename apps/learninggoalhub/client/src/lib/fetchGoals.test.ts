import { beforeEach, describe, expect, it, vi } from "vitest";

const get = vi.fn();
vi.mock("../api/client.ts", () => ({ api: { GET: (...args: unknown[]) => get(...args) } }));

const { fetchAllGoals } = await import("./fetchGoals.ts");

describe("fetchAllGoals", () => {
  beforeEach(() => get.mockReset());

  it("follows pagination until every goal is loaded", async () => {
    get
      .mockResolvedValueOnce({ data: { content: [{ id: 1 }], page: { totalElements: 2 } } })
      .mockResolvedValueOnce({ data: { content: [{ id: 2 }], page: { totalElements: 2 } } });

    await expect(fetchAllGoals(7)).resolves.toEqual([{ id: 1 }, { id: 2 }]);
  });

  it("leaves out the legacy gap-analysis goals of earlier prototypes", async () => {
    get.mockResolvedValueOnce({
      data: { content: [{ id: 1, origin: "EXTRACTED" }, { id: 2, origin: "GAP" }], page: { totalElements: 2 } },
    });

    await expect(fetchAllGoals(7)).resolves.toEqual([{ id: 1, origin: "EXTRACTED" }]);
  });

  it("fails instead of returning a partial list when a later page fails", async () => {
    get
      .mockResolvedValueOnce({ data: { content: [{ id: 1 }], page: { totalElements: 2 } } })
      .mockResolvedValueOnce({ error: { status: 500 } });

    await expect(fetchAllGoals(7)).rejects.toThrow("Could not load learning goals.");
  });
});
