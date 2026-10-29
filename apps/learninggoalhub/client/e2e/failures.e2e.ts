import { expect, test } from "@playwright/test";
import { courseRow, failNextModelCalls, lectureFiles, resetFakeLlm, uniqueName } from "./helpers.ts";
import { SEARCHING_LECTURE, SORTING_LECTURE, textPdf } from "./pdf.ts";

test.beforeEach(async ({ request }) => {
  await resetFakeLlm(request);
});

test("a failed upload is retried into the same course, not a second one", async ({ page }) => {
  let failUpload = true;
  await page.route("**/api/courses/*/documents**", async (route) => {
    if (route.request().method() === "POST" && failUpload) {
      failUpload = false;
      return route.fulfill({ status: 500, contentType: "application/json", body: "{}" });
    }
    return route.fallback();
  });
  const name = uniqueName("Upload");
  await page.goto("./");
  await page.getByRole("button", { name: "Add course" }).click();
  const dialog = page.getByRole("dialog", { name: "Create course" });
  await dialog.getByLabel("Course title").fill(name);
  await page.locator('input[type="file"]').first().setInputFiles(lectureFiles);
  await dialog.getByRole("button", { name: /Create course/ }).click();
  await expect(dialog).toContainText("Course created, but the upload failed (HTTP 500).");

  await dialog.getByRole("button", { name: /Create course/ }).click();
  await expect(courseRow(page, name)).toContainText("Analyzed", { timeout: 60_000 });
  await expect(page.getByRole("link", { name: new RegExp(`^${name}`) }))
    .toHaveAccessibleName(new RegExp(`^${name} 2 2 4 Analyzed`));
  await page.reload();
  await expect(courseRow(page, name)).toHaveCount(1);
});

test("a run whose model calls fail names the sessions, saves nothing, and can be retried", async ({ page, request }) => {
  await failNextModelCalls(request, 100);
  const name = uniqueName("Failing");
  await page.goto("./");
  await page.getByRole("button", { name: "Add course" }).click();
  const dialog = page.getByRole("dialog", { name: "Create course" });
  await dialog.getByLabel("Course title").fill(name);
  await page.locator('input[type="file"]').first().setInputFiles(lectureFiles);
  await dialog.getByRole("button", { name: /Create course/ }).click();
  await expect(courseRow(page, name)).toContainText("Failed", { timeout: 60_000 });
  await expect(page.getByRole("link", { name: new RegExp(`^${name}`) }))
    .toHaveAccessibleName(new RegExp(`^${name} 2 0 0 Failed`));

  await resetFakeLlm(request);
  await page.getByRole("link", { name: new RegExp(`^${name}`) }).click();
  const failure = page.getByRole("dialog", { name: "Analysis failed" });
  await expect(failure).toContainText("2 sessions could not be analysed. No partial Topic tree was saved.");
  // The sessions run in parallel, so they fail in either order.
  await expect(failure.getByRole("listitem")).toHaveCount(2);
  await expect(failure.getByRole("listitem").filter({ hasText: "sorting.pdf" })).toHaveCount(1);
  await expect(failure.getByRole("listitem").filter({ hasText: "searching.pdf" })).toHaveCount(1);
  await failure.getByRole("button", { name: "Retry extraction" }).click();
  // A finished run opens the course with the skill review.
  await expect(page.getByRole("heading", { level: 1, name })).toBeVisible({ timeout: 60_000 });
  await expect(page.getByRole("dialog", { name: "Review your skills" })).toContainText("0 of 4 accepted");
});

test("a course needs a lecture, and a PDF without text is refused with the reason", async ({ page }) => {
  const name = uniqueName("Scanned");
  await page.goto("./");
  await page.getByRole("button", { name: "Add course" }).click();
  const dialog = page.getByRole("dialog", { name: "Create course" });
  await dialog.getByLabel("Course title").fill(name);
  await expect(dialog).toContainText("Add at least one lecture PDF.");
  await expect(dialog.getByRole("button", { name: /Create course/ })).toBeDisabled();

  // A page with no text layer stands in for a scan: the server reads no text from it either way.
  await page.locator('input[type="file"]').first().setInputFiles([
    ...lectureFiles,
    { name: "scan.pdf", mimeType: "application/pdf", buffer: textPdf([[]]) },
  ]);
  await dialog.getByRole("button", { name: /Create course/ }).click();
  await expect(dialog).toContainText("Course created, but the upload failed: scan.pdf has no selectable text");

  await dialog.getByRole("button", { name: "Remove scan.pdf" }).click();
  await dialog.getByRole("button", { name: /Create course/ }).click();
  await expect(courseRow(page, name)).toContainText("Analyzed", { timeout: 60_000 });
});

test("a course whose analysis never started can start it from its page", async ({ page }) => {
  let failStart = true;
  await page.route("**/api/courses/*/extract**", async (route) => {
    if (route.request().method() === "POST" && failStart) {
      failStart = false;
      return route.fulfill({ status: 500, contentType: "application/json", body: "{}" });
    }
    return route.fallback();
  });
  const name = uniqueName("Unstarted");
  await page.goto("./");
  await page.getByRole("button", { name: "Add course" }).click();
  const dialog = page.getByRole("dialog", { name: "Create course" });
  await dialog.getByLabel("Course title").fill(name);
  await page.locator('input[type="file"]').first().setInputFiles(lectureFiles);
  await dialog.getByRole("button", { name: /Create course/ }).click();
  await expect(dialog).toContainText("Course created, but extraction could not be started.");
  await dialog.getByRole("button", { name: "Close" }).click();

  await page.getByRole("link", { name: new RegExp(`^${name}`) }).click();
  await expect(page.getByText("2 documents uploaded, not analysed yet.")).toBeVisible();
  await page.getByRole("button", { name: "Start analysis" }).click();
  await page.goto("./");
  await expect(courseRow(page, name)).toContainText("Analyzed", { timeout: 60_000 });
});

test("a retried upload sends an exercise that shares a stored lecture's filename", async ({ page }) => {
  let failExercises = true;
  await page.route("**/api/courses/*/documents**", async (route) => {
    const request = route.request();
    if (request.method() === "POST" && request.url().includes("kind=EXERCISE") && failExercises) {
      failExercises = false;
      return route.fulfill({ status: 500, contentType: "application/json", body: "{}" });
    }
    return route.fallback();
  });
  const name = uniqueName("Same name");
  await page.goto("./");
  await page.getByRole("button", { name: "Add course" }).click();
  const dialog = page.getByRole("dialog", { name: "Create course" });
  await dialog.getByLabel("Course title").fill(name);
  const inputs = page.locator('input[type="file"]');
  await inputs.nth(0).setInputFiles({ name: "week1.pdf", mimeType: "application/pdf", buffer: textPdf(SORTING_LECTURE) });
  await inputs.nth(1).setInputFiles({ name: "week1.pdf", mimeType: "application/pdf", buffer: textPdf(SEARCHING_LECTURE) });
  await dialog.getByRole("button", { name: /Create course/ }).click();
  await expect(dialog).toContainText("Course created, but the upload failed (HTTP 500).");

  await dialog.getByRole("button", { name: /Create course/ }).click();
  await expect(courseRow(page, name)).toContainText("Analyzed", { timeout: 60_000 });
  // Two documents: the exercise was sent on the retry, not taken for the stored lecture.
  await expect(page.getByRole("link", { name: new RegExp(`^${name}`) }))
    .toHaveAccessibleName(new RegExp(`^${name} 2 `));
});
