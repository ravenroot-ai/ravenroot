// The connection vocabulary: which control appears for which state, what the panel may say about a
// connection in progress, and what it must still not say about custody.
//
// The red-first pair lives in `assistant-not-connected.test.js`, which imports only the preexisting
// session exports so that its failure is the absent CONTROL rather than an absent export. This file
// is the other half: it needs the connection vocabulary to check it, so it imports it.
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it, vi } from 'vitest';

import {
  ASSISTANT_PATHS,
  AssistantUnavailableError,
  RavenrootAssistantClient,
} from '../src/assistant-client.js';
import {
  CONNECTION_FAILURE_TEXT,
  CONNECTION_FAILURE_TOKENS,
  DEGRADED,
  ERROR,
  HOST_NOT_ALLOWLISTED,
  INSECURE_REFUSED,
  INERT,
  INERT_REASON_ORDER,
  INERT_REASON_TEXT,
  NOT_LINKED,
  NOT_SIGNED_IN,
  NO_PROFILE,
  READY,
  SERVICE_UNAVAILABLE,
  connectionFailureText,
  deriveState,
  offersConnection,
} from '../src/assistant-session.js';

// What `GET /v1/assistant` answers when the deployment is whole and the author is authenticated to
// Ravenroot, and the one remaining thing is this author's own connection to the provider.
const LINK_MISSING = Object.freeze({
  reachable: true, configured: true, allowlisted: true, signedIn: true, linkRequired: true,
});

const page = async () => {
  const html = await readFile('index.html', 'utf8');
  return new DOMParser().parseFromString(html, 'text/html');
};

const assistantPanel = document_ => document_.querySelector('.panel[data-panel-id="assistant"]');

// ── WHEN IT APPEARS, AND — THE HALF THAT IS EASY TO GET WRONG — WHEN IT DOES NOT ────────────────
//
// The generous mistake is to show "Connect" whenever the panel is inert. Three of the four other
// inert reasons are an OPERATOR's to fix, and the fourth is a Ravenroot session, so a connect
// button on any of them invites the author to perform an act that cannot resolve what is actually
// wrong — which is precisely what distinguishing the reasons was for.
describe('the control appears for exactly one reason', () => {
  it('appears when the author is not connected', () => {
    expect(offersConnection(deriveState({ availability: LINK_MISSING }))).toBe(true);
  });

  it.each([
    ['the service is absent', { reachable: false }, SERVICE_UNAVAILABLE],
    ['no provider is configured', { reachable: true, configured: false }, NO_PROFILE],
    ['plaintext is refused',
      { reachable: true, configured: true, insecureRefused: true }, INSECURE_REFUSED],
    ['the host is not allowlisted',
      { reachable: true, configured: true, allowlisted: false }, HOST_NOT_ALLOWLISTED],
    ['the Ravenroot session is not authenticated',
      { reachable: true, configured: true, allowlisted: true, signedIn: false }, NOT_SIGNED_IN],
  ])('does not appear when %s', (_why, availability, reason) => {
    const state = deriveState({ availability });
    expect(state.reason, 'the fixture must actually provoke the reason it claims to').toBe(reason);
    expect(offersConnection(state),
      `a connect control on ${reason} would invite the author to do something that cannot fix it`)
      .toBe(false);
  });

  it('does not appear when the panel is working', () => {
    const ready = deriveState({
      availability: { reachable: true, configured: true, allowlisted: true, signedIn: true },
    });
    expect(ready.state).toBe(READY);
    expect(offersConnection(ready)).toBe(false);
    const degraded = deriveState({
      availability: { reachable: true, configured: true, allowlisted: true, signedIn: true },
      context: { degraded: true },
    });
    expect(degraded.state).toBe(DEGRADED);
    expect(offersConnection(degraded)).toBe(false);
  });

  it('does not appear on a failed turn, which is not a connection problem', () => {
    const failed = deriveState({ error: { reason: null, message: 'the turn failed' } });
    expect(failed.state).toBe(ERROR);
    expect(offersConnection(failed)).toBe(false);
  });

  it('treats anything that is not a panel state as no offer, rather than as an offer', () => {
    for (const value of [undefined, null, {}, { state: INERT }, 'not-linked']) {
      expect(offersConnection(value)).toBe(false);
    }
  });
});

// ── THE FIFTH REASON TAKES ITS PLACE IN THE DECLARED ORDER, IT DOES NOT SIT BESIDE IT ───────────
describe('the reason ordering, extended rather than appended to by accident', () => {
  it('evaluates the connection last, after everything that would make connecting pointless', () => {
    expect(INERT_REASON_ORDER).toEqual([
      SERVICE_UNAVAILABLE, NO_PROFILE, INSECURE_REFUSED,
      HOST_NOT_ALLOWLISTED, NOT_SIGNED_IN, NOT_LINKED,
    ]);
  });

  // The order is a claim about precedence, so it is provoked rather than read off the constant: a
  // body that is missing several things at once must name the most fundamental one.
  it('names the operator gap first when the author is also unconnected', () => {
    const state = deriveState({
      availability: {
        reachable: true, configured: false, allowlisted: false, signedIn: false, linkRequired: true,
      },
    });
    expect(state.reason).toBe(NO_PROFILE);
  });

  it('names the Ravenroot session before the provider connection', () => {
    const state = deriveState({
      availability: {
        reachable: true, configured: true, allowlisted: true, signedIn: false, linkRequired: true,
      },
    });
    expect(state.reason).toBe(NOT_SIGNED_IN);
  });

  it('gives it a sentence of its own, distinct from the other four', () => {
    const sentences = INERT_REASON_ORDER.map(reason => INERT_REASON_TEXT[reason]);
    expect(new Set(sentences).size).toBe(INERT_REASON_ORDER.length);
    expect(INERT_REASON_TEXT[NOT_LINKED].length).toBeGreaterThan(40);
  });
});

// ── THE FIELD'S POLARITY IS THE COMPATIBILITY DECISION, SO IT IS PINNED ──────────────────────────
//
// `linkRequired` is named for the state that is NEW, so its absence reads as the behaviour that
// already shipped. The alternative name — `linked` — would have inverted this: every status body
// written before the field existed, and every API-key deployment, would read `linked: false` and go
// inert behind a connect button that resolves nothing. The operator-key path remains
// the default, and a default that breaks when a field is missing is not a default.
describe('a deployment that says nothing about connections', () => {
  it('is READY, exactly as it was before this field existed', () => {
    expect(deriveState({
      availability: { reachable: true, configured: true, allowlisted: true, signedIn: true },
    }).state).toBe(READY);
  });

  it.each([['a string', 'true'], ['a number', 1], ['null', null], ['undefined', undefined]])(
    'reads a non-boolean %s as no connection requirement', (_what, value) => {
      expect(deriveState({
        availability: {
          reachable: true, configured: true, allowlisted: true, signedIn: true, linkRequired: value,
        },
      }).state).toBe(READY);
    });
});

// ── THE OUTCOMES THE MECHANISM ALREADY DISTINGUISHES, KEPT DISTINCT HERE ────────────────────────
describe('what the panel can say about a connection in progress', () => {
  it('has a sentence for every outcome the device grant names, and no others', () => {
    expect([...CONNECTION_FAILURE_TOKENS].sort()).toEqual([
      'ACCESS_DENIED', 'AUTHORIZATION_PENDING', 'EXPIRED_TOKEN', 'SLOW_DOWN', 'UNAVAILABLE',
    ]);
  });

  it('says something different for each, because they need different things from the author', () => {
    const sentences = CONNECTION_FAILURE_TOKENS.map(token => CONNECTION_FAILURE_TEXT[token]);
    expect(new Set(sentences).size).toBe(sentences.length);
    for (const sentence of sentences) expect(sentence.length).toBeGreaterThan(20);
  });

  // These three outcomes are checked for the distinction that matters to the reader:
  // whether waiting helps, whether it is over, and whether starting again is the remedy.
  it('tells a refused author it is over, and an expired one to start again', () => {
    expect(CONNECTION_FAILURE_TEXT.ACCESS_DENIED).toMatch(/again|new|start/i);
    expect(CONNECTION_FAILURE_TEXT.EXPIRED_TOKEN).toMatch(/again|new|start/i);
    expect(CONNECTION_FAILURE_TEXT.ACCESS_DENIED)
      .not.toBe(CONNECTION_FAILURE_TEXT.EXPIRED_TOKEN);
  });

  it('does not map an unknown token onto a neighbour', () => {
    expect(connectionFailureText('SLOW_DOWN_A_BIT')).toBeNull();
    expect(connectionFailureText('')).toBeNull();
    expect(connectionFailureText(null)).toBeNull();
    expect(connectionFailureText('SLOW_DOWN')).toBe(CONNECTION_FAILURE_TEXT.SLOW_DOWN);
  });
});

// ── THE CUSTODY RULE COVERS EVERY CONNECTION-STATUS SENTENCE ────────────────────────────────────
//
// The rule is not "avoid the old wording"; it is that the panel says nothing about where the
// credential lives, because it cannot observe that. The connection sentences are the likeliest
// place to break it — they are written about a sign-in — so they are held to the same matchers as
// the inert reasons, in the same shape, including the control that proves the matchers can fire.
describe('the connection sentences claim nothing about custody', () => {
  const CUSTODY_CLAIMS = [
    /never holds/i, /on your behalf/i, /your own (model )?subscription/i, /api key/i,
    /device (flow|authorization)/i, /keychain/i, /we (do not|don.t) store/i,
  ];

  const EVERY_SENTENCE = [
    [NOT_LINKED, INERT_REASON_TEXT[NOT_LINKED]],
    ...CONNECTION_FAILURE_TOKENS.map(token => [token, CONNECTION_FAILURE_TEXT[token]]),
  ];

  it.each(EVERY_SENTENCE)('%s says nothing about credential custody', (_name, text) => {
    for (const claim of CUSTODY_CLAIMS) {
      expect(text, `"${text}" makes a custody claim the panel cannot observe`).not.toMatch(claim);
    }
  });

  it('has matchers that actually detect a custody claim', () => {
    const offending = 'Sign in with the device flow; Ravenroot never holds your API key.';
    expect(CUSTODY_CLAIMS.filter(claim => claim.test(offending)).length).toBeGreaterThan(2);
  });

  it('keeps the same silence in the shipped markup around the control', async () => {
    const panel = assistantPanel(await page());
    const region = panel.querySelector('#assistant-connection');
    expect(region).not.toBeNull();
    for (const claim of CUSTODY_CLAIMS) {
      expect(region.textContent, 'the connection region must not describe custody either')
        .not.toMatch(claim);
    }
  });
});

// ── THE VOCABULARY IS PINNED AGAINST THE SERVER'S OWN ENUM, NOT AGAINST ITSELF ──────────────────
//
// The same mechanism `assistant-session.test.js` uses for the eight send failures, applied to the
// five connection outcomes and for the same reason: a token added on one side and not the other
// must FAIL here rather than quietly render a sentence about a different outcome. The token names
// are the server's enum constants, so this reads the enum.
//
// `fileURLToPath` + `path.join` rather than `new URL(literal, import.meta.url)`, matching the
// existing reader in `assistant-session.test.js`. Only ENOENT means "absent": every other error
// propagates, so a breakage of the read mechanism reds this instead of turning it into a tautology.
const DEVICE_GRANT_SOURCE = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  '..', '..',
  'ravenroot-server/src/main/java/ai/ravenroot/server/assistant/oauth/AssistantDeviceAuthorization.java',
);

async function readDeviceGrantSource() {
  try {
    return await readFile(DEVICE_GRANT_SOURCE, 'utf8');
  } catch (error) {
    if (error && error.code === 'ENOENT') return null;
    throw error;
  }
}

describe('the panel and the mechanism name the same outcomes', () => {
  it('understands exactly the failures the device grant can end in', async () => {
    const source = await readDeviceGrantSource();
    if (source === null) return; // The server tree is genuinely absent; nothing to compare against.
    const enumBody = /public enum Failure \{([\s\S]*?)\n    \}/.exec(source);
    expect(enumBody, 'the Failure enum must be findable, or this test compares nothing')
      .not.toBeNull();
    const serverTokens = [...enumBody[1].matchAll(/^\s{8}([A-Z][A-Z_]+),?$/gm)]
      .map(match => match[1]);
    expect(serverTokens.length,
      'the enum scrape must find constants, or an empty set would trivially match').toBe(5);
    expect([...serverTokens].sort()).toEqual([...CONNECTION_FAILURE_TOKENS].sort());
  });
});

// ── THE CLIENT HALF: WHAT IT ASKS FOR, AND WHAT IT REFUSES TO RENDER ────────────────────────────
describe('the connection client', () => {
  const SERVICE = 'https://ravenroot.example';
  const jsonResponse = (status, body) => ({
    ok: status >= 200 && status < 300, status, json: async () => body,
  });
  const clientWith = fetchImpl => new RavenrootAssistantClient(SERVICE, { fetchImpl });

  const GRANT = Object.freeze({
    userCode: 'WDJB-MJHT',
    verificationUri: 'https://provider.example/device',
    verificationUriComplete: 'https://provider.example/device?user_code=WDJB-MJHT',
    interval: 5,
    expiresIn: 900,
  });

  it('asks this product, on the declared path, and never a provider', async () => {
    const fetchImpl = vi.fn(async () => jsonResponse(200, GRANT));
    await clientWith(fetchImpl).beginConnection();
    const [url, options] = fetchImpl.mock.calls[0];
    expect(String(url)).toBe(`${SERVICE}${ASSISTANT_PATHS.connection}`);
    expect(options.method).toBe('POST');
    // The panel's request carries no destination of its own. Where the exchange goes is the
    // operator's allowlisted configuration, decided in the JVM, and nothing here can influence it.
    expect(options.body).toBeUndefined();
  });

  it('returns what the author has to be shown', async () => {
    const grant = await clientWith(vi.fn(async () => jsonResponse(200, GRANT))).beginConnection();
    expect(grant.userCode).toBe('WDJB-MJHT');
    expect(grant.verificationUri).toBe('https://provider.example/device');
    expect(grant.interval).toBe(5);
  });

  // THE DEVICE CODE IS THE HALF THAT REDEEMS THE GRANT. The route does not send it; this asserts
  // that a route which started to would still not put it anywhere the panel could render.
  it('carries no field for the device code, even when one is sent', async () => {
    const grant = await clientWith(vi.fn(async () => jsonResponse(200,
      { ...GRANT, deviceCode: 'the-secret-half' }))).beginConnection();
    expect(JSON.stringify(grant)).not.toContain('the-secret-half');
    expect(Object.keys(grant).sort()).toEqual([
      'expiresIn', 'interval', 'userCode', 'verificationUri', 'verificationUriComplete',
    ]);
  });

  // Half an instruction is not an instruction. An author looking at a code with no address, or an
  // address with no code, cannot act — and a panel showing one of them looks like it is working.
  it.each([
    ['no code', { verificationUri: 'https://provider.example/device' }],
    ['no address', { userCode: 'WDJB-MJHT' }],
    ['neither', {}],
    ['a body it cannot read', null],
  ])('refuses to render a grant with %s', async (_what, body) => {
    await expect(clientWith(vi.fn(async () => jsonResponse(200, body))).beginConnection())
      .rejects.toBeInstanceOf(AssistantUnavailableError);
  });

  it('reads a refusal through the standard envelope, keeping its reference quotable', async () => {
    const fetchImpl = vi.fn(async () => jsonResponse(409, {
      code: 'UNKNOWN_RESOURCE', message: 'this deployment cannot begin a connection',
      correlationId: 'abc123',
    }));
    await expect(clientWith(fetchImpl).beginConnection()).rejects.toMatchObject({
      reason: 'service-unavailable',
      message: expect.stringContaining('abc123'),
    });
  });

  it.each([
    ['still waiting', { state: 'waiting', reason: 'AUTHORIZATION_PENDING' }, 'waiting'],
    ['told to slow down', { state: 'waiting', reason: 'SLOW_DOWN', retryAfter: 10 }, 'waiting'],
    ['finished', { state: 'linked' }, 'linked'],
    ['forgotten', { state: 'none' }, 'none'],
    ['something this build has no word for', { state: 'transcending' }, 'none'],
  ])('reports a connection that is %s', async (_what, body, expected) => {
    const progress = await clientWith(vi.fn(async () => jsonResponse(200, body)))
      .connectionProgress();
    expect(progress.state).toBe(expected);
  });

  it('honours the interval the server sends and refuses a nonsensical one', async () => {
    const slow = await clientWith(vi.fn(async () => jsonResponse(200,
      { state: 'waiting', reason: 'SLOW_DOWN', retryAfter: 10 }))).connectionProgress();
    expect(slow.retryAfter).toBe(10);
    // Zero is the dangerous one: coerced it becomes a poll with no interval at all.
    for (const retryAfter of [0, -5, '10', null]) {
      const answer = await clientWith(vi.fn(async () => jsonResponse(200,
        { state: 'waiting', reason: 'SLOW_DOWN', retryAfter }))).connectionProgress();
      expect(answer.retryAfter).toBeNull();
    }
  });

  it('carries an unrecognised reason without mapping it onto a neighbour', async () => {
    const progress = await clientWith(vi.fn(async () => jsonResponse(200,
      { state: 'waiting', reason: 'SLOW_DOWN_A_BIT' }))).connectionProgress();
    expect(progress.reason).toBe('SLOW_DOWN_A_BIT');
    expect(connectionFailureText(progress.reason)).toBeNull();
  });

  it('names an unreachable service rather than reporting a connection that failed', async () => {
    const fetchImpl = vi.fn(async () => { throw new Error('network down'); });
    await expect(clientWith(fetchImpl).beginConnection()).rejects.toMatchObject({
      reason: 'service-unavailable',
    });
  });
});

// ── THE CONNECTION REGION OBEYS THE PANEL'S EXISTING ACCESSIBILITY RULES ─────────────────────────
describe('the connection region as shipped', () => {
  it('is announced politely, like everything else in this panel', async () => {
    const panel = assistantPanel(await page());
    const region = panel.querySelector('#assistant-connection');
    expect(region.getAttribute('role')).toBe('status');
    expect(region.getAttribute('aria-live')).toBe('polite');
    expect(region.hasAttribute('hidden')).toBe(true);
  });

  it('ships the code and the address as named, empty elements rather than as placeholders', async () => {
    const document_ = await page();
    for (const id of ['assistant-connection-code', 'assistant-connection-uri']) {
      const node = document_.getElementById(id);
      expect(node, `#${id} must ship, so nothing has to be created mid-announcement`).not.toBeNull();
      expect(node.textContent.trim()).toBe('');
    }
    // The code is read aloud and typed by hand, so it is marked as the literal string it is.
    expect(document_.getElementById('assistant-connection-code').tagName.toLowerCase()).toBe('code');
  });

  it('is not a graph command, and does not become one by carrying a command id', async () => {
    const panel = assistantPanel(await page());
    expect(panel.querySelectorAll('[data-command-id]')).toHaveLength(0);
    const connect = panel.querySelector('[data-action="connect-assistant"]');
    expect(connect.hasAttribute('data-command-id')).toBe(false);
    expect(connect.getAttribute('type')).toBe('button');
  });
});
