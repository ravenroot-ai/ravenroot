import { expect } from '@playwright/test';
import { createHash } from 'node:crypto';
export const PID = '10000000-0000-0000-0000-000000000001', TID = '20000000-0000-0000-0000-000000000002';
export const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes; }); return { promise, resolve }; };
export async function deploymentService(page, { gate, lose } = {}) {
  const held = new Map(), calls = [], registered = deferred();
  await page.route('**/v1/configuration', route => route.fulfill({ json: { schemaVersion: 1, graphDocumentMaxBytes: 10485760, workspace: { tenantId: 'tenant' } } }));
  await page.route('**/v1/node-types', route => route.fulfill({ json: [{ behavior: 'external.consume', displayName: 'Source', category: 'Sources', description: '', visualType: 'actor',
    agentic: false, capabilities: [], properties: [], defaultNature: 'SOURCE', allowedNatures: ['SOURCE'], natureProperty: 'runtime.nature' }] }));
  await page.route('**/v1/events**', route => route.fulfill({ status: 204, body: '' }));
  await page.route('**/v1/executions/**', route => route.fulfill({ json: { executionId: TID, traversalId: TID, processInstanceId: PID, status: 'COMPLETED', payload: null } }));
  await page.route('**/v1/deployments**', async route => {
    const request = route.request(), url = new URL(request.url()), parts = url.pathname.split('/').filter(Boolean), id = url.searchParams.get('id') || parts[2], action = parts[3], method = request.method();
    calls.push({ id, action, method, body: request.postData() });
    if (method === 'POST' && !action) {
      const saved = await page.evaluate(async () => {
        const db = await new Promise((resolve, reject) => { const open = indexedDB.open('ravenroot-workspaces', 1); open.onsuccess = () => resolve(open.result); open.onerror = () => reject(open.error); });
        const rows = await new Promise((resolve, reject) => { const read = db.transaction('tenant-workspaces').objectStore('tenant-workspaces').getAll(); read.onsuccess = () => resolve(read.result); read.onerror = () => reject(read.error); });
        db.close(); return rows.flatMap(row => row.documents).map(doc => doc.deploymentRun);
      });
      expect(saved.some(run => run?.deploymentId === id && run.graphMl === request.postData())).toBe(true);
      held.set(id, { deploymentId: id, tenantId: 'tenant', graphVersion: createHash('sha256').update(request.postData()).digest('hex'), state: 'REGISTERED', sourceCount: 1,
        scope: 'LOCAL_PROCESS', diagnostic: null, continuity: 'PROCESS_LOCAL', deploymentRevision: null, desiredState: null, observedState: null, recoveryFailure: null });
      registered.resolve(id); if (gate) await gate.promise; if (lose === 'registration') { await route.abort('failed'); return; }
    }
    const status = held.get(id); if (!status) { await route.fulfill({ status: 404, json: { error: 'UNKNOWN_RESOURCE' } }); return; }
    if (action === 'start') { status.state = 'READY'; if (lose === 'activation' && calls.filter(c => c.action === 'start').length === 1) { await route.abort('failed'); return; } }
    if (action === 'view') {
      await route.fulfill({ json: { viewerSourceVersion: '1', source: { kind: 'deployment', deploymentId: id, graphVersion: status.graphVersion, incarnationId: 'runtime-1' }, lifecycle: status.state,
        projection: { viewerContractVersion: '1.0', nodes: [{ id: 'start', kind: 'START', label: 'Start' }, { id: 'source', kind: 'BEHAVIOR', label: 'Source' }], edges: [{ id: 'edge', source: 'start', target: 'source' }] } } }); return;
    }
    if (action === 'initial-start') {
      const input = request.postDataJSON(); await route.fulfill({ status: status.initial ? 200 : 202, json: { outcome: status.initial ? 'DUPLICATE' : 'ACCEPTED', requestId: input.requestId, processInstanceId: PID, traversalId: TID } }); status.initial = true; return;
    }
    await route.fulfill({ json: status });
  });
  return { calls, registered: registered.promise };
}
