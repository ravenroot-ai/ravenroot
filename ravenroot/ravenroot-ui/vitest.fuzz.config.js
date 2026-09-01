import { defineConfig } from 'vitest/config';

// `npm run test:fuzz` runs ONLY the fast-check fuzz specs
// (test/**/*.fuzz.test.js), the mirror image of vitest.config.js's own exclusion of that same
// pattern. Deliberately a separate config file rather than a CLI --include flag, so both the
// inclusion and the exclusion are committed, reviewable and cannot drift apart silently.
export default defineConfig({
  test: {
    environment: 'jsdom',
    include: ['test/**/*.fuzz.test.js'],
  },
});
