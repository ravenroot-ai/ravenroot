import { defineConfig } from 'vite';
import { fileURLToPath } from 'node:url';

export default defineConfig({
  base: './',
  server: {
    // Same-origin in application code; Vite proxies to the local Java service during development.
    proxy: {
      '/v1': process.env.RAVENROOT_SERVICE_URL || 'http://127.0.0.1:8080',
      '/health': process.env.RAVENROOT_SERVICE_URL || 'http://127.0.0.1:8080',
    },
  },
  build: {
    target: 'es2022',
    rollupOptions: {
      preserveEntrySignatures: 'exports-only',
      input: {
        main: fileURLToPath(new URL('./index.html', import.meta.url)),
        'embed-viewer': fileURLToPath(new URL('./src/embed-viewer-entry.js', import.meta.url)),
      },
      output: {
        // The server launch document has one stable, same-origin module seam. Shared implementation
        // chunks remain content hashed; only this small entry name is part of the server contract.
        entryFileNames: chunk => chunk.name === 'embed-viewer'
          ? 'embed-viewer.js'
          : 'assets/[name]-[hash].js',
      },
    },
  },
});
