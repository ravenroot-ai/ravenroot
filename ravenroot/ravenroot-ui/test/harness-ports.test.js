import { readFile } from 'node:fs/promises';

import { describe, expect, it } from 'vitest';

import {
  DEFAULT_SERVICE_PORT,
  DEFAULT_UI_PORT,
  DERIVATION_ENABLED,
  FIXED_SERVICE_PORT,
  FIXED_UI_PORT,
} from '../e2e/ports.mjs';

// The static half of the port knob's proving control (UI-04).
//
// The runtime half — a cleanup reporting success on a port nobody used, and the SIGTERM-mid-run
// regression — lives in `e2e/verify-port-knob.sh`, because both are about processes and signals and
// neither can be honestly reproduced from inside a test runner.

// The four files that pinned the ports before the knob existed. `load-harness.sh` is deliberately
// NOT in this list: it is bash, it cannot import the module, and its ONE remaining literal is the
// documented fallback that the first test below pins.
const DERIVED_FILES = [
  'e2e/ui-fixture-server.mjs',
  'e2e/security-boundary.spec.js',
  'e2e/node-catalog-loopback.spec.js',
  'playwright.config.js',
];

// cwd-relative, matching `panes.test.js` which reads `src/styles.css` the same way.
const read = path => readFile(path, 'utf8');

describe('the harness port knob', () => {
  it('leaves no hardcoded port literal in any file that now derives one', async () => {
    const sources = await Promise.all(DERIVED_FILES.map(read));
    for (const [index, source] of sources.entries()) {
      const literals = source.match(/\b(4173|4174)\b/g) || [];
      expect(literals, `${DERIVED_FILES[index]} still pins a port literal`).toEqual([]);
    }
  });

  it('would notice a literal that had been left behind', () => {
    // CONTROL for the absence above: an absence assertion whose matcher is wrong passes for every
    // input. Show the same expression finding a literal when one is present.
    const planted = "const url = 'http://127.0.0.1:4173/';";
    expect(planted.match(/\b(4173|4174)\b/g)).toEqual(['4173']);
  });

  it('leaves no literal in the bash harness either — it invokes the module instead', async () => {
    const script = await read('e2e/load-harness.sh');
    // The old duplicated fallback is gone. Bash cannot import the module, so it CALLS it, once, at
    // startup — never inside the EXIT trap, which must not gain a failure mode.
    expect(script).not.toMatch(/UI_PORT="\$\{RR_UI_PORT:-\d+\}"/);
    expect(script).toContain('--print-ui-port');
    // CONTROL: the matcher must be capable of catching the literal form it is forbidding.
    expect('UI_PORT="${RR_UI_PORT:-4173}"').toMatch(/UI_PORT="\$\{RR_UI_PORT:-\d+\}"/);
  });

  it('derives both knobs independently, because the service origin is what CSP allows', async () => {
    const server = await read('e2e/ui-fixture-server.mjs');
    expect(server).toContain('SERVICE_ORIGIN');
    expect(server).toContain('UI_PORT');
    expect(DEFAULT_UI_PORT).not.toBe(DEFAULT_SERVICE_PORT);
  });

  it('derives a default per worktree rather than taking a fixed one', () => {
    // The knob alone removed the impossibility and left the footgun: a run setting nothing still
    // took a fixed default, so two agents at defaults collided exactly as before.
    expect(DERIVATION_ENABLED).toBe(true);
    expect(DEFAULT_UI_PORT).not.toBe(FIXED_UI_PORT);
    expect(DEFAULT_SERVICE_PORT).not.toBe(FIXED_SERVICE_PORT);
    // An even base with the service at base + 1 keeps each worktree's PAIR disjoint from every
    // other worktree's pair, so one run's UI port can never land on another's service port.
    expect(DEFAULT_UI_PORT % 2).toBe(0);
    expect(DEFAULT_SERVICE_PORT).toBe(DEFAULT_UI_PORT + 1);
    expect(DEFAULT_UI_PORT).toBeGreaterThanOrEqual(20000);
    expect(DEFAULT_UI_PORT).toBeLessThanOrEqual(59998);
  });
});

describe('the CSP connect canary', () => {
  // ── CASE 1 OF THE PROVING CONTROL, INVERTED BY MEASUREMENT ────────────────────────────────────
  //
  // The hazard here is NOT a knob that skips this site. It is a knob thorough enough to NORMALISE
  // it. Measured, three runs of `security-boundary.spec.js`:
  //
  // * baseline -> PASSES
  // * `localhost` kept, port replaced by a garbage 59999 -> STILL PASSES
  // * port correct, host changed to 127.0.0.1 -> FAILS
  //
  // The port is inert and the HOST carries the assertion, because CSP is enforced before any
  // network attempt. Rewriting the host to the dotted form "for consistency" turns the canary into
  // a same-origin fetch, which the policy allows, and the test silently stops testing anything.
  const canaryLine = source => source.split('\n').find(line => line.includes('csp-connect-canary')
    && line.includes('fetch('));

  it('still fetches a NON-self host, so the violation it asserts can still fire', async () => {
    const spec = await read('e2e/security-boundary.spec.js');
    const line = canaryLine(spec);
    expect(line).toBeDefined();
    expect(line).toContain('http://localhost:');
    expect(line, 'the canary host was normalised to 127.0.0.1 — it is now same-origin and CSP ALLOWS it')
      .not.toMatch(/fetch\(`?http:\/\/127\.0\.0\.1:/);
  });

  it('would notice the host being normalised', () => {
    // THE CONTROL. Run the same two expressions over a line that HAS been normalised, and require
    // them to reject it — otherwise the assertion above is satisfied by any line at all.
    const normalised = '    void fetch(`http://127.0.0.1:${UI_PORT}/csp-connect-canary`).catch(() => {});';
    expect(normalised).not.toContain('http://localhost:');
    expect(normalised).toMatch(/fetch\(`?http:\/\/127\.0\.0\.1:/);
  });
});
