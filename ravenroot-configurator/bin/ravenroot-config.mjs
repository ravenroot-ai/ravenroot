#!/usr/bin/env node
import { main } from "../dist-node/src/cli.js";

main(process.argv.slice(2)).catch((error) => {
  const message = error instanceof Error ? error.message : String(error);
  process.stderr.write(`ravenroot-config: ${message}\n`);
  process.exitCode = 1;
});
