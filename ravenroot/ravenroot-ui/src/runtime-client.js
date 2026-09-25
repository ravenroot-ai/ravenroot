import {
  validateHumanTaskAttention,
  validateHumanTaskCapability,
  validateHumanTaskRow,
} from './human-task-attention.js';

const DEFAULT_MAX_FRAME_BYTES = 64 * 1024;
const DEFAULT_MAX_RETRIES = 5;
const DEFAULT_RETRY_DELAY_MS = 1_000;
const MIN_RETRY_DELAY_MS = 250;
const MAX_RETRY_DELAY_MS = 30_000;
const HUMAN_TASK_DECISION_OUTCOMES = new Set(['APPLIED', 'ALREADY_APPLIED']);

export const MAX_GRAPH_DOCUMENT_BYTES = 256 * 1024 * 1024;
const LEGACY_PROGRAM_AUTHORING = Object.freeze({
  maxSourceBytes: 1024 * 1024,
  maxBuildRequestBytes: 10 * 1024 * 1024,
  maxProgramsPerBuild: 256,
});

function validProgramAuthoring(authoring) {
  return authoring && typeof authoring === 'object' && !Array.isArray(authoring)
    && Number.isSafeInteger(authoring.maxSourceBytes)
    && authoring.maxSourceBytes >= 1
    && authoring.maxSourceBytes <= LEGACY_PROGRAM_AUTHORING.maxSourceBytes
    && Number.isSafeInteger(authoring.maxBuildRequestBytes)
    && authoring.maxBuildRequestBytes >= authoring.maxSourceBytes
    && authoring.maxBuildRequestBytes <= LEGACY_PROGRAM_AUTHORING.maxBuildRequestBytes
    && Number.isSafeInteger(authoring.maxProgramsPerBuild)
    && authoring.maxProgramsPerBuild >= 1
    && authoring.maxProgramsPerBuild <= LEGACY_PROGRAM_AUTHORING.maxProgramsPerBuild;
}

/** Validate the versioned operator-owned limits exposed by the connected runtime. */
export function validateRuntimeConfiguration(value) {
  const legacy = value?.schemaVersion === 1 && value?.programAuthoring === undefined;
  const authoring = legacy ? LEGACY_PROGRAM_AUTHORING : value?.programAuthoring;
  if (!value || typeof value !== 'object' || Array.isArray(value)
      || (value.schemaVersion !== 1 && value.schemaVersion !== 2)
      || (value.schemaVersion === 1 && !legacy)
      || !Number.isSafeInteger(value.graphDocumentMaxBytes)
      || value.graphDocumentMaxBytes < 1
      || value.graphDocumentMaxBytes > MAX_GRAPH_DOCUMENT_BYTES
      || !validProgramAuthoring(authoring)) {
    throw new Error('Runtime configuration is not a supported versioned document');
  }
  let workspace = null;
  if (value.workspace !== undefined) {
    if (!value.workspace || typeof value.workspace !== 'object' || Array.isArray(value.workspace)
        || typeof value.workspace.tenantId !== 'string' || value.workspace.tenantId.length === 0
        || Object.keys(value.workspace).some(key => key !== 'tenantId')) {
      throw new Error('Runtime configuration workspace scope is malformed');
    }
    workspace = Object.freeze({ tenantId: value.workspace.tenantId });
  }
  const humanTasks = value.humanTasks == null ? null : validateHumanTaskCapability(value.humanTasks);
  return {
    schemaVersion: value.schemaVersion,
    graphDocumentMaxBytes: value.graphDocumentMaxBytes,
    programAuthoring: Object.freeze({
      maxSourceBytes: authoring.maxSourceBytes,
      maxBuildRequestBytes: authoring.maxBuildRequestBytes,
      maxProgramsPerBuild: authoring.maxProgramsPerBuild,
    }),
    workspace,
    ...(humanTasks ? { humanTasks } : {}),
  };
}

export class RuntimeAuthorizationError extends Error {
  constructor(message, status) {
    super(message);
    this.name = 'RuntimeAuthorizationError';
    this.status = status;
  }
}

// A failed request used to surface only the JSON body's `error` field -- status, method and
// route were already known to the client at the point of failure and were simply not shown, so
// "method not allowed" reached the user with nothing to act on. Built once here, in `#json`, so
// every caller (start, run, execution, nodeTypes, program-artifact operations) gets the same
// context instead of each call site having to add it.
//
// `status` is null when the fetch promise itself rejected. That is NOT the same thing as "the
// request never reached the service": a browser rejects the fetch promise, with the identical
// shape, both for a genuine network/DNS/TLS failure and for a request the service actually
// received and refused on origin grounds -- `BrowserOriginPolicy` answers every /v1 route with a
// 403 that carries no `Access-Control-Allow-Origin`, and the browser reports that to the caller as
// a rejected promise, not as a readable 403. The client cannot tell these two
// apart from here, so the message must not assert which one happened -- it names the possibilities
// instead of picking one.
export class RuntimeRequestError extends Error {
  constructor(reason, { status = null, method, path, code = null, correlationId = null,
    incidentId = null, finding = null } = {}) {
    const route = String(path || '').split('?')[0];
    const where = status == null ? `${method} ${route}` : `HTTP ${status} ${method} ${route}`;
    const hint = status == null
      ? ' -- check the runtime service address, its network reachability, or whether it allows this origin'
      : status === 404
        ? ' -- check the runtime service address: the request reached a server but not an API route'
        : '';
    super(`${reason} (${where})${hint}`);
    this.name = 'RuntimeRequestError';
    this.status = status;
    this.method = method;
    this.path = path;
    this.code = code;
    this.correlationId = correlationId;
    this.incidentId = incidentId;
    this.finding = finding;
  }
}

const ADMISSION_PHASES = new Set([
  'GRAPHML_PARSE', 'SEMANTIC_STRUCTURE', 'PROPERTY_SCHEMA', 'CAPABILITY', 'RUNTIME_NATURE',
  'BYPASS', 'RUNTIME_CONCURRENCY', 'RESOURCE_LIMIT', 'SOURCE_REQUIREMENT',
  'SOURCE_CONSTRUCTION', 'SOURCE_START', 'MANAGED_INGRESS', 'STARTUP',
]);
const ADMISSION_REASONS = new Set([
  'GRAPHML_REJECTED', 'INVALID_STRUCTURE', 'DUPLICATE_ELEMENT', 'MISSING_START', 'MISSING_END',
  'INVALID_TERMINAL_COUNT', 'DANGLING_EDGE', 'INVALID_PROPERTY', 'REQUIRED_PROPERTY_MISSING',
  'PROPERTY_TYPE_INVALID', 'PROPERTY_VALUE_NOT_ALLOWED', 'PROPERTY_OUT_OF_RANGE',
  'PROPERTY_TOO_LARGE', 'PROPERTY_COLLECTION_INVALID', 'PROPERTY_NAME_NEAR_MISS',
  'RESERVED_PROPERTY', 'WORKSPACE_REFERENCE_INVALID', 'REMOVED_BEHAVIOR',
  'CAPABILITY_UNAVAILABLE', 'RUNTIME_NATURE_INVALID', 'RUNTIME_NATURE_NOT_ALLOWED',
  'RUNTIME_NATURE_UNSUPPORTED', 'BYPASS_INVALID', 'RUNTIME_CONCURRENCY_INVALID',
  'GRAPH_LIMIT_EXCEEDED', 'SOURCE_CAPABILITY_MISMATCH', 'SOURCE_REQUIRED',
  'SOURCE_START_REFUSED', 'STARTUP_FAILED',
]);
const SAFE_HANDLE = /^[A-Za-z0-9._:-]{1,128}$/;
const NODE_REF = /^sha256:[0-9a-f]{32}$/;
const SOURCE_REASON = /^[a-z][a-z0-9-]{0,63}$/;
const UNSAFE_DIAGNOSTIC_TEXT = /[\u0000-\u001f\u007f-\u009f\u061c\u200e\u200f\u202a-\u202e\u2066-\u2069]/u;
const UNREDACTED_CREDENTIAL = /(?:\b(?:authorization|proxy-authorization)\s*[:=]\s*(?:bearer|basic)\s+|\bbearer\s+|\b(?:api[-_.]?key|access[-_.]?token|refresh[-_.]?token|id[-_.]?token|password|passwd|client[-_.]?secret|private[-_.]?key|credential|secret|token)\s*[:=]\s*)(?!\[ravenroot:redacted:credential\])\S+/iu;
const JWT_CREDENTIAL = /\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b/u;
const UNREDACTED_LOCATION_ASSIGNMENT = /(?:^|[^A-Za-z0-9_])(?:host(?:name)?|profile)\s*[:=]\s*(?!\[ravenroot:redacted:(?:host|profile)\])[^\s,;|?&#]+/iu;
const UNREDACTED_URI_AUTHORITY = /[a-z][a-z0-9+.-]{0,31}:\/\/(?!\[ravenroot:redacted:host\])[^\s/?#]+/iu;
const PROPERTY_TOKEN = /^[A-Za-z][A-Za-z0-9_.-]{0,63}$/u;

function safeDiagnosticToken(value, maximumBytes) {
  return typeof value === 'string' && value.length > 0 && !UNSAFE_DIAGNOSTIC_TEXT.test(value)
    && value === value.normalize('NFC') && !UNREDACTED_CREDENTIAL.test(value)
    && !JWT_CREDENTIAL.test(value) && !UNREDACTED_LOCATION_ASSIGNMENT.test(value)
    && !UNREDACTED_URI_AUTHORITY.test(value)
    && new TextEncoder().encode(value).length <= maximumBytes;
}

export function validateDiagnosticFinding(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)
      || value.contract !== 'ravenroot.graph-admission/1'
      || !ADMISSION_PHASES.has(value.phase) || !ADMISSION_REASONS.has(value.reason)
      || !SAFE_HANDLE.test(value.incidentId || '')
      || (value.nodeId !== undefined && !safeDiagnosticToken(value.nodeId, 128))
      || (value.nodeRef !== undefined && !NODE_REF.test(value.nodeRef))
      || ((value.nodeId === undefined) !== (value.nodeRef === undefined))
      || (value.propertyName !== undefined && (!PROPERTY_TOKEN.test(value.propertyName)
        || !safeDiagnosticToken(value.propertyName, 64)))) {
    throw new Error('Graph admission finding is malformed');
  }
  return Object.freeze({ ...value });
}

export function validateStartupFailure(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)
      || value.contract !== 'ravenroot.startup-failure/1' || !ADMISSION_PHASES.has(value.phase)
      || !(value.reason === 'STARTUP_FAILED' || SOURCE_REASON.test(value.reason || ''))
      || !SAFE_HANDLE.test(value.incidentId || '')
      || (value.nodeId !== undefined && !safeDiagnosticToken(value.nodeId, 128))
      || (value.nodeRef !== undefined && !NODE_REF.test(value.nodeRef))
      || ((value.nodeId === undefined) !== (value.nodeRef === undefined))) {
    throw new Error('Startup failure is malformed');
  }
  return Object.freeze({ ...value });
}

function validatedErrorEnvelope(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)
      || value.contract !== 'ravenroot.error/1'
      || typeof value.code !== 'string' || !/^[A-Za-z0-9._:-]{1,128}$/.test(value.code)
      || !safeDiagnosticToken(value.message, 512)
      || (value.error !== undefined && value.error !== value.message)
      || !SAFE_HANDLE.test(value.correlationId || '')
      || (value.incidentId !== undefined && !SAFE_HANDLE.test(value.incidentId))) return null;
  let finding = null;
  try { if (value.finding !== undefined) finding = validateDiagnosticFinding(value.finding); }
  catch { return null; }
  if (finding && value.incidentId !== finding.incidentId) return null;
  return Object.freeze({ code: value.code, message: value.message, correlationId: value.correlationId,
    incidentId: value.incidentId || null, finding });
}

// The runtime refused to compile the artifact's source, and said why. Distinct from
// RuntimeRequestError because nothing about the request failed -- it reached the service, the
// service ran the runtime, and this is the runtime's answer.
//
// The location is NOT appended to the message even though `line` and `column` are carried here.
// Measured against the real worker, both shipped languages already state it in their own text
// ("... on line 1 (artifact-id, line 2)" for GraalPy, "artifact-id:1:31 ..." for Graal.js), so
// appending it would print the same fact twice in one sentence. The fields exist for a caller that
// wants to act on the position -- move a cursor, mark a gutter -- rather than read it.
export class ProgramSourceRejectedError extends Error {
  constructor({ diagnostic = '', line = 0, column = 0, artifactId = '' } = {}) {
    super(`The program source was rejected: ${diagnostic || 'the runtime stated no reason'}`);
    this.name = 'ProgramSourceRejectedError';
    this.diagnostic = diagnostic;
    this.line = line;
    this.column = column;
    this.artifactId = artifactId;
  }
}

function validateProgramBuildSnapshot(value, expectedBuildId = '') {
  if (!value || typeof value !== 'object' || Array.isArray(value)
      || typeof value.buildId !== 'string' || !value.buildId
      || !Number.isSafeInteger(value.revision) || value.revision < 1
      || typeof value.terminal !== 'boolean' || !Array.isArray(value.programs)) {
    throw new Error('Program build response is not a valid durable snapshot');
  }
  if (expectedBuildId && value.buildId !== expectedBuildId) {
    throw new Error(`Program build response id ${value.buildId} does not match ${expectedBuildId}`);
  }
  return value;
}

const SOURCE_SESSION_STATES = new Set([
  'STARTING', 'LISTENING', 'DEGRADED', 'FAILED', 'STOPPING', 'STOPPED',
]);

export function validateSourceSessionStatus(value, expectedSessionId = '') {
  if (!value || typeof value !== 'object' || Array.isArray(value)
      || typeof value.sessionId !== 'string' || !value.sessionId
      || !SOURCE_SESSION_STATES.has(value.state)
      || !Number.isSafeInteger(value.sourceCount) || value.sourceCount < 1
      || value.scope !== 'LOCAL_PROCESS'
      // Optional rather than required, and the difference is deliberate. A runtime that does not
      // report the deployment its session runs under cannot have its events attributed to the
      // graph, but that is a degraded view -- refusing the response outright would turn it into a
      // failure to start, which is worse and is not what the caller asked about. `updateSourceSession`
      // says so once in the activity panel instead of leaving the canvas quietly blank.
      || (value.deploymentId !== null && value.deploymentId !== undefined
        && (typeof value.deploymentId !== 'string' || !value.deploymentId))
      || (value.diagnostic !== null && value.diagnostic !== undefined
        && (typeof value.diagnostic !== 'string' || value.diagnostic.length > 192
          || !safeDiagnosticToken(value.diagnostic, 768)))
      || (value.failure !== null && value.failure !== undefined && (() => {
        try { validateStartupFailure(value.failure); return false; } catch { return true; }
      })())
      || (value.failure !== null && value.failure !== undefined && value.state !== 'FAILED')) {
    throw new Error('Source session response is not a valid process-local status');
  }
  if (expectedSessionId && value.sessionId !== expectedSessionId) {
    throw new Error(`Source session response id ${value.sessionId} does not match ${expectedSessionId}`);
  }
  return value;
}

// The tenant-scoped, process-local deployment lifecycle. Unlike SOURCE_SESSION_STATES above,
// `sourceCount` here is legitimately 0 -- a graph naming no effective SOURCE is registrable and
// controllable as a deployment, which is the capability this lifecycle exposes. Source sessions
// still require >= 1; see `validateSourceSessionStatus` above.
const LOCAL_DEPLOYMENT_STATES = new Set([
  'REGISTERED', 'STARTING', 'READY', 'DEGRADED', 'STOPPING', 'STOPPED', 'FAILED',
]);
const DEPLOYMENT_COMMAND_OUTCOMES = new Set([
  'ACCEPTED', 'CONVERGED', 'REPLAYED', 'IDEMPOTENCY_CONFLICT', 'STALE_GENERATION',
  'SUPERSEDED', 'REFUSED', 'FAILED', 'TERMINAL',
]);
const LIFECYCLE_COMMANDS = new Set([
  'START', 'PAUSE', 'RESUME', 'CANCEL', 'DRAIN', 'STOP', 'RESTART', 'UNDEPLOY',
]);
const LIFECYCLE_COMMANDS_BY_SCOPE = Object.freeze({
  DEPLOYMENT: LIFECYCLE_COMMANDS,
  PROCESS: new Set(['PAUSE', 'RESUME', 'CANCEL', 'DRAIN', 'STOP']),
});

export function validateLifecycleCapabilities(value, expectedScope) {
  if (!value || typeof value !== 'object' || Array.isArray(value)
      || value.contractVersion !== 1 || value.scope !== expectedScope
      || !Array.isArray(value.commands) || value.commands.length === 0
      || (value.drainBound !== undefined && (typeof value.drainBound !== 'string' || !value.drainBound))) {
    throw new Error('Lifecycle capabilities are not a supported versioned contract');
  }
  const seen = new Set();
  const allowed = LIFECYCLE_COMMANDS_BY_SCOPE[expectedScope];
  const commands = value.commands.map(item => {
    if (!item || typeof item !== 'object' || Array.isArray(item)
        || !allowed?.has(item.command) || seen.has(item.command)
        || typeof item.available !== 'boolean' || typeof item.reasonRequired !== 'boolean'
        || (item.unavailableReason !== null && typeof item.unavailableReason !== 'string')
        || (item.available && item.unavailableReason !== null)
        || (!item.available && !item.unavailableReason)) {
      throw new Error('Lifecycle capabilities contain an invalid command');
    }
    seen.add(item.command);
    return Object.freeze({ ...item });
  });
  return Object.freeze({ ...value, commands: Object.freeze(commands) });
}

function safeGeneration(value) {
  return Number.isSafeInteger(value) && value >= 0;
}

function ambiguousDeploymentDelivery(error) {
  return error instanceof RuntimeRequestError
    && (error.status == null || (error.status === 200
      && /could not be read|not valid JSON/i.test(error.message)));
}

export function validateDeploymentCommandOutcome(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)
      || !DEPLOYMENT_COMMAND_OUTCOMES.has(value.outcome)) {
    throw new Error('Deployment command response is not a valid durable outcome');
  }
  const valid = (() => {
    switch (value.outcome) {
      case 'ACCEPTED':
        return typeof value.commandId === 'string' && value.commandId
          && safeGeneration(value.fromGeneration) && safeGeneration(value.generation)
          && value.generation === value.fromGeneration + 1;
      case 'CONVERGED':
        return typeof value.commandId === 'string' && value.commandId
          && safeGeneration(value.generation) && typeof value.observed === 'string' && value.observed;
      case 'REPLAYED':
        return value.original?.outcome !== 'REPLAYED'
          && Boolean(validateDeploymentCommandOutcome(value.original));
      case 'IDEMPOTENCY_CONFLICT':
        return typeof value.key === 'string' && value.key;
      case 'STALE_GENERATION':
        return safeGeneration(value.expected) && safeGeneration(value.generation)
          && value.expected !== value.generation;
      case 'SUPERSEDED':
        return typeof value.by === 'string' && value.by && safeGeneration(value.generation);
      case 'REFUSED':
        return typeof value.reason === 'string' && value.reason;
      case 'FAILED':
        return typeof value.cause === 'string' && value.cause;
      case 'TERMINAL':
        return typeof value.commandId === 'string' && value.commandId && safeGeneration(value.generation);
      default:
        return false;
    }
  })();
  if (!valid) throw new Error('Deployment command response is not a valid durable outcome');
  return value;
}

const EXECUTION_CONTROL_OUTCOMES = Object.freeze({
  pause: new Set(['PAUSED', 'ALREADY_PAUSED', 'NOT_ACTIVE']),
  resume: new Set(['RESUMED', 'NOT_PAUSED', 'NOT_ACTIVE']),
  cancel: new Set(['CANCELLED', 'ALREADY_CANCELLED', 'ALREADY_COMPLETED']),
});

function validateExecutionControlResult(value, expectedExecutionId, operation) {
  if (!value || typeof value !== 'object' || Array.isArray(value)
      || !EXECUTION_CONTROL_OUTCOMES[operation]?.has(value.outcome)
      || value.traversalId !== expectedExecutionId
      || typeof value.note !== 'string') {
    throw new Error(`Execution ${operation} response is invalid`);
  }
  return value;
}

export function validateLocalDeploymentStatus(value, expectedDeploymentId = '') {
  if (!value || typeof value !== 'object' || Array.isArray(value)
      || typeof value.deploymentId !== 'string' || !value.deploymentId
      || typeof value.tenantId !== 'string' || !value.tenantId
      || !LOCAL_DEPLOYMENT_STATES.has(value.state)
      || !Number.isSafeInteger(value.sourceCount) || value.sourceCount < 0
      || value.scope !== 'LOCAL_PROCESS'
      || (value.deploymentGeneration !== null && value.deploymentGeneration !== undefined
        && !safeGeneration(value.deploymentGeneration))
      || (value.graphVersion !== null && value.graphVersion !== undefined
        && (typeof value.graphVersion !== 'string' || !value.graphVersion))
      || !['PROCESS_LOCAL', 'DURABLE'].includes(value.continuity)
      || (value.deploymentRevision !== null && (!safeGeneration(value.deploymentRevision)
        || value.deploymentRevision < 1))
      || (value.desiredState !== null && (typeof value.desiredState !== 'string' || !value.desiredState))
      || (value.observedState !== null && (typeof value.observedState !== 'string' || !value.observedState))
      || (value.recoveryFailure !== null
        && (typeof value.recoveryFailure !== 'string' || !value.recoveryFailure))
      || (value.diagnostic !== null && value.diagnostic !== undefined
        && (typeof value.diagnostic !== 'string' || value.diagnostic.length > 192
          || !safeDiagnosticToken(value.diagnostic, 768)))
      || (value.failure !== null && value.failure !== undefined && (() => {
        try { validateStartupFailure(value.failure); return false; } catch { return true; }
      })())
      || (value.failure !== null && value.failure !== undefined && value.state !== 'FAILED')) {
    throw new Error('Deployment response is not a valid process-local status');
  }
  if (expectedDeploymentId && value.deploymentId !== expectedDeploymentId) {
    throw new Error(`Deployment response id ${value.deploymentId} does not match ${expectedDeploymentId}`);
  }
  const durable = value.deploymentGeneration !== null && value.deploymentGeneration !== undefined;
  if (durable !== (value.continuity === 'DURABLE')
      || durable !== (value.deploymentRevision !== null)
      || durable !== (value.desiredState !== null)
      || durable !== (value.observedState !== null)) {
    throw new Error('Deployment response carries inconsistent continuity metadata');
  }
  if (value.lifecycleCapabilities !== undefined) {
    validateLifecycleCapabilities(value.lifecycleCapabilities, 'DEPLOYMENT');
  }
  return value;
}

export function validateProcessInventoryPage(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value) || !Array.isArray(value.items)
      || !Number.isSafeInteger(value.maxPageSize) || value.maxPageSize < 1
      || typeof value.retainedFrom !== 'string'
      || (value.nextCursor !== null && (typeof value.nextCursor !== 'string' || !value.nextCursor))) {
    throw new Error('Process inventory response is not a valid authoritative page');
  }
  for (const item of value.items) {
    if (!item || typeof item !== 'object' || Array.isArray(item)
        || typeof item.tenantId !== 'string' || !item.tenantId
        || typeof item.processInstanceId !== 'string' || !item.processInstanceId
        || typeof item.status !== 'string' || !item.status
        || (item.terminationReason !== null
          && (typeof item.terminationReason !== 'string' || !item.terminationReason))
        || typeof item.cancelled !== 'boolean'
        || typeof item.disposition !== 'string' || !item.disposition
        || typeof item.graphVersion !== 'string' || !item.graphVersion
        || !Number.isSafeInteger(item.revision) || item.revision < 1
        || !Number.isSafeInteger(item.lifecycleGeneration) || item.lifecycleGeneration < 0
        || !Number.isSafeInteger(item.fencingToken) || item.fencingToken < 0
        || (item.controlState !== null && item.controlState !== undefined
          && (typeof item.controlState !== 'string' || !item.controlState))) {
      throw new Error('Process inventory contains an invalid authoritative target');
    }
    if (item.lifecycleCapabilities !== undefined) {
      validateLifecycleCapabilities(item.lifecycleCapabilities, 'PROCESS');
    }
  }
  return value;
}

// Whether the service requires authentication is the SERVICE'S answer, never a constant compiled
// into this bundle. The client sends the request and reacts to the status it gets back: a 401 or a
// 403 is surfaced as a RuntimeAuthorizationError exactly as before, and a rejected credential is
// cleared when its provider can atomically prove that the rejected snapshot is still current. What
// was removed is only the client's refusal to ASK — which made a loopback service
// that authorises everyone look like a service that had rejected us, and emptied the node palette
// without a single request leaving the browser. The gate itself lives on the server and is
// untouched; a bearer token is still attached whenever one is held, still never placed in a URL,
// and cross-origin use is still gated by explicit user confirmation in the app layer.
export class RavenrootRuntimeClient {
  constructor(baseUrl, options = {}) {
    this.baseUrl = normalizeBaseUrl(baseUrl);
    this.fetchImpl = options.fetchImpl || globalThis.fetch?.bind(globalThis);
    this.tokenProvider = options.tokenProvider || memoryTokenProvider(options.accessToken);
    this.maxFrameBytes = options.maxFrameBytes || DEFAULT_MAX_FRAME_BYTES;
    this.maxRetries = options.maxRetries ?? DEFAULT_MAX_RETRIES;
    this.retryDelayMs = options.retryDelayMs || DEFAULT_RETRY_DELAY_MS;
    this.sleep = options.sleep || (delay => new Promise(resolve => setTimeout(resolve, delay)));
    this.connection = null;
    this.deploymentConnections = new Set();
    this.lastEventId = '';
  }

  connect(onEvent, onConnectionChange = () => {}) {
    this.disconnect();
    if (!this.fetchImpl) throw new Error('Fetch API is not supported by this browser');
    const controller = new AbortController();
    this.connection = controller;
    void this.#consumeEvents(controller.signal, onEvent, onConnectionChange);
    return () => this.disconnect();
  }

  async #consumeEvents(signal, onEvent, onConnectionChange) {
    let failures = 0;
    let refreshed = false;
    while (!signal.aborted && failures <= this.maxRetries) {
      try {
        const credential = await this.#requestCredential();
        if (signal.aborted) return;
        const headers = { Accept: 'text/event-stream' };
        if (this.lastEventId) headers['Last-Event-ID'] = this.lastEventId;
        const response = await this.fetchImpl(`${this.baseUrl}/v1/events?include=diagnostics`, {
          method: 'GET',
          headers: this.#headers(headers, credential.accessToken),
          credentials: 'omit',
          cache: 'no-store',
          signal,
        });
        if (signal.aborted) return;
        if (response.status === 401 && !refreshed
            && typeof this.tokenProvider.refreshAccessTokenIfCurrent === 'function') {
          const refreshSucceeded = await this.#refreshAccessTokenIfCurrent(credential);
          if (signal.aborted) return;
          if (refreshSucceeded) {
            refreshed = true;
            continue;
          }
        }
        if (response.status === 401 || response.status === 403) {
          await this.#clearAccessTokenIfCurrent(credential);
          if (signal.aborted) return;
          throw new RuntimeAuthorizationError(
            response.status === 401 ? 'Authentication expired' : 'Access revoked', response.status);
        }
        if (!response.ok || !response.body?.getReader) {
          throw new Error(`Live events failed with HTTP ${response.status}`);
        }

        refreshed = false;
        onConnectionChange('connected', 'Live events connected');
        const reconnectDelay = await this.#readEventStream(response.body.getReader(), signal, onEvent,
          onConnectionChange);
        if (signal.aborted) return;
        failures += 1;
        onConnectionChange('reconnecting', 'Live events disconnected');
        await this.sleep(reconnectDelay);
      } catch (error) {
        if (signal.aborted) return;
        if (error instanceof RuntimeAuthorizationError) {
          onConnectionChange(error.status === 403 ? 'revoked' : 'authentication-required', error.message);
          return;
        }
        failures += 1;
        if (failures > this.maxRetries) {
          onConnectionChange('error', `Live events stopped after ${this.maxRetries} retries: ${error.message}`);
          return;
        }
        onConnectionChange('reconnecting', error.message);
        await this.sleep(this.retryDelayMs);
      }
    }
    if (!signal.aborted) {
      onConnectionChange('error', `Live events stopped after ${this.maxRetries} retries`);
    }
  }

  async #readEventStream(reader, signal, onEvent, onConnectionChange) {
    const decoder = new TextDecoder();
    let buffer = '';
    let reconnectDelay = this.retryDelayMs;
    while (!signal.aborted) {
      const { value, done } = await reader.read();
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done }).replace(/\r\n/g, '\n');
      if (buffer.length > this.maxFrameBytes && !buffer.includes('\n\n')) {
        await reader.cancel?.();
        throw new Error(`SSE frame exceeds ${this.maxFrameBytes} bytes`);
      }
      let boundary;
      while ((boundary = buffer.indexOf('\n\n')) >= 0) {
        const frame = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary + 2);
        if (frame.length > this.maxFrameBytes) throw new Error(`SSE frame exceeds ${this.maxFrameBytes} bytes`);
        const parsed = parseEventFrame(frame);
        if (parsed.retry !== undefined) {
          reconnectDelay = Math.min(MAX_RETRY_DELAY_MS, Math.max(MIN_RETRY_DELAY_MS, parsed.retry));
        }
        if (parsed.type === 'execution' && /^data(?::|$)/m.test(frame)) {
          try {
            const event = normalizeRuntimeEvent(JSON.parse(parsed.data));
            if (Object.hasOwn(event, 'schemaVersion')) {
              executionEventCursor(parsed.id);
              if (parsed.id !== event.id) throw new Error('Execution event identity mismatch');
            }
            await onEvent(event);
            if (signal.aborted) return reconnectDelay;
            // Resume only after delivery accepts this execution, never across a rejected frame.
            if (parsed.id !== undefined && !parsed.id.includes('\0')) this.lastEventId = parsed.id;
          } catch {
            try { await reader.cancel?.(); } catch { /* Preserve the classified stream failure. */ }
            throw new Error('Invalid or undelivered execution event');
          }
        }
      }
      if (done) return reconnectDelay;
    }
    return reconnectDelay;
  }

  async start(graphMl, payload = '') {
    return this.#json(`/v1/executions?payload=${encodeURIComponent(payload)}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/graphml+xml; charset=utf-8' },
      body: graphMl,
    });
  }

  async inspectGraph(graphMl, purpose = 'EXECUTION') {
    if (!['EXECUTION', 'LOCAL_DEPLOYMENT', 'SOURCE_SESSION'].includes(purpose)) {
      throw new Error('Unsupported graph admission purpose');
    }
    const result = await this.#json(`/v1/graphs/inspect?purpose=${encodeURIComponent(purpose)}`, {
      method: 'POST',
      headers: { Accept: 'application/json', 'Content-Type': 'application/graphml+xml; charset=utf-8' },
      body: graphMl,
    });
    if (!result || typeof result !== 'object' || Array.isArray(result)
        || typeof result.valid !== 'boolean') throw new Error('Graph inspection response is malformed');
    // `findings` was added after the inspection endpoint. An N-1 runtime still returns the
    // authoritative `valid` flag plus its legacy `violations` array; absence of the new field is
    // not itself a refusal and must not make optional preflight a dependency of start.
    if (result.findings === undefined) {
      if (!Array.isArray(result.violations)) throw new Error('Graph inspection response is malformed');
      return Object.freeze({ ...result, findings: Object.freeze([]) });
    }
    if (!Array.isArray(result.findings) || result.findings.length > 1) {
      throw new Error('Graph inspection response is malformed');
    }
    return Object.freeze({ ...result,
      findings: Object.freeze(result.findings.map(validateDiagnosticFinding)) });
  }

  async run(graphMl, payload = '') {
    return this.#json(`/v1/executions?mode=run&payload=${encodeURIComponent(payload)}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/graphml+xml; charset=utf-8' },
      body: graphMl,
    });
  }

  async startSourceSession(sessionId, graphMl) {
    const id = String(sessionId || '');
    if (!id) throw new Error('Source session start requires an id');
    const result = await this.#json(`/v1/source-sessions?id=${encodeURIComponent(id)}`, {
      method: 'POST',
      headers: { Accept: 'application/json', 'Content-Type': 'application/graphml+xml; charset=utf-8' },
      body: graphMl,
    });
    return validateSourceSessionStatus(result, id);
  }

  async sourceSession(sessionId, { signal } = {}) {
    const id = String(sessionId || '');
    if (!id) throw new Error('Source session observation requires an id');
    const result = await this.#json(`/v1/source-sessions/${encodeURIComponent(id)}`, {
      method: 'GET', headers: { Accept: 'application/json' }, signal,
    });
    return validateSourceSessionStatus(result, id);
  }

  async stopSourceSession(sessionId) {
    const id = String(sessionId || '');
    if (!id) throw new Error('Source session stop requires an id');
    const result = await this.#json(`/v1/source-sessions/${encodeURIComponent(id)}`, {
      method: 'DELETE', headers: { Accept: 'application/json' },
    });
    return validateSourceSessionStatus(result, id);
  }

  /**
   * Lists the authenticated tenant's own process-local deployments, in the order the
   * server returns them -- the wire shape `{ deployments: [...] }`, each entry the same status object
   * {@link #deployment} returns for one id, so each is validated the same way.
   */
  async deployments() {
    const result = await this.#json('/v1/deployments', {
      method: 'GET', headers: { Accept: 'application/json' },
    });
    if (!result || typeof result !== 'object' || !Array.isArray(result.deployments)) {
      throw new Error('Deployment list response is not a valid process-local status list');
    }
    return result.deployments.map(entry => validateLocalDeploymentStatus(entry));
  }

  /**
   * Registers an immutable graph version under `deploymentId`. Starts nothing -- the returned
   * status is REGISTERED; {@link #startDeployment} is the separate call that serves it. Re-registering
   * the identical id with the identical graph is a no-op that returns the current status; a different
   * graph is refused (409 on the wire).
   */
  async registerDeployment(deploymentId, graphMl) {
    const id = String(deploymentId || '');
    if (!id) throw new Error('Deployment registration requires an id');
    const result = await this.#json(`/v1/deployments?id=${encodeURIComponent(id)}`, {
      method: 'POST',
      headers: { Accept: 'application/json', 'Content-Type': 'application/graphml+xml; charset=utf-8' },
      body: graphMl,
    });
    return validateLocalDeploymentStatus(result, id);
  }

  /** Observes one registered deployment; a 404 means this tenant holds no such id. */
  async deployment(deploymentId, { signal } = {}) {
    const id = String(deploymentId || '');
    if (!id) throw new Error('Deployment observation requires an id');
    const result = await this.#json(`/v1/deployments/${encodeURIComponent(id)}`, {
      method: 'GET', headers: { Accept: 'application/json' }, signal,
    });
    return validateLocalDeploymentStatus(result, id);
  }

  /** Resolves one immutable, tenant-scoped deployment presentation without returning raw GraphML. */
  async deploymentView(deploymentId, { signal } = {}) {
    const id = String(deploymentId || '');
    if (!id) throw new Error('Deployment view requires an id');
    const result = await this.#json(`/v1/deployments/${encodeURIComponent(id)}/view`, {
      method: 'GET', headers: { Accept: 'application/json' }, signal,
    });
    return validateDeploymentViewEnvelope(result, id);
  }

  /**
   * Opens the deployment/version/incarnation-scoped event stream with an Authorization header.
   * Credentials never enter the URL and the server remains responsible for filtering before a
   * frame is queued or serialized.
   */
  connectDeploymentView(envelope, onFrame, onConnectionChange = () => {}) {
    const view = validateDeploymentViewEnvelope(envelope);
    const controller = new AbortController();
    this.deploymentConnections.add(controller);
    void this.#consumeDeploymentEvents(view, controller.signal, onFrame, onConnectionChange)
      .finally(() => this.deploymentConnections.delete(controller));
    return () => controller.abort();
  }

  async #consumeDeploymentEvents(view, signal, onFrame, onConnectionChange) {
    let failures = 0;
    let cursor = '';
    while (!signal.aborted && failures <= this.maxRetries) {
      const source = view.source;
      const path = `/v1/deployments/${encodeURIComponent(source.deploymentId)}/events`
        + `?graphVersion=${encodeURIComponent(source.graphVersion)}`;
      try {
        const credential = await this.#requestCredential();
        if (signal.aborted) return;
        const headers = {
          Accept: 'text/event-stream',
          'X-Ravenroot-Deployment-Incarnation': source.incarnationId,
        };
        if (cursor) headers['Last-Event-ID'] = cursor;
        const response = await this.fetchImpl(`${this.baseUrl}${path}`, {
          method: 'GET', headers: this.#headers(headers, credential.accessToken), credentials: 'omit',
          cache: 'no-store', signal,
        });
        if (response.status === 401 || response.status === 403) {
          await this.#clearAccessTokenIfCurrent(credential);
          throw new RuntimeAuthorizationError(response.status === 401
            ? 'Authentication expired' : 'Deployment observation was revoked', response.status);
        }
        if (!response.ok || !response.body?.getReader) {
          throw new RuntimeRequestError('Deployment event stream failed', {
            status: response.status, method: 'GET', path,
          });
        }
        onConnectionChange('connected', 'Deployment live observation connected');
        const read = await this.#readDeploymentStream(response.body.getReader(), signal, source, frame => {
          cursor = frame.cursor || cursor;
          onFrame(frame);
        });
        if (signal.aborted || read.terminal) return;
        failures += 1;
        onConnectionChange('reconnecting', 'Deployment observation disconnected; reconnecting');
        await this.sleep(read.retryDelay);
      } catch (error) {
        if (signal.aborted) return;
        if (error instanceof RuntimeAuthorizationError) {
          onConnectionChange('revoked', error.message);
          return;
        }
        failures += 1;
        if (failures > this.maxRetries) {
          onConnectionChange('error', `Deployment observation stopped: ${error.message}`);
          return;
        }
        onConnectionChange('reconnecting', error.message);
        await this.sleep(this.retryDelayMs);
      }
    }
  }

  async #readDeploymentStream(reader, signal, source, onFrame) {
    const decoder = new TextDecoder();
    let buffer = '';
    let retryDelay = this.retryDelayMs;
    let terminal = false;
    while (!signal.aborted) {
      const { value, done } = await reader.read();
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done }).replace(/\r\n/g, '\n');
      if (buffer.length > this.maxFrameBytes && !buffer.includes('\n\n')) {
        await reader.cancel?.();
        throw new Error(`SSE frame exceeds ${this.maxFrameBytes} bytes`);
      }
      let boundary;
      while ((boundary = buffer.indexOf('\n\n')) >= 0) {
        const raw = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary + 2);
        if (raw.length > this.maxFrameBytes) throw new Error(`SSE frame exceeds ${this.maxFrameBytes} bytes`);
        const parsed = parseEventFrame(raw);
        if (parsed.retry !== undefined) {
          retryDelay = Math.min(MAX_RETRY_DELAY_MS, Math.max(MIN_RETRY_DELAY_MS, parsed.retry));
        }
        if (!parsed.data) continue;
        const payload = JSON.parse(parsed.data);
        const type = parsed.type === 'execution' ? 'execution'
          : parsed.type === 'source-gap' ? 'gap'
            : parsed.type === 'source-invalidated' ? 'invalidated' : parsed.type;
        const frame = validateDeploymentViewFrame({
          type,
          deploymentId: payload.deploymentId ?? source.deploymentId,
          graphVersion: payload.graphVersion ?? source.graphVersion,
          incarnationId: payload.incarnationId ?? source.incarnationId,
          cursor: parsed.id || payload.cursor,
          ...(type === 'execution' ? { event: deploymentExecutionEvent(payload) } : {}),
          ...(type === 'lifecycle' ? { lifecycle: payload.lifecycle } : {}),
          ...(['gap', 'invalidated'].includes(type) ? { reason: payload.reason } : {}),
        });
        onFrame(frame);
        if (frame.type === 'gap' || frame.type === 'invalidated'
            || (frame.type === 'lifecycle' && frame.lifecycle === 'UNDEPLOYED')) {
          terminal = true;
          await reader.cancel?.();
          return { retryDelay, terminal };
        }
      }
      if (done) return { retryDelay, terminal };
    }
    return { retryDelay, terminal };
  }

  /** Starts a registered deployment; the call answers only once it has reached READY, or the
   * truthful FAILED state if startup rolled back -- never merely "accepted". */
  async startDeployment(deploymentId, options = {}) {
    return this.#deploymentCommand(deploymentId, 'start', options);
  }

  /** Stops a deployment and leaves it registered and re-startable -- distinct from
   * {@link #undeployDeployment}, which stops it and then removes the registration. */
  async stopDeployment(deploymentId, options = {}) {
    return this.#deploymentCommand(deploymentId, 'stop', options);
  }

  /** A completed stop followed by a start, never the two overlapping (server-side
   * guarantee; see RouteTable's own note on `/v1/deployments/{id}/restart`). */
  async restartDeployment(deploymentId, options = {}) {
    return this.#deploymentCommand(deploymentId, 'restart', options);
  }

  async pauseDeployment(deploymentId, options = {}) {
    return this.#deploymentCommand(deploymentId, 'pause', options);
  }

  async resumeDeployment(deploymentId, options = {}) {
    return this.#deploymentCommand(deploymentId, 'resume', options);
  }

  async cancelDeployment(deploymentId, options = {}) {
    return this.#deploymentCommand(deploymentId, 'cancel', options);
  }

  async drainDeployment(deploymentId, options = {}) {
    return this.#deploymentCommand(deploymentId, 'drain', options);
  }

  /** Stops the deployment and then removes its registration. Legacy servers return the STOPPED
   * status captured at removal; durable servers return a typed terminal outcome and retain a tombstone
   * for exact replay even though the id no longer resolves through GET. */
  async undeployDeployment(deploymentId, options = {}) {
    return this.#deploymentCommand(deploymentId, 'undeploy', options);
  }

  async #deploymentCommand(deploymentId, action,
    { expectedGeneration, idempotencyKey, reason, disposition } = {}) {
    const id = String(deploymentId || '');
    if (!id) throw new Error(`Deployment ${action} requires an id`);
    const path = action === 'undeploy' ? `/v1/deployments/${encodeURIComponent(id)}`
      : `/v1/deployments/${encodeURIComponent(id)}/${action}`;
    const method = action === 'undeploy' ? 'DELETE' : 'POST';

    // Absence means a compatibility deployment. Presence switches the entire command onto the
    // durable contract; unsafe int64 values are refused rather than rounded into another fence.
    if (expectedGeneration === undefined || expectedGeneration === null) {
      const result = await this.#json(path, { method, headers: { Accept: 'application/json' } });
      return validateLocalDeploymentStatus(result, id);
    }
    if (!safeGeneration(expectedGeneration)) {
      throw new Error('Deployment generation is outside JavaScript’s safe integer range');
    }
    if ((['pause', 'cancel', 'stop', 'undeploy'].includes(action))
        && (typeof reason !== 'string' || !reason.trim() || reason.length > 256)) {
      throw new Error(`Deployment ${action} requires a reason of at most 256 characters`);
    }
    if (action === 'undeploy'
        && !['DRAIN_FIRST', 'CANCEL_IN_FLIGHT', 'REFUSE_IF_BUSY'].includes(disposition)) {
      throw new Error('Deployment undeploy requires an explicit supported disposition');
    }
    const key = idempotencyKey || globalThis.crypto?.randomUUID?.();
    if (typeof key !== 'string' || !key) {
      throw new Error('Secure deployment idempotency key generation is unavailable');
    }
    const headers = {
      Accept: 'application/json',
      'Idempotency-Key': key,
      'X-Ravenroot-Expected-Generation': String(expectedGeneration),
      ...((['pause', 'cancel', 'stop', 'undeploy'].includes(action))
        ? { 'X-Ravenroot-Reason': reason.trim() } : {}),
      ...(action === 'undeploy' ? { 'X-Ravenroot-Undeploy-Disposition': disposition } : {}),
    };
    let result;
    try {
      result = await this.#json(path, { method, headers });
    } catch (error) {
      if (!ambiguousDeploymentDelivery(error)) throw error;
      // A single transport retry is the only automatic resubmission, and it reuses the exact intent.
      try {
        result = await this.#json(path, { method, headers });
      } catch (secondError) {
        if (!ambiguousDeploymentDelivery(secondError)) throw secondError;
        try {
          const status = await this.deployment(id);
          return { outcome: null, status, reconciliation: {
            delivery: 'AMBIGUOUS', authoritative: 'STATE',
          } };
        } catch (readError) {
          if (action === 'undeploy' && readError instanceof RuntimeRequestError
              && readError.status === 404) {
            return { outcome: null, status: null, reconciliation: {
              delivery: 'AMBIGUOUS', authoritative: 'NOT_FOUND',
            } };
          }
          throw readError;
        }
      }
    }
    const outcome = validateDeploymentCommandOutcome(result);
    const terminal = outcome.outcome === 'TERMINAL'
      || (outcome.outcome === 'REPLAYED' && outcome.original.outcome === 'TERMINAL');
    const refreshOnly = ['STALE_GENERATION', 'SUPERSEDED', 'REFUSED', 'FAILED',
      'IDEMPOTENCY_CONFLICT'].includes(outcome.outcome);
    let status = null;
    const attempts = refreshOnly || terminal ? 1 : 40;
    for (let attempt = 0; attempt < attempts; attempt += 1) {
      try {
        status = await this.deployment(id);
      } catch (error) {
        if (error instanceof RuntimeRequestError && error.status === 404 && action === 'undeploy') {
          status = null;
          break;
        }
        throw error;
      }
      const settled = action === 'stop' ? ['STOPPED', 'FAILED'].includes(status.state)
        : action === 'undeploy' ? false
          : ['pause', 'resume', 'cancel', 'drain'].includes(action) ? true
          : ['READY', 'DEGRADED', 'FAILED'].includes(status.state);
      if (settled || refreshOnly) break;
      await this.sleep(250);
    }
    return { outcome, status };
  }

  async execution(executionId, { signal } = {}) {
    return this.#json(`/v1/executions/${encodeURIComponent(executionId)}`, {
      method: 'GET',
      headers: { Accept: 'application/json' },
      signal,
    });
  }

  async #controlExecution(executionId, operation, { signal } = {}) {
    const id = String(executionId || '');
    if (!id) throw new Error(`Execution ${operation} requires an id`);
    const result = await this.#json(`/v1/executions/${encodeURIComponent(id)}/${operation}`, {
      method: 'POST', headers: { Accept: 'application/json' }, signal,
    });
    return validateExecutionControlResult(result, id, operation);
  }

  async pauseExecution(executionId, options = {}) {
    return this.#controlExecution(executionId, 'pause', options);
  }

  async resumeExecution(executionId, options = {}) {
    return this.#controlExecution(executionId, 'resume', options);
  }

  async cancelExecution(executionId, options = {}) {
    return this.#controlExecution(executionId, 'cancel', options);
  }

  async controlProcess(processInstanceId, operation, expectedGeneration,
    { idempotencyKey, reason = '', signal } = {}) {
    const id = String(processInstanceId || '');
    const allowed = new Set(['pause', 'resume', 'cancel', 'drain', 'stop']);
    if (!id || !allowed.has(operation) || !Number.isSafeInteger(expectedGeneration)
        || expectedGeneration < 1 || typeof idempotencyKey !== 'string' || !idempotencyKey) {
      throw new Error('Process lifecycle command is invalid');
    }
    const query = reason ? `?reason=${encodeURIComponent(reason)}` : '';
    const result = await this.#json(`/v1/processes/${encodeURIComponent(id)}/${operation}${query}`, {
      method: 'POST', signal, headers: {
        Accept: 'application/json', 'Idempotency-Key': idempotencyKey,
        'X-Ravenroot-Expected-Generation': String(expectedGeneration),
      },
    });
    if (!result || result.processInstanceId !== id || !Number.isSafeInteger(result.generation)
        || typeof result.outcome !== 'string' || typeof result.state !== 'string'
        || typeof result.reason !== 'string' || !Array.isArray(result.traversals)) {
      throw new Error(`Process ${operation} response is invalid`);
    }
    return result;
  }

  /**
   * The durable, tenant-scoped process inventory (issue 154): what the runtime's own persisted
   * record says exists, surviving a restart. This is the authoritative source the UI shares with
   * the API, CLI, audit and recovery -- distinct from anything derived from the event stream or
   * kept only in this client's own memory, exactly as `execution()` above already reads the
   * server's stored outcome rather than reconstructing one from events.
   *
   * `filters` mirrors `GET /v1/executions/inventory`'s own optional query parameters verbatim
   * (`status`, `ownerWorkerId`, `deploymentId`, `includeTerminal`, `limit`, `cursor` -- named exactly
   * like the fields the response itself carries, not a shorter alias, so a caller filtering by a
   * value it just read off a previous response uses the identical name) rather than inventing a
   * client-side vocabulary for them; an absent or blank value is simply omitted from the request,
   * and the server refuses any other parameter name outright rather than ignoring it. The response
   * body -- `items`, `nextCursor`, `retainedFrom`, `maxPageSize` -- is returned unmodified, so a
   * caller can tell "never existed" from "expired by retention" from `retainedFrom` without a
   * second request, and can read this deployment's declared page-size bound from `maxPageSize`
   * instead of discovering it by trial and error.
   */
  async processInventory(filters = {}, { signal } = {}) {
    const params = new URLSearchParams();
    for (const key of ['status', 'ownerWorkerId', 'deploymentId', 'includeTerminal', 'limit', 'cursor']) {
      const value = filters?.[key];
      if (value === undefined || value === null || value === '') continue;
      params.set(key, String(value));
    }
    const query = params.toString();
    const result = await this.#json(`/v1/executions/inventory${query ? `?${query}` : ''}`, {
      method: 'GET',
      headers: { Accept: 'application/json' },
      signal,
    });
    return validateProcessInventoryPage(result);
  }

  /**
   * One durable process instance's traversals from the inventory (issue 154). `processInstanceId`
   * is a process instance id, not the `executionId`/traversal id `execution()` above takes -- see
   * `RavenrootServer#readProcessInstanceTraversals`'s own Javadoc for why the two id spaces are
   * deliberately distinct rather than an inconsistency. The response body -- `traversals`,
   * `retainedFrom` -- is returned unmodified; `retainedFrom` is this tenant's same retention floor
   * `processInventory()` carries, present here too so a caller diagnosing an absence has it on
   * whichever of the two responses it is holding.
   */
  async processInstanceTraversals(processInstanceId, { signal } = {}) {
    const id = String(processInstanceId || '');
    if (!id) throw new Error('Process instance traversals require an id');
    return this.#json(`/v1/executions/${encodeURIComponent(id)}/traversals`, {
      method: 'GET',
      headers: { Accept: 'application/json' },
      signal,
    });
  }

  /** Reads the bounded, tenant-authorized actionable Human Task projection for one exact graph
   * context. The runtime configuration supplies page and polling limits; this method supplies no
   * browser-owned defaults. */
  async humanTaskAttention(filters = {}, { signal, capability } = {}) {
    const policy = validateHumanTaskCapability(capability);
    const hasDeployment = typeof filters?.deploymentId === 'string' && filters.deploymentId.length > 0;
    const hasProcess = typeof filters?.processInstanceId === 'string' && filters.processInstanceId.length > 0;
    const hasGraph = typeof filters?.graphVersion === 'string' && filters.graphVersion.length > 0;
    const hasTask = typeof filters?.taskId === 'string' && filters.taskId.length > 0;
    const hasGeneration = filters?.generation !== undefined && filters?.generation !== null;
    const hasContextPart = hasGraph || hasDeployment || hasProcess
      || filters?.traversalId != null || filters?.nodeId != null;
    if (hasTask !== hasGeneration
        || (!hasTask && (!hasGraph || hasDeployment === hasProcess))
        || (hasTask && hasContextPart && (!hasGraph || hasDeployment === hasProcess))
        || (hasGeneration && (!Number.isSafeInteger(filters.generation) || filters.generation < 1))) {
      throw new Error('Human Task attention requires an exact task locator or graph and deployment or process context');
    }
    const requestedLimit = filters?.limit ?? policy.attentionPageSize;
    if (!Number.isSafeInteger(requestedLimit) || requestedLimit < 1
        || requestedLimit > policy.attentionPageSizeMax) {
      throw new Error('Human Task attention page size is outside the advertised policy');
    }
    const params = new URLSearchParams();
    for (const key of ['graphVersion', 'deploymentId', 'processInstanceId', 'traversalId', 'nodeId',
      'taskId', 'generation', 'limit', 'cursor']) {
      const value = filters?.[key];
      if (value === undefined || value === null || value === '') continue;
      params.set(key, String(value));
    }
    params.set('limit', String(requestedLimit));
    const result = await this.#json(`/v1/human-tasks/attention?${params}`, {
      method: 'GET', headers: { Accept: 'application/json' }, signal,
    });
    return validateHumanTaskAttention(result, policy, { ...filters, limit: Number(params.get('limit')) });
  }

  /** Applies one explicit simple-confirmation action. A comment is decision metadata in this JSON
   * resource; the server constructs the fixed resolve payload separately. */
  async confirmHumanTask(taskId, generation, action, comment = '', { signal, capability } = {}) {
    const policy = validateHumanTaskCapability(capability);
    const id = String(taskId || '');
    const normalizedAction = String(action || '').toLowerCase();
    if (!id || !Number.isSafeInteger(generation) || generation < 1
        || !['resolve', 'deny', 'cancel'].includes(normalizedAction)) {
      throw new Error('Human Task confirmation requires task id, generation, and a permitted action');
    }
    const result = await this.#json(`/v1/human-tasks/${encodeURIComponent(id)}/confirmation/`
      + `${normalizedAction}?generation=${generation}`, {
      method: 'POST',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify({ schemaVersion: 1, comment: String(comment ?? '') }), signal,
    });
    if (!result || typeof result !== 'object' || Array.isArray(result)
        || result.schemaVersion !== 1 || !HUMAN_TASK_DECISION_OUTCOMES.has(result.outcome)
        || !result.task) {
      throw new Error('Human Task confirmation response is invalid');
    }
    return Object.freeze({ schemaVersion: 1, outcome: result.outcome,
      task: validateHumanTaskRow(result.task, policy) });
  }

  /** Applies the canonical settlement document for confirmation, form, custom, or provider UI. */
  async settleHumanTask(task, action, comment = '', response = null,
    { signal, capability, overrideReason = null } = {}) {
    validateHumanTaskCapability(capability);
    const id = String(task?.taskId || '');
    const normalizedAction = String(action || '').toUpperCase();
    if (!id || !Number.isSafeInteger(task?.generation) || task.generation < 1
        || !['RESOLVE', 'DENY', 'CANCEL'].includes(normalizedAction)) {
      throw new Error('Human Task settlement requires task id, generation, and a permitted action');
    }
    const document = { schemaVersion: 1, action: normalizedAction, comment: String(comment ?? '') };
    if (normalizedAction === 'RESOLVE') {
      let envelope = response;
      if (!envelope && task.interactionPresentation?.kind === 'CONFIRMATION') {
        envelope = { contract: 'ravenroot.payload/1', schema: 'ravenroot.human-task.confirmation',
          schemaVersion: '1', kind: 'SCALAR', value: true };
      }
      if (!envelope) throw new Error('Resolve requires a typed Human Task response');
      const bytes = new TextEncoder().encode(JSON.stringify(envelope));
      let binary = '';
      bytes.forEach(byte => { binary += String.fromCharCode(byte); });
      document.response = { contentType: task.interactionPresentation?.kind === 'CONFIRMATION'
        ? 'application/json' : 'application/vnd.ravenroot.payload+json', payloadBase64: btoa(binary) };
    }
    if (overrideReason) document.override = { version: 1, reason: String(overrideReason) };
    const result = await this.#json(`/v1/human-tasks/${encodeURIComponent(id)}/settle?generation=${task.generation}`, {
      method: 'POST', headers: { Accept: 'application/json',
        'Content-Type': 'application/json; charset=utf-8' }, body: JSON.stringify(document), signal,
    });
    if (!result || result.schemaVersion !== 1 || typeof result.outcome !== 'string'
        || result.taskId !== id || !Number.isSafeInteger(result.generation)) {
      throw new Error('Human Task settlement response is invalid');
    }
    return Object.freeze(result);
  }

  /** Issues a short-lived registered-presentation capability for one exact task generation. */
  async issueHumanTaskInteraction(task, { signal, capability } = {}) {
    validateHumanTaskCapability(capability);
    const id = String(task?.taskId || '');
    if (!id || !Number.isSafeInteger(task?.generation) || task.generation < 1
        || !['CUSTOM', 'EXTERNAL'].includes(task?.interactionPresentation?.kind)) {
      throw new Error('Registered Human Task interaction requires an exact custom or external task');
    }
    const result = await this.#json(`/v1/human-tasks/${encodeURIComponent(id)}/interaction`
      + `?generation=${task.generation}`, {
      method: 'POST', headers: { Accept: 'application/json' }, signal,
    });
    if (!result || result.schemaVersion !== 1 || typeof result.capability !== 'string'
        || typeof result.capabilityId !== 'string' || typeof result.launchUri !== 'string'
        || typeof result.origin !== 'string' || result.taskId !== id
        || result.generation !== task.generation || !Array.isArray(result.actions)
        || !result.responseSchema || typeof result.responseSchema.maxBytes !== 'number') {
      throw new Error('Human Task interaction launch response is invalid');
    }
    const launch = new URL(result.launchUri);
    if (launch.origin !== result.origin || !['http:', 'https:'].includes(launch.protocol)) {
      throw new Error('Human Task interaction launch origin is invalid');
    }
    return Object.freeze({ ...result, launchUri: launch.href });
  }

  /** Completes through the capability-only endpoint; no bearer or browser credentials are sent. */
  async completeHumanTaskInteraction(launch, action, comment = '', response = null, { signal } = {}) {
    if (!this.fetchImpl || !launch?.capability || !launch?.taskId) {
      throw new Error('Human Task interaction capability is unavailable');
    }
    const normalizedAction = String(action || '').toUpperCase();
    const document = { schemaVersion: 1, capability: launch.capability,
      action: normalizedAction, comment: String(comment ?? '') };
    if (normalizedAction === 'RESOLVE') {
      if (!response || typeof response.contentType !== 'string'
          || typeof response.payloadBase64 !== 'string') {
        throw new Error('Registered Human Task resolve requires a bounded encoded response');
      }
      document.response = { contentType: response.contentType, payloadBase64: response.payloadBase64 };
    }
    const path = '/v1/human-task-interactions/complete';
    let responseMessage;
    try {
      responseMessage = await this.fetchImpl(`${this.baseUrl}${path}`, {
        method: 'POST', headers: { Accept: 'application/json',
          'Content-Type': 'application/json; charset=utf-8' },
        credentials: 'omit', cache: 'no-store', body: JSON.stringify(document), signal,
      });
    } catch (failure) {
      throw new RuntimeRequestError(failure?.message || 'Human Task interaction request failed',
        { method: 'POST', path });
    }
    const parsed = await responseMessage.json().catch(() => null);
    if (!responseMessage.ok) {
      throw new RuntimeRequestError(parsed?.message || parsed?.error || 'Human Task interaction was refused',
        { status: responseMessage.status, method: 'POST', path });
    }
    if (!parsed || parsed.schemaVersion !== 1 || typeof parsed.outcome !== 'string'
        || parsed.taskId !== launch.taskId || !Number.isSafeInteger(parsed.generation)) {
      throw new Error('Human Task interaction completion response is invalid');
    }
    return Object.freeze(parsed);
  }

  async revokeHumanTaskInteraction(task, launch, { signal } = {}) {
    if (!task?.taskId || !Number.isSafeInteger(task.generation) || !launch?.capability) return;
    await this.#json(`/v1/human-tasks/${encodeURIComponent(task.taskId)}/interaction`
      + `?generation=${task.generation}`, {
      method: 'DELETE', headers: { Accept: 'application/json',
        'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify({ schemaVersion: 1, capability: launch.capability }), signal,
    });
  }

  async nodeTypes() {
    const result = await this.#json('/v1/node-types', { method: 'GET', headers: { Accept: 'application/json' } });
    if (!Array.isArray(result)) throw new Error('Node catalog response is not an array');
    return result;
  }

  /** The connected runtime's effective, operator-owned browser ingestion configuration. */
  async configuration() {
    const result = await this.#json('/v1/configuration', {
      method: 'GET', headers: { Accept: 'application/json' },
    });
    return validateRuntimeConfiguration(result);
  }

  async programArtifacts() {
    return this.#json('/v1/program-artifacts', { method: 'GET', headers: { Accept: 'application/json' } });
  }

  /** Starts or rejoins one durable server-owned graph readiness operation. */
  async buildProgramArtifacts(programs, authoring = LEGACY_PROGRAM_AUTHORING) {
    if (!validProgramAuthoring(authoring)) {
      throw new Error('Program build requires valid authoring limits');
    }
    if (!Array.isArray(programs) || programs.length < 1
        || programs.length > authoring.maxProgramsPerBuild) {
      throw new Error(`Program build requires between 1 and ${authoring.maxProgramsPerBuild} programs`);
    }
    const submission = programs.map((program, index) => {
      const nodeId = String(program?.nodeId ?? '');
      const language = String(program?.language ?? '');
      const source = String(program?.source ?? '');
      const testPayload = String(program?.testPayload ?? 'test payload');
      if (!nodeId || !language) throw new Error(`Program build entry ${index + 1} requires nodeId and language`);
      if (new TextEncoder().encode(source).byteLength > authoring.maxSourceBytes) {
        throw new Error(`Program build entry ${index + 1} exceeds the configured source byte limit`);
      }
      return { nodeId, language, source, testPayload };
    });
    const body = JSON.stringify({ programs: submission });
    if (new TextEncoder().encode(body).byteLength > authoring.maxBuildRequestBytes) {
      throw new Error('Program build request exceeds the configured byte limit');
    }
    const result = await this.#json('/v1/program-artifacts/build', {
      method: 'POST',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json; charset=utf-8' },
      body,
    });
    return validateProgramBuildSnapshot(result);
  }

  /** Observes a durable build through the caller's authenticated tenant boundary. */
  async programArtifactBuild(buildId, { signal } = {}) {
    const id = String(buildId ?? '');
    if (!id) throw new Error('Program build observation requires a build id');
    const result = await this.#json(`/v1/program-artifacts/builds/${encodeURIComponent(id)}`, {
      method: 'GET', headers: { Accept: "application/json" }, signal,
    });
    return validateProgramBuildSnapshot(result, id);
  }

  /** One independently-authenticated graph-level approval; never a per-node lifecycle loop. */
  async approveProgramArtifactBatch(artifactIds, reason) {
    if (!Array.isArray(artifactIds) || artifactIds.length < 1 || artifactIds.length > 256) {
      throw new Error('Program batch approval requires between 1 and 256 artifact ids');
    }
    const body = {
      artifactIds: artifactIds.map(id => String(id)),
      reason: String(reason ?? ''),
    };
    if (body.artifactIds.some(id => !id) || !body.reason.trim()) {
      throw new Error('Program batch approval requires artifact ids and a reason');
    }
    const result = await this.#json('/v1/program-artifacts/approve-batch', {
      method: 'POST',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify(body),
    });
    if (!Array.isArray(result?.artifacts)) throw new Error('Program approval response has no artifacts array');
    return result.artifacts;
  }

  /**
   * The program languages this deployment's runtime declares support for -- read here rather
   * than listed in the workbench, so a language the runtime adds needs no change on this side. Each
   * entry carries `id` (the exact value to send back as `createProgramArtifact`'s `language`),
   * `displayName` and `exampleSource` (a starter an author can run unmodified).
   */
  async programLanguages() {
    const result = await this.#json('/v1/program-languages', { method: 'GET', headers: { Accept: 'application/json' } });
    if (!Array.isArray(result)) throw new Error('Program language catalog response is not an array');
    return result;
  }

  /**
   * `language` has no default. A default of 'javascript' would silently create a JavaScript artifact
   * when the caller omits the language. Omission instead fails here, in the client, rather than
   * creating an artifact in a language nobody chose.
   */
  async createProgramArtifact(source, { language, name = '' } = {}) {
    if (!language) throw new Error('createProgramArtifact requires an explicit language (no default)');
    const query = `language=${encodeURIComponent(language)}&name=${encodeURIComponent(name)}`;
    return this.#json(`/v1/program-artifacts?${query}`, {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain; charset=utf-8', Accept: 'application/json' },
      body: source,
    });
  }

  /**
   * Resolves with the artifact when the runtime accepted the source, and REJECTS with a
   * `ProgramSourceRejectedError` when it did not.
   *
   * The route answers 200 for both outcomes, because a source that does not compile is a result of a
   * well-formed request rather than a malformed one -- see the route's own description. That is
   * right for the HTTP contract and wrong for this method's caller: an author who pressed Validate
   * and got back an artifact still in `GENERATED` would be shown a completed step. Converting the
   * rejected outcome into a rejected promise here, in the transport adapter, is what keeps every
   * caller's existing `try/catch` correct without any of them learning the new body shape -- the
   * same reason `#json` builds `RuntimeRequestError` once instead of at each call site.
   *
   * Nothing else in the client hides an outcome this way, and nothing else should: this is a
   * transport whose two outcomes map onto a promise's two, not a licence to translate outcomes
   * generally.
   */
  async validateProgramArtifact(id) {
    const outcome = await this.#artifactOperation(id, 'validate');
    if (outcome?.outcome === 'rejected') throw new ProgramSourceRejectedError(outcome);
    return outcome?.artifact || outcome;
  }

  /**
   * Runs a smoke test using the explicit body contract. JSON is decoded by the service into a
   * structured payload; text stays literal even when it looks like JSON. New callers never place
   * test data in a URL.
   */
  async testProgramArtifact(id, payload = '', { mediaType = 'text/plain' } = {}) {
    if (mediaType !== 'application/json' && mediaType !== 'text/plain') {
      throw new Error('Program test payload mediaType must be application/json or text/plain');
    }
    let body;
    if (mediaType === 'application/json' && typeof payload !== 'string') {
      body = JSON.stringify(payload);
      if (body === undefined) throw new Error('Program test JSON payload is not serializable');
    } else {
      body = String(payload ?? '');
    }
    return this.#artifactOperation(id, 'test', {}, {
      body,
      headers: { Accept: 'application/json', 'Content-Type': `${mediaType}; charset=utf-8` },
    });
  }

  async approveProgramArtifact(id, reason) {
    return this.#artifactOperation(id, 'approve', { reason });
  }

  async activateProgramArtifact(id) {
    return this.#artifactOperation(id, 'activate');
  }

  async #artifactOperation(id, operation, parameters = {}, request = {}) {
    const query = Object.entries(parameters).map(([key, value]) =>
      `${encodeURIComponent(key)}=${encodeURIComponent(value)}`).join('&');
    return this.#json(`/v1/program-artifacts/${encodeURIComponent(id)}/${operation}${query ? `?${query}` : ''}`,
      { method: 'POST', ...request, headers: { Accept: 'application/json', ...(request.headers || {}) } });
  }

  runnerCatalog() {
    return this.#json('/v1/runner-plane/catalog', { method: 'GET', headers: { Accept: 'application/json' } });
  }
  runnerHealth(cursor = null) {
    return this.#json('/v1/runner-plane/health' + (cursor ? '?cursor=' + encodeURIComponent(cursor) : ''),
      { method: 'GET', headers: { Accept: 'application/json' } });
  }
  runnerAudit(afterOffset = 0) {
    if (!Number.isSafeInteger(afterOffset) || afterOffset < 0) throw new Error('Invalid runner audit offset');
    return this.#json('/v1/runner-plane/audit?afterOffset=' + afterOffset,
      { method: 'GET', headers: { Accept: 'application/json' } });
  }
  runnerResource(key) {
    return this.#json('/v1/runner-plane/catalog/' + encodeURIComponent(key),
      { method: 'GET', headers: { Accept: 'application/json' } });
  }
  saveRunnerResource(resource) {
    const body = JSON.stringify(resource);
    if (new TextEncoder().encode(body).length > 1_048_576) throw new Error('Runner definition exceeds the document limit');
    return this.#json('/v1/runner-plane/catalog', { method: 'PUT',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' }, body });
  }
  runnerWorkspace(processId) {
    return this.#json('/v1/runner-plane/workspaces/' + encodeURIComponent(processId),
      { method: 'GET', headers: { Accept: 'application/json' } });
  }
  runnerAvailability() {
    return this.#json('/v1/runner-plane/availability',
      { method: 'GET', headers: { Accept: 'application/json' } });
  }
  stopRunnerWorkspace(processId, nodeId, expectedRevision) {
    if (!Number.isSafeInteger(expectedRevision) || expectedRevision < 1 || !nodeId) throw new Error('Exact Workspace and revision required');
    return this.#json('/v1/runner-plane/workspaces/' + encodeURIComponent(processId)
      + '/resources/' + encodeURIComponent(nodeId) + '/abort',
    { method: 'POST', headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify({ expectedRevision }) });
  }
  runnerOperation(processId, jobId, operation) {
    if (!['cancel', 'reconcile'].includes(operation)) throw new Error('Unsupported operator runner action');
    return this.#json('/v1/runner-plane/workspaces/' + encodeURIComponent(processId)
      + '/jobs/' + encodeURIComponent(jobId) + '/' + operation,
    { method: 'POST', headers: { Accept: 'application/json' } });
  }
  runnerArtifact(processId, jobId, artifactId) {
    return this.#json('/v1/runner-plane/workspaces/' + encodeURIComponent(processId)
      + '/jobs/' + encodeURIComponent(jobId) + '/artifacts/' + encodeURIComponent(artifactId),
    { method: 'GET', headers: { Accept: 'application/json' } });
  }
  resolveRunnerContinuation(processId, jobId, expectedRevision, resolution) {
    if (!Number.isSafeInteger(expectedRevision) || expectedRevision < 1
        || !['RESUME', 'ACKNOWLEDGE', 'ABANDON'].includes(resolution)) throw new Error('Explicit runner continuation revision and resolution required');
    return this.#json('/v1/runner-plane/workspaces/' + encodeURIComponent(processId)
      + '/jobs/' + encodeURIComponent(jobId) + '/resolve-continuation',
    { method: 'POST', headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify({ expectedRevision, resolution }) });
  }

  async #json(path, options) {
    if (!this.fetchImpl) throw new Error('Fetch API is not supported by this browser');
    const credential = await this.#requestCredential();
    const method = options.method || 'GET';
    let response;
    try {
      response = await this.fetchImpl(`${this.baseUrl}${path}`, {
        ...options,
        credentials: 'omit',
        cache: 'no-store',
        headers: this.#headers(options.headers, credential.accessToken),
      });
    } catch {
      throw new RuntimeRequestError('Service request failed', { method, path });
    }
    if (response.status === 401 || response.status === 403) {
      await this.#clearAccessTokenIfCurrent(credential);
      throw new RuntimeAuthorizationError(response.status === 401 ? 'Authentication expired' : 'Access revoked',
        response.status);
    }
    // The body is read as text, never with `.json()` directly. A base URL that lands on a proxy,
    // CDN or unrelated server in front of the real service typically answers its own 404/error page
    // in HTML, not this service's JSON
    // envelope. Reading raw text first means a parse failure never loses status/method/route to an
    // opaque "Unexpected token '<' ... is not valid JSON": it still becomes a RuntimeRequestError.
    // `response.text()` rejecting (a transfer that cuts off mid-response)
    // is a different failure from a body that arrived intact and turned out not to be JSON --
    // folding both into "not valid JSON" would make the same false-cause mistake at the body read.
    // `readFailed` keeps them apart.
    let raw = '';
    let readFailed = false;
    try {
      raw = await response.text();
    } catch {
      readFailed = true;
    }
    let body = null;
    if (!readFailed) {
      try {
        body = raw ? JSON.parse(raw) : null;
      } catch {
        body = null;
      }
    }
    if (!response.ok) {
      const envelope = readFailed ? null : validatedErrorEnvelope(body);
      const legacyCode = !envelope && body && typeof body === 'object' && !Array.isArray(body)
        && typeof body.error === 'string' && /^[A-Z][A-Z0-9_]{0,127}$/.test(body.error)
        ? body.error : null;
      const reason = readFailed ? 'Service response could not be read'
        : envelope?.message || legacyCode || 'Service request failed';
      throw new RuntimeRequestError(reason, { status: response.status, method, path,
        code: envelope?.code || legacyCode, correlationId: envelope?.correlationId || null,
        incidentId: envelope?.incidentId || null, finding: envelope?.finding || null });
    }
    if (readFailed) {
      throw new RuntimeRequestError('Service response could not be read', { status: response.status, method, path });
    }
    if (body === null) {
      throw new RuntimeRequestError('Service response is not valid JSON', { status: response.status, method, path });
    }
    return body;
  }

  async #accessToken() {
    return String(await this.tokenProvider.getAccessToken?.() || '');
  }

  async #requestCredential() {
    if (typeof this.tokenProvider.getAccessTokenSnapshot !== 'function') {
      return Object.freeze({ accessToken: await this.#accessToken(), providerSnapshot: null });
    }
    const providerSnapshot = await this.tokenProvider.getAccessTokenSnapshot();
    if (!providerSnapshot || typeof providerSnapshot !== 'object'
        || typeof providerSnapshot.accessToken !== 'string') {
      throw new Error('Access token provider returned an invalid credential snapshot');
    }
    return Object.freeze({ accessToken: providerSnapshot.accessToken, providerSnapshot });
  }

  async #clearAccessTokenIfCurrent(credential) {
    if (!credential.providerSnapshot
        || typeof this.tokenProvider.clearAccessTokenIfCurrent !== 'function') return false;
    try {
      return Boolean(await this.tokenProvider.clearAccessTokenIfCurrent(credential.providerSnapshot));
    } catch {
      return false;
    }
  }

  async #refreshAccessTokenIfCurrent(credential) {
    if (!credential.providerSnapshot
        || typeof this.tokenProvider.clearAccessTokenIfCurrent !== 'function') return false;
    try {
      return Boolean(await this.tokenProvider.refreshAccessTokenIfCurrent(credential.providerSnapshot));
    } catch {
      return false;
    }
  }

  #headers(headers = {}, token = '') {
    return token ? { ...headers, Authorization: `Bearer ${token}` } : { ...headers };
  }

  disconnect() {
    this.connection?.abort();
    this.connection = null;
    for (const connection of this.deploymentConnections) connection.abort();
    this.deploymentConnections.clear();
  }
}

export function validateDeploymentViewEnvelope(value, expectedDeploymentId = '') {
  const source = value?.source;
  const projection = value?.projection;
  if (!value || value.viewerSourceVersion !== '1' || source?.kind !== 'deployment'
      || typeof source.deploymentId !== 'string' || !source.deploymentId
      || typeof source.graphVersion !== 'string' || !source.graphVersion
      || typeof source.incarnationId !== 'string' || !source.incarnationId
      || typeof value.lifecycle !== 'string'
      || projection?.viewerContractVersion !== '1.0'
      || !Array.isArray(projection.nodes) || !Array.isArray(projection.edges)) {
    throw new Error('Deployment view response is not a valid immutable projection');
  }
  if (expectedDeploymentId && source.deploymentId !== expectedDeploymentId) {
    throw new Error(`Deployment view id ${source.deploymentId} does not match ${expectedDeploymentId}`);
  }
  return value;
}

export function validateDeploymentViewFrame(frame) {
  if (!frame || !['execution', 'lifecycle', 'gap', 'invalidated'].includes(frame.type)
      || typeof frame.deploymentId !== 'string' || !frame.deploymentId
      || typeof frame.graphVersion !== 'string' || !frame.graphVersion
      || typeof frame.incarnationId !== 'string' || !frame.incarnationId) {
    throw new Error('Deployment observation frame is invalid');
  }
  if (frame.type === 'execution') frame = { ...frame, event: normalizeRuntimeEvent(frame.event) };
  return frame;
}

/** Closed runtime projection: fields not required to draw truthful activity never reach UI state. */
export function deploymentExecutionEvent(payload) {
  const value = payload?.event && typeof payload.event === 'object' ? payload.event : payload;
  return {
    type: value?.type,
    executionId: value?.executionId ?? value?.traversalId,
    nodeId: value?.nodeId ?? null,
    edgeId: value?.edgeId ?? null,
    activeInstances: Number(value?.activeInstances) || 0,
    inFlightArrivals: Number(value?.inFlightArrivals) || 0,
    fallback: Boolean(value?.fallback),
    occurredAt: typeof value?.occurredAt === 'string' ? value.occurredAt : null,
    publicReason: typeof value?.publicReason === 'string' ? value.publicReason : null,
    description: typeof value?.description === 'string' ? value.description : '',
  };
}

// Versioned SSE data and legacy unversioned projections share the UI's type/executionId aliases.
// Validate the declared wire variant before adding those UI aliases; unknown members survive.
export function normalizeRuntimeEvent(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('execution event must be an object');
  }
  const type = value.eventType ?? value.type;
  const executionId = value.executionId ?? value.traversalId;
  if (value.type != null && value.eventType != null && value.type !== value.eventType) {
    throw new Error('type and eventType disagree');
  }
  if (value.executionId != null && value.traversalId != null
      && value.executionId !== value.traversalId) {
    throw new Error('executionId and traversalId disagree');
  }
  if (typeof type !== 'string' || !type) throw new Error('execution event type is missing');
  if (typeof executionId !== 'string' || !executionId) {
    throw new Error('execution event traversal id is missing');
  }
  if (Object.hasOwn(value, 'schemaVersion')) validateVersionedRuntimeEvent(value);
  return { ...value, type, executionId };
}

const EVENT_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const LIVE_EVENT_FIELDS = [
  'sequence', 'engineId', 'executionId', 'activeInstances', 'inFlightArrivals', 'fallback',
  'publicReason', 'message', 'messageRedacted', 'messageTruncated', 'output',
  'outputRedacted', 'outputTruncated', 'processingDuration',
];
const DURABLE_EVENT_FIELDS = ['journalOffset', 'streamSequence', 'eventId', 'causationId', 'handlerId'];

function validateVersionedRuntimeEvent(value) {
  const invalid = () => { throw new Error('execution event is not a valid schema version 1 envelope'); };
  const text = field => typeof value[field] === 'string' && value[field].length > 0;
  const uuid = field => typeof value[field] === 'string' && EVENT_UUID.test(value[field]);
  const nullableUuid = field => value[field] === null || uuid(field);
  if (value.schemaVersion !== 1) throw new Error('unsupported execution event schema version');
  if (value.source !== 'RING' && value.source !== 'DURABLE') {
    throw new Error('unsupported execution event source');
  }
  if (!text('eventType') || !text('occurredAt') || !uuid('processInstanceId') || !uuid('traversalId')) invalid();
  if (Object.hasOwn(value, 'type') && typeof value.type !== 'string') invalid();
  // Instant's extended-year text is valid server data; Date.parse would narrow that contract.
  const id = executionEventCursor(value.id);
  const forbidden = value.source === 'RING' ? DURABLE_EVENT_FIELDS : LIVE_EVENT_FIELDS;
  if (forbidden.some(field => Object.hasOwn(value, field))) {
    throw new Error('execution event contains fields from the other source');
  }
  for (const field of ['graphVersion', 'description']) {
    if (Object.hasOwn(value, field) && typeof value[field] !== 'string') invalid();
  }
  for (const field of ['invocationId', 'attemptId']) {
    if (Object.hasOwn(value, field) && !nullableUuid(field)) invalid();
  }
  for (const field of ['nodeId', 'edgeId']) {
    if (Object.hasOwn(value, field) && value[field] !== null && typeof value[field] !== 'string') invalid();
  }
  const nativeCursor = value.source === 'RING' ? value.sequence : value.journalOffset;
  // JSON.parse rounds unsafe native numbers. This checks their representable value only; the
  // bounded decimal id remains the exact identity. Adjacent unsafe integers cannot be distinguished
  // after parsing, so never reconstruct an exact cursor from the compatibility number.
  if (!Number.isInteger(nativeCursor) || nativeCursor !== Number(id)) invalid();
  if (value.source === 'DURABLE') {
    if (id < 1n || !uuid('eventId') || !nullableUuid('causationId') || !nullableUuid('handlerId')
        || !Number.isInteger(value.streamSequence) || value.streamSequence < 1
        || value.streamSequence > Number(9223372036854775807n)) invalid();
    return;
  }
  if (typeof value.engineId !== 'string' || !uuid('executionId') || value.type !== value.eventType
      || !Number.isInteger(value.activeInstances) || value.activeInstances < 0
      || !Number.isInteger(value.inFlightArrivals) || value.inFlightArrivals < 0
      || typeof value.fallback !== 'boolean'
      || !(value.publicReason === null || (typeof value.publicReason === 'string'
          && value.publicReason.length <= 64 && /^[A-Za-z0-9._:-]+$/.test(value.publicReason)))
      || !(value.message === null || typeof value.message === 'string')
      || typeof value.messageRedacted !== 'boolean' || typeof value.messageTruncated !== 'boolean'
      || !(value.processingDuration === null || (typeof value.processingDuration === 'number'
          && Number.isFinite(value.processingDuration) && value.processingDuration >= 0))) invalid();
  for (const field of ['outputRedacted', 'outputTruncated']) {
    if (Object.hasOwn(value, field) && typeof value[field] !== 'boolean') invalid();
  }
}

function executionEventCursor(value) {
  if (typeof value !== 'string' || value.length > 20 || !/^(0|-?[1-9][0-9]*)$/.test(value)) {
    throw new Error('execution event id must be a canonical signed-long decimal string');
  }
  const id = BigInt(value);
  if (id < -9223372036854775808n || id > 9223372036854775807n) {
    throw new Error('execution event id is outside the signed-long range');
  }
  return id;
}

/**
 * Returns the process-memory credential provider used by the UI.
 *
 * Runtime authorization failures use the snapshot methods to clear only the credential generation
 * that issued the rejected request. Custom providers may implement the same three-method capability:
 * `getAccessTokenSnapshot`, `clearAccessTokenIfCurrent`, and optionally
 * `refreshAccessTokenIfCurrent`. The conditional methods may return promises, but must compare and
 * commit atomically. A conditional refresh must also reject a stale snapshot before starting its
 * external work and check it again when committing the new credential. Legacy providers that only
 * expose `getAccessToken` remain usable for requests; the runtime deliberately does not call their
 * unconditional clear or refresh methods on 401/403.
 */
export function memoryTokenProvider(initialToken = '') {
  let current = Object.freeze({ accessToken: String(initialToken || '') });
  const replace = value => {
    current = Object.freeze({ accessToken: String(value || '') });
  };
  return {
    getAccessToken: () => current.accessToken,
    getAccessTokenSnapshot: () => current,
    setAccessToken: replace,
    clearAccessToken: () => replace(''),
    clearAccessTokenIfCurrent: snapshot => {
      if (snapshot !== current) return false;
      replace('');
      return true;
    },
  };
}

export function parseEventFrame(frame) {
  const event = { type: 'message', data: '' };
  const data = [];
  for (const line of String(frame).split('\n')) {
    if (!line || line.startsWith(':')) continue;
    const colon = line.indexOf(':');
    const field = colon < 0 ? line : line.slice(0, colon);
    let value = colon < 0 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    if (field === 'event') event.type = value;
    else if (field === 'data') data.push(value);
    else if (field === 'id') event.id = value;
    else if (field === 'retry' && /^\d+$/.test(value)) event.retry = Number(value);
  }
  event.data = data.join('\n');
  return event;
}

function normalizeBaseUrl(value) {
  const baseUrl = String(value || '').trim().replace(/\/$/, '');
  if (!baseUrl) return '';
  const parsed = new URL(baseUrl, globalThis.location?.origin || 'http://localhost');
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') throw new Error('Service URL must use HTTP(S)');
  return parsed.origin + parsed.pathname.replace(/\/$/, '');
}
