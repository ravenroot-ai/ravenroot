import { readFile } from 'node:fs/promises';

import { describe, expect, it, vi } from 'vitest';

import { catalogEmptyState } from '../src/catalog-empty-state.js';
import {
  RavenrootRuntimeClient,
  RuntimeAuthorizationError,
  RuntimeRequestError,
  memoryTokenProvider,
} from '../src/runtime-client.js';

// The catalog a loopback server serves when it requires no authentication. Three behaviors, because
// the reported defect was a palette that came back EMPTY on a loopback deployment -- the names do not
// matter to that defect, but they must be names the product actually serves.
//
// This fixture is hand-written rather than read from a server, so stale behavior names can leave it
// green while asserting the opposite of what ships. It therefore uses `delay` and `json-path`, the
// same shipped behaviors as `e2e/node-catalog-loopback.spec.js`.
const LOOPBACK_CATALOG = [
  { behavior: 'template', displayName: 'Template', category: 'core', description: 'Renders a template', visualType: 'flow', agentic: false, capabilities: [], properties: [] },
  { behavior: 'delay', displayName: 'Delay', category: 'core', description: 'Waits', visualType: 'flow', agentic: false, capabilities: ['control-flow'], properties: [] },
  { behavior: 'json-path', displayName: 'JSONPath', category: 'core', description: 'Selects matches', visualType: 'flow', agentic: false, capabilities: ['deterministic'], properties: [] },
];

function jsonResponse(body, status = 200) {
  // `#json` now reads the body as text first (so a non-JSON error page still yields a
  // typed error), so a fake response needs `.text()` too, not just `.json()`.
  return { ok: status >= 200 && status < 300, status, text: async () => JSON.stringify(body), json: async () => body };
}

describe('node catalog availability is discovered from the service, not assumed by the client', () => {
  it('asks the service for the catalog even when no access token is held', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse(LOOPBACK_CATALOG));
    const client = new RavenrootRuntimeClient('', { fetchImpl });

    await client.nodeTypes();

    // The defect: today this request never departs, so the palette empties without the service
    // ever being asked whether it requires authentication at all.
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    expect(fetchImpl.mock.calls[0][0]).toBe('/v1/node-types');
    expect(fetchImpl.mock.calls[0][1].headers).not.toHaveProperty('Authorization');
    expect(fetchImpl.mock.calls[0][1]).toEqual(expect.objectContaining({
      credentials: 'omit',
      cache: 'no-store',
    }));
  });

  it('returns the complete loopback catalog, in the order the server sent it', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse(LOOPBACK_CATALOG));
    const client = new RavenrootRuntimeClient('', { fetchImpl });

    const catalog = await client.nodeTypes();

    // Every entry, unreordered and unfiltered: the defect was a palette that showed nothing, so what
    // is pinned is that the client hands on what it received rather than which names it received.
    expect(catalog.map(type => type.behavior)).toEqual(['template', 'delay', 'json-path']);
    expect(catalog).toHaveLength(LOOPBACK_CATALOG.length);
    expect(catalogEmptyState(null, catalog).kind).toBe('available');
  });

  it('opens the live event stream without a token instead of refusing it client-side', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({ ok: false, status: 404 });
    const states = [];
    const client = new RavenrootRuntimeClient('', {
      fetchImpl,
      maxRetries: 0,
      sleep: vi.fn(async () => {}),
    });

    client.connect(() => {}, (status, message) => states.push([status, message]));
    await vi.waitFor(() => expect(states.at(-1)?.[0]).toBe('error'));

    expect(fetchImpl).toHaveBeenCalled();
    expect(fetchImpl.mock.calls[0][0]).toBe('/v1/events?include=diagnostics');
    expect(states.map(([status]) => status)).not.toContain('authentication-required');
  });

  // ── PRESERVATION CONTROL: A REAL 401 RETAINS ITS AUTHENTICATION BEHAVIOR ────────────────────

  it('keeps the typed error, the message and the token clearing of a real 401', async () => {
    const provider = memoryTokenProvider('live-token');
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse({ error: 'unauthorized' }, 401));
    const client = new RavenrootRuntimeClient('', { fetchImpl, tokenProvider: provider });

    const error = await client.nodeTypes().catch(caught => caught);

    expect(error).toBeInstanceOf(RuntimeAuthorizationError);
    expect(error.status).toBe(401);
    expect(error.message).toBe('Authentication expired');
    expect(await provider.getAccessToken()).toBe('');
  });

  it('keeps the connection state of a real 401 on the event stream', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({ ok: false, status: 401 });
    const states = [];
    const client = new RavenrootRuntimeClient('', {
      fetchImpl,
      accessToken: 'live-token',
      sleep: vi.fn(async () => {}),
    });

    client.connect(() => {}, (status, message) => states.push([status, message]));
    await vi.waitFor(() => expect(states.at(-1)?.[0]).toBe('authentication-required'));

    expect(states.at(-1)).toEqual(['authentication-required', 'Authentication expired']);
  });

  it('keeps a real 403 terminal and typed', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse({ error: 'denied' }, 403));
    const client = new RavenrootRuntimeClient('', { fetchImpl, accessToken: 'live-token' });

    const error = await client.nodeTypes().catch(caught => caught);

    expect(error).toBeInstanceOf(RuntimeAuthorizationError);
    expect(error.status).toBe(403);
    expect(error.message).toBe('Access revoked');
  });
});

describe('the empty palette distinguishes an unauthorised service from an unreachable one', () => {
  it('reports a 401 as authentication required, naming the service as the source of the refusal', () => {
    const state = catalogEmptyState(new RuntimeAuthorizationError('Authentication expired', 401), []);

    expect(state.kind).toBe('authentication-required');
    expect(state.message).toMatch(/401/);
    expect(state.message).toMatch(/authentication/i);
    expect(state.message).not.toMatch(/unreachable/i);
  });

  it('reports a transport failure as unreachable rather than as an authorization problem', () => {
    const state = catalogEmptyState(new TypeError('Failed to fetch'), []);

    expect(state.kind).toBe('unreachable');
    expect(state.message).toMatch(/unreachable/i);
    expect(state.message).not.toMatch(/401/);
    expect(state.message).not.toMatch(/authentication is required/i);
  });

  // `error.status` being a number means a response WAS received (a 404, a
  // 500 -- anything not 401/403), so a sentence attributing silence to the service would contradict
  // the reason text that already says the request reached a server.
  it('does not say the service was silent when the service actually answered with an error', () => {
    const responded = new Error('not found (HTTP 404 GET /v1/node-types) -- check the runtime '
      + 'service address: the request reached a server but not an API route');
    responded.status = 404;

    const state = catalogEmptyState(responded, []);

    expect(state.kind).toBe('unreachable');
    expect(state.message).not.toMatch(/did not answer/i);
  });

  // A status-less error (the fetch promise itself rejected) is NOT proof the
  // service was silent -- the established example is BrowserOriginPolicy answering 403 with a JSON
  // body the browser withholds from the page for lacking Access-Control-Allow-Origin, which surfaces
  // as an indistinguishable rejected promise. The composed message must therefore make no claim
  // about what the service did, in either direction, and it must not carry two sentences that
  // contradict each other -- the actual defect this finding was opened for.
  it('makes no claim about the service on a rejected fetch, and never contradicts itself', () => {
    const rejected = new RuntimeRequestError('Failed to fetch', { method: 'GET', path: '/v1/node-types' });
    expect(rejected.status).toBeNull();

    const state = catalogEmptyState(rejected, []);

    expect(state.kind).toBe('unreachable');
    expect(state.message).not.toMatch(/did not answer/i);
    expect(state.message).not.toMatch(/service (was|is) silent/i);
    expect(state.message).not.toMatch(/no answer/i);
    // The forbidden pairing: an enumerated hint ("...or whether it allows this origin") sitting next
    // to a flat denial that any response arrived. If the hint text is present at all, the denial must
    // not be, in the same composed string a user actually reads.
    if (/whether it allows this origin/i.test(state.message)) {
      expect(state.message).not.toMatch(/did not answer|was silent|no response/i);
    }
  });

  it('gives the two failures different text, so one is never mistaken for the other', () => {
    const unauthorised = catalogEmptyState(new RuntimeAuthorizationError('Authentication expired', 401), []);
    const unreachable = catalogEmptyState(new TypeError('Failed to fetch'), []);
    const revoked = catalogEmptyState(new RuntimeAuthorizationError('Access revoked', 403), []);

    expect(new Set([unauthorised.message, unreachable.message, revoked.message]).size).toBe(3);
    expect(new Set([unauthorised.kind, unreachable.kind, revoked.kind]).size).toBe(3);
  });

  it('separates "not connected yet" and "the service answered with an empty catalog" from a failure', () => {
    expect(catalogEmptyState(null, null).kind).toBe('disconnected');
    expect(catalogEmptyState(null, []).kind).toBe('empty');
    expect(catalogEmptyState(null, []).message).not.toMatch(/unreachable|401/i);
  });

  // ── THE PAGE CONNECTS BY ITSELF, SO "IN FLIGHT" IS A STATE THE USER CAN SEE ──────────────

  it('says the connection is under way instead of asking for one that is already happening', () => {
    const state = catalogEmptyState(null, null, true);

    expect(state.kind).toBe('connecting');
    expect(state.message).toMatch(/connecting/i);
    // The call to action belongs to the state where the user still has something to do.
    expect(state.message).not.toBe(catalogEmptyState(null, null).message);
    expect(catalogEmptyState(null, null, false).kind).toBe('disconnected');
  });

  it('keeps an answer authoritative over the in-flight state, so the attempt cannot mask a result', () => {
    // A late `pending` flag must never repaint a 401, a transport failure or a delivered catalog.
    expect(catalogEmptyState(new RuntimeAuthorizationError('Authentication expired', 401), [], true).kind)
      .toBe('authentication-required');
    expect(catalogEmptyState(new TypeError('Failed to fetch'), [], true).kind).toBe('unreachable');
    expect(catalogEmptyState(null, [], true).kind).toBe('empty');
    expect(catalogEmptyState(null, LOOPBACK_CATALOG, true).kind).toBe('available');
  });

  it('gives the in-flight state a kind of its own, distinct from every terminal state', () => {
    const kinds = [
      catalogEmptyState(null, null, true).kind,
      catalogEmptyState(null, null).kind,
      catalogEmptyState(null, []).kind,
      catalogEmptyState(new RuntimeAuthorizationError('Authentication expired', 401), []).kind,
      catalogEmptyState(new RuntimeAuthorizationError('Access revoked', 403), []).kind,
      catalogEmptyState(new TypeError('Failed to fetch'), []).kind,
    ];

    expect(new Set(kinds).size).toBe(kinds.length);
  });
});

describe('the page attempts the connection at load rather than assuming it may not', () => {
  it('calls connectRuntime from the load handler, with no flag and no environment variable gating it', async () => {
    const app = await readFile('src/app.js', 'utf8');
    const boot = app.slice(app.indexOf("window.addEventListener('load'"));

    expect(boot).toContain('connectRuntime(true)');
    // The attempt must not be conditional on anything the build or the environment decides.
    expect(boot).not.toMatch(/import\.meta\.env|process\.env/);
  });

  it('does not hand the change listener an Event where connectRuntime expects its boot flag', async () => {
    const app = await readFile('src/app.js', 'utf8');

    expect(app).not.toContain("addEventListener('change', connectRuntime)");
    expect(app).toContain("addEventListener('change', () => connectRuntime())");
  });
});

describe('the client no longer compiles an authentication assumption into the bundle', () => {
  it('has no protected-mode constant and no pre-emptive authorization throw', async () => {
    const [app, runtime] = await Promise.all([
      readFile('src/app.js', 'utf8'),
      readFile('src/runtime-client.js', 'utf8'),
    ]);

    expect(runtime).not.toContain('protectedMode');
    expect(app).not.toContain('protectedMode');
    expect(runtime).not.toContain("RuntimeAuthorizationError('Authentication required'");
    // The server's gate is untouched: the client still surfaces what the service answered.
    expect(runtime).toContain("'Authentication expired'");
    expect(runtime).toContain("'Access revoked'");
  });
});
