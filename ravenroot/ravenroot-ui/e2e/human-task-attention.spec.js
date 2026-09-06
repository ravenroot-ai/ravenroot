import { createServer } from 'node:http';
import { mkdir } from 'node:fs/promises';

import { expect, test } from '@playwright/test';

import { logSummary, scanForViolations, summarizeViolations } from './accessibility-helpers.mjs';
import { SERVICE_ORIGIN, SERVICE_PORT, UI_ORIGIN } from './ports.mjs';

const condition = (property, value) => ({ contract: 'ravenroot.property-condition/1', property,
  operator: 'EQUALS', values: [value] });
const property = (name, displayName, defaultValue, extra = {}) => ({ name, displayName,
  type: 'STRING', required: false, description: `${displayName} fixture.`, defaultValue,
  allowedValues: [], adapterBinding: false, visibleWhen: null, requiredWhen: null, ...extra });
const HUMAN_TASK = { behavior: 'human-task', displayName: 'Human task', category: 'Human workflow',
  description: 'Durable confirmation.', visualType: 'flow', agentic: false,
  capabilities: ['durable', 'human-task', 'embedded-confirmation-v1'], defaultNature: 'WORKER',
  allowedNatures: ['WORKER'],
  natureProperty: 'runtime.nature', defaultMaxConcurrency: 1, maxConcurrencyCeiling: 1,
  maxConcurrencyProperty: 'runtime.maxConcurrency', properties: [
    property('title', 'Title', '', { required: true }),
    property('description', 'Description', '', { type: 'TEXT' }),
    property('responseContentType', 'Response media type', 'application/vnd.ravenroot.payload+json'),
    property('responseSchema', 'Response schema', 'ravenroot.human-task.response'),
    property('responseSchemaVersion', 'Response schema version', '1'),
    property('responseKind', 'Response kind', 'MAP', { allowedValues: ['SCALAR', 'LIST', 'MAP'] }),
    property('maxResponseBytes', 'Maximum response bytes', '262144', { type: 'INTEGER',
      minimumValue: '1', maximumValue: '2097152' }),
    property('confirmationPresentationVersion', 'Confirmation presentation', '', {
      allowedValues: ['1'] }),
    property('confirmationPrompt', 'Confirmation prompt', 'Confirm this task.', {
      type: 'TEXT', maximumUtf8Bytes: 4096, visibleWhen: condition('confirmationPresentationVersion', '1') }),
    property('confirmationComment', 'Comment', 'OPTIONAL', { allowedValues: ['DISALLOWED', 'OPTIONAL', 'REQUIRED'],
      visibleWhen: condition('confirmationPresentationVersion', '1') }),
    property('confirmationActions', 'Actions', 'RESOLVE,DENY,CANCEL', { maximumItems: 3,
      maximumItemUtf8Bytes: 7, visibleWhen: condition('confirmationPresentationVersion', '1') }),
    property('confirmationResolveLabel', 'Resolve label', 'Confirm', { maximumUtf8Bytes: 64,
      visibleWhen: condition('confirmationPresentationVersion', '1') }),
    property('confirmationDenyLabel', 'Deny label', 'Deny', { maximumUtf8Bytes: 64,
      visibleWhen: condition('confirmationPresentationVersion', '1') }),
    property('confirmationCancelLabel', 'Cancel label', 'Cancel', { maximumUtf8Bytes: 64,
      visibleWhen: condition('confirmationPresentationVersion', '1') }),
  ] };
const CAPABILITY = { schemaVersion: 1, confirmationPresentationVersions: [1],
  confirmationPromptMaxUtf8Bytes: 4096, confirmationActionLabelMaxUtf8Bytes: 64,
  commentMaxUtf8Bytes: 4096, attentionPollMillis: 100, attentionBackoffMaxMillis: 400,
  attentionPageSize: 1, attentionPageSizeMax: 4 };

let service;
let tasks;
let decisionMode;
let attentionMode;
let requests;
let capabilityResponse;
let catalogResponse;
let executionContext;

function task(id, generation, status = 'WAITING') {
  return { taskId: id, generation, status, graphVersion: 'graph-v1', deploymentId: null,
    processInstanceId: 'process-v1', traversalId: `traversal-${id}`, nodeId: 'human-confirmation',
    createdAt: `2026-09-06T08:0${generation}:00Z`, expiresAt: '2026-09-07T08:00:00Z',
    escalateAt: status === 'ESCALATED' ? '2026-09-06T08:00:30Z' : null,
    promptMaxUtf8Bytes: 8192, actionLabelMaxUtf8Bytes: 128, commentMaxUtf8Bytes: 2048,
    presentation: { version: 1, prompt: `Confirm ${id}?`, commentRequirement: 'OPTIONAL',
      actions: ['RESOLVE', 'DENY', 'CANCEL'], resolveLabel: 'Confirm', denyLabel: 'Deny', cancelLabel: 'Cancel' },
    availableActions: ['RESOLVE', 'DENY', 'CANCEL'] };
}

function json(response, status, body, headers) {
  response.writeHead(status, { ...headers, 'Content-Type': 'application/json; charset=utf-8' });
  response.end(JSON.stringify(body));
}

function startService() {
  service = createServer(async (request, response) => {
    const headers = { 'Access-Control-Allow-Origin': UI_ORIGIN, Vary: 'Origin',
      'Access-Control-Allow-Headers': 'authorization,content-type',
      'Access-Control-Allow-Methods': 'GET,POST,OPTIONS' };
    if (request.method === 'OPTIONS') { response.writeHead(204, headers).end(); return; }
    const url = new URL(request.url, SERVICE_ORIGIN);
    requests.push(url.pathname + url.search);
    if (url.pathname === '/v1/configuration') return json(response, 200, {
      schemaVersion: 1, graphDocumentMaxBytes: 10 * 1024 * 1024, humanTasks: capabilityResponse }, headers);
    if (url.pathname === '/v1/node-types') return json(response, 200, catalogResponse, headers);
    if (url.pathname === '/v1/events') { response.writeHead(204, headers).end(); return; }
    if (url.pathname === '/v1/executions') return json(response, 200, { executionId: 'traversal-v1',
      ...executionContext, executionPolicy: 'durable' }, headers);
    if (url.pathname === '/v1/human-tasks/attention') {
      const requestedGraph = url.searchParams.get('graphVersion');
      if (attentionMode === 'network'
          || (attentionMode === 'offline-graph-b' && requestedGraph === 'graph-b')) {
        request.socket.destroy(); return;
      }
      let live = attentionMode === 'empty-graph-b' && requestedGraph === 'graph-b' ? []
        : tasks.filter(entry => ['WAITING', 'ESCALATED'].includes(entry.status)).map(entry => ({ ...entry,
          graphVersion: requestedGraph || entry.graphVersion,
          processInstanceId: url.searchParams.get('processInstanceId') || entry.processInstanceId }));
      const nodeId = url.searchParams.get('nodeId');
      if (nodeId) live = live.filter(entry => entry.nodeId === nodeId);
      const exactTask = url.searchParams.get('taskId');
      if (exactTask) live = live.filter(entry => entry.taskId === exactTask
        && entry.generation === Number(url.searchParams.get('generation')));
      const offset = url.searchParams.get('cursor') === 'second' ? 1 : 0;
      const limit = Number(url.searchParams.get('limit'));
      const items = live.slice(offset, offset + limit);
      return json(response, 200, { schemaVersion: 1, items,
        nextCursor: offset + limit < live.length ? 'second' : null,
        counts: { pending: live.length, escalated: live.filter(entry => entry.status === 'ESCALATED').length },
        nodeCounts: nodeId || exactTask ? [] : live.length ? [{ nodeId: 'human-confirmation',
          pending: live.length, escalated: live.filter(entry => entry.status === 'ESCALATED').length }] : [] }, headers);
    }
    const match = url.pathname.match(/^\/v1\/human-tasks\/([^/]+)\/confirmation\/(resolve|deny|cancel)$/);
    if (match) {
      const selected = tasks.find(entry => entry.taskId === decodeURIComponent(match[1]));
      if (decisionMode === 'unauthorized') return json(response, 404, { error: 'not found' }, headers);
      if (decisionMode === 'network') { request.socket.destroy(); return; }
      if (decisionMode === 'stale') {
        decisionMode = 'normal'; selected.generation += 1;
        return json(response, 409, { error: 'Human Task generation no longer matches' }, headers);
      }
      if (decisionMode === 'expired') {
        selected.status = 'EXPIRED'; selected.availableActions = [];
        return json(response, 409, { error: 'Human Task is no longer actionable' }, headers);
      }
      selected.status = match[2] === 'resolve' ? 'RESOLVED' : match[2] === 'deny' ? 'DENIED' : 'CANCELLED';
      selected.availableActions = [];
      return json(response, 200, { schemaVersion: 1, outcome: 'APPLIED', task: selected }, headers);
    }
    json(response, 404, { error: 'not found' }, headers);
  });
  return new Promise((resolve, reject) => service.once('error', reject)
    .listen(SERVICE_PORT, '127.0.0.1', resolve));
}

async function connectAndCreate(page) {
  await page.goto('/');
  await page.locator('#service-url').fill(SERVICE_ORIGIN);
  page.once('dialog', dialog => dialog.accept());
  await page.locator('#service-url').press('Tab');
  await page.locator('#btn-new').click();
  await addHumanTaskNode(page);
}

async function addHumanTaskNode(page) {
  await page.locator('#btn-modify').click();
  await page.locator('#node-catalog [data-catalog-add="human-task"]').click();
  await page.locator('#node-editor input[name="id"]').fill('human-confirmation');
  await page.locator('[data-catalog-property="title"]').fill('Confirm durable task');
  await page.locator('[data-catalog-property="confirmationPresentationVersion"]').selectOption('1');
  await page.locator('#node-editor button[type="submit"]').click();
  await page.evaluate(() => {
    const positions = { start: [100, 100], 'human-confirmation': [300, 100], dosomething: [500, 100],
      end: [700, 60], error: [700, 280] };
    for (const [id, [x, y]] of Object.entries(positions)) {
      window.ravenroot.activeDocument().cy.getElementById(id).position({ x, y });
    }
    window.ravenroot.activeDocument().cy.fit(undefined, 60);
  });
}

async function runAndSelect(page, graphVersion = executionContext.graphVersion) {
  page.once('dialog', dialog => dialog.accept());
  await page.locator('#btn-run').click();
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().execution.graphVersion))
    .toBe(graphVersion);
  await page.evaluate(() => {
    window.ravenroot.activeDocument().cy.getElementById('human-confirmation').emit('tap');
  });
}

test.beforeEach(async () => {
  tasks = [task('task-1', 1, 'ESCALATED'), task('task-2', 2)];
  decisionMode = 'normal';
  attentionMode = 'normal';
  requests = [];
  capabilityResponse = { ...CAPABILITY };
  catalogResponse = [HUMAN_TASK];
  executionContext = { processInstanceId: 'process-v1', graphVersion: 'graph-v1' };
  await startService();
});
test.afterEach(async () => new Promise(resolve => {
  service.closeAllConnections();
  service.close(resolve);
}));

test('generic authoring opts into the versioned confirmation and preserves its catalog defaults', async ({ page }) => {
  await connectAndCreate(page);
  const properties = await page.evaluate(() => window.ravenroot.activeDocument().graph
    .nodeMap['human-confirmation'].properties);
  expect(properties.confirmationPresentationVersion).toBe('1');
  expect(properties.confirmationPrompt).toBe('Confirm this task.');
  expect(properties.confirmationActions).toBe('RESOLVE,DENY,CANCEL');
  expect(properties.responseContentType).toBe('application/vnd.ravenroot.payload+json');
  expect(properties.responseKind).toBe('MAP');
});

test('a catalog that cannot admit new confirmations omits authoring while runtime capability remains',
  async ({ page }) => {
  capabilityResponse = { ...CAPABILITY, confirmationPromptMaxUtf8Bytes: 4,
    confirmationActionLabelMaxUtf8Bytes: 2, commentMaxUtf8Bytes: 8 };
  catalogResponse = [{ ...HUMAN_TASK,
    capabilities: HUMAN_TASK.capabilities.filter(value => value !== 'embedded-confirmation-v1'),
    properties: HUMAN_TASK.properties.filter(value => !value.name.startsWith('confirmation')) }];
  await page.goto('/');
  await page.locator('#service-url').fill(SERVICE_ORIGIN);
  page.once('dialog', dialog => dialog.accept());
  await page.locator('#service-url').press('Tab');
  await page.locator('#btn-new').click();
  await page.locator('#btn-modify').click();
  await page.locator('#node-catalog [data-catalog-add="human-task"]').click();
  await expect(page.locator('[data-catalog-property^="confirmation"]')).toHaveCount(0);
});

test('historical duplicate labels remain operable with explicit dispositions', async ({ page }) => {
  tasks[0] = task('task-1', 1, 'ESCALATED');
  tasks[0].presentation = { ...tasks[0].presentation, resolveLabel: 'Ｐｒｏｃｅｅｄ',
    denyLabel: '  proceed  ' };
  tasks = [tasks[0]];
  await connectAndCreate(page);
  await runAndSelect(page);
  await page.locator('[data-human-task-id="task-1"]').click();
  await expect(page.getByRole('button', { name: 'Resolve — Ｐｒｏｃｅｅｄ', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Deny — proceed', exact: true })).toBeVisible();
  await page.locator('[data-human-task-action="DENY"]').click();
  await expect(page.locator('.human-task-status')).toContainText('No actionable');
});

test('two tasks page independently and explicit decisions remove the final non-colour halo', async ({ page }) => {
  await connectAndCreate(page);
  await runAndSelect(page);
  await expect.poll(() => requests.filter(value => value.startsWith('/v1/human-tasks/attention')).join('\n'))
    .toContain('nodeId=human-confirmation');
  await expect(page.locator('.human-task-status')).toBeVisible();
  await expect(page.locator('.human-task-status')).toContainText('2 actionable tasks');
  await expect(page.locator('[data-human-task-id]')).toHaveCount(1);
  await expect(page.locator('[data-human-task-id="task-1"]')).toContainText('Escalated');
  await page.locator('.human-task-pagination .btn', { hasText: 'Next' }).click();
  await expect(page.locator('[data-human-task-id="task-2"]')).toBeVisible();
  await page.locator('.human-task-pagination .btn', { hasText: 'Previous' }).click();
  await expect(page.locator('[data-human-task-id="task-1"]')).toBeVisible();
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().cy
    .getElementById('human-confirmation').data('label'))).toContain('⚑ 2');

  await page.locator('[data-human-task-id="task-1"]').click();
  await expect(page.locator('#human-task-dialog')).toBeVisible();
  await page.locator('[data-human-task-action="RESOLVE"]').click();
  await expect(page.locator('.human-task-status')).toContainText('1 actionable task');
  await expect(page.locator('[data-human-task-id="task-2"]')).toBeVisible();
  await page.locator('[data-human-task-id="task-2"]').click();
  await page.locator('[data-human-task-action="DENY"]').click();
  await expect(page.locator('.human-task-status')).toContainText('No actionable');
  await page.evaluate(() => {
    window.ravenroot.activeDocument().cy.getElementById('human-confirmation').unselect();
  });
  await expect.poll(() => page.evaluate(() => {
    const node = window.ravenroot.activeDocument().cy.getElementById('human-confirmation');
    return { pending: node.data('humanTaskPending'), borderStyle: node.renderedStyle('border-style'),
      pulsing: node.hasClass('human-task-pulse') };
  })).toEqual({ pending: 0, borderStyle: 'solid', pulsing: false });
  expect(await page.evaluate(() => Number(window.ravenroot.activeDocument().cy
    .getElementById('human-confirmation').renderedStyle('underlay-opacity')))).toBeLessThan(0.2);
  expect(await page.evaluate(() => window.ravenroot.activeDocument().cy
    .getElementById('human-confirmation').renderedStyle('label'))).not.toContain('⚑');
});

test('long prompts keep both Inspector rows and their identities visible', async ({ page }, testInfo) => {
  capabilityResponse = { ...CAPABILITY, attentionPageSize: 2 };
  tasks = tasks.map((entry, index) => ({ ...entry, presentation: {
    ...entry.presentation, prompt: `${index ? 'Second' : 'First'} ${'bounded prompt '.repeat(180)}`,
  } }));
  await connectAndCreate(page);
  await runAndSelect(page);

  const rows = page.locator('[data-human-task-id]');
  await expect(rows).toHaveCount(2);
  await expect(rows.nth(0).locator('.human-task-row-identity')).toContainText('Task task-1');
  await expect(rows.nth(1).locator('.human-task-row-identity')).toContainText('Task task-2');
  await expect(rows.nth(1)).toBeInViewport();
  for (const prompt of await rows.locator('.human-task-row-prompt').all()) {
    await expect(prompt).toHaveCSS('-webkit-line-clamp', '3');
    expect((await prompt.boundingBox())?.height).toBeLessThan(50);
  }
  await page.screenshot({ path: testInfo.outputPath('human-task-long-prompt-inspector.png'), fullPage: true });
});

test('attention survives Design and Monitoring renderer changes with a pulsing non-colour carrier', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'no-preference' });
  await connectAndCreate(page);
  await runAndSelect(page);
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().cy
    .getElementById('human-confirmation').hasClass('human-task-pulse')), {
    intervals: [100], timeout: 2500,
  }).toBe(true);

  await page.locator('#btn-monitoring').click();
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().renderer.kind)).toBe('elastic');
  await expect.poll(() => page.evaluate(() => {
    const renderer = window.ravenroot.activeDocument().renderer;
    const circle = renderer.nodeSelection.filter(item => item.id === 'human-confirmation').node();
    const label = renderer.nodeLabelSelection.filter(item => item.id === 'human-confirmation').text();
    return { label, attention: circle?.classList.contains('human-task-attention'),
      escalated: circle?.classList.contains('is-escalated') };
  })).toEqual({ label: 'Human task\n⚑ 2 · ▲ 1', attention: true, escalated: true });
  await expect(page.locator('.human-task-status')).toBeInViewport();
  await expect(page.locator('.human-task-status')).toContainText('2 actionable tasks');
  if (process.env.RR_VISUAL_EVIDENCE_DIR) {
    await mkdir(process.env.RR_VISUAL_EVIDENCE_DIR, { recursive: true });
    await page.screenshot({ path: `${process.env.RR_VISUAL_EVIDENCE_DIR}/human-task-attention-monitoring.png`,
      fullPage: true });
  }

  await page.locator('#btn-design').click();
  await expect(page.locator('.doc-pane--active')).not.toHaveAttribute('aria-busy', 'true');
  await expect.poll(() => page.evaluate(() => ({ renderer: window.ravenroot.activeDocument().renderer.kind,
    label: window.ravenroot.activeDocument().cy.getElementById('human-confirmation').renderedStyle('label'),
  }))).toEqual({ renderer: 'cytoscape', label: 'Human task\n⚑ 2 · ▲ 1' });
});

test('a failed switch to a document sharing the node id clears the previous halo and rows', async ({ page }) => {
  executionContext = { processInstanceId: 'process-a', graphVersion: 'graph-a' };
  await connectAndCreate(page);
  await runAndSelect(page);
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().cy
    .getElementById('human-confirmation').data('humanTaskPending'))).toBe(2);
  const documentA = await page.evaluate(() => window.ravenroot.activeDocument().id);

  await page.locator('#btn-new').click();
  await addHumanTaskNode(page);
  executionContext = { processInstanceId: 'process-b', graphVersion: 'graph-b' };
  attentionMode = 'empty-graph-b';
  await runAndSelect(page);
  const documentB = await page.evaluate(() => window.ravenroot.activeDocument().id);
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().cy
    .getElementById('human-confirmation').data('humanTaskPending'))).toBe(0);

  attentionMode = 'normal';
  await page.locator('#document-switcher').click();
  await page.locator(`[data-document-activate="${documentA}"]`).click();
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().cy
    .getElementById('human-confirmation').data('humanTaskPending'))).toBe(2);

  attentionMode = 'offline-graph-b';
  await page.locator('#document-switcher').click();
  await page.locator(`[data-document-activate="${documentB}"]`).click();
  await expect.poll(() => requests.filter(value => value.includes('graphVersion=graph-b')).length)
    .toBeGreaterThan(0);
  await expect.poll(() => page.evaluate(() => {
    const node = window.ravenroot.activeDocument().cy.getElementById('human-confirmation');
    return { pending: node.data('humanTaskPending'), label: node.renderedStyle('label'),
      pulsing: node.hasClass('human-task-pulse') };
  })).toEqual({ pending: 0, label: 'Human task', pulsing: false });
  await expect(page.locator('[data-human-task-id]')).toHaveCount(0);
});

test('stale generation and ambiguous network loss reconcile without an automatic duplicate decision', async ({ page }) => {
  await connectAndCreate(page);
  await runAndSelect(page);
  decisionMode = 'stale';
  await page.locator('[data-human-task-id="task-1"]').click();
  await page.locator('[data-human-task-action="RESOLVE"]').click();
  await expect(page.locator('[data-human-task-error]')).toContainText('HTTP 409 POST');
  await expect(page.locator('[data-human-task-id="task-1"]')).toHaveAttribute('data-human-task-generation', '2');
  await page.locator('[data-human-task-close]').click();

  decisionMode = 'network';
  await page.locator('[data-human-task-id="task-1"]').click();
  await page.locator('[data-human-task-action="RESOLVE"]').click();
  await expect(page.locator('[data-human-task-error]')).toContainText(/POST \/v1\/human-tasks/);
  expect(tasks[0].status).toBe('ESCALATED');
  decisionMode = 'normal';
  await page.locator('[data-human-task-close]').click();
  await expect(page.locator('[data-human-task-id="task-1"]')).toBeVisible();
});

test('browser reload restores an exact task only after the service origin and token are reauthorized', async ({ page }) => {
  tasks[0].presentation = { ...tasks[0].presentation, resolveLabel: 'A', denyLabel: '\u{1ccd6}' };
  await connectAndCreate(page);
  await runAndSelect(page);
  await page.locator('[data-human-task-id="task-1"]').click();
  await expect(page.locator('#human-task-dialog')).toBeVisible();
  await page.evaluate(() => window.ravenroot.activeDocument().history.markSaved());

  await page.reload();
  await expect(page.locator('#service-url')).toHaveValue(SERVICE_ORIGIN);
  await expect(page.locator('#runtime-connection')).toHaveClass(/authentication-required/);
  page.once('dialog', dialog => dialog.accept());
  await page.locator('#access-token').fill('replacement-token');
  await page.locator('#access-token').press('Enter');
  await expect(page.locator('#human-task-dialog')).toBeVisible();
  await expect(page.locator('[data-human-task-identity]')).toContainText('Task task-1');
  await expect(page.getByRole('button', { name: 'Resolve — A', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Deny — \u{1ccd6}', exact: true })).toBeVisible();
  expect(requests.some(value => value === '/v1/human-tasks/attention?taskId=task-1&generation=1&limit=1'))
    .toBe(true);

  await page.locator('[data-human-task-action="DENY"]').click();
  await expect(page.locator('#human-task-dialog')).toBeHidden();
  expect(tasks[0].status).toBe('DENIED');
  expect(await page.evaluate(() => localStorage.getItem('ravenroot.human-task.selection.v1'))).toBeNull();
});

test('an outage suspends stale modal details and retains only the exact recovery locator', async ({ page }) => {
  await connectAndCreate(page);
  await runAndSelect(page);
  await page.locator('[data-human-task-id="task-1"]').click();
  const locator = await page.evaluate(() =>
    JSON.parse(localStorage.getItem('ravenroot.human-task.selection.v1')));
  await expect(page.locator('#human-task-dialog')).toBeVisible();

  attentionMode = 'network';
  await expect(page.locator('.human-task-status')).toContainText('could not be refreshed');
  await expect(page.locator('#human-task-dialog')).toBeHidden();
  expect(await page.evaluate(() =>
    JSON.parse(localStorage.getItem('ravenroot.human-task.selection.v1')))).toEqual(locator);

  attentionMode = 'normal';
  await expect(page.locator('#human-task-dialog')).toBeVisible();
  await expect(page.locator('[data-human-task-identity]')).toContainText('Task task-1');
});

test('unauthorized, expiry, cancellation and poll reconnect states stay deterministic', async ({ page }) => {
  await connectAndCreate(page);
  await runAndSelect(page);
  await expect(page.locator('[data-human-task-id="task-1"]')).toBeVisible();

  decisionMode = 'unauthorized';
  await page.locator('[data-human-task-id="task-1"]').click();
  await page.locator('[data-human-task-action="RESOLVE"]').click();
  await expect(page.locator('[data-human-task-error]')).toContainText('HTTP 404 POST');
  await expect(page.locator('#human-task-dialog')).toBeVisible();
  await page.locator('[data-human-task-close]').click();

  decisionMode = 'expired';
  await page.locator('[data-human-task-id="task-1"]').click();
  await page.locator('[data-human-task-action="RESOLVE"]').click();
  await expect(page.locator('[data-human-task-error]')).toContainText('HTTP 409 POST');
  await expect(page.locator('.human-task-status')).toContainText('1 actionable task');
  await page.locator('[data-human-task-close]').click();
  expect(tasks[0].status).toBe('EXPIRED');

  attentionMode = 'network';
  await expect(page.locator('.human-task-status')).toContainText('could not be refreshed');
  tasks[1].status = 'CANCELLED';
  tasks[1].availableActions = [];
  attentionMode = 'normal';
  await expect(page.locator('.human-task-status')).toContainText('No actionable');
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().cy
    .getElementById('human-confirmation').data('humanTaskPending'))).toBe(0);
});

test('a tighter current policy keeps an older task pinned to its presentation and comment budget', async ({ page }) => {
  capabilityResponse = { ...CAPABILITY, confirmationPromptMaxUtf8Bytes: 4,
    confirmationActionLabelMaxUtf8Bytes: 2, commentMaxUtf8Bytes: 8 };
  tasks = [task('task-old', 4)];
  tasks[0].commentMaxUtf8Bytes = 16;
  await connectAndCreate(page);
  await runAndSelect(page);
  const row = page.locator('[data-human-task-id="task-old"]');
  await expect(row).toContainText('Confirm task-old?');
  await row.click();
  await expect(page.locator('[data-human-task-comment-hint]')).toContainText('/ 16 UTF-8 bytes');
  await page.locator('[data-human-task-comment]').fill('🙂🙂🙂🙂');
  await page.locator('[data-human-task-action="RESOLVE"]').click();
  await expect(page.locator('.human-task-status')).toContainText('No actionable');
});

test('keyboard focus, screen-reader structure and reduced motion remain usable', async ({ page }, testInfo) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await connectAndCreate(page);
  await runAndSelect(page);
  const row = page.locator('[data-human-task-id="task-1"]');
  await expect(row).toBeVisible();
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().cy
    .getElementById('human-confirmation').data('humanTaskPending'))).toBe(2);
  await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().cy
    .getElementById('human-confirmation').renderedStyle('label'))).toContain('⚑ 2 · ▲ 1');
  await row.scrollIntoViewIfNeeded();
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  const evidenceDirectory = process.env.RR_VISUAL_EVIDENCE_DIR;
  if (evidenceDirectory) {
    await mkdir(evidenceDirectory, { recursive: true });
    await page.screenshot({ path: `${evidenceDirectory}/human-task-attention-desktop.png`, fullPage: true });
  }
  await row.focus();
  await page.keyboard.press('Enter');
  const dialog = page.locator('#human-task-dialog');
  await expect(dialog).toBeVisible();
  await expect(page.locator('[data-human-task-action="RESOLVE"]')).toBeFocused();
  await expect(dialog).toHaveAttribute('aria-labelledby', 'human-task-dialog-title');
  await expect(dialog).toHaveAttribute('aria-describedby', 'human-task-dialog-prompt');
  const dialogBox = await dialog.boundingBox();
  const viewport = page.viewportSize();
  expect(Math.abs(dialogBox.x + dialogBox.width / 2 - viewport.width / 2)).toBeLessThan(2);
  expect(Math.abs(dialogBox.y + dialogBox.height / 2 - viewport.height / 2)).toBeLessThan(2);

  const accessibility = await scanForViolations(page, { include: ['#human-task-dialog'] });
  const summary = summarizeViolations(accessibility);
  logSummary('Human Task confirmation dialog', summary);
  await testInfo.attach('human-task-dialog-axe.json', {
    body: Buffer.from(JSON.stringify(summary, null, 2)), contentType: 'application/json',
  });

  if (evidenceDirectory) {
    await page.screenshot({ path: `${evidenceDirectory}/human-task-dialog-desktop.png`, fullPage: true });
  }

  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
  await expect(row).toBeFocused();
  await page.waitForTimeout(1100);
  expect(await page.evaluate(() => window.ravenroot.activeDocument().cy
    .getElementById('human-confirmation').hasClass('human-task-pulse'))).toBe(false);
  await page.evaluate(() => { window.ravenroot.activeDocument().cy.$(':selected').unselect(); });
  await expect.poll(() => page.evaluate(() => {
    const node = window.ravenroot.activeDocument().cy.getElementById('human-confirmation');
    return { label: node.renderedStyle('label'), borderStyle: node.renderedStyle('border-style'),
      underlayOpacity: Number(node.renderedStyle('underlay-opacity')) };
  })).toMatchObject({ label: expect.stringContaining('⚑ 2 · ▲ 1'),
    borderStyle: 'double', underlayOpacity: expect.any(Number) });
  await expect.poll(() => page.evaluate(() => Number(window.ravenroot.activeDocument().cy
    .getElementById('human-confirmation').renderedStyle('underlay-opacity')))).toBeGreaterThan(0);
  if (evidenceDirectory) {
    await page.screenshot({ path: `${evidenceDirectory}/human-task-attention-unselected-desktop.png`,
      fullPage: true });
  }
});
