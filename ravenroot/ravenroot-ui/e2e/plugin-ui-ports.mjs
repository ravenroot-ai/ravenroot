import { createHash } from 'node:crypto';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

// QA-11: the port a REAL `ravenroot.jar` binds to for the plugin
// palette/Inspector proof (`scripts/verify-plugin-palette-ui.sh`,
// `e2e/plugin-ui/plugin-palette-inspector.spec.js`). This is deliberately a SEPARATE module from
// `./ports.mjs`, not a second caller of the same one: `ports.mjs` derives the UI/service PAIR the
// standard `npm run test:e2e` suite uses (a static-file fixture server plus a stubbed service), and
// this proof runs a genuine JVM server that serves both the API and the built UI from the SAME
// origin. Sharing a port with the standard suite would let a concurrent `npm run test:e2e` and a
// concurrent `verify-plugin-palette-ui.sh` collide on the same worktree — exactly the hazard
// `ports.mjs`'s own derivation exists to remove for its pair, reproduced here for this one.
//
// Same derivation shape as `ports.mjs` (an even base in [20000, 59998], derived from this file's own
// absolute path so two worktrees cannot collide without anyone setting anything), seeded from a
// DIFFERENT string (this file's path, not `ports.mjs`'s) so the two pairs land in different places
// for the same worktree too.

const HERE = dirname(fileURLToPath(import.meta.url));

function deriveBase(seed) {
  const digest = createHash('sha256').update(seed).digest();
  return 20000 + (digest.readUInt32BE(0) % 20000) * 2;
}

export const DERIVATION_ENABLED = process.env.RR_PORT_DERIVE !== '0';
const FIXED_PLUGIN_UI_PORT = 4179;
const base = deriveBase(HERE);
const DEFAULT_PLUGIN_UI_PORT = DERIVATION_ENABLED ? base : FIXED_PLUGIN_UI_PORT;

const read = (name, fallback) => {
  const raw = process.env[name];
  if (raw === undefined || raw === '') return fallback;
  const port = Number(raw);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error(`${name} must be an integer port between 1 and 65535, received ${raw}`);
  }
  return port;
};

export const RR_PLUGIN_UI_PORT = read('RR_PLUGIN_UI_PORT', DEFAULT_PLUGIN_UI_PORT);
export const RR_PLUGIN_UI_ORIGIN = `http://127.0.0.1:${RR_PLUGIN_UI_PORT}`;

// `scripts/verify-plugin-palette-ui.sh` is POSIX shell and cannot import this module; it invokes
// this once at startup, the same way `load-harness.sh` reads `ports.mjs`.
if (process.argv[2] === '--print-port') process.stdout.write(String(RR_PLUGIN_UI_PORT));
