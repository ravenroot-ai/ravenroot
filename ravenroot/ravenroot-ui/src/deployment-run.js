import { productError } from './product-error.js';

const flights = new WeakMap();
const ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const PHASES = new Set(['PREPARED', 'REGISTERED', 'READY', 'RECONCILE', 'FAILED']);
const HARD_GRAPH_BYTES = 256 * 1024 * 1024;
const HARD_INPUT_BYTES = 64 * 1024 * 1024;
const failure = code => productError('workspace.run.associationChanged', new Error(code));
const utf8Bytes = value => new TextEncoder().encode(value).byteLength;

/** Closed persisted value: no client, controller, credentials, or browser promise crosses storage. */
export function persistedDeploymentRun(value) {
  if (value == null) return null;
  if (typeof value !== 'object' || Array.isArray(value) || value.schemaVersion !== 1
      || !['serviceUrl', 'tenantId', 'inputJson'].every(key => typeof value[key] === 'string' && value[key])
      || typeof value.graphMl !== 'string' || typeof value.registrationAllowed !== 'boolean'
      || value.registrationAllowed && !value.graphMl
      || !['deploymentId', 'activationRequestId', 'initialStartRequestId'].every(key => ID.test(value[key]))
      || !PHASES.has(value.phase)
      || (value.registrationIdentity !== null && (typeof value.registrationIdentity !== 'string' || !value.registrationIdentity || value.registrationIdentity.length > 256))
      || (value.graphVersion !== null && !/^[a-f0-9]{64}$/.test(value.graphVersion))
      || (value.incarnationId !== null && (typeof value.incarnationId !== 'string' || !value.incarnationId || value.incarnationId.length > 256))
      || !['processInstanceId', 'traversalId'].every(key => value[key] === null || UUID.test(value[key]))
      || (value.processInstanceId === null) !== (value.traversalId === null)
      || value.graphMl.length > HARD_GRAPH_BYTES || utf8Bytes(value.graphMl) > HARD_GRAPH_BYTES
      || value.inputJson.length > HARD_INPUT_BYTES || utf8Bytes(value.inputJson) > HARD_INPUT_BYTES) throw failure('INVALID_PERSISTED_RUN');
  try { JSON.parse(value.inputJson); } catch { throw failure('INVALID_INPUT_JSON'); }
  return { schemaVersion: 1, serviceUrl: value.serviceUrl, tenantId: value.tenantId,
    deploymentId: value.deploymentId, activationRequestId: value.activationRequestId, initialStartRequestId: value.initialStartRequestId,
    registrationIdentity: value.registrationIdentity, registrationAllowed: value.registrationAllowed, graphMl: value.graphMl, inputJson: value.inputJson, graphVersion: value.graphVersion,
    incarnationId: value.incarnationId, processInstanceId: value.processInstanceId, traversalId: value.traversalId, phase: value.phase };
}

export function createDeploymentRunIntent({ serviceUrl, tenantId, graphMl, inputJson,
  randomId = () => globalThis.crypto.randomUUID(), attachment = null }) {
  return persistedDeploymentRun({ schemaVersion: 1, serviceUrl, tenantId, graphMl, inputJson,
    deploymentId: attachment?.deploymentId || `run-${randomId()}`, activationRequestId: randomId(), initialStartRequestId: randomId(),
    registrationAllowed: !attachment, graphVersion: attachment?.graphVersion || null, incarnationId: null, registrationIdentity: null, processInstanceId: null, traversalId: null, phase: 'PREPARED' });
}

export async function graphVersionOf(graphMl) {
  const bytes = await globalThis.crypto.subtle.digest('SHA-256', new TextEncoder().encode(graphMl));
  return [...new Uint8Array(bytes)].map(byte => byte.toString(16).padStart(2, '0')).join('');
}

/** Coalesces user Run intents on the captured origin. Focus never grants or revokes authority.
 * Persist resolves only after the exact tenant workspace has durably stored the association.
 * Restoration calls no mutation here: only an explicit Run enters this function.
 */
export function runOriginDeployment(owner, { client, serviceUrl, tenantId, graphMl, inputJson,
  persist, isCurrent, pin = graphVersionOf, onPhase = () => {}, randomId, attachment = null }) {
  const existing = flights.get(owner);
  if (existing) return existing;
  const incarnation = owner.incarnation;
  const intent = owner.deploymentRun || createDeploymentRunIntent({ serviceUrl, tenantId, graphMl, inputJson, randomId, attachment });
  owner.deploymentRun = intent;
  const current = () => owner.incarnation === incarnation && owner.deploymentRun === intent && isCurrent(owner, client)
    && intent.serviceUrl === serviceUrl && intent.tenantId === tenantId;
  const check = () => { if (!current()) throw failure('ORIGIN_AUTHORITY_CHANGED'); };
  const save = async () => { check(); await persist(owner, intent); check(); onPhase(owner, intent); };
  const task = (async () => {
    check();
    if (!intent.graphVersion) intent.graphVersion = await pin(intent.graphMl);
    await save(); // Before even registration: response loss cannot manufacture another identity.
    try {
      let status;
      try { status = await client.deployment(intent.deploymentId); }
      catch (error) {
        if (error?.status !== 404) throw error;
        check();
        if (intent.registrationIdentity) status = await client.restoreDeployment(intent.deploymentId, { registrationIdentity: intent.registrationIdentity, graphVersion: intent.graphVersion });
        else {
          if (!intent.registrationAllowed || intent.incarnationId || intent.processInstanceId) throw failure('ATTACHED_DEPLOYMENT_NOT_FOUND');
          status = await client.registerDeployment(intent.deploymentId, intent.graphMl);
        }
      }
      check();
      if (status.tenantId !== tenantId || status.graphVersion !== intent.graphVersion) throw failure('DEPLOYMENT_PIN_MISMATCH');
      if (intent.registrationIdentity && (status.continuity !== 'DURABLE' || status.registrationIdentity !== intent.registrationIdentity))
        throw failure('DURABLE_REGISTRATION_CHANGED');
      if (!intent.registrationIdentity && status.continuity === 'DURABLE' && status.registrationIdentity)
        intent.registrationIdentity = status.registrationIdentity;
      const priorPhase = intent.phase;
      intent.phase = 'REGISTERED'; await save();
      if (!['READY', 'DEGRADED'].includes(status.state)) {
        // A later explicit Run may reactivate the same deployment. A previously proven activation
        // gets a new command identity; an unresolved delivery retains its saved request.
        if (intent.processInstanceId && priorPhase === 'READY') {
          intent.activationRequestId = (randomId || (() => globalThis.crypto.randomUUID()))(); await save();
        }
        const result = await client.startDeployment(intent.deploymentId, {
          expectedGeneration: status.deploymentGeneration, idempotencyKey: intent.activationRequestId,
        });
        check(); status = result.status || result;
      }
      if (!['READY', 'DEGRADED'].includes(status.state)) {
        intent.phase = status.state === 'FAILED' ? 'FAILED' : 'RECONCILE'; await save();
        throw failure('ACTIVATION_NOT_READY');
      }
      const view = await client.deploymentView(intent.deploymentId); check();
      const rebound = Boolean(intent.incarnationId && intent.incarnationId !== view.source.incarnationId);
      if (view.source.graphVersion !== intent.graphVersion || view.source.deploymentId !== intent.deploymentId
          || rebound && !(intent.registrationIdentity && status.continuity === 'DURABLE' && status.registrationIdentity === intent.registrationIdentity))
        throw failure('DEPLOYMENT_INCARNATION_CHANGED');
      if (!rebound) intent.incarnationId = view.source.incarnationId;
      intent.phase = rebound ? 'RECONCILE' : 'READY'; await save();
      if (intent.processInstanceId && !rebound) return { intent, status, view, initial: null };
      // Saved admission identities require read-only marker reconciliation. An intent that never
      // received identities may finish its original admission on explicit Run; the permanent
      // marker atomically returns the original traversal if the earlier response was lost.
      const observingSavedAdmission = rebound && Boolean(intent.processInstanceId);
      const initial = observingSavedAdmission ? await client.reconcileInitialStartDeployment(intent.deploymentId, {
        incarnationId: view.source.incarnationId, graphVersion: intent.graphVersion, requestId: intent.initialStartRequestId,
      }) : await client.initialStartDeployment(intent.deploymentId, {
        incarnationId: view.source.incarnationId, graphVersion: intent.graphVersion, requestId: intent.initialStartRequestId,
        input: JSON.parse(intent.inputJson),
      });
      check();
      if (observingSavedAdmission && initial.outcome !== 'DUPLICATE' || !['ACCEPTED', 'DUPLICATE'].includes(initial.outcome)) throw failure('INITIAL_START_UNRESOLVED');
      if (initial.requestId !== intent.initialStartRequestId
          || intent.processInstanceId && (intent.processInstanceId !== initial.processInstanceId || intent.traversalId !== initial.traversalId))
        throw failure('INITIAL_START_IDENTITY_CHANGED');
      intent.processInstanceId = initial.processInstanceId; intent.traversalId = initial.traversalId;
      intent.incarnationId = view.source.incarnationId; intent.phase = 'READY'; await save();
      return { intent, status, view, initial };
    } catch (error) {
      if (current() && intent.phase !== 'FAILED') { intent.phase = 'RECONCILE'; await save(); }
      throw error;
    }
  })();
  flights.set(owner, task);
  task.finally(() => { if (flights.get(owner) === task) flights.delete(owner); }).catch(() => {});
  return task;
}
