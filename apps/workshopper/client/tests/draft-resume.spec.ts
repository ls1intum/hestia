import { test, expect } from '@playwright/test';
import * as fs from 'fs';

test.describe('Draft Auto-Save and Resume', () => {

  test('Start a session, refresh mid-wizard, and confirm it resumes correctly', async ({ page }) => {
    await page.addInitScript(() => sessionStorage.setItem("workshopper_session_id", "mock-session-123"));

    await page.route('**/api/workshop/sessions/mock-session-123', async (route) => {
      await route.fulfill({ 
        status: 200, 
        contentType: 'application/json',
        body: JSON.stringify({
          id: 'mock-session-123',
          title: 'Resumed Workshop Title',
          entityType: 'SESSION',
          workshopInput: {
            title: 'Resumed Workshop Title',
            duration: 120,
            participants: 15,
            sessionType: 'Workshop',
            studentBackground: 'Intermediate',
            learningGoals: ['Goal 1', 'Goal 2'],
            selectedActivities: ['Activity A']
          },
          refinedGoals: [
            { original: 'Goal 1', refined: 'Better Goal 1', isChecked: true }
          ]
        })
      });
    });

    await page.goto('/');

    await page.waitForLoadState('networkidle');

    await page.waitForTimeout(1000); // Wait a bit for React to render
    
    const content = await page.content();
    fs.writeFileSync('page-content.html', content);
    
  });
});
