// @vitest-environment jsdom
import "../test/dom.ts";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ThemeProvider } from "./ThemeProvider.tsx";
import { useTheme } from "./context.ts";

function ThemeProbe() {
  const { resolved, toggle } = useTheme();
  return <button onClick={toggle}>{resolved}</button>;
}

describe("ThemeProvider", () => {
  beforeEach(() => {
    // jsdom has no matchMedia; the OS preference here is light.
    window.matchMedia = vi.fn().mockReturnValue({
      matches: false,
      addEventListener: () => {},
      removeEventListener: () => {},
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
    document.documentElement.removeAttribute("data-theme");
  });

  it("remembers the chosen theme", async () => {
    render(
      <ThemeProvider>
        <ThemeProbe />
      </ThemeProvider>,
    );

    await userEvent.click(screen.getByRole("button", { name: "light" }));

    expect(localStorage.getItem("hestia-theme")).toBe("dark");
    expect(document.documentElement.getAttribute("data-theme")).toBe("dark");
  });

  it("still renders and switches the theme when storage is blocked", async () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new DOMException("blocked", "SecurityError");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("blocked", "SecurityError");
    });

    render(
      <ThemeProvider>
        <ThemeProbe />
      </ThemeProvider>,
    );
    await userEvent.click(screen.getByRole("button", { name: "light" }));

    expect(screen.getByRole("button", { name: "dark" })).toBeTruthy();
    expect(document.documentElement.getAttribute("data-theme")).toBe("dark");
  });
});
