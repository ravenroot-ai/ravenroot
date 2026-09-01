import { defineConfig } from '@playwright/test';

import { RR_PLUGIN_UI_ORIGIN } from './e2e/plugin-ui-ports.mjs';

// QA-11 defines this as a SEPARATE Playwright project from `playwright.config.js`, not
// an extra spec folder under the same one. The default config's `webServer` always starts
// `e2e/ui-fixture-server.mjs`, a static-file server that serves `dist/` and never touches a JVM; the
// service it talks to in every existing spec is a synthetic Node HTTP stub (that same fixture
// server, or `page.route`). That is deliberately fast and hermetic, and stays exactly as it is —
// this file does not change it.
//
// That stub cannot prove that a plugin's REAL node types,
// built and activated the documented way, reaching the palette and the Inspector with no UI code
// changed to make it happen. So this config's `webServer` is the actual `ravenroot.jar` — the same
// runtime `quickstart.md` Path 1 and `docs/getting-started/plugin-bundles.md` document — started by
// `scripts/verify-plugin-palette-ui.sh`, which builds the jar and a real `ravenroot-mail` bundle
// first and exports every `RAVENROOT_*` variable this process needs before invoking `playwright
// test` against this config. It is not part of `npm run test:e2e` and is not meant to be: it needs a
// JDK and Maven on the machine, and it is slower, exactly like `scripts/verify-plugin-activation-on-
// image.sh` is already kept outside the default suite for the same
// reason.
//
// The JAR serves the API and the built UI from the SAME origin (`ravenroot-distribution` packages
// `ravenroot-ui/dist` as a resource — see quickstart.md Path 1: "open http://127.0.0.1:8080 for the
// editor UI"), so there is no separate UI fixture server here and no cross-origin CSP/CORS knob to
// wire up: `baseURL` and the JVM's own `RAVENROOT_PORT` are the SAME derived port.
export default defineConfig({
  testDir: './e2e/plugin-ui',
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  workers: 1,
  use: {
    baseURL: RR_PLUGIN_UI_ORIGIN,
    browserName: 'chromium',
    headless: true,
    trace: 'retain-on-failure',
  },
  webServer: {
    // `RR_PLUGIN_UI_JAR`, `RAVENROOT_PORT` (== RR_PLUGIN_UI_ORIGIN's port),
    // `RAVENROOT_PLUGINS_INSTALL_DIR` and `RAVENROOT_ENABLED_PLUGINS` (present only for the green
    // run) are exported by `scripts/verify-plugin-palette-ui.sh` before this config is loaded, the
    // same way it exports `RAVENROOT_AUTH_MODE`/`RAVENROOT_BIND_ADDRESS`. `java -jar` is the command
    // itself, not `ravenroot/scripts/server.sh foreground`, so Playwright's own SIGTERM on teardown
    // reaches the JVM directly rather than a wrapper shell that would need its own signal relay.
    command: 'java -jar "${RR_PLUGIN_UI_JAR}"',
    url: `${RR_PLUGIN_UI_ORIGIN}/health`,
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
