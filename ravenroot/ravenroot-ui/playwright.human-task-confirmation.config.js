import { defineConfig } from '@playwright/test';

// The owning JUnit harness starts and stops real Ravenroot child JVMs on one reusable SQLite store.
// The browser drives that harness through a loopback-only control origin, so this project must not
// start the normal Node fixture server and must not assume that a service already exists at config
// evaluation time.
export default defineConfig({
  testDir: './e2e/human-task-confirmation-real',
  // The JUnit owner supplies a UUID-scoped server-target directory so the ordinary Playwright suite
  // cannot clear this project's screenshot, axe attachment, or failure trace. Direct local runs keep
  // Playwright's existing project-local default.
  outputDir: process.env.RAVENROOT_HUMAN_TASK_CONFIRMATION_OUTPUT_DIR || './test-results',
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  workers: 1,
  timeout: 120_000,
  preserveOutput: 'always',
  use: {
    browserName: 'chromium',
    headless: true,
    trace: 'retain-on-failure',
  },
});
