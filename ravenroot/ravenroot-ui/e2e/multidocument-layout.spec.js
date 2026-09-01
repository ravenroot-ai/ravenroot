import { expect, test } from '@playwright/test';

const WIDE = { width: 1800, height: 900 };
const MATRIX_WIDE = { width: 3000, height: 1600 };
const WORKSPACE_LAYOUT_KEY = 'ravenroot.workspace-layout.v1';

async function stubService(page) {
  await page.route('**/v1/node-types', route =>
    route.fulfill({ status: 200, contentType: 'application/json; charset=utf-8', body: '[]' }));
  await page.route('**/v1/events', route =>
    route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' }));
}

async function openDocuments(page, count) {
  await page.evaluate(total => {
    while (window.ravenroot.workspace.documents.length < total) {
      window.ravenroot.openDocument({ name: `layout-${window.ravenroot.workspace.documents.length + 1}.graphml` });
    }
  }, count);
}

// The command dispatch and readback deliberately share a browser task. A later ResizeObserver
// delivery must not be required to make the command truthful: state, persistence and the visible
// projection describe one user decision, not successive eventually-consistent versions of it.
async function commandProjection(page, mode) {
  await page.locator('[data-menu="layout"]').click();
  return page.evaluate(target => {
    const command = document.querySelector(`#application-menu [data-command-id="workspace.${target}"]`);
    if (!command) throw new Error(`workspace.${target} command was not rendered`);
    command.click();

    const host = document.getElementById('cy');
    const layout = window.ravenroot.workspaceLayout();
    const style = getComputedStyle(host);
    return {
      mode: layout.mode,
      stored: JSON.parse(localStorage.getItem('ravenroot.workspace-layout.v1')),
      hostMode: host.dataset.workspaceMode,
      visibility: host.dataset.workspaceVisibility,
      templates: { columns: style.gridTemplateColumns, rows: style.gridTemplateRows },
      panes: [...host.querySelectorAll('.doc-pane.doc-pane--shown')].map(pane => ({
        id: pane.dataset.documentId,
        cell: [pane.dataset.workspaceColumn, pane.dataset.workspaceRow],
        rendererOwner: window.ravenroot.workspace.find(pane.dataset.documentId).container === pane.querySelector('.doc-canvas'),
      })),
      splitters: [...host.querySelectorAll('.workspace-splitter:not([hidden])')]
        .map(splitter => splitter.dataset.workspaceAxis).sort(),
    };
  }, mode);
}

async function applicationMenuCommand(page, menu, commandId) {
  await page.locator(`[data-menu="${menu}"]`).click();
  await page.locator(`#application-menu [data-command-id="${commandId}"]`).click();
}

function expectModeProjection(projection, mode, count) {
  expect(projection).toMatchObject({
    mode,
    stored: { version: 1, mode },
    hostMode: mode,
    visibility: 'all',
  });
  expect(projection.panes).toHaveLength(count);
  expect(projection.panes.every(pane => pane.rendererOwner)).toBe(true);
}

test.describe('multi-document layout command projection', () => {
  test.use({ viewport: WIDE });

  test('one Layout menu click atomically projects grid state, storage, geometry and renderer owners', async ({ page }) => {
    await stubService(page);
    await page.goto('/');
    await openDocuments(page, 3);

    const projection = await commandProjection(page, 'grid');

    expect(projection.mode).toBe('grid');
    expect(projection.stored).toEqual({ version: 1, mode: 'grid' });
    expect(projection.hostMode).toBe('grid');
    expect(projection.visibility).toBe('all');
    expect(projection.templates.columns).not.toBe('none');
    expect(projection.templates.rows).not.toBe('none');
    expect(projection.panes).toHaveLength(3);
    expect(projection.panes.map(pane => pane.cell)).toEqual([['1', '1'], ['2', '1'], ['1', '2']]);
    expect(projection.panes.every(pane => pane.rendererOwner)).toBe(true);
    expect(projection.splitters).toEqual(['column', 'row']);
  });

  test('one Grid command stays feasible when live graph bounds contract without a resize', async ({ page }) => {
    await stubService(page);
    await page.goto('/');
    await openDocuments(page, 2);

    // The two bounds are deliberately extreme but entirely real Cytoscape state. The graph owns a
    // pan/zoom viewport inside a pane, so this must never decide whether its pane may exist.
    await page.evaluate(() => {
      const [wide] = window.ravenroot.workspace.documents;
      wide.cy.stop();
      wide.cy.getElementById('start').position({ x: 0, y: 100 });
      wide.cy.getElementById('end').position({ x: 20_000, y: 100 });
    });

    const firstProjection = await commandProjection(page, 'grid');
    expect(firstProjection).toMatchObject({
      mode: 'grid',
      stored: { version: 1, mode: 'grid' },
      hostMode: 'grid',
      visibility: 'all',
      splitters: ['column'],
    });
    expect(firstProjection.panes).toHaveLength(2);
    expect(firstProjection.panes.every(pane => pane.rendererOwner)).toBe(true);

    // No resize and no duplicate command: contract the graph's own bounds in the same rendered
    // workspace. Before the first command chose `active-only` from the wide bounding box;
    // this harmless contraction did not rerun the planner, and a second identical command was
    // needed to reveal both panes. The first command is now sufficient.
    const afterContraction = await page.evaluate(() => {
      const [wide] = window.ravenroot.workspace.documents;
      wide.cy.getElementById('end').position({ x: 60, y: 100 });
      const host = document.getElementById('cy');
      return {
        plan: window.ravenroot.workspaceLayout().plan.visibility,
        hostVisibility: host.dataset.workspaceVisibility,
        shown: host.querySelectorAll('.doc-pane.doc-pane--shown').length,
        stored: JSON.parse(localStorage.getItem('ravenroot.workspace-layout.v1')),
      };
    });
    expect(afterContraction).toEqual({
      plan: 'all', hostVisibility: 'all', shown: 2, stored: { version: 1, mode: 'grid' },
    });
  });

  test('covers H/V/G for one through five documents and every directed mode transition', async ({ page }) => {
    await page.setViewportSize(MATRIX_WIDE);
    await stubService(page);
    await page.goto('/');

    // N=1/2/3/4/5, each in all three modes. This is a practical full cross-product at a viewport
    // that can satisfy all structural minima; responsive refusal is covered separately below.
    for (let count = 1; count <= 5; count += 1) {
      await openDocuments(page, count);
      for (const mode of ['horizontal', 'vertical', 'grid']) {
        expectModeProjection(await commandProjection(page, mode), mode, count);
      }
    }

    // State transition coverage is explicit rather than inferred from the loop above.
    for (const [from, to] of [
      ['horizontal', 'vertical'], ['vertical', 'horizontal'], ['horizontal', 'grid'],
      ['grid', 'horizontal'], ['vertical', 'grid'], ['grid', 'vertical'],
    ]) {
      expectModeProjection(await commandProjection(page, from), from, 5);
      expectModeProjection(await commandProjection(page, to), to, 5);
    }
  });

  test('keeps GraphML/Cytoscape and Graphify/Elastic owners visible through grid and chrome changes', async ({ page }) => {
    await page.setViewportSize(MATRIX_WIDE);
    await stubService(page);
    await page.goto('/');
    const graphmlId = await page.evaluate(() => window.ravenroot.activeDocument().id);
    const graphify = JSON.stringify({
      nodes: [{ id: 'source', label: 'Source', type: 'file' }, { id: 'method', label: 'method()', type: 'method' }],
      edges: [{ source: 'source', target: 'method', relation: 'contains' }],
    });
    await page.locator('#file-inp').setInputFiles({
      name: 'catalog.json', mimeType: 'application/json', buffer: Buffer.from(graphify),
    });
    await expect.poll(() => page.evaluate(() => window.ravenroot.workspace.size)).toBe(2);
    await page.evaluate(id => window.ravenroot.activateDocument(id), graphmlId);
    await page.locator('#btn-monitoring').click();
    await expect.poll(() => page.evaluate(documentId =>
      window.ravenroot.workspace.find(documentId).renderer?.kind, graphmlId)).toBe('elastic');

    expectModeProjection(await commandProjection(page, 'grid'), 'grid', 2);
    await applicationMenuCommand(page, 'view', 'view.leftPanels');
    await applicationMenuCommand(page, 'view', 'view.rightInspector');
    expectModeProjection(await commandProjection(page, 'grid'), 'grid', 2);
    await applicationMenuCommand(page, 'view', 'view.leftPanels');
    await applicationMenuCommand(page, 'view', 'view.rightInspector');

    const owners = await page.evaluate(() => window.ravenroot.workspace.documents.map(document_ => ({
      format: document_.graph.format,
      kind: document_.renderer.kind,
      shown: document_.pane.classList.contains('doc-pane--shown'),
      connected: document_.container.isConnected,
      viewport: document_.renderer.kind === 'elastic'
        ? [document_.renderer.host.clientWidth, document_.renderer.host.clientHeight]
        : [document_.cy.width(), document_.cy.height()],
    })));
    expect(owners).toEqual(expect.arrayContaining([
      expect.objectContaining({ format: 'graphml', kind: 'elastic', shown: true, connected: true }),
      expect.objectContaining({ format: 'graphify', kind: 'cytoscape', shown: true, connected: true }),
    ]));
    expect(owners.every(owner => owner.viewport.every(value => value > 0))).toBe(true);
  });

  test('falls back at a narrow breakpoint and recovers all panes after responsive resize', async ({ page }) => {
    await stubService(page);
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.goto('/');
    await openDocuments(page, 2);
    // 453px yields two 226px rows after the grid rule, below the measured 228px structural floor.
    await page.evaluate(() => { document.getElementById('cy-wrap').style.flex = '0 0 453px'; });
    const narrow = await commandProjection(page, 'grid');
    expect(narrow).toMatchObject({ mode: 'grid', hostMode: 'grid', visibility: 'active-only' });
    expect(narrow.panes).toHaveLength(1);

    await page.setViewportSize(MATRIX_WIDE);
    await page.evaluate(() => { document.getElementById('cy-wrap').style.removeProperty('flex'); });
    await expect.poll(() => page.evaluate(() => ({
      visibility: document.getElementById('cy').dataset.workspaceVisibility,
      shown: document.querySelectorAll('#cy .doc-pane.doc-pane--shown').length,
      sizes: window.ravenroot.workspace.documents.map(document_ => [document_.cy.width(), document_.cy.height()]),
    }))).toEqual({ visibility: 'all', shown: 2, sizes: [expect.any(Array), expect.any(Array)] });
    expect(await page.evaluate(() => window.ravenroot.workspace.documents
      .every(document_ => document_.cy.width() > 0 && document_.cy.height() > 0))).toBe(true);
  });
});

// "Single pane" is a deliberate user choice — always exactly the active document — and must
// stay distinguishable from the `active-only`/`insufficient-space` fallback the tests above already
// cover: this suite runs at MATRIX_WIDE, a viewport wide enough that the fallback could never fire,
// specifically so a `visibility: 'active-only'` reading here can only be explained by the new mode.
test.describe('single pane mode', () => {
  test.use({ viewport: MATRIX_WIDE });

  test('shows exactly the active document at any viewport size, and follows the active document on switch', async ({ page }) => {
    await stubService(page);
    await page.goto('/');
    await openDocuments(page, 3);

    const projection = await commandProjection(page, 'single');
    expect(projection).toMatchObject({ mode: 'single', hostMode: 'single', visibility: 'active-only' });
    expect(projection.panes).toHaveLength(1);

    // The shape alone (one shown pane) is also what the narrow-viewport fallback produces; the
    // `reason` is what a stored plan or a future reader can use to tell them apart.
    const reason = await page.evaluate(() => window.ravenroot.workspaceLayout().plan.reason);
    expect(reason).toBe('single-mode');

    const [first, , third] = await page.evaluate(() => window.ravenroot.workspace.documents.map(document_ => document_.id));
    expect(projection.panes[0].id).toBe(third);

    await page.evaluate(id => window.ravenroot.activateDocument(id), first);
    await expect.poll(() => page.evaluate(() => [...document.querySelectorAll('#cy .doc-pane.doc-pane--shown')]
      .map(pane => pane.dataset.documentId))).toEqual([first]);
    expect(await page.evaluate(() => document.getElementById('cy').dataset.workspaceVisibility)).toBe('active-only');
  });

  test('does not disturb the horizontal/vertical/grid narrow-viewport fallback', async ({ page }) => {
    await stubService(page);
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.goto('/');
    await openDocuments(page, 2);
    await page.evaluate(() => { document.getElementById('cy-wrap').style.flex = '0 0 453px'; });

    const narrow = await commandProjection(page, 'grid');
    expect(narrow).toMatchObject({ mode: 'grid', hostMode: 'grid', visibility: 'active-only' });
    const fallbackReason = await page.evaluate(() => window.ravenroot.workspaceLayout().plan.reason);
    expect(fallbackReason).toBe('insufficient-space');
  });

  // The menu entry lists open documents and lets the user switch between
  // them. That is the toolbar document-switcher popover, already exercised elsewhere in this repo;
  // this proves the new View-menu command opens the SAME dialog rather than a second one.
  test('is reachable from the View menu, opening the same document switcher the toolbar button opens', async ({ page }) => {
    await stubService(page);
    await page.goto('/');
    await openDocuments(page, 2);

    const dialog = page.locator('#document-switcher-dialog');
    await expect(dialog).toBeHidden();
    await applicationMenuCommand(page, 'view', 'view.graphs');
    await expect(dialog).toBeVisible();
    await expect(dialog.locator('.document-switcher-row')).toHaveCount(2);

    const [first] = await page.evaluate(() => window.ravenroot.workspace.documents.map(document_ => document_.id));
    await dialog.locator(`[data-document-activate="${first}"]`).click();
    expect(await page.evaluate(() => window.ravenroot.activeDocument().id)).toBe(first);
  });
});
