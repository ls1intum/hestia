// @vitest-environment jsdom
import "../test/dom.ts";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import ConfirmDialog from "./ConfirmDialog.tsx";

function Harness({ busy = false, onCancel = () => {} }: { busy?: boolean; onCancel?: () => void }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button onClick={() => setOpen(true)}>Delete course</button>
      <button>Behind the dialog</button>
      {open && (
        <ConfirmDialog
          title="Delete course?"
          message="This cannot be undone."
          confirmLabel="Delete"
          busy={busy}
          onConfirm={() => {}}
          onCancel={() => {
            onCancel();
            setOpen(false);
          }}
        />
      )}
    </>
  );
}

describe("ConfirmDialog", () => {
  it("names itself by its title and message", async () => {
    render(<Harness />);
    await userEvent.click(screen.getByRole("button", { name: "Delete course" }));

    const dialog = screen.getByRole("dialog", { name: "Delete course?" });
    expect(dialog.getAttribute("aria-describedby")).toBe(screen.getByText("This cannot be undone.").id);
  });

  it("keeps Tab and Shift+Tab inside the dialog", async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(screen.getByRole("button", { name: "Delete course" }));
    const cancel = screen.getByRole("button", { name: "Cancel" });
    const confirm = screen.getByRole("button", { name: "Delete" });

    cancel.focus();
    await user.tab({ shift: true });
    expect(document.activeElement).toBe(confirm);
    await user.tab();
    expect(document.activeElement).toBe(cancel);
  });

  it("returns focus to the button that opened it", async () => {
    const user = userEvent.setup();
    render(<Harness />);
    const opener = screen.getByRole("button", { name: "Delete course" });
    await user.click(opener);
    expect(screen.getByRole("dialog").contains(document.activeElement)).toBe(true);

    await user.keyboard("{Escape}");

    expect(screen.queryByRole("dialog")).toBeNull();
    expect(document.activeElement).toBe(opener);
  });

  it("stays open on Escape and backdrop clicks while its action runs", async () => {
    const user = userEvent.setup();
    const onCancel = vi.fn();
    render(<Harness busy onCancel={onCancel} />);
    await user.click(screen.getByRole("button", { name: "Delete course" }));

    await user.keyboard("{Escape}");
    await user.click(screen.getByRole("dialog"));

    expect(onCancel).not.toHaveBeenCalled();
    expect(screen.getByRole("dialog")).toBeTruthy();
  });
});
