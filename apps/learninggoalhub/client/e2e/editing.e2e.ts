import { expect, test, type Page } from "@playwright/test";
import { createAnalysedCourse, resetFakeLlm, uniqueName } from "./helpers.ts";

test.beforeEach(async ({ page, request }) => {
  await resetFakeLlm(request);
  const name = uniqueName("Editing");
  await createAnalysedCourse(page, name);
  await page.getByRole("link", { name: new RegExp(`^${name}`) }).click();
  await page.getByRole("dialog", { name: "Review your skills" }).getByRole("button", { name: "Done" }).click();
  await page.getByRole("button", { name: "Expand all" }).click();
});

function goalRow(page: Page, text: string) {
  return page.getByRole("table", { name: "Topic tree" }).getByRole("row").filter({ hasText: text });
}

test("renamed, re-levelled and deleted goals stay that way after a reload", async ({ page }) => {
  const mergeSort = goalRow(page, "Implementing merge sort").first();
  await mergeSort.getByRole("button", { name: "Rename goal" }).click();
  await mergeSort.getByRole("textbox", { name: "Goal wording" }).fill("Implementing merge sort recursively");
  await page.keyboard.press("Enter");
  await expect(goalRow(page, "Implementing merge sort recursively")).toHaveCount(1);

  const binarySearch = goalRow(page, "Applying binary search").first();
  await binarySearch.getByRole("button", { name: /^Bloom level/ }).click();
  await page.getByRole("button", { name: /^Analy[sz]e/ }).click();
  await expect(binarySearch.getByRole("button", { name: /^Bloom level/ })).toHaveAccessibleName(/Analy[sz]e/);

  const knowledge = goalRow(page, "Terms of insertion");
  await knowledge.getByRole("button", { name: "Delete goal" }).click();
  await page.getByRole("dialog").getByRole("button", { name: /^Delete/ }).click();
  await expect(knowledge).toHaveCount(0);

  await page.reload();
  await page.getByRole("button", { name: "Expand all" }).click();
  await expect(goalRow(page, "Implementing merge sort recursively")).toHaveCount(1);
  await expect(goalRow(page, "Applying binary search").first().getByRole("button", { name: /^Bloom level/ }))
    .toHaveAccessibleName(/Analy[sz]e/);
  await expect(goalRow(page, "Terms of insertion")).toHaveCount(0);
});

test("a rejected edit shows the server's reason and keeps the stored wording", async ({ page }) => {
  await page.route("**/api/courses/*/learning-goals/*", async (route) => {
    if (route.request().method() !== "PATCH") return route.fallback();
    await route.fulfill({
      status: 409,
      contentType: "application/json",
      body: JSON.stringify({ code: "CONFLICT", message: "The goal was changed by someone else." }),
    });
  });
  const row = goalRow(page, "Implementing merge sort").first();
  await row.getByRole("button", { name: "Rename goal" }).click();
  await row.getByRole("textbox", { name: "Goal wording" }).fill("A wording the server rejects");
  await page.keyboard.press("Enter");

  await expect(page.getByText("The goal was changed by someone else.")).toBeVisible();
  await expect(goalRow(page, "A wording the server rejects")).toHaveCount(0);
  await page.unroute("**/api/courses/*/learning-goals/*");
  await page.reload();
  await page.getByRole("button", { name: "Expand all" }).click();
  await expect(goalRow(page, "Implementing merge sort")).not.toHaveCount(0);
  await expect(goalRow(page, "A wording the server rejects")).toHaveCount(0);
});

test("a goal's source opens on its page with the quoted passage highlighted", async ({ page }) => {
  const row = goalRow(page, "Comparing the running").first();
  await row.getByRole("button", { name: "Source: sorting.pdf, page 2" }).click();
  await expect(page.getByText("p. 2 of 2")).toBeVisible();
  const highlight = page.locator("canvas + div[aria-hidden='true']");
  await expect(highlight.first()).toBeVisible();
  await page.getByRole("button", { name: "Close PDF preview" }).click();
  await expect(page.locator("canvas")).toHaveCount(0);
});
