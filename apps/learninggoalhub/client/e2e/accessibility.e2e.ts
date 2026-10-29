import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";
import { createAnalysedCourse, resetFakeLlm, uniqueName } from "./helpers.ts";

async function violations(page: Page) {
  // Colours mid-transition (a tab switching its fill) would read as a contrast failure.
  // A string, since the e2e files compile without the DOM types.
  await page.waitForFunction(`document.getAnimations().every((a) => a.playState !== "running")`);
  const result = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa"]).analyze();
  return result.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(" ")).join(", ")}`);
}

for (const colorScheme of ["light", "dark"] as const) {
  test.describe(`${colorScheme} theme`, () => {
    test.use({ colorScheme });

    test("the course list, Topic tree and source pane pass a WCAG AA scan", async ({ page, request }) => {
      await resetFakeLlm(request);
      const name = uniqueName("Accessible");
      await createAnalysedCourse(page, name);
      expect(await violations(page)).toEqual([]);

      await page.getByRole("link", { name: new RegExp(`^${name}`) }).click();
      await page.getByRole("dialog", { name: "Review your skills" }).getByRole("button", { name: "Done" }).click();
      await page.getByRole("button", { name: "Expand all" }).click();
      await page.getByRole("button", { name: "Source: sorting.pdf, page 1" }).first().click();
      await expect(page.locator("canvas")).toBeVisible();
      expect(await violations(page)).toEqual([]);

      await page.getByRole("tab", { name: "Diagram" }).click();
      expect(await violations(page)).toEqual([]);
    });
  });
}
