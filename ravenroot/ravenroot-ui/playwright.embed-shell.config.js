import { defineConfig } from '@playwright/test';

const port = Number.parseInt(process.env.RR_EMBED_SHELL_TEST_PORT ?? '4317', 10);
if (!Number.isSafeInteger(port) || port < 1 || port > 65_535) {
  throw new Error('RR_EMBED_SHELL_TEST_PORT must be a valid port.');
}
const origin = `http://127.0.0.1:${port}`;

export default defineConfig({
  testDir: './e2e',
  testMatch: 'embed-shell-lifecycle.browser.mjs',
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  workers: 1,
  timeout: 20_000,
  use: {
    baseURL: origin,
    browserName: 'chromium',
    headless: true,
    trace: 'off',
  },
  webServer: {
    command: 'node e2e/embed-shell-lifecycle-fixture.mjs',
    url: `${origin}/healthz`,
    reuseExistingServer: false,
    timeout: 10_000,
  },
});
