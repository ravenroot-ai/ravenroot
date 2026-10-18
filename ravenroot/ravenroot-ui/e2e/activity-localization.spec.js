import { expect, test } from '@playwright/test';
import { UI_ORIGIN } from './ports.mjs';

const authoredName = 'Authored Ω <img src=x onerror="globalThis.__executed=true">';
const opaqueUrl = 'https://provider.example/review/17?<img src=x onerror="globalThis.__executed=true">&value="Ω"';
const graphMl = `<?xml version="1.0" encoding="UTF-8"?>
<graphml xmlns="http://graphml.graphdrawing.org/xmlns">
  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
  <graph id="authored-graph" edgedefault="directed">
    <node id="start"><data key="kind">START</data></node>
    <node id="end"><data key="kind">END</data></node>
    <edge id="authored-edge" source="start" target="end"/>
  </graph>
</graphml>`;
const catalogs = ['fr', 'de'].map(locale => ({ schema: 'ravenroot.ui-catalog/v1', locale, messages: {
  'workspace.notice.editor.23': `${locale}_EDITOR`,
  'workspace.notice.graph.review': `${locale}[{detail1}|{reviewType}|{url}]`,
  'workspace.app.existing': `${locale}_EXISTING`, 'workspace.app.new': `${locale}_NEW`,
  'workspace.app.pull.request': `${locale}_PULL`, 'workspace.app.merge.request': `${locale}_MERGE`,
  'workspace.notice.history': `${locale}[{verb}|{label}]`,
  'workspace.app.undo': `${locale}_UNDO`, 'workspace.app.redo': `${locale}_REDO`,
  'workspace.history.insert.nodes': `${locale}_INSERT`,
  'workspace.history.addNode': `${locale}_ADD {id}`,
  'workspace.history.edit.node': `${locale}_EDIT {id}`,
} }));

async function snapshot(page) {
  return page.evaluate(() => ({
    activeId: window.ravenroot.workspace.activeId,
    selected: window.cy.nodes(':selected').map(node => node.id()),
    documents: window.ravenroot.documents().map(document_ => ({ id: document_.id,
      graphMl: window.ravenroot.serializeGraphML(document_.graph),
      authoring: JSON.stringify(document_.authoring),
      revision: document_.history.revision(), dirty: document_.history.isDirty(),
      name: document_.name, displayName: document_.displayName,
    })),
  }));
}

async function switchLocale(page, locale) {
  await page.locator('#menu-view').click();
  await page.locator('[data-command-id="view.language"]').click();
  await page.locator('#language-selection').selectOption(locale);
  await expect(page.locator('html')).toHaveAttribute('lang', locale);
  await page.locator('.language-dialog button[type="submit"]').click();
}

for (const provider of ['github', 'gitlab']) for (const reused of [false, true]) {
  test(`${provider} ${reused ? 'existing' : 'new'} mounted review repaints nested labels without changing history`, async ({ page }) => {
    let currentProvider = provider; let releaseRequests = 0; let configurationRequests = 0;
    await page.route('**/ui-locales.json', route => route.fulfill({ contentType: 'application/json',
      body: JSON.stringify({ schema: 'ravenroot.ui-locales/v1', defaultLocale: 'fr', catalogs }) }));
    await page.route('**/v1/configuration', route => { configurationRequests++; return route.fulfill({ contentType: 'application/json', body: JSON.stringify({
      schemaVersion: 1, graphDocumentMaxBytes: 1024 * 1024,
      graphAuthoring: { mode: 'git', provider: currentProvider, operations: ['OPEN', 'SAVE', 'RELEASE_PROPOSAL'],
        repositoryDisplay: 'Authored provider Ω', releaseBranch: 'main', maxDocumentBytes: 1024 * 1024 },
    }) }); });
    await page.route('**/v1/node-types', route => route.fulfill({ contentType: 'application/json', body: '[]' }));
    await page.route('**/v1/events**', route => route.fulfill({ status: 204, body: '' }));
    await page.route('**/v1/graph-authoring**', route => {
      const url = new URL(route.request().url());
      if (url.pathname.endsWith('/release')) {
        releaseRequests++;
        return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ reused, url: opaqueUrl }) });
      }
      if (url.pathname === '/v1/graph-authoring') return route.fulfill({ contentType: 'application/json',
        body: JSON.stringify({ items: [{ documentId: 'authored.graphml', releaseVersion: 7, draftDeleted: false }] }) });
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({
        documentId: 'authored.graphml', graphId: 'authored-graph', releaseVersion: 7,
        released: false, published: false, draftDeleted: false,
        revision: { draft: 'authored-draft', release: 'absent', publication: 'absent' },
        graphMl: Buffer.from(graphMl).toString('base64'),
      }) });
    });
    await page.goto('/');
    await expect(page.locator('html')).toHaveAttribute('lang', 'fr');
    await expect(page.locator('#btn-open')).toContainText('Open from repository');
    page.once('dialog', dialog => dialog.accept('authored.graphml [draft]'));
    await page.locator('#btn-open').click();
    await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().authoring.providerDocumentId))
      .toBe('authored.graphml');
    const otherDocument = await page.evaluate(() => {
      const original = window.ravenroot.workspace.activeId;
      const other = window.ravenroot.openDocument({ name: 'Later workspace.graphml' });
      window.ravenroot.activateDocument(original);
      return other;
    });
    await page.locator('#btn-modify').click();
    await page.locator('#btn-add-node').click();
    await page.locator('#node-editor input[name="name"]').fill(authoredName);
    await page.locator('#node-editor button[type="submit"]').click();
    await page.evaluate(() => { window.cy.nodes().last().select(); });
    const before = await snapshot(page);
    expect(before.documents.some(item => item.dirty)).toBe(true);
    await page.locator('#menu-file').click();
    await page.locator('[data-command-id="file.repositoryRelease"]').click();
    const review = page.locator('#activity-log .activity-entry').filter({ hasText: opaqueUrl });
    const expected = locale => `${locale}[${locale}_${reused ? 'EXISTING' : 'NEW'}|${locale}_${provider === 'github' ? 'PULL' : 'MERGE'}|${opaqueUrl}]`;
    await expect(review.locator('.activity-detail')).toHaveText(expected('fr'));
    await review.evaluate(element => { element.dataset.retainedProbe = 'same-row'; });
    const navigationCount = await page.evaluate(() => performance.getEntriesByType('navigation').length);
    for (const locale of ['de', 'fr']) {
      await switchLocale(page, locale);
      await expect(review.locator('.activity-detail')).toHaveText(expected(locale));
      await expect(review.locator('.activity-title')).toHaveText(`${locale}_EDITOR`);
      await expect(review).toHaveAttribute('data-retained-probe', 'same-row');
      expect(await snapshot(page)).toEqual(before);
      expect(await review.locator('img, svg, a, script').count()).toBe(0);
      expect(await page.evaluate(() => globalThis.__executed)).toBeUndefined();
    }
    // A real second activity family: undo/redo retains command meaning and authored node data.
    await page.locator('#btn-undo').click();
    await page.locator('#btn-redo').click();
    const history = page.locator('#activity-log .activity-detail').last();
    await history.evaluate(element => { element.dataset.retainedHistory = 'same'; });
    const retainedHistory = page.locator('[data-retained-history="same"]');
    const historyFr = await history.textContent();
    expect(historyFr).toContain('fr_REDO');
    expect(historyFr).toMatch(/fr_(ADD|EDIT)/);
    const afterHistory = await snapshot(page);
    await switchLocale(page, 'de');
    await expect(retainedHistory).toContainText('de_REDO');
    await expect(retainedHistory).toContainText(/de_(ADD|EDIT)/);
    expect(await snapshot(page)).toEqual(afterHistory);
    await switchLocale(page, 'fr');
    await expect(retainedHistory).toHaveText(historyFr);
    expect(await snapshot(page)).toEqual(afterHistory);
    // Replace active workspace and refresh actual provider configuration after the event.
    currentProvider = provider === 'github' ? 'gitlab' : 'github';
    await page.evaluate(id => window.ravenroot.activateDocument(id), otherDocument);
    const beforeConfiguration = configurationRequests;
    await page.locator('#service-url').dispatchEvent('change');
    await expect.poll(() => configurationRequests).toBeGreaterThan(beforeConfiguration);
    await expect.poll(() => page.evaluate(() => window.ravenroot.workspacePersistence().authority.state)).toBe('ready');
    await switchLocale(page, 'de');
    await expect(review.locator('.activity-detail')).toHaveText(expected('de'));
    await expect(retainedHistory).toContainText('de_REDO');
    await expect(retainedHistory).toContainText(/de_(ADD|EDIT)/);
    expect(releaseRequests).toBe(1);
    expect(await page.evaluate(() => performance.getEntriesByType('navigation').length)).toBe(navigationCount);
    expect(page.url()).toBe(`${UI_ORIGIN}/`);
  });
}
