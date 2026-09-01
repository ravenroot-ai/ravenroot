import { defineConfig } from '@playwright/test';

const parentOrigin = process.env.RR_EMBED_PARENT_ORIGIN;
if (parentOrigin === undefined) throw new Error('RR_EMBED_PARENT_ORIGIN is required');

export default defineConfig({
  testDir: './e2e',
  testMatch: 'embed-browser-spec.mjs',
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  workers: 1,
  timeout: 30_000,
  use: {
    baseURL: parentOrigin,
    browserName: 'chromium',
    headless: true,
    ignoreHTTPSErrors: true,
    // A trace records the one-use launch URL. Even though the ticket is consumed immediately, this
    // security suite must not turn credentials into a diagnostic artifact.
    trace: 'off',
  },
  webServer: {
    command: 'node e2e/embed-browser-fixture.mjs',
    url: `${parentOrigin}/healthz`,
    ignoreHTTPSErrors: true,
    reuseExistingServer: false,
    timeout: 30_000,
  },
});
