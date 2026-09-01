import { readFile } from 'node:fs/promises';

import { describe, expect, it, vi } from 'vitest';

import {
  API_KEY_SCHEME,
  BASIC_SCHEME,
  CREDENTIAL_PATHS,
  CredentialError,
  CredentialLeakError,
  OAUTH_TOKEN_SCHEME,
  RavenrootCredentialClient,
  credentialUrl,
  normalizeCredential,
} from '../src/credential-client.js';

function jsonResponse(body, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: async () => body };
}

const REFERENCE = 'rrc_0123456789abcdef0123456789abcdef';
const VALUE = 'sk-the-actual-secret-nobody-may-see-again';

const STORED = {
  reference: REFERENCE, label: 'Weather API', scheme: API_KEY_SCHEME,
  username: '', createdAt: '2026-08-28T09:15:00Z',
};

// ── THE DESTINATION SET IS CLOSED ────────────────────────────────────────────────────────────────
//
// Exported and exercised directly rather than only through the two callers that already obey it — a
// control reachable only via compliant callers is a control whose failure mode (someone widening it)
// no test can reach. Same reasoning as `assistantUrl` in `assistant-client.js`.
describe('the path control', () => {
  it('permits the one route this client has', () => {
    expect(credentialUrl('', CREDENTIAL_PATHS.credentials)).toBe('/v1/credentials');
    expect(credentialUrl('https://ravenroot.test', CREDENTIAL_PATHS.credentials))
      .toBe('https://ravenroot.test/v1/credentials');
  });

  it.each([
    'https://provider.attacker.example',
    '/v1/executions',
    '/v1/credentials/../../admin',
    // The API exposes no per-credential route: a path carrying a reference is not "not built yet",
    // it is not permitted.
    `/v1/credentials/${REFERENCE}`,
    '/v1/credentials?value=leak',
  ])('refuses %s', path => {
    expect(() => credentialUrl('', path)).toThrow(/not permitted/);
  });
});

describe('listing the credentials an author holds', () => {
  it('asks the fixed route, omits browser credentials, and never caches the answer', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse({ credentials: [STORED] }));
    await new RavenrootCredentialClient('', { fetchImpl }).list();

    expect(fetchImpl.mock.calls[0][0]).toBe('/v1/credentials');
    expect(fetchImpl.mock.calls[0][1]).toEqual(expect.objectContaining({
      method: 'GET', credentials: 'omit', cache: 'no-store',
    }));
  });

  it('carries the Ravenroot session token when one is held, and omits the header when none is', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse({ credentials: [] }));
    await new RavenrootCredentialClient('', { fetchImpl }).list();
    expect(fetchImpl.mock.calls[0][1].headers).not.toHaveProperty('Authorization');

    await new RavenrootCredentialClient('', {
      fetchImpl, tokenProvider: { getAccessToken: () => 'session-token' },
    }).list();
    expect(fetchImpl.mock.calls[1][1].headers.Authorization).toBe('Bearer session-token');
  });

  it('projects exactly the five fields the route documents', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse({ credentials: [STORED] }));
    const listing = await new RavenrootCredentialClient('', { fetchImpl }).list();

    expect(listing.surface).toBe('present');
    expect(listing.credentials).toEqual([{
      reference: REFERENCE, label: 'Weather API', scheme: API_KEY_SCHEME,
      username: '', createdAt: '2026-08-28T09:15:00Z',
    }]);
  });

  it('reads a 404 or a 501 as "this deployment does not offer credential storage", not as a failure',
    async () => {
      for (const status of [404, 501]) {
        const fetchImpl = vi.fn().mockResolvedValue({ ok: false, status, json: async () => ({}) });
        const listing = await new RavenrootCredentialClient('', { fetchImpl }).list();
        expect(listing.surface).toBe('absent');
        expect(listing.credentials).toEqual([]);
      }
    });

  it('raises the API-01 envelope prose, and keeps the machine-readable members with it', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse({
      contract: 'ravenroot.api/1', code: 'UNAUTHORIZED', message: 'Authenticate first.',
      correlationId: 'corr-7',
    }, 401));

    await expect(new RavenrootCredentialClient('', { fetchImpl }).list())
      .rejects.toMatchObject({
        name: 'CredentialError', status: 401, code: 'UNAUTHORIZED',
        message: 'Authenticate first.', correlationId: 'corr-7',
      });
  });

  it('names a transport failure without repeating the browser\'s non-diagnosis', async () => {
    const fetchImpl = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));
    const failure = await new RavenrootCredentialClient('', { fetchImpl }).list()
      .then(() => null, error => error);

    expect(failure).toBeInstanceOf(CredentialError);
    expect(failure.reason).toBe('NO_HTTP_STATUS');
    expect(failure.message).not.toContain('Failed to fetch');
  });
});

// ── THE VALUE GOES ONE WAY, IN A BODY ────────────────────────────────────────────────────────────
describe('storing a credential', () => {
  it('puts the value in the request body and nothing whatever in the URL', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse(STORED, 201));
    await new RavenrootCredentialClient('https://ravenroot.test', { fetchImpl })
      .create({ label: 'Weather API', scheme: API_KEY_SCHEME, value: VALUE });

    const [url, options] = fetchImpl.mock.calls[0];
    // A value in a URL lands in access logs, in `Referer` and in browser history, and no transport
    // security removes it from any of the three.
    expect(url).toBe('https://ravenroot.test/v1/credentials');
    expect(url).not.toContain(VALUE);
    expect(options.method).toBe('POST');
    expect(JSON.parse(options.body).value).toBe(VALUE);
  });

  it('sends `username` for basic and omits the member entirely for the other two kinds', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse(STORED, 201));
    const client = new RavenrootCredentialClient('', { fetchImpl });

    await client.create({ label: 'Registry', scheme: BASIC_SCHEME, username: 'ada', value: VALUE });
    expect(JSON.parse(fetchImpl.mock.calls[0][1].body).username).toBe('ada');

    // Not `''` — ABSENT. The service refuses a `username` member on a non-basic credential, so
    // sending an empty one would be refused just as loudly as sending a real one.
    for (const scheme of [API_KEY_SCHEME, OAUTH_TOKEN_SCHEME]) {
      await client.create({ label: 'K', scheme, username: 'left-over-from-a-kind-change', value: VALUE });
      const body = JSON.parse(fetchImpl.mock.calls.at(-1)[1].body);
      expect(Object.hasOwn(body, 'username')).toBe(false);
    }
  });

  it('never proposes a reference, whatever the caller left on the draft', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse(STORED, 201));
    // Every member the server answers 400 to, all at once: the projection is what makes this
    // impossible to send by accident, not a rule someone has to remember.
    await new RavenrootCredentialClient('', { fetchImpl }).create({
      label: 'Weather API', scheme: API_KEY_SCHEME, value: VALUE,
      reference: 'rrc_proposed', id: 'proposed', credentialRef: 'proposed',
      credentialReference: 'proposed', name: 'proposed', apiKey: 'another-secret',
    });

    const body = JSON.parse(fetchImpl.mock.calls[0][1].body);
    expect(Object.keys(body).sort()).toEqual(['label', 'scheme', 'value']);
  });

  it('returns the created credential WITHOUT any value, and renders nothing that could be one', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse(STORED, 201));
    const created = await new RavenrootCredentialClient('', { fetchImpl })
      .create({ label: 'Weather API', scheme: API_KEY_SCHEME, value: VALUE });

    expect(created).toEqual({
      reference: REFERENCE, label: 'Weather API', scheme: API_KEY_SCHEME,
      username: '', createdAt: '2026-08-28T09:15:00Z',
    });
    // The strongest form of the assertion: nothing anywhere in what a caller could render contains
    // the value that was just sent.
    expect(JSON.stringify(created)).not.toContain(VALUE);
  });

  it('refuses to hand back a creation answer that echoes the value', async () => {
    // A server defect, not a client one — which is exactly why it must be loud here rather than
    // absorbed. The projection alone would already drop it; the throw is what makes it visible.
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse({ ...STORED, value: VALUE }, 201));

    await expect(new RavenrootCredentialClient('', { fetchImpl })
      .create({ label: 'Weather API', scheme: API_KEY_SCHEME, value: VALUE }))
      .rejects.toBeInstanceOf(CredentialLeakError);
  });

  it.each(['value', 'secret', 'apiKey', 'api_key', 'password', 'accessToken', 'privateKey', 'token'])(
    'refuses to render a listing carrying %s', key => {
      expect(() => normalizeCredential({ ...STORED, [key]: 'leaked' }))
        .toThrow(CredentialLeakError);
    });

  it('reads a 404 or a 501 on the write as "no such surface" rather than as a rejected credential',
    async () => {
      for (const status of [404, 501]) {
        const fetchImpl = vi.fn().mockResolvedValue({ ok: false, status, json: async () => ({}) });
        const failure = await new RavenrootCredentialClient('', { fetchImpl })
          .create({ label: 'K', scheme: API_KEY_SCHEME, value: VALUE })
          .then(() => null, error => error);
        expect(failure).toBeInstanceOf(CredentialError);
        expect(failure.reason).toBe('SURFACE_ABSENT');
      }
    });

  it('surfaces the service\'s own refusal prose without ever quoting the request', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse({
      code: 'INVALID_REQUEST', message: 'A basic credential needs a username.',
    }, 400));
    const failure = await new RavenrootCredentialClient('', { fetchImpl })
      .create({ label: 'K', scheme: BASIC_SCHEME, username: 'ada', value: VALUE })
      .then(() => null, error => error);

    expect(failure.message).toBe('A basic credential needs a username.');
    expect(failure.message).not.toContain(VALUE);
  });
});

// ── THE STRUCTURAL RULES, READ OFF THE SOURCE ────────────────────────────────────────────────────
//
// Deliberately coarse — the token may not appear in the file AT ALL, comments included — because a
// search that has to tell code from prose is a search that can be talked around, and there is no
// reason this particular file needs to discuss the subject in a spelling a machine cannot check.
describe('the file\'s own rules', () => {
  it('contains no logging of any kind, and stores nothing on the instance', async () => {
    const source = await readFile('src/credential-client.js', 'utf8');

    expect(source).not.toContain('console');
    expect(source).not.toContain('debugger');
    // Rule 3: the draft is projected into a body and dropped. No field of the class holds a value.
    const client = new RavenrootCredentialClient('', { fetchImpl: vi.fn() });
    expect(Object.keys(client).sort()).toEqual(['baseUrl', 'fetchImpl', 'tokenProvider']);
  });

  it('refuses a base URL that is not HTTP(S), before any request can be built', () => {
    expect(() => new RavenrootCredentialClient('javascript:alert(1)')).toThrow(/HTTP\(S\)/);
  });
});
