import { defineConfig } from '@playwright/test';

import { UI_ORIGIN } from './e2e/ports.mjs';

export default defineConfig({
  testDir: './e2e',
  // QA-11: the plugin and Human Task process-restart folders are SEPARATE Playwright projects
  // that drive real Ravenroot JVMs, not this config's stub
  // `ui-fixture-server.mjs`. Left unignored here, this config's own recursive `testDir` scan would
  // pick that spec up and run it against the wrong server — it has no `/v1/node-types` endpoint at
  // all — failing every `npm run test:e2e` run for a reason that has nothing to do with the suite
  // itself. Run them only through their owning integration harnesses.
  testIgnore: ['**/plugin-ui/**', '**/human-task-confirmation-real/**'],
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
