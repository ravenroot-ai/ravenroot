import { defineConfig } from '@playwright/test';

const origin = process.env.RAVENROOT_LAUNCH_PROFILE_ORIGIN;
if (!origin) throw new Error('RAVENROOT_LAUNCH_PROFILE_ORIGIN is required');

export default defineConfig({
  testDir: './e2e/launch-profile-real',
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  workers: 1,
  use: {
    baseURL: origin,
    browserName: 'chromium',
    headless: true,
    trace: 'retain-on-failure',
  },
});
