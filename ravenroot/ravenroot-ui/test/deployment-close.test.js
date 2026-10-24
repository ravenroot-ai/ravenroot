import { expect, it, vi } from 'vitest';
import { boundDeploymentNeedsCloseDecision, stopOriginDeployment } from '../src/deployment-run.js';
import { captureDocumentCloseSnapshot, classifyDocumentCloseTargets, resolveDocumentCloseSnapshot } from '../src/document-close-plan.js';

function fixture(durable = false) {
  const binding = { deploymentId: 'origin', tenantId: 'tenant', serviceUrl: 'https://service',
    graphVersion: 'a'.repeat(64), incarnationId: 'runtime-1', registrationIdentity: durable ? 'permanent-1' : null };
  const owner = { id: 'x', incarnation: 'document-1', deploymentRun: binding };
  const status = { ...binding, state: 'READY', continuity: durable ? 'DURABLE' : 'PROCESS_LOCAL',
    deploymentGeneration: durable ? 7 : null,
    lifecycleCapabilities: { contractVersion: 1, scope: 'DEPLOYMENT', commands: [{ command: 'STOP', available: true }] } };
  const client = { deployment: vi.fn(async () => ({ ...status })),
    deploymentView: vi.fn(async () => ({ source: { ...binding } })),
    stopDeployment: vi.fn(async () => { status.state = 'STOPPED'; }) };
  const options = { client, isCurrent: () => true, reason: vi.fn(async () => 'Document close requested by operator') };
  return { binding, owner, status, client, options };
}

for (const durable of [false, true]) it(`stops only the captured ${durable ? 'durable registration' : 'runtime incarnation'} and reads authoritative STOPPED`, async () => {
  const f = fixture(durable);
  expect(boundDeploymentNeedsCloseDecision(f.owner)).toBe(true);
  expect(classifyDocumentCloseTargets([f.owner]).activeSessions).toEqual([f.owner]);
  expect(await stopOriginDeployment(f.owner, f.options)).toBe(true);
  expect(f.client.stopDeployment).toHaveBeenCalledExactlyOnceWith('origin', expect.objectContaining({ binding: {
    incarnationId: 'runtime-1', registrationIdentity: f.binding.registrationIdentity, graphVersion: f.binding.graphVersion,
  }, ...(durable ? { expectedGeneration: 7, reason: 'Document close requested by operator', idempotencyKey: expect.any(String) } : {}) }));
  expect(f.client.deployment).toHaveBeenCalledTimes(2);
  expect(boundDeploymentNeedsCloseDecision(f.owner)).toBe(true); // Another client may restart it later.
});

for (const field of ['tenantId', 'graphVersion', 'deploymentId', 'registrationIdentity']) it(`refuses stale ${field} before mutation`, async () => {
  const f = fixture(true); f.status[field] = 'replacement';
  await expect(stopOriginDeployment(f.owner, f.options)).rejects.toThrow();
  expect(f.client.stopDeployment).not.toHaveBeenCalled();
});

it('refuses a process-local replacement even with the same alias and identical graph', async () => {
  const f = fixture(); f.client.deploymentView.mockResolvedValue({ source: { ...f.binding, incarnationId: 'replacement' } });
  await expect(stopOriginDeployment(f.owner, f.options)).rejects.toThrow();
  expect(f.client.stopDeployment).not.toHaveBeenCalled();
});

it('uses current durable generation and runtime without changing saved initial identities', async () => {
  const f = fixture(true), original = structuredClone(f.binding);
  f.client.deploymentView.mockResolvedValue({ source: { ...f.binding, incarnationId: 'rehydrated-runtime' } });
  expect(await stopOriginDeployment(f.owner, f.options)).toBe(true);
  expect(f.binding).toEqual(original);
  expect(f.client.stopDeployment.mock.calls[0][1].binding.incarnationId).toBe('rehydrated-runtime');
});

it('coalesces pending Stop but refuses authority replacement', async () => {
  const f = fixture(); let release, current = true;
  const gate = new Promise(resolve => { release = resolve; });
  f.options.isCurrent = () => current;
  f.client.deployment.mockImplementationOnce(async () => { await gate; return f.status; });
  const first = stopOriginDeployment(f.owner, f.options);
  expect(stopOriginDeployment(f.owner, f.options)).toBe(first);
  current = false; release();
  await expect(first).rejects.toThrow(); expect(f.client.stopDeployment).not.toHaveBeenCalled();
});

it('does not close over a later binding in the same document incarnation', () => {
  const f = fixture(), snapshot = captureDocumentCloseSnapshot([f.owner]);
  f.owner.deploymentRun = { ...f.binding, deploymentId: 'later' };
  expect(resolveDocumentCloseSnapshot({ find: () => f.owner }, snapshot)).toEqual([]);
});

it('keeps the decision active after failed Stop or non-stopped authoritative state', async () => {
  const f = fixture(); f.client.stopDeployment.mockResolvedValue({ state: 'STOPPED' });
  expect(await stopOriginDeployment(f.owner, f.options)).toBe(false);
  expect(boundDeploymentNeedsCloseDecision(f.owner)).toBe(true);
  f.client.stopDeployment.mockRejectedValue(new Error('unavailable'));
  await expect(stopOriginDeployment(f.owner, f.options)).rejects.toThrow('unavailable');
  expect(boundDeploymentNeedsCloseDecision(f.owner)).toBe(true);
});

for (const pending of ['registration', 'activation']) it(`queues Stop behind ${pending}, survives closed origin, and never admits a later initial START`, async () => {
  const { runOriginDeployment } = await import('../src/deployment-run.js');
  const f = fixture(), version = f.binding.graphVersion;
  delete f.owner.deploymentRun;
  let release, documentOpen = true, listener = false;
  const gate = new Promise(resolve => { release = resolve; });
  f.status.state = 'REGISTERED'; f.status.runtimeIncarnationId = 'runtime-1';
  f.client.deployment.mockRejectedValueOnce({ status: 404 });
  f.client.registerDeployment = vi.fn(async id => {
    if (pending === 'registration') await gate;
    f.status.deploymentId = id; return { ...f.status };
  });
  f.client.startDeployment = vi.fn(async (id, command) => {
    expect(command.binding.incarnationId).toBe('runtime-1');
    expect(f.owner.deploymentRun.incarnationId).toBe('runtime-1');
    if (pending === 'activation') await gate;
    listener = true; f.status.state = 'READY'; return { ...f.status };
  });
  f.client.deploymentView.mockImplementation(async id => ({ source: { deploymentId: id, graphVersion: version, incarnationId: 'runtime-1' } }));
  f.client.initialStartDeployment = vi.fn();
  f.client.stopDeployment.mockImplementation(async () => { listener = false; f.status.state = 'STOPPED'; });
  const running = runOriginDeployment(f.owner, { client: f.client, serviceUrl: 'https://service', tenantId: 'tenant',
    graphMl: '<original/>', inputJson: '{"original":true}', pin: async () => version,
    persist: vi.fn(async () => {}), isCurrent: () => documentOpen });
  await vi.waitFor(() => expect(pending === 'registration' ? f.client.registerDeployment : f.client.startDeployment).toHaveBeenCalledTimes(1));
  expect(boundDeploymentNeedsCloseDecision(f.owner)).toBe(true);
  const stopped = stopOriginDeployment(f.owner, f.options);
  documentOpen = false; // Closing the origin does not revoke its captured cleanup authority.
  expect(f.client.stopDeployment).not.toHaveBeenCalled();
  release();
  expect((await running).stoppedBeforeInitial).toBe(true);
  expect(await stopped).toBe(true);
  expect(listener).toBe(false);
  expect(f.client.initialStartDeployment).not.toHaveBeenCalled();
  expect(f.client.stopDeployment).toHaveBeenCalledTimes(1);
  if (pending === 'registration') expect(f.client.startDeployment).not.toHaveBeenCalled();
  expect(f.owner.deploymentRun.inputJson).toBe('{"original":true}');
});

it('stops a cold server-issued runtime without opening a view or activating a listener', async () => {
  const f = fixture(); f.status.state = 'REGISTERED'; f.status.runtimeIncarnationId = 'runtime-1';
  f.client.deploymentView.mockRejectedValue({ status: 404 });
  expect(await stopOriginDeployment(f.owner, f.options)).toBe(true);
  expect(f.client.deploymentView).not.toHaveBeenCalled();
  expect(f.client.stopDeployment).toHaveBeenCalledTimes(1);
});
