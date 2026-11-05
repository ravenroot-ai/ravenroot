import { productError } from './product-error.js';

const OBSERVATION_TIMEOUT_MS = 10_000;
const OPAQUE_CURSOR = /^[A-Za-z0-9_-]{43}$/;

/** A deadline is failure, never evidence of subscription. The scoped lifecycle cursor also
 * permits replay if an established stream drops before its first execution event. */
export function createDeploymentObservationBarrier({ source, isCurrent, cancel, signal,
  timeoutMs = OBSERVATION_TIMEOUT_MS }) {
  let pending = true, confirmed = false, terminal = false, resolve, reject;
  const ready = new Promise((yes, no) => { resolve = yes; reject = no; });
  // Read-only restored observers need not await readiness. Keep the original rejecting promise
  // available to admission callers without an unhandled rejection on later retirement.
  ready.catch(() => {});
  const cleanup = () => { clearTimeout(timer); signal?.removeEventListener('abort', aborted); };
  const fail = reason => {
    terminal = true; confirmed = false;
    if (pending) {
      pending = false; cleanup();
      reject(productError('workspace.run.observationFailed', new Error(reason)));
    }
    cancel();
  };
  const aborted = () => fail('OBSERVATION_CANCELLED');
  const timer = setTimeout(() => fail('OBSERVATION_TIMEOUT'), timeoutMs);
  const canAdmit = () => confirmed && !terminal && isCurrent();
  const barrier = {
    ready, canAdmit,
    get active() { return !terminal; },
    get pending() { return pending && !terminal; },
    frame(frame, result) {
      if (terminal) return;
      if (!isCurrent()) { fail('OBSERVATION_RETIRED'); return; }
      if (!result.accepted && ['duplicate', 'traversal-selection'].includes(result.reason)) return;
      if (!result.accepted || result.terminal
          || frame.deploymentId !== source.deploymentId || frame.graphVersion !== source.graphVersion
          || frame.incarnationId !== source.incarnationId) { fail('OBSERVATION_SOURCE_CHANGED'); return; }
      if (frame.type !== 'lifecycle') return;
      if (!['READY', 'DEGRADED'].includes(frame.lifecycle) || !OPAQUE_CURSOR.test(frame.cursor || '')) {
        fail('OBSERVATION_HANDSHAKE_INVALID'); return;
      }
      confirmed = true;
      if (pending) {
        pending = false; cleanup();
        resolve({ source, canAdmit });
      }
    },
    connection(state) {
      if (state !== 'connected') confirmed = false;
      if (state === 'revoked' || state === 'error') fail('OBSERVATION_UNAVAILABLE');
    },
    retire() { if (!terminal) fail('OBSERVATION_RETIRED'); },
  };
  signal?.addEventListener('abort', aborted, { once: true });
  if (signal?.aborted) aborted();
  return barrier;
}
