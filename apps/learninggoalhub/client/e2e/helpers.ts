import { expect, type APIRequestContext, type Page } from "@playwright/test";
import { SEARCHING_LECTURE, SORTING_LECTURE, textPdf } from "./pdf.ts";

const fakeLlm = `http://localhost:${process.env.FAKE_LLM_PORT ?? 4010}`;

/** A course name no earlier run used, since the tests share one database. */
export function uniqueName(prefix: string): string {
  return `${prefix} ${Date.now().toString(36)}`;
}

export async function resetFakeLlm(request: APIRequestContext): Promise<void> {
  await request.post(`${fakeLlm}/__reset`);
}

/** Makes the fake model answer the next `times` calls with a 400, which the server does not retry. */
export async function failNextModelCalls(request: APIRequestContext, times: number): Promise<void> {
  await request.post(`${fakeLlm}/__fail`, { data: { times } });
}

export const lectureFiles = [
  { name: "sorting.pdf", mimeType: "application/pdf", buffer: textPdf(SORTING_LECTURE) },
  { name: "searching.pdf", mimeType: "application/pdf", buffer: textPdf(SEARCHING_LECTURE) },
];

/** Creates a course through the dialog with both lectures and waits for its extraction to finish. */
export async function createAnalysedCourse(page: Page, name: string): Promise<void> {
  await page.goto("./");
  await page.getByRole("button", { name: "Add course" }).click();
  const dialog = page.getByRole("dialog", { name: "Create course" });
  await dialog.getByLabel("Course title").fill(name);
  await page.locator('input[type="file"]').first().setInputFiles(lectureFiles);
  await dialog.getByRole("button", { name: /Create course/ }).click();
  await expect(courseRow(page, name)).toContainText("Analyzed", { timeout: 60_000 });
}

export function courseRow(page: Page, name: string) {
  return page.getByRole("listitem").filter({ has: page.getByRole("link", { name: new RegExp(`^${name}\\b`) }) });
}
