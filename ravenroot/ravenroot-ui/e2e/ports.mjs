import { createHash } from 'node:crypto';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

// The two ports the end-to-end harness binds, and the ONE place their defaults are written.
//
// ── WHY THIS FILE EXISTS (UI-04) ───────────────────────────────────────────────
//
// Eleven sites across four files, plus `load-harness.sh`, need the same derived ports. Independent
// literals create a correctness hazard: if one fixture server dies and another worktree's server
// takes the shared fixed port, the suite can continue measuring the WRONG WORKTREE while looking
// healthy. Deriving the defaults per worktree removes that cross-run collision.
//
// ── TWO KNOBS, NOT ONE ───────────────────────────────────────────────────────────────────────────
//
// The UI port and the service port are INDEPENDENT and must stay so. The service port is what the
// UI is allowed to reach across an origin boundary — it appears inside the CSP `connect-src` and in
// the CORS assertions of two specs — so collapsing them into one knob would make it impossible to
// move the UI without also moving the origin those tests are about.
//
// ── THE DEFAULT IS DERIVED PER WORKTREE, NOT FIXED ───────────────────────────────────────────────
//
// Configurable ports removed the IMPOSSIBILITY but a fixed default would leave the FOOTGUN: two
// runs that set no environment variable would collide — and an opt-in guard against a hazard that
// has already fired twice in
// one run is a guard nobody opts into at the moment it matters.
//
// So the DEFAULT ITSELF is derived from THIS FILE'S OWN ABSOLUTE PATH. Two worktrees are two paths,
// so two concurrent runs cannot collide without anyone setting anything. The path is used rather
// than the working directory because `cwd` varies by caller — Playwright and `load-harness.sh`
// enter from different places — while the module's location is a property of the worktree itself.
//
// Range: an EVEN base in [20000, 59998], with the service port at base + 1. Even bases keep each
// worktree's PAIR disjoint from every other worktree's pair, so the UI port of one run can never
// land on the service port of another. Well-known and ephemeral ranges are avoided at both ends.
//
// COLLISION IS POSSIBLE AND IS DELIBERATELY LOUD. Two different paths can hash to the same base —
// about one pair in twenty thousand. There is no silent recovery: the second run fails to bind and
// says so, and `RR_UI_PORT` / `RR_SERVICE_PORT` are the escape hatch. A quiet fallback to "some
// other free port" would reintroduce exactly the class of failure this exists to remove, because
// nobody would learn that two runs had ever been in conflict.

const HERE = dirname(fileURLToPath(import.meta.url));

function deriveBase(seed) {
  const digest = createHash('sha256').update(seed).digest();
  return 20000 + (digest.readUInt32BE(0) % 20000) * 2;
}

// The historical fixed pair. Reachable only by explicitly disabling derivation, which exists so the
// proving control can demonstrate the collision it prevents — a guard that cannot be switched off
// cannot be shown to be doing anything.
export const FIXED_UI_PORT = 4173;
export const FIXED_SERVICE_PORT = 4174;

export const DERIVATION_ENABLED = process.env.RR_PORT_DERIVE !== '0';

const base = deriveBase(HERE);
export const DEFAULT_UI_PORT = DERIVATION_ENABLED ? base : FIXED_UI_PORT;
export const DEFAULT_SERVICE_PORT = DERIVATION_ENABLED ? base + 1 : FIXED_SERVICE_PORT;

// Each concurrent worktree needs private default ports; sharing one port can silently contaminate a
// passing suite with another run's server.
const read = (name, fallback) => {
  const raw = process.env[name];
  if (raw === undefined || raw === '') return fallback;
  const port = Number(raw);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error(`${name} must be an integer port between 1 and 65535, received ${raw}`);
  }
  return port;
};

export const UI_PORT = read('RR_UI_PORT', DEFAULT_UI_PORT);
export const SERVICE_PORT = read('RR_SERVICE_PORT', DEFAULT_SERVICE_PORT);

export const UI_ORIGIN = `http://127.0.0.1:${UI_PORT}`;
export const SERVICE_ORIGIN = `http://127.0.0.1:${SERVICE_PORT}`;

// `load-harness.sh` is bash and cannot import this module. Rather than duplicate a literal there,
// it invokes this ONCE AT STARTUP — never inside its EXIT trap, which must not gain a new failure
// mode in the one place least able to absorb one.
if (process.argv[2] === '--print-ui-port') process.stdout.write(String(UI_PORT));
if (process.argv[2] === '--print-service-port') process.stdout.write(String(SERVICE_PORT));
