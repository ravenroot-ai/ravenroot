function requiredText(value, name) {
  if (typeof value !== 'string' || !value.length) throw new TypeError(`${name} is missing`);
  return value;
}

export const MAX_AUTHORED_RELEASE_VERSION = 9_007_199_254_740_991;

export function parseAuthoredReleaseVersion(value) {
  if (typeof value === 'number') {
    if (Number.isSafeInteger(value) && value >= 1 && value <= MAX_AUTHORED_RELEASE_VERSION) return value;
  } else if (typeof value === 'string' && /^[1-9][0-9]*$/.test(value)) {
    const parsed = BigInt(value);
    if (parsed <= BigInt(MAX_AUTHORED_RELEASE_VERSION)) return Number(parsed);
  }
  throw new TypeError('Authored release version must be a canonical safe positive integer');
}

function requiredSha256(value) {
  if (typeof value !== 'string' || !/^[0-9a-f]{64}$/.test(value)) {
    throw new TypeError('Published graph digest must be lowercase SHA-256');
  }
  return value;
}

function requiredArtifactRef(value) {
  if (typeof value !== 'string' || !/^[0-9a-f]{64}:[0-9a-f]{64}$/.test(value)) {
    throw new TypeError('Published artifact reference is malformed');
  }
  return value;
}

function validatePublishedArtifacts(value) {
  if (!value || typeof value !== 'object' || !Array.isArray(value.items)) {
    throw new Error('Published graph catalog response is malformed');
  }
  const identities = new Set();
  return Object.freeze({ items: Object.freeze(value.items.map(item => {
    if (!item || typeof item !== 'object' || Array.isArray(item)
        || typeof item.graphId !== 'string'
        || !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(item.graphId)
        || item.graphId === 'submission') {
      throw new Error('Published graph catalog response is malformed');
    }
    const releaseVersion = parseAuthoredReleaseVersion(item.releaseVersion);
    const identity = `${item.graphId}\0${releaseVersion}`;
    if (identities.has(identity)) throw new Error('Published graph catalog contains duplicate identities');
    identities.add(identity);
    return Object.freeze({ ...item, releaseVersion, sha256: requiredSha256(item.sha256),
      artifactRef: requiredArtifactRef(item.artifactRef) });
  })) });
}

export function validateGraphAuthoringCapability(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)
      || !['local', 'git'].includes(value.mode)
      || !['none', 'github'].includes(value.provider)
      || !Array.isArray(value.operations)
      || value.operations.some(item => typeof item !== 'string')) {
    throw new Error('Runtime graph authoring capability is malformed');
  }
  return Object.freeze({
    mode: value.mode,
    provider: value.provider,
    operations: Object.freeze([...value.operations]),
    repositoryDisplay: typeof value.repositoryDisplay === 'string' ? value.repositoryDisplay : '',
    releaseBranch: typeof value.releaseBranch === 'string' ? value.releaseBranch : '',
    maxDocumentBytes: Number.isSafeInteger(value.maxDocumentBytes) ? value.maxDocumentBytes : null,
  });
}

export class GraphAuthoringClient {
  constructor(baseUrl = '', { tokenProvider = () => null, fetchImpl = globalThis.fetch } = {}) {
    this.baseUrl = String(baseUrl).replace(/\/$/, '');
    if (typeof tokenProvider === 'function') this.tokenProvider = tokenProvider;
    else if (typeof tokenProvider?.getAccessToken === 'function') {
      this.tokenProvider = () => tokenProvider.getAccessToken();
    } else throw new TypeError('tokenProvider must supply an access token');
    if (typeof fetchImpl !== 'function') throw new TypeError('fetchImpl must be a function');
    // Calling a stored reference as `this.fetchImpl()` gives browser-native fetch the wrong
    // receiver in engines that enforce its Window binding. The wrapper invokes it as a plain
    // function while preserving injected test transports.
    this.fetchImpl = (...arguments_) => fetchImpl(...arguments_);
  }

  list(cursor = '') { return this.#json(`/v1/graph-authoring${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''}`); }
  open(documentId, source = 'draft') {
    return this.#json(`/v1/graph-authoring/${encodeURIComponent(documentId)}?source=${encodeURIComponent(source)}`);
  }
  history(documentId, cursor = '') {
    return this.#json(`/v1/graph-authoring/${encodeURIComponent(documentId)}/history${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''}`);
  }
  diff(documentId, from, to) {
    return this.#json(`/v1/graph-authoring/${encodeURIComponent(documentId)}/diff?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`);
  }
  save(documentId, graphMl, revision, idempotencyKey = crypto.randomUUID()) {
    return this.#json(`/v1/graph-authoring/${encodeURIComponent(documentId)}`, {
      method: 'PUT', body: graphMl, headers: this.#mutationHeaders(revision, idempotencyKey,
        { 'Content-Type': 'application/graphml+xml; charset=utf-8' }),
    });
  }
  restore(documentId, sourceRevision, revision, idempotencyKey = crypto.randomUUID()) {
    return this.#json(`/v1/graph-authoring/${encodeURIComponent(documentId)}/restore?revision=${encodeURIComponent(sourceRevision)}`,
      { method: 'POST', headers: this.#mutationHeaders(revision, idempotencyKey) });
  }
  discard(documentId, revision, idempotencyKey = crypto.randomUUID()) {
    return this.#json(`/v1/graph-authoring/${encodeURIComponent(documentId)}/discard`,
      { method: 'POST', headers: this.#mutationHeaders(revision, idempotencyKey) });
  }
  delete(documentId, revision, idempotencyKey = crypto.randomUUID()) {
    return this.#json(`/v1/graph-authoring/${encodeURIComponent(documentId)}`,
      { method: 'DELETE', headers: this.#mutationHeaders(revision, idempotencyKey), empty: true });
  }
  release(documentId, revision, idempotencyKey = crypto.randomUUID()) {
    return this.#json(`/v1/graph-authoring/${encodeURIComponent(documentId)}/release`,
      { method: 'POST', headers: this.#mutationHeaders(revision, idempotencyKey) });
  }
  async artifacts() { return validatePublishedArtifacts(await this.#json('/v1/graph-artifacts')); }
  importArtifact(graphId, releaseVersion, expectedSha256, expectedArtifactRef) {
    const version = parseAuthoredReleaseVersion(releaseVersion);
    const digest = requiredSha256(expectedSha256);
    const artifactRef = requiredArtifactRef(expectedArtifactRef);
    return this.#json(`/v1/graph-artifacts/${encodeURIComponent(graphId)}/${version}/import`
      + `?expectedSha256=${encodeURIComponent(digest)}&expectedArtifactRef=${encodeURIComponent(artifactRef)}`,
      { method: 'POST' });
  }
  deploy(graphId, releaseVersion, deploymentId, expectedSha256, expectedArtifactRef) {
    const version = parseAuthoredReleaseVersion(releaseVersion);
    const digest = requiredSha256(expectedSha256);
    const artifactRef = requiredArtifactRef(expectedArtifactRef);
    return this.#json(`/v1/graph-artifacts/${encodeURIComponent(graphId)}/${version}/deploy?id=${encodeURIComponent(deploymentId)}`
      + `&expectedSha256=${encodeURIComponent(digest)}&expectedArtifactRef=${encodeURIComponent(artifactRef)}`,
      { method: 'POST' });
  }

  #mutationHeaders(revision, key, extra = {}) {
    if (!revision) throw new TypeError('A source revision is required');
    return { ...extra, 'Idempotency-Key': requiredText(key, 'idempotency key'),
      'X-Ravenroot-Expected-Draft': requiredText(revision.draft, 'draft revision'),
      'X-Ravenroot-Expected-Release': requiredText(revision.release, 'release revision'),
      'X-Ravenroot-Expected-Publication': requiredText(revision.publication, 'publication revision') };
  }
  async #json(path, options = {}) {
    const headers = new Headers(options.headers || {});
    headers.set('Accept', 'application/json');
    const token = await this.tokenProvider();
    if (token) headers.set('Authorization', `Bearer ${token}`);
    const response = await this.fetchImpl(`${this.baseUrl}${path}`, { ...options, headers });
    if (options.empty && response.status === 204) return null;
    const value = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(value.error || `Graph authoring request failed (${response.status})`);
    return value;
  }
}

export function decodeGraphMl(document_) {
  return new TextDecoder('utf-8', { fatal: true }).decode(Uint8Array.from(
    atob(requiredText(document_?.graphMl, 'graphMl')), character => character.charCodeAt(0)));
}

const GRAPH_ID_PROPERTY = 'ravenroot.authoring.graphId';
const RELEASE_VERSION_PROPERTY = 'ravenroot.authoring.releaseVersion';

export function repositorySourceStatus(authoring) {
  const version = Number.isSafeInteger(authoring?.releaseVersion) && authoring.releaseVersion > 0
    ? `v${authoring.releaseVersion}` : 'version pending';
  const releasedVersion = Number.isSafeInteger(authoring?.releasedVersion) && authoring.releasedVersion > 0
    ? `v${authoring.releasedVersion}` : null;
  return Object.freeze({
    sourceState: authoring?.draftDeleted ? 'Released source' : 'Repository draft',
    version,
    releaseState: authoring?.released ? `Released${releasedVersion ? ` ${releasedVersion}` : ''}` : 'Not released',
    publicationState: authoring?.published ? 'Published'
      : authoring?.released ? 'Publication pending or failed' : null,
  });
}

/**
 * Makes the server-accepted GraphML the next serialization base while retaining the current graph
 * objects. Edits made during an in-flight save stay in the node/edge model; the accepted source
 * contributes only its canonical XML and server-owned identity/version fields.
 */
export function applyAcceptedGraphMl(currentGraph, document_, parseGraphMl) {
  if (!currentGraph || currentGraph.format !== 'graphml' || typeof parseGraphMl !== 'function') {
    throw new TypeError('An editable GraphML document and parser are required');
  }
  const accepted = parseGraphMl(decodeGraphMl(document_));
  if (accepted?.format !== 'graphml') throw new Error('Repository save returned a non-GraphML document');
  const graphId = accepted.graphProperties?.[GRAPH_ID_PROPERTY];
  const releaseVersion = accepted.graphProperties?.[RELEASE_VERSION_PROPERTY];
  if (graphId !== document_.graphId || String(releaseVersion) !== String(document_.releaseVersion)) {
    throw new Error('Repository save returned inconsistent authored identity metadata');
  }
  currentGraph.sourceXml = accepted.sourceXml;
  currentGraph.graphProperties = {
    ...(currentGraph.graphProperties || {}),
    [GRAPH_ID_PROPERTY]: graphId,
    [RELEASE_VERSION_PROPERTY]: String(releaseVersion),
  };
  return currentGraph;
}
