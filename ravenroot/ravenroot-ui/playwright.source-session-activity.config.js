import { defineConfig } from '@playwright/test';

// The owning JUnit harness (SourceSessionEditorActivityBrowserTest) starts a real Ravenroot server
// that serves this project's own built editor and hosts a real inbound source. There is therefore no
// `webServer` here and no stub: the origin arrives in the environment, already listening.
//
// The run is deliberately slow. The observation window is over twenty seconds of real admitted
// traffic, because a shorter sample can fall between admissions and read as a broken stream -- the
// exact false conclusion that sent the original investigation after a transport fault that did not
// exist.
export default defineConfig({
  testDir: './e2e/source-session-activity-real',
  outputDir: process.env.RAVENROOT_SOURCE_SESSION_OUTPUT_DIR || './test-results',
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  workers: 1,
  timeout: 180_000,
  preserveOutput: 'always',
  use: {
    browserName: 'chromium',
    headless: true,
    trace: 'retain-on-failure',
  },
});
