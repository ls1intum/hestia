import { expect, test as setup } from "@playwright/test";

/**
 * The dev user confirms once that uploads contain no personal data, as a user would in the dialog
 * before the first upload (privacy.e2e.ts shows that dialog with a fresh user). Through the API, so
 * the other tests start from a user who already did.
 */
setup("the dev user has confirmed the upload notice", async ({ request }) => {
  const notice = await request.get("core/api/privacy/upload-confirmation");
  expect(notice.ok()).toBeTruthy();
  const { confirmed, noticeVersion } = (await notice.json()) as { confirmed: boolean; noticeVersion: string };
  if (confirmed) return;
  const state = await request.storageState();
  const token = state.cookies.find((c) => c.name === "XSRF-TOKEN")?.value;
  const confirm = await request.post("core/api/privacy/upload-confirmation", {
    data: { noticeVersion },
    headers: token ? { "X-XSRF-TOKEN": decodeURIComponent(token) } : {},
  });
  expect(confirm.ok()).toBeTruthy();
});
