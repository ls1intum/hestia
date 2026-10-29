import { defineConfig, devices } from "@playwright/test";

// Browser tests against the real server and a fake model (e2e/fake-llm.mjs). They need a
// Postgres on E2E_POSTGRES_PORT and the built server jar; see README.md#browser-tests.
const fakeLlmPort = Number(process.env.FAKE_LLM_PORT ?? 4010);
const serverPort = Number(process.env.E2E_SERVER_PORT ?? 8090);
const clientPort = Number(process.env.E2E_CLIENT_PORT ?? 5174);

export default defineConfig({
  testDir: "e2e",
  // *.e2e.ts, so Vitest (which collects *.test.ts and *.spec.ts) leaves them alone.
  testMatch: "*.e2e.ts",
  // The first test also waits for Vite to compile the app.
  timeout: 60_000,
  // The tests share one fake model, whose failure switch is global.
  workers: 1,
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: `http://localhost:${clientPort}/learninggoalhub/`,
    trace: "retain-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: [
    {
      command: "node e2e/fake-llm.mjs",
      url: `http://localhost:${fakeLlmPort}/__calls`,
      env: { FAKE_LLM_PORT: String(fakeLlmPort) },
      reuseExistingServer: !process.env.CI,
    },
    {
      command: "bash e2e/start-server.sh",
      url: `http://localhost:${serverPort}/actuator/health`,
      timeout: 180_000,
      reuseExistingServer: !process.env.CI,
    },
    {
      command: `npx vite --port ${clientPort} --strictPort`,
      url: `http://localhost:${clientPort}/learninggoalhub/`,
      env: { VITE_API_TARGET: `http://localhost:${serverPort}` },
      reuseExistingServer: !process.env.CI,
    },
  ],
});
