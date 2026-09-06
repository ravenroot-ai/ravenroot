import { defineConfig } from '@playwright/test';

// The owning JUnit harness starts and stops real Ravenroot child JVMs on one reusable SQLite store.
// The browser drives that harness through a loopback-only control origin, so this project must not
// start the normal Node fixture server and must not assume that a service already exists at config
// evaluation time.
export default defineConfig({
  testDir: './e2e/human-task-confirmation-real',
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  workers: 1,
  timeout: 120_000,
  use: {
    browserName: 'chromium',
    headless: true,
    trace: 'retain-on-failure',
  },
});
