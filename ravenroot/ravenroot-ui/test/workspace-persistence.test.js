import { describe, expect, it } from 'vitest';
import { serializeGraphML } from '../src/graph-document.js';

import {
  persistedDocument,
  openWorkspaceDatabase,
  readWorkspaceSnapshot,
  validateWorkspaceSnapshot,
  writeWorkspaceSnapshot,
  workspaceScope,
  workspaceSnapshot,
} from '../src/workspace-persistence.js';

const graph = () => ({ format: 'graphml', sourceXml: '<graphml/>', nodes: [{ id: 'start' }],
  edges: [], nodeMap: { start: { id: 'start' } } });
const document_ = (id, tenantId = 'tenant-a') => ({ documentId: id, tenantId, mode: 'draft',
  name: `${id}.graphml`, displayName: id, graph: graph(), renderMode: 'design', layoutMode: 'cyto',
  visualStyle: 'cyto', fontSize: 20, provenance: { originMode: 'draft', sourceDocumentId: null,
    sourceGraphVersion: null, deploymentId: null }, execution: { executionId: 'never-store', client: {} },
  sourceSession: { sessionId: 'never-store' } });

describe('tenant workspace snapshot', () => {
  it('partitions exact opaque tenants at one normalized service URL', () => {
    const a = workspaceScope('https://runtime.example', ' tenant-a ');
    const b = workspaceScope('https://runtime.example', 'tenant-a');
    expect(a.key).not.toBe(b.key);
  });

  it('stores only matching tenant documents and omits runtime references by construction', () => {
    const scope = workspaceScope('https://runtime.example', 'tenant-a');
    const snapshot = workspaceSnapshot(scope, [document_('a'), document_('b', 'tenant-b')], 'a');
    expect(snapshot.documents.map(item => item.documentId)).toEqual(['a']);
    expect(snapshot.activeDocumentId).toBe('a');
    expect(JSON.stringify(snapshot)).not.toContain('never-store');
    expect(snapshot.documents[0].graph).not.toHaveProperty('nodeMap');
  });

  it('resets runtime projections while preserving arbitrary authored property names and artifact identity', () => {
    const source = document_('runtime');
    Object.assign(source.graph.nodes[0], { instances: 7, arrivals: 4, runtimeState: 'failed',
      runtimeObserved: true, lastEventType: 'NODE_FAILED', lastOccurredAt: 'now',
      processingDuration: 'PT1S', fallback: true, programPhase: 'READY',
      programReadinessState: { phase: 'READY' } });
    source.graph.nodes[0].properties = { nodeMap: '  Δ <xml>  ', artifactId: 'artifact-7' };
    source.graph.nodes[0].propertyTypes = { nodeMap: 'string', artifactId: 'string' };
    source.graph.graphProperties = { nodeMap: 'graph value' };
    source.graph.sourceXml = '<?xml version="1.0"?><graphml xmlns="http://graphml.graphdrawing.org/xmlns"><graph id="g" edgedefault="directed"/></graphml>';
    const stored = persistedDocument(source).graph;
    expect(stored.nodes[0]).toMatchObject({ instances: 0, arrivals: 0, runtimeState: 'idle',
      runtimeObserved: false, lastEventType: null, lastOccurredAt: null,
      processingDuration: null, fallback: false,
      properties: { nodeMap: '  Δ <xml>  ', artifactId: 'artifact-7' } });
    expect(stored.nodes[0]).not.toHaveProperty('programPhase');
    expect(stored.nodes[0]).not.toHaveProperty('programReadinessState');
    expect(stored.graphProperties.nodeMap).toBe('graph value');
    const exported = serializeGraphML(stored);
    expect(exported).toContain('  Δ &lt;xml&gt;  ');
    expect(exported).toContain('graph value');
  });

  it('restores order, selection and node index while rejecting stale or cross-tenant records', () => {
    const scope = workspaceScope('https://runtime.example', 'tenant-a');
    const snapshot = workspaceSnapshot(scope, [document_('a'), document_('b')], 'a');
    const restored = validateWorkspaceSnapshot(snapshot, scope);
    expect(restored.documents.map(item => item.documentId)).toEqual(['a', 'b']);
    expect(restored.activeDocumentId).toBe('a');
    expect(restored.documents[0].graph.nodeMap.start.id).toBe('start');
    expect(restored.documents[0].presentation).toMatchObject({ renderMode: 'design', layoutMode: 'cyto' });
    expect(validateWorkspaceSnapshot({ ...snapshot, activeDocumentId: 'stale' }, scope))
      .toMatchObject({ activeDocumentId: 'b', recoveredStaleSelection: true });
    expect(() => validateWorkspaceSnapshot({ ...snapshot,
      documents: [{ ...snapshot.documents[0], tenantId: 'tenant-b' }] }, scope))
      .toThrow(/tenant or document identity isolation/);
  });

  it('reports open and blocked storage failures without inventing a replacement database', async () => {
    const failing = { open() {
      const request = {};
      queueMicrotask(() => { request.error = new Error('open failed'); request.onerror(); });
      return request;
    } };
    await expect(openWorkspaceDatabase(failing)).rejects.toThrow('open failed');

    const blocked = { open() {
      const request = {};
      queueMicrotask(() => request.onblocked());
      return request;
    } };
    await expect(openWorkspaceDatabase(blocked)).rejects.toThrow('upgrade is blocked');
  });

  it('reports request and transaction failures and closes the failed database', async () => {
    const scope = workspaceScope('https://runtime.example', 'tenant-a');
    const provider = database => ({ open() {
      const request = {};
      queueMicrotask(() => { request.result = database; request.onsuccess(); });
      return request;
    } });
    let readClosed = false;
    const readDatabase = {
      close: () => { readClosed = true; },
      transaction: () => ({ objectStore: () => ({ get: () => {
        const request = {};
        queueMicrotask(() => { request.error = new Error('read failed'); request.onerror(); });
        return request;
      } }) }),
    };
    await expect(readWorkspaceSnapshot(scope, provider(readDatabase))).rejects.toThrow('read failed');
    expect(readClosed).toBe(true);

    let writeClosed = false;
    const writeDatabase = {
      close: () => { writeClosed = true; },
      transaction: () => {
        const tx = { objectStore: () => ({ put: () => ({}) }) };
        queueMicrotask(() => { tx.error = new Error('write aborted'); tx.onabort(); });
        return tx;
      },
    };
    await expect(writeWorkspaceSnapshot(scope, [document_('a')], 'a', provider(writeDatabase)))
      .rejects.toThrow('write aborted');
    expect(writeClosed).toBe(true);
  });

  it('rejects unknown snapshot versions without deleting or rewriting them', () => {
    const scope = workspaceScope('https://runtime.example', 'tenant-a');
    expect(() => validateWorkspaceSnapshot({ ...workspaceSnapshot(scope, [document_('a')], 'a'),
      version: 2 }, scope)).toThrow(/valid version 1 snapshot/);
  });

  it('preserves immutable provenance and deployment identity', () => {
    const stored = persistedDocument({ ...document_('deployed'), mode: 'deployed', provenance: {
      originMode: 'draft', sourceDocumentId: 'draft', sourceGraphVersion: 'graph-v1',
      deploymentId: 'deployment-a',
    } });
    expect(stored.provenance).toEqual({ originMode: 'draft', sourceDocumentId: 'draft',
      sourceGraphVersion: 'graph-v1', deploymentId: 'deployment-a' });
  });
});
