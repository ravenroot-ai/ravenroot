import { defineConfig } from '@playwright/test';

import { UI_ORIGIN } from './e2e/ports.mjs';

export default defineConfig({
  testDir: './e2e',
  // QA-11: `e2e/plugin-ui/` is a SEPARATE Playwright project
  // (`playwright.plugin-ui.config.js`) that drives a real `ravenroot.jar`, not this config's stub
  // `ui-fixture-server.mjs`. Left unignored here, this config's own recursive `testDir` scan would
  // pick that spec up and run it against the wrong server — it has no `/v1/node-types` endpoint at
  // all — failing every `npm run test:e2e` run for a reason that has nothing to do with the suite
  // itself. Run it only via `scripts/verify-plugin-palette-ui.sh`.
  testIgnore: '**/plugin-ui/**',
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  workers: 1,
  use: {
    baseURL: UI_ORIGIN,
    browserName: 'chromium',
    headless: true,
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'node e2e/ui-fixture-server.mjs',
    url: `${UI_ORIGIN}/`,
    reuseExistingServer: false,
    timeout: 30_000,
  },
});
