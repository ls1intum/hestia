// @vitest-environment jsdom
import "../test/dom.ts";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

const patch = vi.fn();
vi.mock("../api/client.ts", () => ({ api: { PATCH: (...args: unknown[]) => patch(...args) } }));

const { default: RenameCourseDialog } = await import("./RenameCourseDialog.tsx");

function renderDialog(onClose = () => {}) {
  const queryClient = new QueryClient();
  render(
    <QueryClientProvider client={queryClient}>
      <RenameCourseDialog course={{ id: 3, name: "Algorithms" }} onClose={onClose} />
    </QueryClientProvider>,
  );
}

async function renameTo(name: string) {
  const user = userEvent.setup();
  const field = screen.getByLabelText("Course title");
  await user.clear(field);
  await user.type(field, name);
  await user.click(screen.getByRole("button", { name: "Save" }));
}

describe("RenameCourseDialog", () => {
  beforeEach(() => patch.mockReset());

  it("sends only the new name and closes once it is saved", async () => {
    patch.mockResolvedValue({ response: new Response(null, { status: 200 }) });
    const onClose = vi.fn();
    renderDialog(onClose);

    await renameTo("Data Structures");

    expect(patch).toHaveBeenCalledWith("/api/courses/{id}", {
      params: { path: { id: 3 } },
      body: { name: "Data Structures" },
    });
    await vi.waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it("shows the server's reason when it rejects the name and stays open", async () => {
    patch.mockResolvedValue({
      error: { code: "CONFLICT", message: "The request conflicts with the stored data" },
      response: new Response(null, { status: 409 }),
    });
    const onClose = vi.fn();
    renderDialog(onClose);

    await renameTo("Data Structures");

    expect(
      await screen.findByText("Could not rename the course. The request conflicts with the stored data."),
    ).toBeTruthy();
    expect(onClose).not.toHaveBeenCalled();
  });

  it("does not offer to save an unchanged or empty name", async () => {
    const user = userEvent.setup();
    renderDialog();
    const save = screen.getByRole("button", { name: "Save" }) as HTMLButtonElement;

    expect(save.disabled).toBe(true);
    await user.clear(screen.getByLabelText("Course title"));
    await user.type(screen.getByLabelText("Course title"), "   ");
    expect(save.disabled).toBe(true);
  });
});
