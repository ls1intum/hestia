import { expect, test } from "@playwright/test";
import { readFile } from "node:fs/promises";
import { courseRow, createAnalysedCourse, resetFakeLlm, uniqueName } from "./helpers.ts";

test.beforeEach(async ({ request }) => {
  await resetFakeLlm(request);
});

test("a new course is extracted, reviewed once, and keeps the review after a reload", async ({ page }) => {
  const name = uniqueName("Algorithms");
  await createAnalysedCourse(page, name);
  // Documents, topics, skills, status.
  await expect(page.getByRole("link", { name: new RegExp(`^${name}`) }))
    .toHaveAccessibleName(new RegExp(`^${name} 2 2 4 Analyzed`));

  await page.getByRole("link", { name: new RegExp(`^${name}`) }).click();
  const review = page.getByRole("dialog", { name: "Review your skills" });
  await expect(review).toContainText("0 of 4 accepted");
  const topics = review.getByRole("listitem");
  await expect(topics.nth(0)).toContainText("Sorting algorithms");
  await topics.nth(0).getByRole("button", { name: "Accept all" }).click();
  await expect(review).toContainText("2 of 4 accepted");

  await topics.nth(1).getByRole("button", { name: "Rename this topic" }).click();
  await topics.nth(1).getByRole("textbox", { name: "Goal wording" }).fill("Search methods");
  await page.keyboard.press("Enter");
  await expect(topics.nth(1)).toContainText("Search methods");
  await review.getByRole("button", { name: "Done" }).click();
  await expect(review).toBeHidden();

  await page.reload();
  const tree = page.getByRole("table", { name: "Topic tree" });
  await expect(tree.getByRole("row").filter({ hasText: "Search methods" })).toHaveCount(1);
  await expect(tree).toContainText("Sorting algorithms");
  // The review is offered once per extraction, not on every visit.
  await expect(page.getByRole("dialog", { name: "Review your skills" })).toBeHidden();
  await page.getByRole("button", { name: "More actions" }).click();
  await page.getByRole("menuitem", { name: "Review skills" }).click();
  await expect(page.getByRole("dialog", { name: "Review your skills" })).toContainText("2 of 4 accepted");
});

test("the goals export as CSV and a deleted course stays gone", async ({ page }) => {
  const name = uniqueName("Export");
  await createAnalysedCourse(page, name);
  await page.getByRole("link", { name: new RegExp(`^${name}`) }).click();
  await page.getByRole("dialog", { name: "Review your skills" }).getByRole("button", { name: "Done" }).click();

  await page.getByRole("button", { name: "More actions" }).click();
  const downloading = page.waitForEvent("download");
  await page.getByRole("menuitem", { name: "Export CSV" }).click();
  const csv = await readFile(await (await downloading).path(), "utf8");
  expect(csv).toContain("Implementing merge sort on an array of integers");
  expect(csv).toContain("Applying binary search to a sorted array");

  await page.getByRole("button", { name: "More actions" }).click();
  await page.getByRole("menuitem", { name: "Delete course" }).click();
  await page.getByRole("dialog").getByRole("button", { name: /^Delete/ }).click();
  await expect(page).toHaveURL(/\/learninggoalhub\/?$/);
  await page.goto("./");
  await expect(page.getByRole("heading", { level: 1 })).toContainText("Your Courses");
  await expect(courseRow(page, name)).toHaveCount(0);
});
