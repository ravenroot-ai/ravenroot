import { readFile } from 'node:fs/promises';

import { describe, expect, it, vi } from 'vitest';

import {
  ASSISTANT_PATHS,
  AssistantUnavailableError,
  RavenrootAssistantClient,
  assistantUrl,
  failureFromEnvelope,
} from '../src/assistant-client.js';
import { composeContext } from '../src/assistant-context.js';
import {
  ASSISTANT_FAILURE_TEXT,
  ASSISTANT_FAILURE_TOKENS,
  inertReason,
} from '../src/assistant-session.js';

const SERVICE = 'https://ravenroot.example';

const jsonResponse = (status, body) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
});

const clientWith = (fetchImpl, options = {}) =>
  new RavenrootAssistantClient(SERVICE, { fetchImpl, ...options });

const urlsCalled = fetchImpl => fetchImpl.mock.calls.map(([url]) => String(url));

// ── THE CONTROL: NO PROVIDER CALL EVER ORIGINATES IN THE BROWSER ─────────────────────────────────
//
// This is not a preference and not a comment. Browser-originated egress is invisible to the
// JVM-wide guard, and a provider token reachable from page script is one injection
// away from subscription theft in a UI that renders user-authored graph content. The control is
// that the destination is not a parameter: every request goes through `#url`, which accepts only
// the two module-level path constants.
describe('the panel cannot reach anything but its own Ravenroot service', () => {
  it('sends every request to the configured service origin and nowhere else', async () => {
    const fetchImpl = vi.fn(async () => jsonResponse(200, {
      configured: true, allowlisted: true, signedIn: true, provider: 'Example Model',
    }));
    const client = clientWith(fetchImpl);
    await client.status();
    await client.send({ prompt: 'hello', context: composeContext({}) });

    expect(urlsCalled(fetchImpl)).toEqual([
      `${SERVICE}${ASSISTANT_PATHS.status}`,
      `${SERVICE}${ASSISTANT_PATHS.messages}`,
    ]);
  });

  // A hostile or merely wrong status response must not be able to move the panel's destination.
  // This is the test that reds if someone lets the server name its own endpoint.
  it('a hostile status response cannot redirect the panel', async () => {
    const fetchImpl = vi.fn(async () => jsonResponse(200, {
      configured: true,
      allowlisted: true,
      signedIn: true,
      // Every shape an implementation might be tempted to follow.
      provider: 'https://provider.attacker.example/v1/messages',
      providerEndpoint: 'https://provider.attacker.example/v1/messages',
      endpoint: 'https://provider.attacker.example',
      baseUrl: 'https://provider.attacker.example',
      href: 'https://provider.attacker.example',
    }));
    const client = clientWith(fetchImpl);
    const status = await client.status();
    await client.send({ prompt: 'hello', context: composeContext({}) });

    expect(urlsCalled(fetchImpl)).toEqual([
      `${SERVICE}${ASSISTANT_PATHS.status}`,
      `${SERVICE}${ASSISTANT_PATHS.messages}`,
    ]);
    expect(urlsCalled(fetchImpl).some(url => url.includes('attacker'))).toBe(false);
    // The provider name survives as a LABEL, which is all it may ever be.
    expect(status.provider).toBe('https://provider.attacker.example/v1/messages');
  });

  // The guard itself, exercised directly rather than only through the two callers that obey it.
  // Deleting the check in `assistantUrl` reds every case below.
  it.each([
    ['an absolute provider URL', 'https://provider.attacker.example/v1/messages'],
    ['a protocol-relative host', '//provider.attacker.example/v1/messages'],
    ['a traversal out of the namespace', '/v1/assistant/../../v1/executions'],
    ['a plausible neighbour route', '/v1/assistant/tools'],
    ['an empty path', ''],
    ['the service root', '/'],
  ])('refuses %s as a destination', (_label, path) => {
    expect(() => assistantUrl(SERVICE, path)).toThrow(/not permitted/);
  });

  it('permits exactly the declared paths and builds them onto the service base', () => {
    expect(assistantUrl(SERVICE, ASSISTANT_PATHS.status)).toBe(`${SERVICE}/v1/assistant`);
    expect(assistantUrl(SERVICE, ASSISTANT_PATHS.messages)).toBe(`${SERVICE}/v1/assistant/messages`);
    expect(assistantUrl(SERVICE, ASSISTANT_PATHS.connection))
      .toBe(`${SERVICE}/v1/assistant/connection`);
  });

  // Exactly three destinations are declared. The point is not the number: every destination this
  // panel can reach is written out here, on THIS product,
  // so a route that named a provider — or that let a response field name its own endpoint — has to
  // be added to this list by hand before it can be requested at all.
  it('declares exactly three destinations, all on this product', () => {
    expect(Object.values(ASSISTANT_PATHS)).toEqual([
      '/v1/assistant', '/v1/assistant/messages', '/v1/assistant/connection',
    ]);
    expect(Object.values(ASSISTANT_PATHS).every(path => path.startsWith('/v1/assistant'))).toBe(true);
    expect(Object.isFrozen(ASSISTANT_PATHS)).toBe(true);
  });

  // THE HOST HALF OF THE CONTROL. `assistantUrl` validates the path and passes `baseUrl` through
  // untouched, so "no provider call originates in the browser" rests on the assistant client only
  // ever being constructed with the Ravenroot service URL the rest of the UI already uses. That is
  // a property of the CALL SITE, so the call site is what this pins — asserted against the source,
  // the same way the panel's markup guarantees are.
  it('is constructed exactly once, from the same base URL as the runtime client', async () => {
    const app = await readFile('src/app.js', 'utf8');
    const sites = [...app.matchAll(/new RavenrootAssistantClient\(([^,)]*)/g)].map(m => m[1].trim());
    expect(sites).toEqual(['baseUrl']);

    // And `baseUrl` there is the very same expression handed to the runtime client, so the panel
    // cannot acquire a destination of its own without that becoming visible in this test.
    const runtimeSites = [...app.matchAll(/new RavenrootRuntimeClient\(([^,)]*)/g)].map(m => m[1].trim());
    expect(runtimeSites).toEqual(['baseUrl']);

    // No provider host is named anywhere in the UI source, which is the blunt version of the claim.
    expect(app).not.toMatch(/https?:\/\/[^\s'"`]*(anthropic|openai|googleapis|api\.)/i);
  });

  it('carries the Ravenroot session token, never a provider credential', async () => {
    const fetchImpl = vi.fn(async () => jsonResponse(200, {}));
    await clientWith(fetchImpl, { tokenProvider: { getAccessToken: () => 'ravenroot-session' } }).status();
    expect(fetchImpl.mock.calls[0][1].headers.Authorization).toBe('Bearer ravenroot-session');
    // The user's own product authentication is what makes "a denial to the user is a denial to the
    // panel" true: the same bearer the rest of the UI uses, so the same authorization applies.
    expect(fetchImpl.mock.calls[0][1].credentials).toBe('omit');
  });
});

// ── THE HONEST ABSENT-BACKEND BEHAVIOUR ──────────────────────────────────────────────────────────
//
// There is no `/v1/assistant` route in this repository. The panel must say so, not simulate.
describe('when the assistant service does not exist', () => {
  it.each([404, 501])('reports HTTP %s as unreachable rather than as a sign-in problem', async status => {
    const availability = await clientWith(vi.fn(async () => jsonResponse(status, {}))).status();
    expect(availability.reachable).toBe(false);
    expect(availability.signedIn).toBe(false);
    expect(availability.detail).toMatch(/does not provide the assistant service/);
  });

  it('reports a transport failure as unreachable rather than throwing at the panel', async () => {
    const availability = await clientWith(vi.fn(async () => { throw new Error('ECONNREFUSED'); })).status();
    expect(availability.reachable).toBe(false);
    expect(availability.detail).toMatch(/ECONNREFUSED/);
  });

  // A panel must never appear to work by echoing the prompt.
  it('NEVER synthesizes a reply — send throws instead of returning an answer', async () => {
    const client = clientWith(vi.fn(async () => jsonResponse(404, {})));
    await expect(client.send({ prompt: 'what is happening?', context: composeContext({}) }))
      .rejects.toThrow(AssistantUnavailableError);
    await expect(client.send({ prompt: 'what is happening?', context: composeContext({}) }))
      .rejects.toThrow(/was not sent/);
  });

  it('names the reason on the failure, so the panel can settle into the right inert state', async () => {
    const client = clientWith(vi.fn(async () => jsonResponse(404, {})));
    await client.send({ prompt: 'x', context: composeContext({}) })
      .catch(error => expect(error.reason).toBe('service-unavailable'));
  });
});

describe('reading the deployment status', () => {
  it('treats 401 and 403 as signed-out, which is a different fact from absent', async () => {
    for (const status of [401, 403]) {
      const availability = await clientWith(vi.fn(async () => jsonResponse(status, {}))).status();
      expect(availability.reachable).toBe(true);
      expect(availability.signedIn).toBe(false);
    }
  });

  it('reads a missing or non-boolean field as FALSE, never optimistically as true', async () => {
    const availability = await clientWith(vi.fn(async () => jsonResponse(200, {
      configured: 'yes', allowlisted: 1, signedIn: 'true',
    }))).status();
    expect(availability).toMatchObject({ configured: false, allowlisted: false, signedIn: false });
  });

  it('treats an unreadable body as unreachable rather than as ready', async () => {
    const availability = await clientWith(vi.fn(async () => ({
      ok: true, status: 200, json: async () => { throw new Error('not json'); },
    }))).status();
    expect(availability.reachable).toBe(false);
  });
});

describe('what actually leaves the browser', () => {
  it('sends only the classes the chips declared attached', async () => {
    const fetchImpl = vi.fn(async () => jsonResponse(200, { text: 'ok' }));
    const context = composeContext({
      graph: () => ({ nodeCount: 1 }),
      catalog: () => { throw new Error('offline'); },
    });
    await clientWith(fetchImpl).send({ prompt: 'q', context });

    const body = JSON.parse(fetchImpl.mock.calls[0][1].body);
    expect(Object.keys(body.context)).toEqual(['graph']);
    expect(body.attached).toEqual(['graph']);
    // The class whose chip said unavailable is absent from the wire, not merely absent from the UI.
    expect(JSON.stringify(body)).not.toContain('offline');
  });

  it('drops a payload key that no context class corresponds to', async () => {
    const fetchImpl = vi.fn(async () => jsonResponse(200, { text: 'ok' }));
    await clientWith(fetchImpl).send({
      prompt: 'q',
      // A payload that acquired an undeclared key would be undisclosed egress: no chip describes
      // it, so the user was never told.
      context: { payload: { graph: { a: 1 }, secrets: 'hunter2' }, classes: [] },
    });
    const body = JSON.parse(fetchImpl.mock.calls[0][1].body);
    expect(Object.keys(body.context)).toEqual(['graph']);
    expect(JSON.stringify(body)).not.toContain('hunter2');
  });

  // The only path that could ever have produced an assistant-labelled turn the service did not
  // author. A 200 whose body will not parse used to fall through to `text: ''`.
  it('rejects a 2xx whose body cannot be read, rather than answering with nothing', async () => {
    const client = clientWith(vi.fn(async () => ({
      ok: true, status: 200, json: async () => { throw new Error('not json'); },
    })));
    await expect(client.send({ prompt: 'q', context: composeContext({}) }))
      .rejects.toThrow(AssistantUnavailableError);
    await expect(client.send({ prompt: 'q', context: composeContext({}) }))
      .rejects.toThrow(/cannot read/);
  });

  it('still rejects a non-2xx whose body cannot be read, with the status in the message', async () => {
    const client = clientWith(vi.fn(async () => ({
      ok: false, status: 502, json: async () => { throw new Error('not json'); },
    })));
    await expect(client.send({ prompt: 'q', context: composeContext({}) })).rejects.toThrow(/502/);
  });

  it('returns the reply as inert text and recognises no action in it', async () => {
    const fetchImpl = vi.fn(async () => jsonResponse(200, {
      text: 'Node 3 is the bottleneck.',
      model: 'example-1',
      // A client with effector support might read these; this read-only client must not.
      toolCalls: [{ command: 'run.play' }],
      actions: [{ id: 'cancel' }],
    }));
    const reply = await clientWith(fetchImpl).send({ prompt: 'q', context: composeContext({}) });
    expect(reply).toEqual({ text: 'Node 3 is the bottleneck.', model: 'example-1' });
    expect(Object.keys(reply).sort()).toEqual(['model', 'text']);
  });

  it('sends the exact editor binding and returns only the named proposal member as structured data', async () => {
    const structured = { version: 1, id: 'p-1' };
    const fetchImpl = vi.fn(async () => jsonResponse(200, {
      text: 'Review this change.', model: 'example-1', truncated: false, proposal: structured,
    }));
    const reply = await clientWith(fetchImpl).send({
      prompt: 'q', context: composeContext({}),
      document: { incarnation: 'doc-incarnation', revision: 9, catalogDigest: 'catalog-digest' },
    });
    const body = JSON.parse(fetchImpl.mock.calls[0][1].body);
    expect(body.document).toEqual({
      incarnation: 'doc-incarnation', revision: 9, catalogDigest: 'catalog-digest',
    });
    expect(reply).toEqual({ text: 'Review this change.', model: 'example-1', proposal: structured });
  });

  it('does not promote JSON-looking prose or unknown action fields into a proposal', async () => {
    const text = '{"proposal":{"version":1},"confirmed":true}';
    const reply = await clientWith(vi.fn(async () => jsonResponse(200, {
      text, model: 'example-1', graphChange: { delete: '*' }, toolCalls: [{ name: 'unknown' }],
    }))).send({ prompt: 'q', context: composeContext({}) });
    expect(reply).toEqual({ text, model: 'example-1' });
    expect(reply.proposal).toBeUndefined();
  });
});

// ── THE REASON MUST SURVIVE THE WIRE, AND IT MUST REACH THE RENDERED STATE ───────────────────────
//
// The defect this replaces: the client read only `body.error` and threw the reason away, so every
// failure rendered as "failed with HTTP <status>". It degraded honestly — no fabrication — which is
// exactly why nothing caught it. A test asserting the call merely REJECTS would still pass with the
// meaning gone, so these assert the reason arrives at the sentence a user reads.
//
// The envelope is API-01's: `{contract, code, message, error, correlationId}`. `code` is the
// machine-readable token; `error` is a compatibility alias of `message`.
describe('a failed turn keeps its meaning', () => {
  const envelope = (code, message, correlationId = 'c0ffee1234') => ({
    contract: 'ravenroot.error/1', code, message, error: message, correlationId,
  });
  const failing = (status, body) => clientWith(vi.fn(async () => ({
    ok: false, status, json: async () => body,
  })));

  it('reads the standard envelope rather than only its alias field', async () => {
    // This used to copy ErrorCode.REQUEST_INTERRUPTED's real prose by hand. A fixture that
    // mocks `fetch` can never learn that the Java source moved on, so a literal copy here proves
    // nothing about production text -- only pass-through can be proven from this side, and a
    // fixture that is obviously not real prose says so on its face.
    const reason = 'FIXTURE: the request was interrupted before it completed';
    const client = failing(503, envelope('REQUEST_INTERRUPTED', reason));
    const error = await client.send({ prompt: 'q', context: composeContext({}) }).catch(e => e);

    // The token, not just prose — this is what "keeps a name on this side of the wire" means.
    expect(error.code).toBe('REQUEST_INTERRUPTED');
    // And the sentence the user reads is the server's, not the client's generic fallback.
    expect(error.message).toContain(reason);
    expect(error.message).not.toMatch(/failed with HTTP/);
  });

  it('surfaces the correlation id, the only bridge left when the envelope carries no reason token', async () => {
    // The server maps its nine assistant reasons onto four error codes, so `code` alone cannot say
    // which one happened, and records the specific one against this id. The envelope below carries
    // no `assistantReason` — the case this test is about — so quoting the id is how a user gets from
    // the sentence to the actual cause. When the token IS present the panel names the reason itself;
    // the `assistantReason` block further down owns that half.
    const client = failing(409, envelope('CONFLICT', 'the request conflicts with the current state of the resource', 'abc123def456'));
    const error = await client.send({ prompt: 'q', context: composeContext({}) }).catch(e => e);

    expect(error.correlationId).toBe('abc123def456');
    expect(error.message).toContain('abc123def456');
    // Labelled as quotable: a bare hex handle is noise to the person holding it.
    expect(error.message).toMatch(/reference abc123def456/i);
  });

  // Against the exported mapper, not through `send`. A 404 is short-circuited earlier in `send`
  // (a bare 404 from a proxy carries no envelope at all and must still read service-unavailable),
  // so routing this row through `send` would have asserted the envelope mapping while exercising
  // the status branch — a test passing for a reason other than the one it names.
  it.each([
    ['UNKNOWN_RESOURCE', 'service-unavailable'],
    ['AUTHENTICATION_REQUIRED', 'not-signed-in'],
    ['ACCESS_DENIED', 'not-signed-in'],
  ])('maps the %s code to the distinguished reason %s', (code, expected) => {
    expect(failureFromEnvelope(500, envelope(code, 'whatever the prose says')).reason).toBe(expected);
  });

  // And the status short-circuit itself, asserted where it actually lives.
  it.each([404, 501])('reads HTTP %s as service-unavailable even with no envelope at all', async status => {
    const error = await failing(status, null).send({ prompt: 'q', context: composeContext({}) }).catch(e => e);
    expect(error.reason).toBe('service-unavailable');
    expect(error.message).toMatch(/was not sent/);
  });

  it.each([401, 403])('keeps HTTP %s distinguished as not-signed-in through the envelope', async status => {
    const code = status === 401 ? 'AUTHENTICATION_REQUIRED' : 'ACCESS_DENIED';
    const error = await failing(status, envelope(code, 'authentication required'))
      .send({ prompt: 'q', context: composeContext({}) }).catch(e => e);
    expect(error.reason).toBe('not-signed-in');
    expect(error.code).toBe(code);
  });

  it('names no reason for a code it cannot distinguish, rather than guessing one', async () => {
    // The server collapses PROVIDER_REFUSED and TOOL_LOOP_EXHAUSTED onto CONFLICT, so the specific
    // cause is genuinely absent from the response. Inventing one here would be the fabrication this
    // panel exists to avoid; the honest answer is prose plus the correlation id.
    const client = failing(409, envelope('CONFLICT', 'the request conflicts with the current state of the resource'));
    const error = await client.send({ prompt: 'q', context: composeContext({}) }).catch(e => e);
    expect(error.reason).toBeNull();
    expect(error.code).toBe('CONFLICT');
  });

  it('still says something useful when the envelope is absent entirely', async () => {
    const error = await failing(500, null).send({ prompt: 'q', context: composeContext({}) }).catch(e => e);
    expect(error.message).toMatch(/failed with HTTP 500/);
    expect(error.code).toBeNull();
    expect(error.correlationId).toBeNull();
  });

  it('falls back to the alias when a build emits only `error`', async () => {
    const error = await failing(500, { error: 'alias only' })
      .send({ prompt: 'q', context: composeContext({}) }).catch(e => e);
    expect(error.message).toContain('alias only');
  });
});

// ── EVERY INERT REASON IS PROVOKABLE FROM A CONCRETE SERVER RESPONSE ─────────────────────────────
//
// "A state that cannot be provoked is a state nobody has tested." Each row below is a real response
// shape, driven through the real `status()`, so the four reasons are reachable rather than merely
// enumerable. `not-signed-in` is the one worth naming: the SERVER never constructs its own
// NOT_SIGNED_IN inert reason (an absent credential is an operator gap and reports `no-profile`), so
// the reason is reachable only by this route — a 401 or 403 from Ravenroot itself.
describe('each inert reason is reachable from a real response', () => {
  const statusOf = (status, body) => clientWith(vi.fn(async () => ({
    ok: status >= 200 && status < 300, status, json: async () => body,
  }))).status();

  it('reaches service-unavailable when the route is not registered', async () => {
    expect(inertReason(await statusOf(404, {}))).toBe('service-unavailable');
  });

  it('reaches no-profile when the deployment reports nothing configured', async () => {
    expect(inertReason(await statusOf(200, { configured: false, allowlisted: false, signedIn: false })))
      .toBe('no-profile');
  });

  it('reaches host-not-allowlisted when egress policy refuses the provider', async () => {
    expect(inertReason(await statusOf(200, { configured: true, allowlisted: false, signedIn: false })))
      .toBe('host-not-allowlisted');
  });

  it('reaches insecure-refused before host allowlisting for an ineligible plaintext endpoint', async () => {
    expect(inertReason(await statusOf(200, {
      configured: true, insecureRefused: true, allowlisted: false, signedIn: false,
    }))).toBe('insecure-refused');
  });

  it.each([401, 403])('reaches not-signed-in from HTTP %s, its only route', async status => {
    expect(inertReason(await statusOf(status, {}))).toBe('not-signed-in');
  });

  it('reaches no inert reason at all when the deployment is ready', async () => {
    expect(inertReason(await statusOf(200, { configured: true, allowlisted: true, signedIn: true })))
      .toBeNull();
  });
});

// ── THE ASSISTANT IS NOT INVITED TO READ ITSELF ─────────────────────────────────────────────────
//
// Both assistant routes declare posture NEVER: the model must not read its own status or replay its
// own transcript. That is enforced on the server, but the panel is where such a thing would be
// handed over voluntarily, so the outbound shape is asserted here rather than assumed.
//
// THE CLIENT SENDS NO CONVERSATION HISTORY. Each turn is independent. Multi-turn memory would mean replaying
// prior assistant turns into a later prompt, which is exactly what the NEVER posture forbids the
// model to fetch — so if it is ever wanted it is a deliberate decision about what the PANEL sends,
// not something to slip in beside a context class.
describe('what the composer does not send', () => {
  it('sends exactly the prompt, the context and the attached list — no history', async () => {
    const fetchImpl = vi.fn(async () => jsonResponse(200, { text: 'ok' }));
    await clientWith(fetchImpl).send({
      prompt: 'q',
      context: composeContext({ graph: () => ({ nodeCount: 1 }) }),
    });
    const body = JSON.parse(fetchImpl.mock.calls[0][1].body);
    expect(Object.keys(body).sort()).toEqual(['attached', 'context', 'prompt']);
  });

  it('carries no transcript, prior turn or assistant status under any key', async () => {
    const fetchImpl = vi.fn(async () => jsonResponse(200, { text: 'ok' }));
    await clientWith(fetchImpl).send({
      prompt: 'q',
      context: composeContext({ graph: () => ({ nodeCount: 1 }) }),
      // Offered the way a future caller might, and ignored — `send` reads neither.
      transcript: [{ role: 'assistant', text: 'an earlier answer' }],
      history: [{ role: 'assistant', text: 'an earlier answer' }],
    });
    const raw = fetchImpl.mock.calls[0][1].body;
    expect(raw).not.toContain('an earlier answer');
    for (const key of ['transcript', 'history', 'messages', 'turns', 'availability', 'signedIn']) {
      expect(JSON.parse(raw)).not.toHaveProperty(key);
    }
  });

  it('never puts an assistant-status key inside the context payload either', async () => {
    const fetchImpl = vi.fn(async () => jsonResponse(200, { text: 'ok' }));
    await clientWith(fetchImpl).send({
      prompt: 'q',
      context: { payload: { graph: { a: 1 }, assistant: { signedIn: true } }, classes: [] },
    });
    // Filtered by the declared class list, so a status object cannot ride in as a context class.
    expect(Object.keys(JSON.parse(fetchImpl.mock.calls[0][1].body).context)).toEqual(['graph']);
  });
});

// ── THE NAMED REASON NOW CROSSES THE WIRE AND REACHES THE SENTENCE ──────────────────────────────
//
// `assistantReason` is optional and additive. These assert the two halves that matter: a response
// that carries one renders the reason's own sentence, and a response that does not renders exactly
// what it rendered before the field existed.
describe('assistantReason', () => {
  // Previously ErrorCode.CONFLICT's real prose, copied by hand. This block only proves that
  // a known `assistantReason` sentence displaces whatever generic prose the envelope carried, and
  // that an unmapped one falls back to it verbatim -- neither claim depends on the prose being the
  // production string, so a fixture that plainly is not one removes the false promise that it was
  // being kept in sync with `ErrorCode.java` when nothing here could tell.
  const GENERIC_CONFLICT_PROSE = 'FIXTURE: the request conflicts with the current server state';
  const envelope = (extra = {}) => ({
    contract: 'ravenroot.error/1',
    code: 'CONFLICT',
    message: GENERIC_CONFLICT_PROSE,
    error: GENERIC_CONFLICT_PROSE,
    correlationId: 'c0ffee1234',
    ...extra,
  });
  const failWith = body => clientWith(vi.fn(async () => ({ ok: false, status: 409, json: async () => body })));
  const sendTo = client => client.send({ prompt: 'q', context: composeContext({}) }).catch(error => error);

  it.each(ASSISTANT_FAILURE_TOKENS)('renders %s as its own sentence, not the generic code prose', async token => {
    const error = await sendTo(failWith(envelope({ assistantReason: token })));
    expect(error.assistantReason).toBe(token);
    expect(error.assistantReasonKnown).toBe(true);
    expect(error.message).toContain(ASSISTANT_FAILURE_TEXT[token]);
    // The distinction the field exists for: the generic envelope prose no longer wins.
    expect(error.message).not.toContain(GENERIC_CONFLICT_PROSE);
  });

  it('keeps two different reasons rendering two different sentences', async () => {
    const refused = await sendTo(failWith(envelope({ assistantReason: 'ASSISTANT_PROVIDER_REFUSED' })));
    const looped = await sendTo(failWith(envelope({ assistantReason: 'ASSISTANT_TOOL_LOOP_EXHAUSTED' })));
    // Both were CONFLICT before the field existed — this pair IS the collapse, undone.
    expect(refused.message).not.toBe(looped.message);
    expect(refused.code).toBe(looped.code);
  });

  it('distinguishes input rejected before egress from an invalid model proposal after egress', async () => {
    const rawPrompt = 'raw prompt canary';
    const rawToolArguments = 'raw tool arguments canary';
    const preEgress = await sendTo(failWith(envelope({
      assistantReason: 'ASSISTANT_INVALID_TURN', correlationId: 'pre-egress-ref',
    })));
    const postEgress = await sendTo(failWith(envelope({
      assistantReason: 'ASSISTANT_MODEL_PROPOSAL_INVALID', correlationId: 'post-egress-ref',
      message: rawPrompt,
      error: rawToolArguments,
    })));

    expect(preEgress.message).toContain('nothing was sent to the model');
    expect(preEgress.message).toContain('pre-egress-ref');
    expect(postEgress.message).toContain('reached the model');
    expect(postEgress.message).toContain('Nothing was applied');
    expect(postEgress.message).not.toContain('nothing was sent to the model');
    expect(postEgress.message).toContain('post-egress-ref');
    expect(postEgress.message).not.toContain(rawPrompt);
    expect(postEgress.message).not.toContain(rawToolArguments);
  });

  it('still carries code and correlationId alongside the named reason', async () => {
    const error = await sendTo(failWith(envelope({ assistantReason: 'ASSISTANT_EGRESS_REFUSED' })));
    expect(error.code).toBe('CONFLICT');
    expect(error.correlationId).toBe('c0ffee1234');
    expect(error.message).toContain('c0ffee1234');
  });

  // ── AN UNKNOWN TOKEN FALLS BACK; IT DOES NOT GUESS ────────────────────────────────────────────
  it.each([
    ['a word this build has not learned', 'ASSISTANT_QUOTA_EXHAUSTED'],
    ['a longer token sharing a known prefix', 'ASSISTANT_PROVIDER_REFUSED_BY_POLICY'],
  ])('falls back to the envelope prose for %s', async (_label, token) => {
    const error = await sendTo(failWith(envelope({ assistantReason: token })));
    expect(error.assistantReasonKnown).toBe(false);
    // Honest: the server's own prose, not a neighbouring reason's sentence.
    expect(error.message).toContain(GENERIC_CONFLICT_PROSE);
    for (const known of ASSISTANT_FAILURE_TOKENS) {
      expect(error.message).not.toContain(ASSISTANT_FAILURE_TEXT[known]);
    }
    // And the unmapped token is still carried, so the drift is visible rather than swallowed.
    expect(error.assistantReason).toBe(token);
  });

  it('renders exactly as before when the response carries no assistantReason at all', async () => {
    const error = await sendTo(failWith(envelope()));
    expect(error.assistantReason).toBeNull();
    expect(error.assistantReasonKnown).toBe(false);
    expect(error.message).toContain(GENERIC_CONFLICT_PROSE);
    expect(error.message).toContain('c0ffee1234');
  });

  it('ignores a non-string assistantReason rather than stringifying it', async () => {
    const error = await sendTo(failWith(envelope({ assistantReason: { token: 'ASSISTANT_PROVIDER_REFUSED' } })));
    expect(error.assistantReason).toBeNull();
    expect(error.message).toContain(GENERIC_CONFLICT_PROSE);
  });
});
