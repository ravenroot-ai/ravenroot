import { expect, test } from '@playwright/test';

const SOURCE_CATALOG = JSON.stringify([{
  behavior: 'external.consume', displayName: 'External consumer', category: 'Sources',
  description: 'Receives external events.', visualType: 'actor', agentic: false,
  capabilities: [], properties: [], defaultNature: 'SOURCE', allowedNatures: ['SOURCE'],
  natureProperty: 'runtime.nature',
}]);

const sourceGraph = `<?xml version="1.0" encoding="UTF-8"?>
<graphml xmlns="http://graphml.graphdrawing.org/xmlns">
  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
  <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
  <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
  <graph id="close-all" edgedefault="directed">
    <node id="start"><data key="kind">START</data></node>
    <node id="source"><data key="kind">BEHAVIOR</data><data key="behavior">external.consume</data></node>
    <node id="end"><data key="kind">END</data></node>
    <edge source="start" target="source"><data key="outcome">continue</data></edge>
    <edge source="source" target="end"><data key="outcome">continue</data></edge>
  </graph>
</graphml>`;

async function closeAllFromView(page) {
  await page.locator('#menu-view').click();
  await page.locator('[data-command-id="view.closeAllDocuments"]').click();
}

async function makeActiveDirty(page, name) {
  if (await page.locator('#btn-modify').getAttribute('aria-pressed') !== 'true') {
    await page.locator('#btn-modify').click();
  }
  await page.locator('#btn-add-node').click();
  await page.locator('#node-editor input[name="name"]').fill(name);
  await page.locator('#node-editor button[type="submit"]').click();
}

async function stubSourceRuntime(page, { failStop = false, delayStop = false } = {}) {
  let releaseStop;
  const stopGate = new Promise(resolve => { releaseStop = resolve; });
  const calls = { sourceDeletes: 0, deployments: 0, releaseStop };
  await page.route('**/v1/node-types', route => route.fulfill({
    status: 200, contentType: 'application/json', body: SOURCE_CATALOG,
  }));
  await page.route('**/v1/events**', route => route.fulfill({ status: 204, body: '' }));
  await page.route('**/v1/source-sessions**', async route => {
    const request = route.request();
    const sessionId = new URL(request.url()).searchParams.get('id')
      || decodeURIComponent(new URL(request.url()).pathname.split('/').at(-1));
    if (request.method() === 'DELETE') calls.sourceDeletes += 1;
    if (request.method() === 'DELETE' && delayStop) await stopGate;
    if (request.method() === 'DELETE' && failStop) {
      return route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"unavailable"}' });
    }
    const state = request.method() === 'POST' ? 'STARTING'
      : request.method() === 'DELETE' ? 'STOPPED' : 'LISTENING';
    return route.fulfill({
      status: request.method() === 'POST' ? 202 : 200,
      contentType: 'application/json',
      body: JSON.stringify({ sessionId, state, sourceCount: 1, scope: 'LOCAL_PROCESS', diagnostic: null }),
    });
  });
  await page.route('**/v1/deployments**', route => {
    calls.deployments += 1;
    return route.fulfill({ status: 500, contentType: 'application/json', body: '{"error":"must not be called"}' });
  });
  return calls;
}

async function openAndRunSource(page, name) {
  await page.locator('#file-inp').setInputFiles({
    name, mimeType: 'application/xml', buffer: Buffer.from(sourceGraph),
  });
  page.once('dialog', dialog => dialog.accept());
  await page.locator('#btn-run').click();
  await expect(page.locator('#source-session-status')).toContainText('Listening');
}

test.describe('Close All Documents', () => {
  test('is a localized View command, closes every clean layout snapshot, and disables when empty', async ({ page }) => {
    await page.goto('/');
    for (const mode of ['single', 'horizontal', 'vertical', 'grid']) {
      await page.evaluate(targetMode => {
        while (window.ravenroot.workspace.size < 3) window.ravenroot.openDocument();
        window.ravenroot.setWorkspaceLayout(targetMode);
        window.__closeAllOwners = window.ravenroot.workspace.documents.map(owner => ({
          owner,
          cy: owner.cy,
          pane: owner.pane,
        }));
      }, mode);
      await closeAllFromView(page);
      await expect.poll(() => page.evaluate(() => window.ravenroot.workspace.size)).toBe(0);
      await expect(page.locator('#btn-new')).toBeFocused();
      await expect(page.locator('#cy .doc-pane')).toHaveCount(0);
      expect(await page.evaluate(targetMode => ({
        stored: JSON.parse(localStorage.getItem('ravenroot.workspace-layout.v1')).mode,
        splitters: document.querySelectorAll('#cy .workspace-splitter:not([hidden])').length,
        retired: window.__closeAllOwners.every(({ owner, cy, pane }) => cy.destroyed()
          && !pane.isConnected && owner.renderer === null && owner.layoutSessionToken === null
          && owner.sourceSession.pollController === null && owner.execution.executionId === null),
      }), mode)).toEqual({ stored: mode, splitters: 0, retired: true });
      await page.locator('#menu-view').click();
      await expect(page.locator('[data-command-id="view.closeAllDocuments"]')).toHaveText('Close All Documents');
      await expect(page.locator('[data-command-id="view.closeAllDocuments"]')).toHaveAttribute('aria-disabled', 'true');
      await page.keyboard.press('Escape');
      if (mode !== 'grid') await page.locator('#btn-new').click();
    }
  });

  test('remains reachable and clears a responsive grid fallback', async ({ page }) => {
    await page.setViewportSize({ width: 720, height: 800 });
    await page.goto('/');
    await page.evaluate(() => {
      window.ravenroot.openDocument();
      window.ravenroot.openDocument();
      window.ravenroot.setWorkspaceLayout('grid');
    });

    await closeAllFromView(page);

    await expect.poll(() => page.evaluate(() => window.ravenroot.workspace.size)).toBe(0);
    await expect(page.locator('#cy .doc-pane')).toHaveCount(0);
    await expect.poll(() => page.evaluate(() => document.activeElement?.id)).toBe('menu-file');
  });

  test('uses one dirty decision, and Cancel preserves every target and its history', async ({ page }) => {
    await page.goto('/');
    await makeActiveDirty(page, 'First change');
    await page.locator('#btn-new').click();
    await makeActiveDirty(page, 'Second change');

    await closeAllFromView(page);
    const dialog = page.locator('#close-all-documents-dialog');
    await expect(dialog).toBeVisible();
    await expect(dialog.locator('#close-all-documents-list li')).toHaveCount(2);
    await expect(dialog.locator('[data-close-all-action="cancel"]')).toBeFocused();
    await dialog.press('Escape');

    expect(await page.evaluate(() => window.ravenroot.documents().map(document_ => document_.history.isDirty())))
      .toEqual([true, true]);
    await expect(page.locator('#menu-view')).toBeFocused();

    await closeAllFromView(page);
    await dialog.locator('[data-close-all-action="discard"]').click();
    await expect.poll(() => page.evaluate(() => window.ravenroot.workspace.size)).toBe(0);
  });

  test('Save All downloads every dirty document and marks none saved when dispatch fails', async ({ page }) => {
    await page.goto('/');
    await makeActiveDirty(page, 'First change');
    await page.locator('#btn-new').click();
    await makeActiveDirty(page, 'Second change');
    const downloads = [];
    page.on('download', download => downloads.push(download.suggestedFilename()));

    await closeAllFromView(page);
    await page.locator('[data-close-all-action="save"]').click();
    await expect.poll(() => downloads.sort()).toEqual(['untitled-2.graphml', 'untitled.graphml']);
    await expect.poll(() => page.evaluate(() => window.ravenroot.workspace.size)).toBe(0);

    await page.locator('#btn-new').click();
    await makeActiveDirty(page, 'Failed save');
    await page.evaluate(() => { URL.createObjectURL = () => { throw new Error('blocked'); }; });
    await closeAllFromView(page);
    await page.locator('[data-close-all-action="save"]').click();
    await expect(page.locator('#close-all-documents-status')).toContainText('could not be prepared or downloaded');
    expect(await page.evaluate(() => window.ravenroot.documents().map(document_ => document_.history.isDirty())))
      .toEqual([true]);
    await page.locator('[data-close-all-action="cancel"]').click();
    await expect(page.locator('#close-all-documents-dialog')).toBeHidden();
  });

  test('Keep Running detaches all captured sessions, never undeploys, and preserves a later open', async ({ page }) => {
    const calls = await stubSourceRuntime(page);
    await page.goto('/');
    await openAndRunSource(page, 'listener.graphml');
    const captured = await page.evaluate(() => window.ravenroot.workspace.documents.map(document_ => document_.id));

    await closeAllFromView(page);
    await expect(page.locator('#close-all-documents-list li')).toHaveCount(1);
    const later = await page.evaluate(() => window.ravenroot.openDocument({ name: 'later.graphml' }));
    await page.locator('[data-close-all-action="keep"]').click();

    await expect.poll(() => page.evaluate(() => window.ravenroot.workspace.documents.map(document_ => document_.id)))
      .toEqual([later]);
    expect(captured).not.toContain(later);
    expect(calls.sourceDeletes).toBe(0);
    expect(calls.deployments).toBe(0);
    await expect(page.locator('#document-switcher')).toBeFocused();
  });

  test('Cancel at the session step preserves the earlier dirty decision without mutating history', async ({ page }) => {
    const calls = await stubSourceRuntime(page);
    await page.goto('/');
    await openAndRunSource(page, 'listener.graphml');
    await makeActiveDirty(page, 'Listener change');
    const before = await page.evaluate(() => window.ravenroot.workspace.documents.map(document_ => document_.id));

    await closeAllFromView(page);
    await page.locator('[data-close-all-action="discard"]').click();
    await expect(page.locator('#close-all-documents-title')).toHaveText('Close documents with active local sessions?');
    await page.locator('[data-close-all-action="cancel"]').click();

    expect(await page.evaluate(() => window.ravenroot.workspace.documents.map(document_ => document_.id))).toEqual(before);
    expect(await page.evaluate(() => window.ravenroot.workspace.active.history.isDirty())).toBe(true);
    expect(calls.sourceDeletes).toBe(0);
    expect(calls.deployments).toBe(0);
  });

  test('Stop and Close All reaches authoritative stopped state before closing and never undeploys', async ({ page }) => {
    const calls = await stubSourceRuntime(page);
    await page.goto('/');
    await openAndRunSource(page, 'listener.graphml');

    await closeAllFromView(page);
    await page.locator('[data-close-all-action="stop"]').click();

    await expect.poll(() => calls.sourceDeletes).toBe(1);
    await expect.poll(() => page.evaluate(() => window.ravenroot.workspace.size)).toBe(0);
    expect(calls.deployments).toBe(0);
  });

  test('Stop and Close requires authoritative success; failure keeps every target open and never undeploys', async ({ page }) => {
    const calls = await stubSourceRuntime(page, { failStop: true });
    await page.goto('/');
    await openAndRunSource(page, 'listener.graphml');
    const before = await page.evaluate(() => window.ravenroot.workspace.documents.map(document_ => document_.id));

    await closeAllFromView(page);
    await page.locator('[data-close-all-action="stop"]').click();
    await expect(page.locator('#close-all-documents-status')).toContainText('did not reach the stopped state');
    expect(await page.evaluate(() => window.ravenroot.workspace.documents.map(document_ => document_.id))).toEqual(before);
    expect(calls.sourceDeletes).toBe(1);
    expect(calls.deployments).toBe(0);
  });

  test('Escape during a pending stop cancels document closure without undoing the truthful stop result', async ({ page }) => {
    const calls = await stubSourceRuntime(page, { delayStop: true });
    await page.goto('/');
    await openAndRunSource(page, 'listener.graphml');
    const before = await page.evaluate(() => window.ravenroot.workspace.documents.map(document_ => document_.id));

    await closeAllFromView(page);
    await page.locator('[data-close-all-action="stop"]').click();
    await expect.poll(() => calls.sourceDeletes).toBe(1);
    await page.locator('#close-all-documents-dialog').press('Escape');
    calls.releaseStop();

    await expect(page.locator('#close-all-documents-dialog')).toBeHidden();
    await page.waitForTimeout(100);
    expect(await page.evaluate(() => window.ravenroot.workspace.documents.map(document_ => document_.id))).toEqual(before);
    expect(calls.deployments).toBe(0);
  });
});
