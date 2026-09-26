import { defineConfig } from '@playwright/test';

// DeploymentLifecyclePersistenceBrowserTest owns the real JVM server and SQLite registry.
export default defineConfig({
  testDir: './e2e/deployment-persistence-real',
  outputDir: process.env.RAVENROOT_DEPLOYMENT_PERSISTENCE_OUTPUT_DIR || './test-results',
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  workers: 1,
  timeout: 120_000,
  preserveOutput: 'always',
  use: { browserName: 'chromium', headless: true, trace: 'retain-on-failure' },
});
