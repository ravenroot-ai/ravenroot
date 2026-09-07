import { visualGroupPresentation } from './graph-view-state.js';

export const WORKSPACE_DATABASE_NAME = 'ravenroot-workspaces';
export const WORKSPACE_DATABASE_VERSION = 1;
export const WORKSPACE_STORE_NAME = 'tenant-workspaces';
export const WORKSPACE_SNAPSHOT_VERSION = 1;

const MODES = new Set(['draft', 'test', 'deployed']);

export function workspaceScope(serviceUrl, tenantId) {
  if (typeof serviceUrl !== 'string' || !serviceUrl || typeof tenantId !== 'string' || !tenantId) {
    throw new TypeError('A workspace scope needs an exact service URL and tenant id');
  }
  return Object.freeze({ serviceUrl, tenantId, key: JSON.stringify([serviceUrl, tenantId]) });
}

function nullableString(value, label) {
  if (value === null || value === undefined) return null;
  if (typeof value !== 'string' || !value) throw new TypeError(`${label} must be a non-empty string or null`);
  return value;
}

export function canonicalGraphSnapshot(graph) {
  const { nodeMap: _derivedNodeIndex, ...semanticGraph } = graph;
  const canonical = JSON.parse(JSON.stringify(semanticGraph));
  for (const node of canonical.nodes || []) {
    node.instances = 0;
    node.arrivals = 0;
    node.runtimeState = 'idle';
    node.runtimeObserved = false;
    node.lastEventType = null;
    node.lastOccurredAt = null;
    node.processingDuration = null;
    node.fallback = false;
    delete node.programPhase;
    delete node.programReadinessState;
  }
  return canonical;
}

export function persistedDocument(document_) {
  if (!document_ || typeof document_ !== 'object' || !MODES.has(document_.mode)) {
    throw new TypeError('Workspace document mode is invalid');
  }
  const documentId = String(document_.documentId || '');
  const tenantId = nullableString(document_.tenantId, 'tenantId');
  if (!documentId) throw new TypeError('Workspace document id is required');
  if (!document_.graph || !Array.isArray(document_.graph.nodes) || !Array.isArray(document_.graph.edges)) {
    throw new TypeError('Workspace document graph is invalid');
  }
  const graph = canonicalGraphSnapshot(document_.graph);
  return Object.freeze({
    documentId,
    name: String(document_.name || 'untitled.graphml'),
    displayName: String(document_.displayName || document_.name || 'untitled.graphml'),
    tenantId,
    mode: document_.mode,
    provenance: Object.freeze({
      originMode: MODES.has(document_.provenance?.originMode)
        ? document_.provenance.originMode : document_.mode,
      sourceDocumentId: nullableString(document_.provenance?.sourceDocumentId, 'sourceDocumentId'),
      sourceGraphVersion: nullableString(document_.provenance?.sourceGraphVersion, 'sourceGraphVersion'),
      deploymentId: nullableString(document_.provenance?.deploymentId, 'deploymentId'),
    }),
    graph,
    presentation: Object.freeze({
      ...visualGroupPresentation(document_),
      renderMode: (document_.renderMode ?? document_.presentation?.renderMode) === 'monitoring'
        ? 'monitoring' : 'design',
      layoutMode: typeof (document_.layoutMode ?? document_.presentation?.layoutMode) === 'string'
        ? (document_.layoutMode ?? document_.presentation.layoutMode) : 'cyto',
      visualStyle: typeof (document_.visualStyle ?? document_.presentation?.visualStyle) === 'string'
        ? (document_.visualStyle ?? document_.presentation.visualStyle) : 'cyto',
      fontSize: Number.isFinite(document_.fontSize ?? document_.presentation?.fontSize)
        ? (document_.fontSize ?? document_.presentation.fontSize) : 20,
    }),
  });
}

export function workspaceSnapshot(scope, documents, activeDocumentId) {
  const storedDocuments = documents.filter(document_ => document_.tenantId === scope.tenantId)
    .map(persistedDocument);
  return Object.freeze({
    key: scope.key,
    version: WORKSPACE_SNAPSHOT_VERSION,
    serviceUrl: scope.serviceUrl,
    tenantId: scope.tenantId,
    activeDocumentId: storedDocuments.some(document_ => document_.documentId === activeDocumentId)
      ? activeDocumentId : storedDocuments.at(-1)?.documentId ?? null,
    documents: storedDocuments,
  });
}

export function validateWorkspaceSnapshot(value, scope) {
  if (!value || typeof value !== 'object' || Array.isArray(value)
      || value.version !== WORKSPACE_SNAPSHOT_VERSION || value.key !== scope.key
      || value.serviceUrl !== scope.serviceUrl || value.tenantId !== scope.tenantId
      || !Array.isArray(value.documents)) {
    throw new Error('Stored workspace is not a valid version 1 snapshot');
  }
  const ids = new Set();
  const documents = value.documents.map(document_ => {
    const normalized = persistedDocument(document_);
    if (normalized.tenantId !== scope.tenantId || ids.has(normalized.documentId)) {
      throw new Error('Stored workspace violates tenant or document identity isolation');
    }
    ids.add(normalized.documentId);
    normalized.graph.nodeMap = Object.fromEntries(normalized.graph.nodes.map(node => [node.id, node]));
    return normalized;
  });
  const selected = value.activeDocumentId == null ? null : String(value.activeDocumentId);
  const activeDocumentId = selected !== null && ids.has(selected)
    ? selected : documents.at(-1)?.documentId ?? null;
  return { documents, activeDocumentId, recoveredStaleSelection: selected !== null && selected !== activeDocumentId };
}

export function openWorkspaceDatabase(indexedDB = globalThis.indexedDB) {
  if (!indexedDB) return Promise.reject(new Error('IndexedDB is unavailable'));
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(WORKSPACE_DATABASE_NAME, WORKSPACE_DATABASE_VERSION);
    request.onupgradeneeded = () => {
      const database = request.result;
      if (!database.objectStoreNames.contains(WORKSPACE_STORE_NAME)) {
        database.createObjectStore(WORKSPACE_STORE_NAME, { keyPath: 'key' });
      }
    };
    request.onerror = () => reject(request.error || new Error('IndexedDB open failed'));
    request.onblocked = () => reject(new Error('IndexedDB upgrade is blocked'));
    request.onsuccess = () => resolve(request.result);
  });
}

function transaction(database, mode, operation) {
  return new Promise((resolve, reject) => {
    const tx = database.transaction(WORKSPACE_STORE_NAME, mode);
    const request = operation(tx.objectStore(WORKSPACE_STORE_NAME));
    request.onerror = () => reject(request.error || new Error('Workspace storage request failed'));
    tx.onabort = () => reject(tx.error || new Error('Workspace storage transaction aborted'));
    tx.onerror = () => reject(tx.error || new Error('Workspace storage transaction failed'));
    tx.oncomplete = () => resolve(request.result);
  });
}

export async function readWorkspaceSnapshot(scope, indexedDB) {
  const database = await openWorkspaceDatabase(indexedDB);
  try {
    const value = await transaction(database, 'readonly', store => store.get(scope.key));
    return value == null ? null : validateWorkspaceSnapshot(value, scope);
  } finally {
    database.close();
  }
}

export async function writeWorkspaceSnapshot(scope, documents, activeDocumentId, indexedDB) {
  const snapshot = workspaceSnapshot(scope, documents, activeDocumentId);
  const database = await openWorkspaceDatabase(indexedDB);
  try {
    await transaction(database, 'readwrite', store => store.put(snapshot));
    return snapshot;
  } finally {
    database.close();
  }
}
