import { describe, expect, it, vi } from 'vitest';
import { createDeploymentRunIntent, persistedDeploymentRun, runOriginDeployment } from '../src/deployment-run.js';
import { persistedDocument, workspaceSnapshot, validateWorkspaceSnapshot, workspaceScope } from '../src/workspace-persistence.js';
const version = 'a'.repeat(64), process = '10000000-0000-0000-0000-000000000001', traversal = '20000000-0000-0000-0000-000000000002';
const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes; }); return { promise, resolve }; };
function fixture() {
  const owner = { id: 'x', documentId: 'x', incarnation: 'origin', tenantId: 'tenant', mode: 'draft', graph: { nodes: [], edges: [] } }, saved = [];
  let current = true, n = 0;
  const status = { tenantId: 'tenant', graphVersion: version, state: 'REGISTERED', deploymentGeneration: 0 };
  const client = { deployment: vi.fn(async () => { throw { status: 404 }; }), registerDeployment: vi.fn(async id => ({ ...status, deploymentId: id })),
    startDeployment: vi.fn(async id => ({ ...status, deploymentId: id, state: 'READY' })),
    deploymentView: vi.fn(async id => ({ source: { deploymentId: id, graphVersion: version, incarnationId: 'runtime-1' } })),
    initialStartDeployment: vi.fn(async (id, request) => ({ outcome: 'ACCEPTED', requestId: request.requestId, processInstanceId: process, traversalId: traversal })) };
  const options = { client, serviceUrl: 'https://service', tenantId: 'tenant', graphMl: '<original/>', inputJson: '{"original":true}',
    persist: vi.fn(async (_, intent) => { saved.push(structuredClone(intent)); }), isCurrent: origin => current && origin === owner,
    pin: vi.fn(async () => version), randomId: () => `request-${++n}` };
  return { owner, client, options, saved, setCurrent: value => { current = value; } };
}
describe('origin document deployment Run', () => {
  it('persists original identities/bytes/input/pin before registration and coalesces concurrent Run', async () => {
    const f = fixture(), gate = deferred(); f.options.persist.mockImplementationOnce(async (_, intent) => { f.saved.push(structuredClone(intent)); await gate.promise; });
    const first = runOriginDeployment(f.owner, f.options); expect(runOriginDeployment(f.owner, f.options)).toBe(first);
    await Promise.resolve(); expect(f.client.registerDeployment).not.toHaveBeenCalled(); gate.resolve(); const result = await first;
    expect(f.saved[0]).toMatchObject({ deploymentId: result.intent.deploymentId, graphVersion: version, graphMl: '<original/>', inputJson: '{"original":true}', phase: 'PREPARED' });
    expect(f.client.registerDeployment).toHaveBeenCalledTimes(1); expect(f.client.startDeployment).toHaveBeenCalledTimes(1); expect(f.client.initialStartDeployment).toHaveBeenCalledTimes(1);
  });
  it('binds a captured origin through focus switch without borrowing later bytes/input', async () => {
    const f = fixture(), gate = deferred(), other = { id: 'y' }; let active = f.owner;
    f.client.registerDeployment.mockImplementation(async id => { await gate.promise; return { tenantId: 'tenant', graphVersion: version, state: 'REGISTERED', deploymentId: id }; });
    const run = runOriginDeployment(f.owner, f.options); await vi.waitFor(() => expect(f.client.registerDeployment).toHaveBeenCalledTimes(1));
    active = other; f.options.graphMl = '<other/>'; f.options.inputJson = '"other"'; gate.resolve(); const result = await run;
    expect(active).toBe(other); expect(other.deploymentRun).toBeUndefined(); expect(result.intent.graphMl).toBe('<original/>');
    expect(f.client.initialStartDeployment.mock.calls[0][1].input).toEqual({ original: true });
  });
  it('reconciles committed registration response loss to the same deployment and original bytes', async () => {
    const f = fixture(); f.client.registerDeployment.mockRejectedValueOnce(new TypeError('lost registration'));
    await expect(runOriginDeployment(f.owner, f.options)).rejects.toThrow('lost registration'); const original = structuredClone(f.owner.deploymentRun);
    f.client.deployment.mockResolvedValue({ tenantId: 'tenant', graphVersion: version, state: 'READY' });
    await runOriginDeployment(f.owner, { ...f.options, graphMl: '<new-draft/>', inputJson: '{"changed":true}' });
    expect(f.client.registerDeployment).toHaveBeenCalledTimes(1); expect(f.client.startDeployment).not.toHaveBeenCalled();
    expect(f.owner.deploymentRun.deploymentId).toBe(original.deploymentId); expect(f.owner.deploymentRun.initialStartRequestId).toBe(original.initialStartRequestId);
    expect(f.client.initialStartDeployment.mock.calls[0][1].input).toEqual({ original: true });
  });
  it('reuses the unresolved activation request after response loss', async () => {
    const f = fixture(); f.client.startDeployment.mockRejectedValueOnce(new TypeError('lost activation'));
    await expect(runOriginDeployment(f.owner, f.options)).rejects.toThrow('lost activation'); const id = f.owner.deploymentRun.activationRequestId;
    f.client.deployment.mockResolvedValue({ tenantId: 'tenant', graphVersion: version, state: 'REGISTERED', deploymentGeneration: 0 }); await runOriginDeployment(f.owner, f.options);
    expect(f.client.startDeployment.mock.calls.map(call => call[1].idempotencyKey)).toEqual([id, id]);
  });
  it('reuses initial START request and identities after lost response', async () => {
    const f = fixture(); f.client.initialStartDeployment.mockRejectedValueOnce(new TypeError('lost initial'));
    await expect(runOriginDeployment(f.owner, f.options)).rejects.toThrow('lost initial'); const id = f.owner.deploymentRun.initialStartRequestId;
    f.client.deployment.mockResolvedValue({ tenantId: 'tenant', graphVersion: version, state: 'READY' }); await runOriginDeployment(f.owner, f.options);
    expect(f.client.initialStartDeployment.mock.calls.map(call => call[1].requestId)).toEqual([id, id]); expect(f.owner.deploymentRun).toMatchObject({ processInstanceId: process, traversalId: traversal });
  });
  it('later Run uses the pinned deployment without a second initial START', async () => {
    const f = fixture(); await runOriginDeployment(f.owner, f.options); f.client.deployment.mockResolvedValue({ tenantId: 'tenant', graphVersion: version, state: 'READY' });
    await runOriginDeployment(f.owner, { ...f.options, graphMl: '<changed/>', inputJson: '{}' }); expect(f.client.initialStartDeployment).toHaveBeenCalledTimes(1);
    expect(f.client.registerDeployment).toHaveBeenCalledTimes(1); expect(f.owner.deploymentRun.graphMl).toBe('<original/>');
  });
  it('explicit Run reactivates a proven stopped deployment without repeating initial START', async () => {
    const f = fixture(); await runOriginDeployment(f.owner, f.options); const id = f.owner.deploymentRun.activationRequestId;
    f.client.deployment.mockResolvedValue({ tenantId: 'tenant', graphVersion: version, state: 'STOPPED', deploymentGeneration: 2 }); await runOriginDeployment(f.owner, f.options);
    expect(f.client.startDeployment).toHaveBeenCalledTimes(2); expect(f.owner.deploymentRun.activationRequestId).not.toBe(id); expect(f.client.initialStartDeployment).toHaveBeenCalledTimes(1);
  });
  it('restores an inert tenant-scoped association with no client or credentials', async () => {
    const f = fixture(); await runOriginDeployment(f.owner, f.options); const scope = workspaceScope('https://service', 'tenant');
    const restored = validateWorkspaceSnapshot(workspaceSnapshot(scope, [f.owner], 'x'), scope).documents[0]; expect(restored.deploymentRun).toEqual(f.owner.deploymentRun);
    expect(f.client.registerDeployment).toHaveBeenCalledTimes(1);
    expect(persistedDocument({ ...f.owner, deploymentRun: { ...f.owner.deploymentRun, client: { credential: 'never-persist' } } }).deploymentRun).not.toHaveProperty('client');
  });
  it('refuses failed storage before any network effect', async () => {
    const f = fixture(); f.options.persist.mockRejectedValue(new Error('storage unavailable')); await expect(runOriginDeployment(f.owner, f.options)).rejects.toThrow('storage unavailable');
    expect(f.client.deployment).not.toHaveBeenCalled(); expect(f.client.registerDeployment).not.toHaveBeenCalled();
  });
  it.each(['close', 'replace', 'authority'])('fences %s during pending registration', async kind => {
    const f = fixture(), gate = deferred(); f.client.registerDeployment.mockImplementation(async () => { await gate.promise; return { tenantId: 'tenant', graphVersion: version, state: 'REGISTERED' }; });
    const run = runOriginDeployment(f.owner, f.options); await vi.waitFor(() => expect(f.client.registerDeployment).toHaveBeenCalledTimes(1));
    if (kind === 'replace') f.owner.incarnation = 'successor'; else f.setCurrent(false); gate.resolve(); await expect(run).rejects.toThrow();
    expect(f.client.startDeployment).not.toHaveBeenCalled(); expect(f.client.initialStartDeployment).not.toHaveBeenCalled();
  });
  it('refuses foreign tenant or replaced incarnation before effects', async () => {
    const f = fixture(); f.client.deployment.mockResolvedValueOnce({ tenantId: 'foreign', graphVersion: version, state: 'READY' }); await expect(runOriginDeployment(f.owner, f.options)).rejects.toThrow();
    expect(f.client.initialStartDeployment).not.toHaveBeenCalled(); await runOriginDeployment(f.owner, f.options);
    f.client.deployment.mockResolvedValue({ tenantId: 'tenant', graphVersion: version, state: 'READY' });
    f.client.deploymentView.mockResolvedValue({ source: { deploymentId: f.owner.deploymentRun.deploymentId, graphVersion: version, incarnationId: 'replaced' } });
    await expect(runOriginDeployment(f.owner, f.options)).rejects.toThrow(); expect(f.client.initialStartDeployment).toHaveBeenCalledTimes(1);
  });
  it('gives fresh identical files independent deployment identities and rejects malformed persistence', () => {
    const f = fixture(), one = createDeploymentRunIntent(f.options), two = createDeploymentRunIntent(f.options); expect(one.deploymentId).not.toBe(two.deploymentId);
    expect(() => persistedDeploymentRun({ ...one, deploymentId: '../foreign' })).toThrow(); expect(() => persistedDeploymentRun({ ...one, processInstanceId: process })).toThrow();
  });
  it('attached deploy-only consumes its own initial marker and never registers another deployment', async () => {
    const f = fixture(); f.client.deployment.mockResolvedValue({ tenantId: 'tenant', graphVersion: version, state: 'READY' });
    await runOriginDeployment(f.owner, { ...f.options, attachment: { deploymentId: 'already-deployed', graphVersion: version }, graphMl: '' });
    expect(f.owner.deploymentRun.deploymentId).toBe('already-deployed'); expect(f.client.registerDeployment).not.toHaveBeenCalled();
    expect(f.client.initialStartDeployment).toHaveBeenCalledTimes(1);
  });
  it('never re-registers a missing attached identity after restart or removal', async () => {
    const f = fixture(); await expect(runOriginDeployment(f.owner, { ...f.options,
      attachment: { deploymentId: 'old-deployment', graphVersion: version }, graphMl: '' })).rejects.toThrow();
    expect(f.client.registerDeployment).not.toHaveBeenCalled(); expect(f.client.startDeployment).not.toHaveBeenCalled();
  });

  it('rebinds a rehydrated incarnation only through stable durable registration and read-only permanent marker', async () => {
    const f = fixture(); const registrationIdentity = 'durable-registered-identity';
    f.client.registerDeployment.mockResolvedValue({ tenantId: 'tenant', graphVersion: version, state: 'REGISTERED', continuity: 'DURABLE', registrationIdentity });
    await runOriginDeployment(f.owner, f.options); const requestId = f.owner.deploymentRun.initialStartRequestId;
    f.client.deployment.mockResolvedValue({ tenantId: 'tenant', graphVersion: version, state: 'READY', continuity: 'DURABLE', registrationIdentity });
    f.client.deploymentView.mockImplementation(async id => ({ source: { deploymentId: id, graphVersion: version, incarnationId: 'rehydrated' } }));
    f.client.reconcileInitialStartDeployment = vi.fn(async (_, request) => ({ outcome: 'DUPLICATE', requestId: request.requestId, processInstanceId: process, traversalId: traversal }));
    await runOriginDeployment(f.owner, f.options);
    expect(f.client.reconcileInitialStartDeployment).toHaveBeenCalledWith(f.owner.deploymentRun.deploymentId, { incarnationId: 'rehydrated', graphVersion: version, requestId });
    expect(f.client.initialStartDeployment).toHaveBeenCalledTimes(1); expect(f.owner.deploymentRun.incarnationId).toBe('rehydrated');
    expect(f.owner.deploymentRun).toMatchObject({ processInstanceId: process, traversalId: traversal });
  });
  it.each(['missing', 'changed'])('keeps a rehydration %s marker unresolved and never admits new work', async kind => {
    const f = fixture(), registrationIdentity = 'durable-registered-identity';
    f.client.registerDeployment.mockResolvedValue({ tenantId: 'tenant', graphVersion: version, state: 'REGISTERED', continuity: 'DURABLE', registrationIdentity });
    await runOriginDeployment(f.owner, f.options);
    f.client.deployment.mockResolvedValue({ tenantId: 'tenant', graphVersion: version, state: 'READY', continuity: 'DURABLE', registrationIdentity });
    f.client.deploymentView.mockImplementation(async id => ({ source: { deploymentId: id, graphVersion: version, incarnationId: 'rehydrated' } }));
    f.client.reconcileInitialStartDeployment = vi.fn(async (_, request) => {
      if (kind === 'missing') throw new Error('marker missing');
      return { outcome: 'DUPLICATE', requestId: request.requestId, processInstanceId: '30000000-0000-0000-0000-000000000003', traversalId: traversal };
    });
    await expect(runOriginDeployment(f.owner, f.options)).rejects.toThrow();
    await expect(runOriginDeployment(f.owner, f.options)).rejects.toThrow();
    expect(f.client.initialStartDeployment).toHaveBeenCalledTimes(1); expect(f.owner.deploymentRun.incarnationId).toBe('runtime-1');
    expect(f.owner.deploymentRun.phase).toBe('RECONCILE'); expect(f.client.reconcileInitialStartDeployment).toHaveBeenCalledTimes(2);
  });

  it.each(['never admitted', 'admitted response lost'])('finishes %s initial admission after durable restart without saved admission identities', async kind => {
    const f = fixture(), registrationIdentity = 'durable-registered-identity';
    f.client.registerDeployment.mockResolvedValue({ tenantId: 'tenant', graphVersion: version, state: 'REGISTERED', continuity: 'DURABLE', registrationIdentity });
    f.client.initialStartDeployment.mockRejectedValueOnce(new Error(kind));
    await expect(runOriginDeployment(f.owner, f.options)).rejects.toThrow(kind);
    const original = structuredClone(f.owner.deploymentRun);
    expect(original).toMatchObject({ incarnationId: 'runtime-1', processInstanceId: null, traversalId: null });
    f.client.deployment.mockResolvedValue({ tenantId: 'tenant', graphVersion: version, state: 'READY', continuity: 'DURABLE', registrationIdentity });
    f.client.deploymentView.mockImplementation(async id => ({ source: { deploymentId: id, graphVersion: version, incarnationId: 'rehydrated' } }));
    f.client.initialStartDeployment.mockImplementation(async (_, request) => ({ outcome: kind === 'never admitted' ? 'ACCEPTED' : 'DUPLICATE', requestId: request.requestId, processInstanceId: process, traversalId: traversal }));
    f.client.reconcileInitialStartDeployment = vi.fn();
    await runOriginDeployment(f.owner, { ...f.options, inputJson: '{"changed":true}' });
    expect(f.client.initialStartDeployment.mock.lastCall[1]).toEqual({ incarnationId: 'rehydrated', graphVersion: version, requestId: original.initialStartRequestId, input: { original: true } });
    expect(f.client.reconcileInitialStartDeployment).not.toHaveBeenCalled();
    expect(f.owner.deploymentRun).toMatchObject({ incarnationId: 'rehydrated', processInstanceId: process, traversalId: traversal });
  });

  it('restores only a saved exact durable registration after a normal server restart loses its hosted projection', async () => {
    const f = fixture(), registrationIdentity = 'retained-registry-identity';
    f.client.registerDeployment.mockResolvedValue({ tenantId: 'tenant', graphVersion: version, state: 'REGISTERED', continuity: 'DURABLE', registrationIdentity });
    await runOriginDeployment(f.owner, f.options);
    f.client.restoreDeployment = vi.fn(async () => ({ tenantId: 'tenant', graphVersion: version, state: 'READY', continuity: 'DURABLE', registrationIdentity }));
    f.client.deploymentView.mockImplementation(async id => ({ source: { deploymentId: id, graphVersion: version, incarnationId: 'restored-runtime' } }));
    f.client.reconcileInitialStartDeployment = vi.fn(async (_, request) => ({ outcome: 'DUPLICATE', requestId: request.requestId, processInstanceId: process, traversalId: traversal }));
    await runOriginDeployment(f.owner, f.options);
    expect(f.client.restoreDeployment).toHaveBeenCalledWith(f.owner.deploymentRun.deploymentId, { registrationIdentity, graphVersion: version });
    expect(f.client.registerDeployment).toHaveBeenCalledTimes(1); expect(f.client.initialStartDeployment).toHaveBeenCalledTimes(1);
    expect(f.owner.deploymentRun.incarnationId).toBe('restored-runtime');
  });

});
