const DEFAULT_MAX_FRAME_BYTES = 64 * 1024;
const DEFAULT_MAX_RETRIES = 5;
const DEFAULT_RETRY_DELAY_MS = 1_000;
const MIN_RETRY_DELAY_MS = 250;
const MAX_RETRY_DELAY_MS = 30_000;

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
  constructor(reason, { status = null, method, path } = {}) {
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
  }
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
      || (value.diagnostic !== null && value.diagnostic !== undefined
        && (typeof value.diagnostic !== 'string' || value.diagnostic.length > 192))) {
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

export function validateLocalDeploymentStatus(value, expectedDeploymentId = '') {
  if (!value || typeof value !== 'object' || Array.isArray(value)
      || typeof value.deploymentId !== 'string' || !value.deploymentId
      || !LOCAL_DEPLOYMENT_STATES.has(value.state)
      || !Number.isSafeInteger(value.sourceCount) || value.sourceCount < 0
      || value.scope !== 'LOCAL_PROCESS'
      || (value.diagnostic !== null && value.diagnostic !== undefined
        && (typeof value.diagnostic !== 'string' || value.diagnostic.length > 192))) {
    throw new Error('Deployment response is not a valid process-local status');
  }
  if (expectedDeploymentId && value.deploymentId !== expectedDeploymentId) {
    throw new Error(`Deployment response id ${value.deploymentId} does not match ${expectedDeploymentId}`);
  }
  return value;
}

// Whether the service requires authentication is the SERVICE'S answer, never a constant compiled
// into this bundle. The client sends the request and reacts to the status it gets back: a 401 or a
// 403 is surfaced as a RuntimeAuthorizationError exactly as before, and the in-memory token is
// cleared. What was removed is only the client's refusal to ASK — which made a loopback service
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
        const token = await this.#accessToken();
        const headers = { Accept: 'text/event-stream' };
        if (this.lastEventId) headers['Last-Event-ID'] = this.lastEventId;
        const response = await this.fetchImpl(`${this.baseUrl}/v1/events?include=diagnostics`, {
          method: 'GET',
          headers: this.#headers(headers, token),
          credentials: 'omit',
          cache: 'no-store',
          signal,
        });
        if (response.status === 401 && !refreshed && this.tokenProvider.refreshAccessToken) {
          refreshed = true;
          await this.tokenProvider.refreshAccessToken();
          continue;
        }
        if (response.status === 401 || response.status === 403) {
          await this.tokenProvider.clearAccessToken?.();
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
        if (parsed.id !== undefined && !parsed.id.includes('\0')) this.lastEventId = parsed.id;
        if (parsed.retry !== undefined) {
          reconnectDelay = Math.min(MAX_RETRY_DELAY_MS, Math.max(MIN_RETRY_DELAY_MS, parsed.retry));
        }
        if (parsed.type === 'execution' && parsed.data) {
          try {
            onEvent(normalizeRuntimeEvent(JSON.parse(parsed.data)));
          } catch (error) {
            onConnectionChange('error', `Invalid execution event: ${error.message}`);
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

  /** Starts a registered deployment; the call answers only once it has reached READY, or the
   * truthful FAILED state if startup rolled back -- never merely "accepted". */
  async startDeployment(deploymentId) {
    const id = String(deploymentId || '');
    if (!id) throw new Error('Deployment start requires an id');
    const result = await this.#json(`/v1/deployments/${encodeURIComponent(id)}/start`, {
      method: 'POST', headers: { Accept: 'application/json' },
    });
    return validateLocalDeploymentStatus(result, id);
  }

  /** Stops a deployment and leaves it registered and re-startable -- distinct from
   * {@link #undeployDeployment}, which stops it and then removes the registration. */
  async stopDeployment(deploymentId) {
    const id = String(deploymentId || '');
    if (!id) throw new Error('Deployment stop requires an id');
    const result = await this.#json(`/v1/deployments/${encodeURIComponent(id)}/stop`, {
      method: 'POST', headers: { Accept: 'application/json' },
    });
    return validateLocalDeploymentStatus(result, id);
  }

  /** A completed stop followed by a start, never the two overlapping (server-side
   * guarantee; see RouteTable's own note on `/v1/deployments/{id}/restart`). */
  async restartDeployment(deploymentId) {
    const id = String(deploymentId || '');
    if (!id) throw new Error('Deployment restart requires an id');
    const result = await this.#json(`/v1/deployments/${encodeURIComponent(id)}/restart`, {
      method: 'POST', headers: { Accept: 'application/json' },
    });
    return validateLocalDeploymentStatus(result, id);
  }

  /** Stops the deployment and then removes its registration -- the operation that turns
   * "registered and controlled as a local deployment" back into nothing, so an abandoned registration
   * does not outlive the editor session that created it. The response is the STOPPED status captured
   * at the moment of removal, not a fresh GET (the id no longer resolves after this call). */
  async undeployDeployment(deploymentId) {
    const id = String(deploymentId || '');
    if (!id) throw new Error('Deployment undeploy requires an id');
    const result = await this.#json(`/v1/deployments/${encodeURIComponent(id)}`, {
      method: 'DELETE', headers: { Accept: 'application/json' },
    });
    return validateLocalDeploymentStatus(result, id);
  }

  async execution(executionId, { signal } = {}) {
    return this.#json(`/v1/executions/${encodeURIComponent(executionId)}`, {
      method: 'GET',
      headers: { Accept: 'application/json' },
      signal,
    });
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
    return this.#json(`/v1/executions/inventory${query ? `?${query}` : ''}`, {
      method: 'GET',
      headers: { Accept: 'application/json' },
      signal,
    });
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

  async nodeTypes() {
    const result = await this.#json('/v1/node-types', { method: 'GET', headers: { Accept: 'application/json' } });
    if (!Array.isArray(result)) throw new Error('Node catalog response is not an array');
    return result;
  }

  async programArtifacts() {
    return this.#json('/v1/program-artifacts', { method: 'GET', headers: { Accept: 'application/json' } });
  }

  /** Starts or rejoins one durable server-owned graph readiness operation. */
  async buildProgramArtifacts(programs) {
    if (!Array.isArray(programs) || programs.length < 1 || programs.length > 256) {
      throw new Error('Program build requires between 1 and 256 programs');
    }
    const submission = programs.map((program, index) => {
      const nodeId = String(program?.nodeId ?? '');
      const language = String(program?.language ?? '');
      const source = String(program?.source ?? '');
      const testPayload = String(program?.testPayload ?? 'test payload');
      if (!nodeId || !language) throw new Error(`Program build entry ${index + 1} requires nodeId and language`);
      return { nodeId, language, source, testPayload };
    });
    const result = await this.#json('/v1/program-artifacts/build', {
      method: 'POST',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify({ programs: submission }),
    });
    return validateProgramBuildSnapshot(result);
  }

  /** Observes a durable build through the caller's authenticated tenant boundary. */
  async programArtifactBuild(buildId, { signal } = {}) {
    const id = String(buildId ?? '');
    if (!id) throw new Error('Program build observation requires a build id');
    const result = await this.#json(`/v1/program-artifacts/builds/${encodeURIComponent(id)}`, {
      method: 'GET', headers: { Accept: 'application/json' }, signal,
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

  async #json(path, options) {
    if (!this.fetchImpl) throw new Error('Fetch API is not supported by this browser');
    const token = await this.#accessToken();
    const method = options.method || 'GET';
    let response;
    try {
      response = await this.fetchImpl(`${this.baseUrl}${path}`, {
        ...options,
        credentials: 'omit',
        cache: 'no-store',
        headers: this.#headers(options.headers, token),
      });
    } catch (error) {
      throw new RuntimeRequestError(error.message || 'the request failed', { method, path });
    }
    if (response.status === 401 || response.status === 403) {
      await this.tokenProvider.clearAccessToken?.();
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
      const reason = readFailed
        ? 'Service response could not be read'
        : (body && typeof body.error === 'string' && body.error)
          || (raw.trim() ? raw.trim().slice(0, 200) : 'Service request failed');
      throw new RuntimeRequestError(reason, { status: response.status, method, path });
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

  #headers(headers = {}, token = '') {
    return token ? { ...headers, Authorization: `Bearer ${token}` } : { ...headers };
  }

  disconnect() {
    this.connection?.abort();
    this.connection = null;
  }
}

// `/v1/events` may replay the narrow durable projection (`eventType`, `traversalId`) or deliver the
// established live projection (`type`, `executionId`). Everything above this transport boundary
// consumes one canonical shape. Reject contradictory aliases instead of silently routing an event
// to the wrong document; traversalId is the caller-facing execution id by contract.
export function normalizeRuntimeEvent(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('execution event must be an object');
  }
  const type = value.type ?? value.eventType;
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
  return { ...value, type, executionId };
}

export function memoryTokenProvider(initialToken = '') {
  let token = String(initialToken || '');
  return {
    getAccessToken: () => token,
    setAccessToken: value => { token = String(value || ''); },
    clearAccessToken: () => { token = ''; },
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
