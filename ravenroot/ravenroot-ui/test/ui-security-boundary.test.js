import { readFile } from 'node:fs/promises';

import { afterEach, describe, expect, it, vi } from 'vitest';
import { RavenrootRuntimeClient } from '../src/runtime-client.js';
import { RavenrootAssistantClient } from '../src/assistant-client.js';

afterEach(() => vi.unstubAllGlobals());

const view = {
  viewerSourceVersion: '1',
  source: { kind: 'deployment', deploymentId: 'orders', graphVersion: 'sha256:graph',
    incarnationId: 'incarnation-1' },
  lifecycle: 'READY', canonicalDigest: 'sha256:graph',
  projection: { viewerContractVersion: '1.0', graphId: 'orders', graphVersionId: 'sha256:graph',
    canonicalDigest: 'sha256:graph', nodes: [], edges: [] },
};

function transport() {
  return vi.fn(async url => {
    const streaming = String(url).includes('/events?');
    const body = { deployments: [], configured: true, allowlisted: true, signedIn: true,
      provider: 'Example', text: 'answer' };
    return { ok: !streaming, status: streaming ? 403 : 200,
      json: async () => body, text: async () => JSON.stringify(body) };
  });
}

describe('UI browser security boundary', () => {
  it('exposes accessible authentication and revocation controls with explicit storage disclosure', async () => {
    const html = await readFile('index.html', 'utf8');

    expect(html).toContain('id="access-token" type="password"');
    expect(html).toContain('autocomplete="off"');
    expect(html).toContain('id="btn-revoke"');
    expect(html).toContain('aria-describedby="remember-connection-disclosure"');
    expect(html).toContain('readable by page JavaScript');
    expect(html).toContain('id="runtime-connection"');
    expect(html).toContain('role="status"');
    expect(html).toContain('aria-live="polite"');
    expect(html).not.toMatch(/\bon(?:click|change|input)=/);
  });

  it('does not persist an origin trust bypass, anonymous EventSource or raw D3 HTML', async () => {
    const [app, runtime] = await Promise.all([
      readFile('src/app.js', 'utf8'),
      readFile('src/runtime-client.js', 'utf8'),
    ]);

    // Browser tests assert decline leaves no credential anywhere in storage and opt-in permits
    // only the closed connection record. Arbitrary writes still fail the canary-backed assertion.
    expect(app).not.toContain("params.get('service')");
    expect(app).not.toMatch(/d3tip\.html\(/);
    expect(runtime).not.toContain('EventSource');
    expect(runtime).toContain("'Last-Event-ID'");
  });

  it.each([
    ['default service', '', 'same-origin'],
    ['relative service path', '/ravenroot/', 'same-origin'],
    ['normalized host and default port', 'HTTP://UI.EXAMPLE:80/ravenroot/', 'same-origin'],
    ['another host', 'http://other.example', 'omit'],
    ['host suffix', 'http://ui.example.attacker.example', 'omit'],
    ['userinfo is not the destination', 'http://ui.example@other.example', 'omit'],
    ['another port', 'http://ui.example:8080', 'omit'],
    ['another scheme', 'https://ui.example', 'omit'],
    ['protocol-relative other host', '//other.example/ravenroot', 'omit'],
  ])('confines opted-in JSON, assistant and both event requests to the page origin: %s', async (_, baseUrl, credentials) => {
    vi.stubGlobal('location', new URL('http://ui.example/editor'));
    const fetchImpl = transport();
    const runtime = new RavenrootRuntimeClient(baseUrl, { fetchImpl, browserSessions: true,
      accessToken: 'perimeter-token' });
    const assistant = new RavenrootAssistantClient(baseUrl, { fetchImpl,
      tokenProvider: { getAccessToken: () => 'perimeter-token' } });
    assistant.personalMode = true;
    await expect(runtime.deployments()).resolves.toEqual([]);
    await assistant.status();
    await expect(assistant.send({ prompt: 'hello' })).resolves.toMatchObject({ text: 'answer' });
    const changes = [];
    const disconnect = runtime.connect(() => {}, state => changes.push(state));
    const disconnectDeployment = runtime.connectDeploymentView(view, () => {}, state => changes.push(state));
    try {
      await vi.waitFor(() => expect(changes).toEqual(['revoked', 'revoked']));
      expect(fetchImpl).toHaveBeenCalledTimes(5);
      for (const [url, options] of fetchImpl.mock.calls) {
        const destination = new URL(url, location.origin);
        expect(destination.origin === location.origin).toBe(credentials === 'same-origin');
        expect(options.credentials).toBe(credentials);
        expect(options.cache).toBe('no-store');
        expect(options.headers.Authorization).toBe('Bearer perimeter-token');
        expect(url).not.toContain('perimeter-token');
      }
      expect(fetchImpl.mock.calls[2][1].headers['X-Ravenroot-Assistant-Mode']).toBe('personal');
    } finally {
      disconnect();
      disconnectDeployment();
    }
  });

  it.each([false, true])('omits browser credentials without an opted-in trusted page origin (enabled=%s)', async enabled => {
    // Even an HTTP service matching the non-browser URL fallback grants no cookie authority.
    vi.stubGlobal('location', enabled ? undefined : new URL('http://localhost/editor'));
    const fetchImpl = transport();
    const runtime = new RavenrootRuntimeClient('http://localhost', { fetchImpl, browserSessions: enabled });
    const assistant = new RavenrootAssistantClient('http://localhost', { fetchImpl });
    assistant.personalMode = enabled;
    await runtime.deployments();
    await assistant.status();
    expect(fetchImpl).toHaveBeenCalledTimes(2);
    expect(fetchImpl.mock.calls.every(([, options]) => options.credentials === 'omit')).toBe(true);
  });

  it.each(['', 'http://other.example'])('keeps capability-only completion free of bearer and cookies at %s', async baseUrl => {
    vi.stubGlobal('location', new URL('http://ui.example/editor'));
    const tokenProvider = { getAccessToken: vi.fn(() => 'perimeter-token') };
    const fetchImpl = vi.fn(async () => ({ ok: true, status: 200, json: async () => ({
      schemaVersion: 1, outcome: 'APPLIED', taskId: 'task-1', generation: 1,
    }) }));
    const runtime = new RavenrootRuntimeClient(baseUrl, { fetchImpl, browserSessions: true, tokenProvider });
    await runtime.completeHumanTaskInteraction({ taskId: 'task-1', capability: 'one-time-capability' }, 'APPROVE');
    expect(fetchImpl).toHaveBeenCalledExactlyOnceWith(
      `${baseUrl}/v1/human-task-interactions/complete`, expect.objectContaining({ credentials: 'omit' }));
    const [, options] = fetchImpl.mock.calls[0];
    expect(options.headers).not.toHaveProperty('Authorization');
    expect(options.body).toContain('one-time-capability');
    expect(options.body).not.toContain('perimeter-token');
    expect(tokenProvider.getAccessToken).not.toHaveBeenCalled();
  });
});
