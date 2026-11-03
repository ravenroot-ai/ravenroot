import { afterEach, expect, it, vi } from 'vitest';
import { createDeploymentObservationBarrier } from '../src/deployment-observation.js';

const source = { kind: 'deployment', deploymentId: 'original', incarnationId: 'runtime', graphVersion: 'version' };
const handshake = { ...source, type: 'lifecycle', lifecycle: 'READY', cursor: 'a'.repeat(43) };
const accepted = { accepted: true };
afterEach(() => vi.useRealTimers());

it('waits beyond three seconds and through retry notifications for a scoped lifecycle handshake', async () => {
  vi.useFakeTimers();
  const cancel = vi.fn(), barrier = createDeploymentObservationBarrier({ source, isCurrent: () => true, cancel });
  barrier.connection('connected'); barrier.connection('reconnecting');
  await vi.advanceTimersByTimeAsync(4000);
  expect(barrier.pending).toBe(true); expect(barrier.canAdmit()).toBe(false);
  barrier.frame(handshake, accepted);
  expect((await barrier.ready).canAdmit()).toBe(true);
  expect(cancel).not.toHaveBeenCalled();
  barrier.connection('reconnecting');
  expect(barrier.canAdmit()).toBe(false);
  barrier.connection('connected'); expect(barrier.canAdmit()).toBe(false);
  barrier.frame(handshake, accepted); expect(barrier.canAdmit()).toBe(true);
  barrier.retire(); expect(barrier.canAdmit()).toBe(false);
});

it.each(['error', 'revoked'])('never admits on terminal connection state %s', async state => {
  const cancel = vi.fn(), barrier = createDeploymentObservationBarrier({ source, isCurrent: () => true, cancel });
  barrier.connection(state); await expect(barrier.ready).rejects.toThrow();
  barrier.frame(handshake, accepted); expect(barrier.canAdmit()).toBe(false); expect(cancel).toHaveBeenCalled();
});

it.each([
  { ...handshake, cursor: null }, { ...handshake, cursor: 'not-an-anchor' },
  { ...handshake, incarnationId: 'foreign' }, { ...handshake, graphVersion: 'foreign' },
  { ...handshake, lifecycle: 'STOPPED' }, { ...handshake, type: 'gap' },
])('rejects incomplete or foreign confirmation %#', async frame => {
  const barrier = createDeploymentObservationBarrier({ source, isCurrent: () => true, cancel: vi.fn() });
  barrier.frame(frame, frame.type === 'gap' ? { accepted: true, terminal: true } : accepted);
  await expect(barrier.ready).rejects.toThrow(); expect(barrier.canAdmit()).toBe(false);
});

it('times out as failure and fences a late successful callback', async () => {
  vi.useFakeTimers(); const cancel = vi.fn();
  const barrier = createDeploymentObservationBarrier({ source, isCurrent: () => true, cancel });
  await vi.advanceTimersByTimeAsync(10000); await expect(barrier.ready).rejects.toThrow();
  barrier.frame(handshake, accepted); expect(barrier.canAdmit()).toBe(false); expect(cancel).toHaveBeenCalledTimes(1);
});

it('settles Stop cancellation and retirement without waiting for the deadline', async () => {
  const control = new AbortController(), cancel = vi.fn();
  const barrier = createDeploymentObservationBarrier({ source, isCurrent: () => true, cancel, signal: control.signal });
  control.abort(); await expect(barrier.ready).rejects.toThrow(); expect(cancel).toHaveBeenCalledTimes(1);
  barrier.frame(handshake, accepted); expect(barrier.canAdmit()).toBe(false);
});

it('revokes a prior confirmation when its owner or source is retired', async () => {
  let current = true;
  const barrier = createDeploymentObservationBarrier({ source, isCurrent: () => current, cancel: vi.fn() });
  barrier.frame(handshake, accepted); const proof = await barrier.ready;
  current = false; expect(proof.canAdmit()).toBe(false); barrier.retire();
});
