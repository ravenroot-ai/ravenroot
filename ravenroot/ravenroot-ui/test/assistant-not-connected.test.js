// The state in which THE AUTHOR'S OWN CONNECTION is the thing that is missing, and the panel's
// answer to it.
//
// ── WHY THIS FILE IMPORTS ONLY PREEXISTING SESSION EXPORTS ─────────────────────────────────────
//
// This test must fail because the panel offers no way to connect, not because a constant is absent.
// Those are different failures with different diagnoses, and only the first is evidence about the
// product. An
// ES module that imports a name its target does not export fails at LINK time, before a single
// assertion runs, with "does not provide an export named 'NOT_LINKED'" — a report about this file,
// from which nobody could tell whether the panel offers a control or not.
//
// So this file imports only the preexisting `assistant-session.js` exports, writes the state the way
// THE SERVER SENDS IT, and compares against the wire string rather than the constant. Without
// connection-state handling, both assertions below run and both fail on behaviour: `deriveState`
// answers READY — the panel claims to be working while the author is not connected — and the
// shipped panel contains no control that could change that. `assistant-connection.test.js` holds
// everything that legitimately needs the new vocabulary.
import { readFile } from 'node:fs/promises';

import { describe, expect, it } from 'vitest';

import { INERT, deriveState } from '../src/assistant-session.js';

// What `GET /v1/assistant` answers when the deployment is whole — service present, provider
// profile configured, host allowlisted — and the author IS authenticated to Ravenroot, leaving one
// thing outstanding: this author's own connection to the provider.
const LINK_MISSING = Object.freeze({
  reachable: true, configured: true, allowlisted: true, signedIn: true, linkRequired: true,
});

// The wire value, written out rather than imported, for the reason in the header.
const NOT_LINKED_WIRE = 'not-linked';

const page = async () => {
  const html = await readFile('index.html', 'utf8');
  return new DOMParser().parseFromString(html, 'text/html');
};

describe('a deployment that says this author is not connected', () => {
  it('leaves the panel inert with a reason of its own, not ready', () => {
    const state = deriveState({ availability: LINK_MISSING });
    expect(state.state,
      'a panel that reads READY here accepts a message the server has already said it will not '
      + 'serve, and the author finds out by having their question refused')
      .toBe(INERT);
    expect(state.reason,
      'this is not the Ravenroot session and not an operator gap: pooling it onto one of those '
      + 'sends the author to fix something that is not broken')
      .toBe(NOT_LINKED_WIRE);
    expect(state.canCompose).toBe(false);
    expect(state.message.trim().length).toBeGreaterThan(40);
  });

  it('offers a control that starts the connection, in the shipped panel', async () => {
    const document_ = await page();
    const panel = document_.querySelector('.panel[data-panel-id="assistant"]');
    const connect = panel.querySelector('[data-action="connect-assistant"]');
    expect(connect,
      'the panel must ship a control the author can use to connect. Without one the state above '
      + 'is a dead end: it names a remedy and then offers no way to reach it, even though the '
      + 'mechanism exists server-side')
      .not.toBeNull();
    expect(connect.textContent.trim().length,
      'an unlabelled control is not a way to connect').toBeGreaterThan(0);
  });
});

// The other four states must NOT become this one. Written with literals for the same reason as
// above, and provoked from bodies rather than asserted off a table, so a precedence change reds.
describe('the reasons that are not a missing connection', () => {
  it.each([
    ['the service is absent', { reachable: false, linkRequired: true }, 'service-unavailable'],
    ['no provider is configured',
      { reachable: true, configured: false, linkRequired: true }, 'no-profile'],
    ['plaintext is refused',
      { reachable: true, configured: true, insecureRefused: true, linkRequired: true },
      'insecure-refused'],
    ['the provider host is not allowlisted',
      { reachable: true, configured: true, allowlisted: false, linkRequired: true },
      'host-not-allowlisted'],
    ['the Ravenroot session is not authenticated',
      { reachable: true, configured: true, allowlisted: true, signedIn: false, linkRequired: true },
      'not-signed-in'],
  ])('stays %s even when a connection is also outstanding', (_why, availability, expected) => {
    const state = deriveState({ availability });
    expect(state.reason,
      'the connection is evaluated last: connecting cannot help while something more fundamental '
      + 'is missing, and naming it would send the author to the wrong remedy')
      .toBe(expected);
    expect(state.reason).not.toBe(NOT_LINKED_WIRE);
  });

  it('is ready when nothing says a connection is required', () => {
    // The operator-key path remains the default: no `linkRequired` field
    // at all, and the panel behaves exactly as it did before that field existed.
    const state = deriveState({
      availability: { reachable: true, configured: true, allowlisted: true, signedIn: true },
    });
    expect(state.state).not.toBe(INERT);
    expect(state.canCompose).toBe(true);
  });
});
