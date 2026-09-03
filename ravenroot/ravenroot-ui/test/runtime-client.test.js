import { describe, expect, it, vi } from 'vitest';

import {
  ProgramSourceRejectedError,
  RavenrootRuntimeClient,
  RuntimeAuthorizationError,
  RuntimeRequestError,
  memoryTokenProvider,
  normalizeRuntimeEvent,
  parseEventFrame,
  validateLocalDeploymentStatus,
  validateSourceSessionStatus,
} from '../src/runtime-client.js';

describe('process-local source session client', () => {
  const listening = {
    sessionId: 'source-1', state: 'LISTENING', sourceCount: 2,
    scope: 'LOCAL_PROCESS', diagnostic: null,
  };

  it('uses the dedicated authenticated start, observe, and stop routes without a payload', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true, status: 200, text: async () => JSON.stringify(listening),
    });
    const client = new RavenrootRuntimeClient('', { fetchImpl, accessToken: 'token' });

    await client.startSourceSession('source-1', '<graphml/>');
    await client.sourceSession('source-1');
    await client.stopSourceSession('source-1');

    expect(fetchImpl.mock.calls.map(([url, options]) => [url, options.method])).toEqual([
      ['/v1/source-sessions?id=source-1', 'POST'],
      ['/v1/source-sessions/source-1', 'GET'],
      ['/v1/source-sessions/source-1', 'DELETE'],
    ]);
    expect(fetchImpl.mock.calls[0][1].body).toBe('<graphml/>');
    expect(fetchImpl.mock.calls[0][0]).not.toContain('payload');
    for (const [, options] of fetchImpl.mock.calls) {
      expect(options.credentials).toBe('omit');
      expect(options.headers.Authorization).toBe('Bearer token');
    }
  });

  it('rejects cluster claims, unknown states, mismatched ids, zero sources, and unbounded diagnostics', () => {
    expect(() => validateSourceSessionStatus({ ...listening, scope: 'CLUSTER' }, 'source-1'))
      .toThrow(/process-local status/);
    expect(() => validateSourceSessionStatus({ ...listening, state: 'READY' }, 'source-1'))
      .toThrow(/process-local status/);
    expect(() => validateSourceSessionStatus({ ...listening, sourceCount: 0 }, 'source-1'))
      .toThrow(/process-local status/);
    expect(() => validateSourceSessionStatus({ ...listening, sessionId: 'sibling' }, 'source-1'))
      .toThrow(/does not match/);
    expect(() => validateSourceSessionStatus({ ...listening, diagnostic: 'x'.repeat(193) }, 'source-1'))
      .toThrow(/process-local status/);
  });
});

describe('process-local deployment client', () => {
  const ready = {
    deploymentId: 'deployment-1', state: 'READY', sourceCount: 0,
    scope: 'LOCAL_PROCESS', diagnostic: null,
  };

  it('uses the dedicated authenticated register, observe, start, and stop routes', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true, status: 200, text: async () => JSON.stringify(ready),
    });
    const client = new RavenrootRuntimeClient('', { fetchImpl, accessToken: 'token' });

    await client.registerDeployment('deployment-1', '<graphml/>');
    await client.deployment('deployment-1');
    await client.startDeployment('deployment-1');
    await client.stopDeployment('deployment-1');

    expect(fetchImpl.mock.calls.map(([url, options]) => [url, options.method])).toEqual([
      ['/v1/deployments?id=deployment-1', 'POST'],
      ['/v1/deployments/deployment-1', 'GET'],
      ['/v1/deployments/deployment-1/start', 'POST'],
      ['/v1/deployments/deployment-1/stop', 'POST'],
    ]);
    expect(fetchImpl.mock.calls[0][1].body).toBe('<graphml/>');
    for (const [, options] of fetchImpl.mock.calls) {
      expect(options.credentials).toBe('omit');
      expect(options.headers.Authorization).toBe('Bearer token');
    }
  });

  // Unlike a source session, sourceCount === 0 is a VALID deployment status -- a graph with no
  // effective SOURCE is registrable and controllable as a deployment, which is the whole point of
  // this surface. This is the one assertion that would fail if this validator copied
  // validateSourceSessionStatus's own >= 1 floor instead of stating its own.
  it('accepts sourceCount 0, unlike a source session', () => {
    expect(validateLocalDeploymentStatus(ready, 'deployment-1')).toEqual(ready);
    expect(validateLocalDeploymentStatus({ ...ready, sourceCount: 3 }, 'deployment-1').sourceCount).toBe(3);
  });

  it('accepts every LocalDeploymentState value, including REGISTERED which no source session has', () => {
    for (const state of ['REGISTERED', 'STARTING', 'READY', 'DEGRADED', 'STOPPING', 'STOPPED', 'FAILED']) {
      expect(() => validateLocalDeploymentStatus({ ...ready, state })).not.toThrow();
    }
  });

  // The Deployments window needs these three routes in addition to the Run/Stop lifecycle calls.
  it('lists the tenant\'s own deployments, unwrapping the {deployments: [...]} envelope', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true, status: 200, text: async () => JSON.stringify({ deployments: [ready] }),
    });
    const client = new RavenrootRuntimeClient('', { fetchImpl, accessToken: 'token' });

    const result = await client.deployments();

    expect(fetchImpl.mock.calls[0]).toEqual(['/v1/deployments', expect.objectContaining({ method: 'GET' })]);
    expect(result).toEqual([ready]);
  });

  it('rejects a list response that is not the documented envelope shape', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true, status: 200, text: async () => JSON.stringify([ready]),
    });
    const client = new RavenrootRuntimeClient('', { fetchImpl, accessToken: 'token' });

    await expect(client.deployments()).rejects.toThrow(/valid process-local status list/);
  });

  it('restarts on its own route, distinct from stop+start', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true, status: 200, text: async () => JSON.stringify(ready),
    });
    const client = new RavenrootRuntimeClient('', { fetchImpl, accessToken: 'token' });

    await client.restartDeployment('deployment-1');

    expect(fetchImpl.mock.calls[0]).toEqual(
      ['/v1/deployments/deployment-1/restart', expect.objectContaining({ method: 'POST' })]);
  });

  it('undeploys with DELETE on the same route inspect and stop already answer, distinct from stop',
    async () => {
      const fetchImpl = vi.fn().mockResolvedValue({
        ok: true, status: 200, text: async () => JSON.stringify({ ...ready, state: 'STOPPED' }),
      });
      const client = new RavenrootRuntimeClient('', { fetchImpl, accessToken: 'token' });

      const result = await client.undeployDeployment('deployment-1');

      expect(fetchImpl.mock.calls[0]).toEqual(
        ['/v1/deployments/deployment-1', expect.objectContaining({ method: 'DELETE' })]);
      expect(result.state).toBe('STOPPED');
    });

  it('rejects cluster claims, unknown states, mismatched ids, negative counts, and unbounded diagnostics', () => {
    expect(() => validateLocalDeploymentStatus({ ...ready, scope: 'CLUSTER' }, 'deployment-1'))
      .toThrow(/process-local status/);
    expect(() => validateLocalDeploymentStatus({ ...ready, state: 'LISTENING' }, 'deployment-1'))
      .toThrow(/process-local status/);
    expect(() => validateLocalDeploymentStatus({ ...ready, sourceCount: -1 }, 'deployment-1'))
      .toThrow(/process-local status/);
    expect(() => validateLocalDeploymentStatus({ ...ready, deploymentId: 'sibling' }, 'deployment-1'))
      .toThrow(/does not match/);
    expect(() => validateLocalDeploymentStatus({ ...ready, diagnostic: 'x'.repeat(193) }, 'deployment-1'))
      .toThrow(/process-local status/);
  });
});

describe('durable process inventory client (issue 154)', () => {
  const page = {
    items: [{
      tenantId: 'tenant-a', processInstanceId: 'aaaaaaaa-0000-0000-0000-000000000001',
      status: 'RUNNING', disposition: 'ACTIVE', revision: 3, lifecycleGeneration: 2,
      graphVersion: 'sha256:deadbeef', deploymentId: null, workloadId: null, correlationId: null,
      ownerWorkerId: 'worker-1', fencingToken: 7, leaseExpiresAt: '2026-01-01T00:00:30Z',
      traversalCount: 1, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:05Z',
      retainedUntil: null,
    }],
    nextCursor: null,
    retainedFrom: '2025-12-25T00:00:00Z',
  };

  it('reads GET /v1/executions/inventory unfiltered by default and returns the page unmodified', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({ ok: true, status: 200, text: async () => JSON.stringify(page) });
    const client = new RavenrootRuntimeClient('', { fetchImpl, accessToken: 'token' });

    const result = await client.processInventory();

    expect(fetchImpl.mock.calls[0][0]).toBe('/v1/executions/inventory');
    expect(fetchImpl.mock.calls[0][1].method).toBe('GET');
    expect(result).toEqual(page);
  });

  it('sends only the filters the caller actually supplies, as GET /v1/executions/inventory query parameters', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({ ok: true, status: 200, text: async () => JSON.stringify(page) });
    const client = new RavenrootRuntimeClient('', { fetchImpl, accessToken: 'token' });

    await client.processInventory({ status: 'RUNNING,WAITING', deployment: 'deploy-1', includeTerminal: true });

    const url = new URL(fetchImpl.mock.calls[0][0], 'http://localhost');
    expect(url.pathname).toBe('/v1/executions/inventory');
    expect(url.searchParams.get('status')).toBe('RUNNING,WAITING');
    expect(url.searchParams.get('deployment')).toBe('deploy-1');
    expect(url.searchParams.get('includeTerminal')).toBe('true');
    expect(url.searchParams.has('owner')).toBe(false);
    expect(url.searchParams.has('cursor')).toBe(false);
  });

  it('reads GET /v1/executions/{id}/traversals for a process instance id, distinct from execution()', async () => {
    const traversals = { traversals: [{
      traversalId: 'bbbbbbbb-0000-0000-0000-000000000002', position: 0, ingressNodeId: 'start',
      status: 'RUNNING', disposition: 'ACTIVE', invocationCount: 1, parkedAttemptCount: 0,
    }] };
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true, status: 200, text: async () => JSON.stringify(traversals),
    });
    const client = new RavenrootRuntimeClient('', { fetchImpl, accessToken: 'token' });

    const result = await client.processInstanceTraversals('aaaaaaaa-0000-0000-0000-000000000001');

    expect(fetchImpl.mock.calls[0][0]).toBe(
      '/v1/executions/aaaaaaaa-0000-0000-0000-000000000001/traversals');
    expect(result).toEqual(traversals);
  });

  it('rejects a blank process instance id before making a request', async () => {
    const fetchImpl = vi.fn();
    const client = new RavenrootRuntimeClient('', { fetchImpl, accessToken: 'token' });

    await expect(client.processInstanceTraversals('')).rejects.toThrow(/require an id/);
    expect(fetchImpl).not.toHaveBeenCalled();
  });
});

function streamResponse(frames, status = 200) {
  const encoder = new TextEncoder();
  const chunks = frames.map(frame => encoder.encode(frame));
  let index = 0;
  return {
    ok: status >= 200 && status < 300,
    status,
    body: {
      getReader: () => ({
        read: vi.fn(async () => index < chunks.length
          ? { value: chunks[index++], done: false }
          : { value: undefined, done: true }),
        cancel: vi.fn(),
      }),
    },
  };
}

describe('Ravenroot runtime client security boundary', () => {
  it('uses same-origin endpoints, bearer headers and explicitly omits credentials', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: async () => JSON.stringify([{ behavior: 'template', displayName: 'Template' }]),
      json: async () => [{ behavior: 'template', displayName: 'Template' }],
    });
    const client = new RavenrootRuntimeClient('', { fetchImpl, accessToken: 'memory-secret' });

    const catalog = await client.nodeTypes();

    expect(catalog[0].behavior).toBe('template');
    expect(fetchImpl).toHaveBeenCalledWith('/v1/node-types', expect.objectContaining({
      method: 'GET',
      credentials: 'omit',
      cache: 'no-store',
      headers: expect.objectContaining({ Authorization: 'Bearer memory-secret' }),
    }));
    expect(fetchImpl.mock.calls[0][0]).not.toContain('memory-secret');
  });

  // RavenrootRuntimeClient#programLanguages is the one path the artifact workbench has to
  // read a language catalog from -- there is no second, hard-coded list anywhere in this client.
  it('reads the program language catalog from GET /v1/program-languages', async () => {
    const languages = [
      { id: 'javascript', displayName: 'JavaScript', exampleSource: '({ payload }) => payload' },
      { id: 'python', displayName: 'Python', exampleSource: 'def handler(request):\n    pass\nhandler' },
    ];
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: async () => JSON.stringify(languages),
    });
    const client = new RavenrootRuntimeClient('', { fetchImpl, accessToken: 'memory-secret' });

    const catalog = await client.programLanguages();

    expect(catalog).toEqual(languages);
    expect(fetchImpl).toHaveBeenCalledWith('/v1/program-languages', expect.objectContaining({
      method: 'GET',
      credentials: 'omit',
      cache: 'no-store',
      headers: expect.objectContaining({ Authorization: 'Bearer memory-secret' }),
    }));
  });

  it('rejects a program language response that is not a JSON array', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: async () => JSON.stringify({ not: 'an array' }),
    });
    const client = new RavenrootRuntimeClient('', { fetchImpl, accessToken: 'memory-secret' });

    await expect(client.programLanguages()).rejects.toThrow(/not an array/);
  });

  // Previously this pinned the opposite behaviour: with no token the client refused to send the
  // request at all. That refusal emptied the node palette against
  // a loopback service that authorises everyone, without asking it anything. Sending no token is
  // not a leak: no Authorization header exists to leak, the token is still never put in a URL, and
  // the decision belongs to the service. The 401/403 handling below is unchanged and still asserted.
  it('sends unauthenticated requests and lets the service decide, instead of assuming a 401', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: async () => JSON.stringify([{ behavior: 'template', displayName: 'Template' }]),
      json: async () => [{ behavior: 'template', displayName: 'Template' }],
    });
    const client = new RavenrootRuntimeClient('', { fetchImpl });

    const catalog = await client.nodeTypes();

    expect(catalog[0].behavior).toBe('template');
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    expect(fetchImpl.mock.calls[0][1].headers).not.toHaveProperty('Authorization');
    expect(fetchImpl.mock.calls[0][1]).toEqual(expect.objectContaining({ credentials: 'omit' }));
  });

  it('still surfaces a service 401 as the same typed error and clears the in-memory token', async () => {
    const provider = memoryTokenProvider('stale');
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      json: async () => ({ error: 'unauthorized' }),
    });
    const client = new RavenrootRuntimeClient('', { fetchImpl, tokenProvider: provider });

    await expect(client.nodeTypes()).rejects.toEqual(expect.objectContaining({
      name: 'RuntimeAuthorizationError',
      status: 401,
      message: 'Authentication expired',
    }));
    expect(await provider.getAccessToken()).toBe('');
  });

  it('parses multiline SSE fields without interpreting event content as markup', () => {
    expect(parseEventFrame('id: 7\nevent: execution\ndata: {\"detail\":\"<img\"}\ndata: \"}\" \nretry: 400'))
      .toEqual({
        id: '7',
        type: 'execution',
        data: '{"detail":"<img"}\n"}" ',
        retry: 400,
      });
  });

  it('streams with fetch, bounds reconnects and resumes with Last-Event-ID', async () => {
    const received = [];
    const changes = [];
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(streamResponse([
        'id: event-1\nretry: 250\nevent: execution\ndata: {"type":"NODE_STARTED","executionId":"execution-1","nodeId":"n1"}\n\n',
      ]))
      .mockResolvedValueOnce({ ok: false, status: 403 });
    const client = new RavenrootRuntimeClient('', {
      fetchImpl,
      accessToken: 'token',
      sleep: vi.fn(async () => {}),
    });

    const disconnect = client.connect(event => received.push(event), (status, message) => changes.push([status, message]));
    await vi.waitFor(() => expect(changes.at(-1)?.[0]).toBe('revoked'));
    disconnect();

    expect(received).toEqual([{ type: 'NODE_STARTED', executionId: 'execution-1', nodeId: 'n1' }]);
    expect(fetchImpl.mock.calls[0][1]).toEqual(expect.objectContaining({ credentials: 'omit' }));
    expect(fetchImpl.mock.calls[1][1].headers['Last-Event-ID']).toBe('event-1');
    expect(changes.some(([status]) => status === 'connected')).toBe(true);
  });

  it('accepts the server maximum combined EDGE_TRAVERSED projection below the 65,536-byte frame ceiling', async () => {
    // Mirrors EdgeTraversalWireBudget's maximum-valid union fixture. These are server-owned bounds,
    // not a second client policy: the browser proves it can consume the largest frame the server is
    // allowed to publish without locally trimming or imposing an incompatible auxiliary-field cap.
    const edgeId = '\u0001'.repeat(8_192);
    const engineId = '\u0001'.repeat(2_036);
    const event = {
      sequence: 1, occurredAt: '2026-08-30T12:00:00Z', type: 'EDGE_TRAVERSED',
      executionId: 'execution-1', traversalId: 'execution-1', processInstanceId: 'process-1',
      edgeId, tenantId: 'tenant', requestId: 'request', engineId, graphVersion: 'graph',
      nodeId: 'source', publicReason: 'continue', detail: 'edge traversed', nodeCatalogKey: 'catalog',
      deploymentId: 'deployment', workloadId: 'workload',
    };
    const escapedBytes = value => new TextEncoder()
      .encode(JSON.stringify(value).slice(1, -1)).byteLength;
    const auxiliary = [event.tenantId, event.requestId, event.engineId, event.graphVersion,
      event.nodeId, event.publicReason, event.detail, event.nodeCatalogKey, event.deploymentId, event.workloadId];
    expect(escapedBytes(edgeId)).toBe(49_152);
    expect(auxiliary.reduce((sum, value) => sum + escapedBytes(value), 0)).toBe(12_287);

    const frame = `id: 1\nevent: execution\ndata: ${JSON.stringify(event)}\n\n`;
    expect(new TextEncoder().encode(frame).byteLength).toBeLessThan(65_536);
    const received = [];
    const changes = [];
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(streamResponse([frame]))
      .mockResolvedValueOnce({ ok: false, status: 403 });
    const client = new RavenrootRuntimeClient('', {
      fetchImpl, sleep: vi.fn(async () => {}),
    });

    const disconnect = client.connect(value => received.push(value),
      (status, message) => changes.push([status, message]));
    await vi.waitFor(() => expect(changes.at(-1)?.[0]).toBe('revoked'));
    disconnect();
    expect(received).toHaveLength(1);
    expect(received[0].edgeId).toBe(edgeId);
    expect(received[0].engineId).toBe(engineId);
  });

  it('normalizes durable replay fields into the live event contract', () => {
    expect(normalizeRuntimeEvent({
      journalOffset: 91,
      eventType: 'EXECUTION_FAILED',
      traversalId: 'execution-1',
      graphVersion: 'graph-v1',
    })).toEqual({
      journalOffset: 91,
      eventType: 'EXECUTION_FAILED',
      traversalId: 'execution-1',
      graphVersion: 'graph-v1',
      type: 'EXECUTION_FAILED',
      executionId: 'execution-1',
    });
  });

  it('preserves legacy live events and rejects contradictory aliases', () => {
    const live = { type: 'NODE_STARTED', executionId: 'execution-1', nodeId: 'n1' };
    expect(normalizeRuntimeEvent(live)).toEqual(live);
    expect(() => normalizeRuntimeEvent({
      type: 'EXECUTION_COMPLETED', eventType: 'EXECUTION_FAILED',
      executionId: 'execution-1', traversalId: 'execution-1',
    })).toThrow('type and eventType disagree');
    expect(() => normalizeRuntimeEvent({
      type: 'EXECUTION_COMPLETED', executionId: 'execution-1', traversalId: 'execution-2',
    })).toThrow('executionId and traversalId disagree');
  });

  it('delivers durable terminal events with canonical fields to the UI', async () => {
    const received = [];
    const states = [];
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(streamResponse([
        'id: 42\nevent: execution\ndata: {"eventType":"EXECUTION_FAILED","traversalId":"execution-1","graphVersion":"v1"}\n\n',
      ]))
      .mockResolvedValueOnce({ ok: false, status: 403 });
    const client = new RavenrootRuntimeClient('', {
      fetchImpl, sleep: vi.fn(async () => {}),
    });

    const disconnect = client.connect(event => received.push(event),
      (status, message) => states.push([status, message]));
    await vi.waitFor(() => expect(states.at(-1)?.[0]).toBe('revoked'));
    disconnect();

    expect(received).toEqual([expect.objectContaining({
      type: 'EXECUTION_FAILED', executionId: 'execution-1', traversalId: 'execution-1',
    })]);
  });

  it('preserves the server-authored process incarnation on replayed terminal SSE frames', async () => {
    const received = [];
    const states = [];
    const old = '{"type":"EXECUTION_COMPLETED","executionId":"same-id","graphVersion":"same-v",'
      + '"processInstanceId":"process-old","occurredAt":"2026-08-28T10:00:00Z"}';
    const current = '{"type":"EXECUTION_COMPLETED","executionId":"same-id","graphVersion":"same-v",'
      + '"processInstanceId":"process-current","occurredAt":"2026-08-28T10:00:01Z"}';
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(streamResponse([
        `event: execution\ndata: ${old}\n\nevent: execution\ndata: ${old}\n\n`
          + `event: execution\ndata: ${current}\n\n`,
      ]))
      .mockResolvedValueOnce({ ok: false, status: 403 });
    const client = new RavenrootRuntimeClient('', { fetchImpl, sleep: vi.fn(async () => {}) });

    const disconnect = client.connect(event => received.push(event),
      (status, message) => states.push([status, message]));
    await vi.waitFor(() => expect(states.at(-1)?.[0]).toBe('revoked'));
    disconnect();

    expect(received.map(event => event.processInstanceId))
      .toEqual(['process-old', 'process-old', 'process-current']);
  });

  it('refreshes once on 401 and never retries terminal 403', async () => {
    const provider = memoryTokenProvider('expired');
    provider.refreshAccessToken = vi.fn(async () => provider.setAccessToken('fresh'));
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce({ ok: false, status: 401 })
      .mockResolvedValueOnce({ ok: false, status: 403 });
    const states = [];
    const client = new RavenrootRuntimeClient('', { fetchImpl, tokenProvider: provider });

    client.connect(() => {}, (status, message) => states.push([status, message]));
    await vi.waitFor(() => expect(states.at(-1)?.[0]).toBe('revoked'));

    expect(provider.refreshAccessToken).toHaveBeenCalledTimes(1);
    expect(fetchImpl).toHaveBeenCalledTimes(2);
    expect(await provider.getAccessToken()).toBe('');
  });

  it('rejects oversized unframed SSE input and stops after the bounded retry budget', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(streamResponse(['x'.repeat(40)]));
    const states = [];
    const client = new RavenrootRuntimeClient('', {
      fetchImpl,
      accessToken: 'token',
      maxFrameBytes: 32,
      maxRetries: 1,
      sleep: vi.fn(async () => {}),
    });

    client.connect(() => {}, (status, message) => states.push([status, message]));
    await vi.waitFor(() => expect(states.at(-1)?.[0]).toBe('error'));

    expect(fetchImpl).toHaveBeenCalledTimes(2);
    expect(states.some(([, message]) => message.includes('32 bytes'))).toBe(true);
  });

  it('posts GraphML and drives governed artifact operations with no cookies', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: async () => JSON.stringify({ id: 'artifact-1', state: 'GENERATED', executionId: 'execution-1' }),
      json: async () => ({ id: 'artifact-1', state: 'GENERATED', executionId: 'execution-1' }),
    });
    const client = new RavenrootRuntimeClient('https://service.example/root/', {
      fetchImpl,
      accessToken: 'token',
    });

    await client.start('<graphml/>', 'hello world');
    await client.run('<graphml/>', 'real effects');
    await client.execution('execution/with space');
    await client.createProgramArtifact('({ payload }) => payload', { language: 'javascript', name: 'echo' });
    await client.validateProgramArtifact('artifact-1');

    expect(fetchImpl).toHaveBeenNthCalledWith(1,
      'https://service.example/root/v1/executions?payload=hello%20world',
      expect.objectContaining({ method: 'POST', body: '<graphml/>', credentials: 'omit' }));
    expect(fetchImpl).toHaveBeenNthCalledWith(2,
      'https://service.example/root/v1/executions?mode=run&payload=real%20effects',
      expect.objectContaining({ method: 'POST', body: '<graphml/>', credentials: 'omit' }));
    expect(fetchImpl).toHaveBeenNthCalledWith(3,
      'https://service.example/root/v1/executions/execution%2Fwith%20space',
      expect.objectContaining({ method: 'GET', credentials: 'omit' }));
    expect(fetchImpl).toHaveBeenNthCalledWith(4,
      'https://service.example/root/v1/program-artifacts?language=javascript&name=echo',
      expect.objectContaining({ method: 'POST', credentials: 'omit' }));
    expect(fetchImpl).toHaveBeenNthCalledWith(5,
      'https://service.example/root/v1/program-artifacts/artifact-1/validate',
      expect.objectContaining({ method: 'POST', credentials: 'omit' }));
  });

  it('submits one bounded server-owned program build and returns the server identity snapshots', async () => {
    const serverPrograms = [{
      nodeId: 'program-1', artifactId: 'server-artifact-7',
      sourceDigest: 'server-source-digest', payloadDigest: 'server-payload-digest',
      phase: 'REGISTER', revision: 1, createdAt: '2026-08-30T00:00:00Z',
      updatedAt: '2026-08-30T00:00:00Z', terminal: false,
      ready: false, reused: true, smokeOutput: null, diagnostic: '',
    }];
    const snapshot = {
      buildId: 'build-7', revision: 1, createdAt: '2026-08-30T00:00:00Z',
      updatedAt: '2026-08-30T00:00:00Z', terminal: false, programs: serverPrograms,
    };
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true, status: 202, text: async () => JSON.stringify(snapshot),
    });
    const client = new RavenrootRuntimeClient('https://service.example/root', { fetchImpl });

    const result = await client.buildProgramArtifacts([{
      nodeId: 'program-1', language: 'javascript', source: '\n exact source \n',
    }]);

    expect(result).toEqual(snapshot);
    expect(fetchImpl).toHaveBeenCalledOnce();
    expect(fetchImpl).toHaveBeenCalledWith('https://service.example/root/v1/program-artifacts/build',
      expect.objectContaining({
        method: 'POST', credentials: 'omit',
        headers: expect.objectContaining({ 'Content-Type': 'application/json; charset=utf-8' }),
        body: JSON.stringify({ programs: [{
          nodeId: 'program-1', language: 'javascript', source: '\n exact source \n',
          testPayload: 'test payload',
        }] }),
      }));
  });

  it('polls an encoded durable build id with authorization and rejects mismatched identity', async () => {
    const snapshot = {
      buildId: 'build/with space', revision: 6, createdAt: '2026-08-30T00:00:00Z',
      updatedAt: '2026-08-30T00:00:01Z', terminal: true,
      programs: [{
        nodeId: 'program-1', artifactId: 'artifact-1', sourceDigest: 'source', payloadDigest: 'payload',
        phase: 'READY', revision: 6, createdAt: '2026-08-30T00:00:00Z',
        updatedAt: '2026-08-30T00:00:01Z', terminal: true, ready: true, reused: false,
        smokeOutput: { accepted: true }, diagnostic: '',
      }],
    };
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true, status: 200, text: async () => JSON.stringify(snapshot),
    });
    const client = new RavenrootRuntimeClient('', { fetchImpl, accessToken: 'observer-token' });

    expect(await client.programArtifactBuild('build/with space')).toEqual(snapshot);
    expect(fetchImpl).toHaveBeenCalledWith('/v1/program-artifacts/builds/build%2Fwith%20space',
      expect.objectContaining({
        method: 'GET', cache: 'no-store', credentials: 'omit',
        headers: expect.objectContaining({ Authorization: 'Bearer observer-token' }),
      }));

    await expect(client.programArtifactBuild('another-build')).rejects.toThrow(/does not match/);
  });

  it('uses one graph-level batch approval request and no per-artifact lifecycle route', async () => {
    const artifacts = [{ id: 'server-artifact-1', state: 'ACTIVE' }, { id: 'server-artifact-2', state: 'ACTIVE' }];
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true, status: 200, text: async () => JSON.stringify({ artifacts }),
    });
    const client = new RavenrootRuntimeClient('', { fetchImpl });

    expect(await client.approveProgramArtifactBatch(
      ['server-artifact-1', 'server-artifact-2'], 'peer approval',
    )).toEqual(artifacts);

    expect(fetchImpl).toHaveBeenCalledOnce();
    expect(fetchImpl).toHaveBeenCalledWith('/v1/program-artifacts/approve-batch', expect.objectContaining({
      method: 'POST', body: JSON.stringify({
        artifactIds: ['server-artifact-1', 'server-artifact-2'], reason: 'peer approval',
      }),
    }));
    expect(fetchImpl.mock.calls[0][0]).not.toMatch(/server-artifact-[12]\/(approve|activate)/);
  });

  it('rejects an oversized browser program batch before making a request', async () => {
    const fetchImpl = vi.fn();
    const client = new RavenrootRuntimeClient('', { fetchImpl });
    const programs = Array.from({ length: 257 }, (_, index) => ({
      nodeId: `program-${index}`, language: 'javascript', source: 'source', testPayload: 'sample',
    }));

    await expect(client.buildProgramArtifacts(programs)).rejects.toThrow(/1 and 256/);
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('posts structured artifact smoke JSON in a bounded body instead of a query parameter', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: async () => '{"artifact":{"id":"artifact-1","state":"TESTED"},"output":true}',
    });
    const client = new RavenrootRuntimeClient('https://service.example/root', { fetchImpl });

    await client.testProgramArtifact('artifact/1', { ready: true, count: 2 }, {
      mediaType: 'application/json',
    });

    expect(fetchImpl).toHaveBeenCalledWith(
      'https://service.example/root/v1/program-artifacts/artifact%2F1/test',
      expect.objectContaining({
        method: 'POST', body: '{"ready":true,"count":2}', credentials: 'omit',
        headers: expect.objectContaining({ 'Content-Type': 'application/json; charset=utf-8' }),
      }),
    );
    expect(fetchImpl.mock.calls[0][0]).not.toContain('payload=');
  });

  it('keeps JSON-looking artifact smoke text literal when text/plain is selected', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true, status: 200, text: async () => '{"artifact":{"id":"artifact-1","state":"TESTED"}}',
    });
    const client = new RavenrootRuntimeClient('', { fetchImpl });
    const literal = '{"still":"text"}';

    await client.testProgramArtifact('artifact-1', literal, { mediaType: 'text/plain' });

    expect(fetchImpl).toHaveBeenCalledWith('/v1/program-artifacts/artifact-1/test',
      expect.objectContaining({
        body: literal,
        headers: expect.objectContaining({ 'Content-Type': 'text/plain; charset=utf-8' }),
      }));
  });

  it('rejects unsupported artifact smoke payload media types before making a request', async () => {
    const fetchImpl = vi.fn();
    const client = new RavenrootRuntimeClient('', { fetchImpl });

    await expect(client.testProgramArtifact('artifact-1', '<x/>', { mediaType: 'application/xml' }))
      .rejects.toThrow(/application\/json or text\/plain/);
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it.each([
    [400, 'INVALID_PAYLOAD', '{]'],
    [413, 'PAYLOAD_TOO_LARGE', 'oversized fixture'],
    [415, 'UNSUPPORTED_MEDIA_TYPE', 'unsupported fixture'],
  ])('preserves classified artifact smoke body failures (%s %s)', async (status, code, payload) => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: false, status, text: async () => JSON.stringify({ error: code }),
    });
    const client = new RavenrootRuntimeClient('', { fetchImpl });

    await expect(client.testProgramArtifact('artifact-1', payload, { mediaType: 'application/json' }))
      .rejects.toMatchObject({
        name: 'RuntimeRequestError', status, method: 'POST',
        path: '/v1/program-artifacts/artifact-1/test',
      });
    await expect(client.testProgramArtifact('artifact-1', payload, { mediaType: 'application/json' }))
      .rejects.toThrow(new RegExp(`${code}.*HTTP ${status} POST /v1/program-artifacts/artifact-1/test`));
  });

  it('propagates AbortSignal through execution lookup and aborts the live fetch', async () => {
    let requestSignal;
    const fetchImpl = vi.fn((_url, options) => new Promise((_resolve, reject) => {
      requestSignal = options.signal;
      options.signal.addEventListener('abort', () => reject(new Error('lookup aborted')), { once: true });
    }));
    const client = new RavenrootRuntimeClient('', { fetchImpl });
    const controller = new AbortController();

    const lookup = client.execution('execution-1', { signal: controller.signal });
    await vi.waitFor(() => expect(requestSignal).toBe(controller.signal));
    controller.abort();

    await expect(lookup).rejects.toMatchObject({
      name: 'RuntimeRequestError', method: 'GET', path: '/v1/executions/execution-1',
    });
    expect(requestSignal.aborted).toBe(true);
  });

  // The route answers 200 for both outcomes because a source that does not compile is a
  // result, not a malformed request. This client is where that becomes a rejected promise, so every
  // existing caller's try/catch stays correct without learning the new body shape.
  it('rejects with the runtime diagnostic when validate answers a rejected outcome', async () => {
    const body = {
      outcome: 'rejected',
      artifactId: 'artifact-1',
      diagnostic: 'IndentationError: expected an indented block after function definition on line 1 '
        + '(artifact-1, line 2)',
      line: 2,
      column: 0,
    };
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: async () => JSON.stringify(body),
    });
    const client = new RavenrootRuntimeClient('', { fetchImpl, accessToken: 'token' });

    const error = await client.validateProgramArtifact('artifact-1').catch(caught => caught);

    expect(error).toBeInstanceOf(ProgramSourceRejectedError);
    // The exact sentence the author reads. It names the cause and carries the position once, not
    // twice: GraalPy already states the line in its own text, so the structured `line` below exists
    // for a caller that wants to act on the position rather than read it.
    expect(error.message).toBe('The program source was rejected: IndentationError: expected an '
      + 'indented block after function definition on line 1 (artifact-1, line 2)');
    expect(error.message).not.toContain('rejected as invalid');
    expect(error.line).toBe(2);
    expect(error.column).toBe(0);
    expect(error.artifactId).toBe('artifact-1');
  });

  // At the surface the reporter reads, the deployment condition needs NO new handling in the
  // client: it is an ordinary error response, so `#json`'s
  // existing enrichment already carries it.
  //
  // A client-side fixture cannot promise that a given English sentence is what
  // production currently sends, only that the client relays whatever sentence it is given and
  // tells this failure kind apart from a request-validation error. That is what stays asserted
  // below; the fixture prose is deliberately not real, so nobody mistakes it for the current
  // message.
  it('surfaces an unconfigured deployment as a reason naming the sandbox, not the request', async () => {
    const serverProse = "FIXTURE: this deployment's program sandbox needs an operator's attention, "
      + 'not a change to the submitted source.';
    const envelope = {
      contract: 'ravenroot.error/1',
      code: 'PROGRAM_SANDBOX_UNAVAILABLE',
      message: serverProse,
      error: serverProse,
      correlationId: 'c-1',
    };
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: false,
      status: 501,
      text: async () => JSON.stringify(envelope),
    });
    const client = new RavenrootRuntimeClient('', { fetchImpl, accessToken: 'token' });

    const error = await client.validateProgramArtifact('artifact-1').catch(caught => caught);

    // The failure kind a reader (and a caller's try/catch) can act on: not the same class as a
    // rejected source, regardless of what sentence the server used to say so.
    expect(error).toBeInstanceOf(RuntimeRequestError);
    expect(error).not.toBeInstanceOf(ProgramSourceRejectedError);
    // The server's own sentence reaches the reader untouched, with the usual request context appended.
    expect(error.message).toBe(`${serverProse} (HTTP 501 POST /v1/program-artifacts/artifact-1/validate)`);
    expect(error.message).not.toContain('rejected as invalid');
    // the 404 hint must not fire here: this request reached an API route and was answered by it.
    expect(error.message).not.toContain('check the runtime service address');
  });

  it('resolves with the artifact when validate answers a validated outcome', async () => {
    const artifact = { id: 'artifact-1', state: 'VALIDATED', language: 'python' };
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: async () => JSON.stringify({ outcome: 'validated', artifactId: 'artifact-1', artifact }),
    });
    const client = new RavenrootRuntimeClient('', { fetchImpl, accessToken: 'token' });

    expect(await client.validateProgramArtifact('artifact-1')).toEqual(artifact);
  });

  // `language` is a caller-supplied value, not a constant this client special-cases -- the
  // artifact workbench passes whatever the author picked from the runtime-declared catalog.
  it('creates a program artifact in whatever language the caller passes, not only javascript', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: async () => JSON.stringify({ id: 'artifact-2', language: 'python', state: 'GENERATED' }),
    });
    const client = new RavenrootRuntimeClient('https://service.example/root/', { fetchImpl, accessToken: 'token' });

    await client.createProgramArtifact('def handler(request):\n    pass\nhandler',
      { language: 'python', name: 'python-echo' });

    expect(fetchImpl).toHaveBeenCalledWith(
      'https://service.example/root/v1/program-artifacts?language=python&name=python-echo',
      expect.objectContaining({ method: 'POST', credentials: 'omit' }));
  });

  // `language` must not default to 'javascript': a caller that forgets to pass it would otherwise
  // get a JavaScript artifact silently. It
  // has no default now, so a caller that omits it fails loudly here instead.
  it('refuses to create a program artifact when language is omitted, rather than defaulting to javascript', async () => {
    const fetchImpl = vi.fn();
    const client = new RavenrootRuntimeClient('', { fetchImpl, accessToken: 'token' });

    await expect(client.createProgramArtifact('({ payload }) => payload', { name: 'echo' }))
      .rejects.toThrow(/explicit language/);
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('exposes authorization failures as typed errors', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: false,
      status: 403,
      json: async () => ({ error: 'denied' }),
    });
    const client = new RavenrootRuntimeClient('', { fetchImpl, accessToken: 'token' });

    await expect(client.nodeTypes()).rejects.toBeInstanceOf(RuntimeAuthorizationError);
  });

  // ── REQUEST FAILURES CARRY STATUS, METHOD AND ROUTE; A 404 TELLS THE USER WHAT TO CHECK ───────

  it('reports a non-auth failure with status, method and route in the message', async () => {
    // 'method not allowed' happens to equal ErrorCode.METHOD_NOT_ALLOWED verbatim, which is
    // this same trap in miniature -- a fixture that could silently stop matching production and
    // this test would not notice, since it only ever compares the client's output against what
    // this same fixture supplied. A deliberately-fake reason proves the same thing (the reason
    // reaches the message, alongside status/method/route) without implying textual fidelity.
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: false,
      status: 405,
      text: async () => JSON.stringify({ error: 'FIXTURE: verb not permitted on this route' }),
      json: async () => ({ error: 'FIXTURE: verb not permitted on this route' }),
    });
    const client = new RavenrootRuntimeClient('', { fetchImpl });

    const error = await client.start('<graphml/>', 'hello').catch(caught => caught);

    expect(error).toBeInstanceOf(RuntimeRequestError);
    expect(error.status).toBe(405);
    expect(error.method).toBe('POST');
    expect(error.path).toBe('/v1/executions?payload=hello');
    expect(error.message).toContain('FIXTURE: verb not permitted on this route');
    expect(error.message).toContain('HTTP 405');
    expect(error.message).toContain('POST');
    expect(error.message).toContain('/v1/executions');
  });

  it('tells the caller to check the service address on a 404, the shape a misrouted base URL takes', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      text: async () => JSON.stringify({ error: 'not found' }),
      json: async () => ({ error: 'not found' }),
    });
    const client = new RavenrootRuntimeClient('', { fetchImpl });

    const error = await client.start('<graphml/>').catch(caught => caught);

    expect(error).toBeInstanceOf(RuntimeRequestError);
    expect(error.status).toBe(404);
    expect(error.message).toMatch(/check the runtime service address/i);
  });

  it('does not add the address hint to an ordinary non-404 failure', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      text: async () => JSON.stringify({ error: 'internal error' }),
      json: async () => ({ error: 'internal error' }),
    });
    const client = new RavenrootRuntimeClient('', { fetchImpl });

    const error = await client.nodeTypes().catch(caught => caught);

    expect(error.status).toBe(500);
    expect(error.message).not.toMatch(/check the runtime service address/i);
  });

  // A rejected fetch promise has one shape whether the cause is a genuine network failure or a
  // service that received the request and refused it on origin grounds (BrowserOriginPolicy answers
  // every /v1 route with a 403 carrying no Access-Control-Allow-Origin header, which the browser
  // reports as a rejected promise, not a readable 403). The client cannot tell these apart, so the
  // message must invite the check without asserting which one happened.
  it('invites checking the address on a rejected fetch, without claiming the request never arrived', async () => {
    const fetchImpl = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));
    const client = new RavenrootRuntimeClient('', { fetchImpl });

    const error = await client.nodeTypes().catch(caught => caught);

    expect(error).toBeInstanceOf(RuntimeRequestError);
    expect(error.status).toBeNull();
    expect(error.message).toContain('Failed to fetch');
    expect(error.message).toMatch(/check the runtime service address/i);
    // The forbidden assertion: a rejected promise is not proof the request never reached the
    // service, so the message must not say so.
    expect(error.message).not.toMatch(/did not reach the service/i);
    expect(error.message).not.toMatch(/never reached/i);
  });

  // ── A NON-JSON BODY (A PROXY/CDN'S OWN HTML ERROR PAGE IN FRONT OF THE
  // real service) must still produce a typed error with status, method
  // and route -- not an opaque "Unexpected token '<' ... is not valid JSON" ──────────────────────

  it('turns a non-JSON error body into a RuntimeRequestError carrying status, method and route', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      text: async () => '<html><body>404 Not Found</body></html>',
      json: async () => { throw new SyntaxError("Unexpected token '<', ... is not valid JSON"); },
    });
    const client = new RavenrootRuntimeClient('', { fetchImpl });

    const error = await client.nodeTypes().catch(caught => caught);

    expect(error).toBeInstanceOf(RuntimeRequestError);
    expect(error.status).toBe(404);
    expect(error.method).toBe('GET');
    expect(error.path).toBe('/v1/node-types');
    expect(error.message).toContain('HTTP 404');
    expect(error.message).toContain('GET');
    expect(error.message).toContain('/v1/node-types');
    // The raw body text is usable diagnostic content, not swallowed.
    expect(error.message).toContain('404 Not Found');
  });

  it('turns a non-JSON body on an otherwise-ok response into a typed error rather than throwing raw', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: async () => 'not json at all',
      json: async () => { throw new SyntaxError('Unexpected token'); },
    });
    const client = new RavenrootRuntimeClient('', { fetchImpl });

    const error = await client.nodeTypes().catch(caught => caught);

    expect(error).toBeInstanceOf(RuntimeRequestError);
    expect(error.status).toBe(200);
    expect(error.message).toMatch(/not valid JSON/i);
  });

  it('falls back to a generic reason when the non-JSON error body is empty', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: false,
      status: 502,
      text: async () => '',
      json: async () => { throw new SyntaxError('Unexpected end of JSON input'); },
    });
    const client = new RavenrootRuntimeClient('', { fetchImpl });

    const error = await client.nodeTypes().catch(caught => caught);

    expect(error).toBeInstanceOf(RuntimeRequestError);
    expect(error.status).toBe(502);
    expect(error.message).toContain('Service request failed');
  });

  // A truncated transfer on an `ok` response makes `response.text()` reject
  // -- the body was never invalid JSON, it was never fully read. Claiming "not valid JSON" would be
  // a false cause of the same family as findings 1 and A, relocated to the body read.
  it('reports a truncated transfer as unreadable rather than as invalid JSON', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: async () => { throw new TypeError('network error'); },
      json: async () => { throw new TypeError('network error'); },
    });
    const client = new RavenrootRuntimeClient('', { fetchImpl });

    const error = await client.nodeTypes().catch(caught => caught);

    expect(error).toBeInstanceOf(RuntimeRequestError);
    expect(error.status).toBe(200);
    expect(error.message).toMatch(/could not be read/i);
    expect(error.message).not.toMatch(/not valid JSON/i);
  });
});
