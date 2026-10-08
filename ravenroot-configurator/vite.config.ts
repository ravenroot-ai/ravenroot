import { defineConfig } from "vite";

export default defineConfig({
  build: {
    outDir: "dist/ui",
    emptyOutDir: true,
    sourcemap: true
  },
  server: {
    host: "127.0.0.1"
  }
});
