import { defineConfig, configDefaults } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'jsdom',
    include: ['test/**/*.test.js'],
    // Fuzz specs (test/**/*.fuzz.test.js) are excluded from the default
    // `npm test` run -- they are new, deliberately not part of every change, and run separately via
    // `npm run test:fuzz` (vitest.fuzz.config.js). configDefaults.exclude is spread in explicitly:
    // overriding `exclude` at all replaces vitest's own default (node_modules, dist, ...) rather
    // than adding to it, and losing that silently would let the default run start walking
    // node_modules.
    exclude: [...configDefaults.exclude, 'test/**/*.fuzz.test.js'],
  },
});
