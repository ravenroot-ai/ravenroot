import { defineConfig } from '@playwright/test';

// The JUnit harness starts the actual server with this built editor and a durable store.
export default defineConfig({
  testDir: './e2e/saga-authoring-real',
  outputDir: process.env.RAVENROOT_SAGA_OUTPUT || './test-results',
  workers: 1,
  retries: 0,
  timeout: 90_000,
  use: { browserName: 'chromium', headless: true, trace: 'retain-on-failure' },
});
