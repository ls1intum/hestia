import { test, expect } from '@playwright/test';

test.describe('Workshopper Wizard Flow', () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => sessionStorage.clear());
    
    // Mock the sessions list endpoint and draft saving endpoints
    await page.route('**/api/workshop/sessions/draft*', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([])
        });
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ id: 'mock-session-123' })
        });
      }
    });

    // Mock the session detail endpoint
    await page.route('**/api/workshop/session/mock-session-123', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: 'mock-session-123',
          draftStateJson: '{}'
        })
      });
    });

    // Mock the plan generation
    await page.route('**/api/workshop/plan', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([
          { id: 'g1', goal: 'Test Goal 1', priority: 0 }
        ])
      });
    });

    // Mock session skeleton generation (Phase A)
    await page.route('**/api/workshop/skeleton', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          learningGoal: 'Mock Goal',
          blocks: [{ phase: 'LECTURE', lgIndex: 1, duration: 10, title: 'Block 1', description: 'Desc', sections: [] }],
          omittedGoalIndices: []
        })
      });
    });

    // Mock session generation (Phase B)
    await page.route('**/api/workshop/session', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: 'mock-session-123',
          title: 'Test Session',
          sections: [
            { title: 'Sec 1', blocks: [{ id: 'b1', title: 'Block 1', phase: 'LECTURE', duration: 10, sections: [] }] }
          ]
        })
      });
    });

    // Mock slides generation
    await page.route('**/api/workshop/export/block-slides', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([
          { title: 'Slide 1', content: 'Content' }
        ])
      });
    });
  });

  test('should complete the wizard flow', async ({ page }) => {
    // Navigate to root
    page.on('console', msg => console.log('BROWSER CONSOLE:', msg.text()));
    page.on('pageerror', err => console.log('BROWSER ERROR:', err));
    await page.goto('/');

    // Start a new session
    await page.getByRole('button', { name: /(Create|New) Session/i }).click();

    // Step 1: Setup
    await expect(page.getByText('Session Setup')).toBeVisible();
    await page.getByLabel('Session Title').fill('Test Title');
    await page.getByRole('button', { name: /Next step/i }).click();

    // Step 2: Activities
    await expect(page.getByRole('heading', { name: 'Activities' })).toBeVisible();
    await page.getByRole('button', { name: /Next step/i }).click();

    // Step 2b: Materials
    await expect(page.getByRole('heading', { name: 'Materials' })).toBeVisible();
    await page.getByRole('button', { name: /Next step/i }).click();

    // Step 4: Learning Goals
    await expect(page.getByText('Learning Goals')).toBeVisible();
    await page.getByPlaceholder(/e\.g\. apply logistic regression/i).fill('Learn Playwright');
    await page.getByRole('button', { name: /Continue/i }).click();

    // Step 5: Review Plan (if it exists)
    // The previous step might be Review Plan. We will wait for "Test Title" to appear which happens in Timetable.
    await expect(page.getByText(/Test Title/i)).toBeVisible({ timeout: 15000 });
  });

test('should show access denied toast when session belongs to another user', async ({ page }) => {
    // Override the POST endpoint to return 403 Forbidden
    await page.route('**/api/workshop/sessions/draft*', async (route) => {
      if (route.request().method() === 'POST' || route.request().method() === 'PUT') {
        await route.fulfill({
          status: 403,
          contentType: 'text/plain',
          body: 'Access denied: You do not have permission to modify this session'
        });
      } else if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([])
        });
      } else {
        route.fallback();
      }
    });

    await page.goto('/');
    await page.getByRole('button', { name: /(Create|New) Session/i }).click();

    // Try to proceed from step 1, which triggers a draft save
    await expect(page.getByText('Session Setup')).toBeVisible();
    await page.getByLabel('Session Title').fill('Test Title');
    await page.getByRole('button', { name: /Next step/i }).click();

    // The toast should appear indicating access denied
    await expect(page.getByText('Access denied')).toBeVisible();
  });
});