import { expect, test } from "@playwright/test";
import { courseRow, lectureFiles, resetFakeLlm, uniqueName } from "./helpers.ts";

// A user who never uploaded: the development login takes the user from this header.
test.use({ extraHTTPHeaders: { "X-Dev-User": uniqueName("first-upload").replace(/\s/g, "-") } });

test.beforeEach(async ({ request }) => {
  await resetFakeLlm(request);
});

async function startCreating(page: import("@playwright/test").Page, name: string) {
  await page.goto("./");
  await page.getByRole("button", { name: "Add course" }).click();
  const dialog = page.getByRole("dialog", { name: "Create course" });
  await dialog.getByLabel("Course title").fill(name);
  await page.locator('input[type="file"]').first().setInputFiles(lectureFiles);
  await dialog.getByRole("button", { name: /Create course/ }).click();
}

test("the first upload asks once to confirm that it holds no personal data", async ({ page }) => {
  const first = uniqueName("First");
  await startCreating(page, first);
  const notice = page.getByRole("dialog", { name: "Keine personenbezogenen Daten hochladen" });
  await expect(notice).toBeVisible();
  // Cancelling creates nothing; the dialog stays open with its staged files.
  await notice.getByRole("button", { name: "Abbrechen" }).click();
  await expect(notice).toBeHidden();

  await page.getByRole("dialog", { name: "Create course" }).getByRole("button", { name: /Create course/ }).click();
  await expect(notice).toBeVisible();
  const confirm = notice.getByRole("button", { name: "Bestätigen und hochladen" });
  await expect(confirm).toBeDisabled();
  await notice.getByRole("checkbox").check();
  await confirm.click();
  await expect(notice).toBeHidden();
  await expect(courseRow(page, first)).toBeVisible({ timeout: 30_000 });

  // The second upload goes straight through.
  const second = uniqueName("Second");
  await startCreating(page, second);
  await expect(courseRow(page, second)).toBeVisible({ timeout: 30_000 });
  await expect(notice).toBeHidden();
});
