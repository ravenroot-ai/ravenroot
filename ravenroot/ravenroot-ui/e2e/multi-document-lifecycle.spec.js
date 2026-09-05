import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';

// `test/fixtures/edge-authoring.graphml` is the golden fixture regenerated from the editor's own
// edge-authoring entry points (UI-02). This suite does not exercise edge authoring; it only needs a
// valid document to open, drop and stage. Sharing that fixture would couple unrelated behavior:
// changing its edge-authoring surface from four to six nodes would also change this suite's node-id
// assertions. `lifecycle-document.graphml` is private to this suite, so its identifiers can be
// asserted without coupling lifecycle coverage to edge-authoring coverage.
const graphMl = readFileSync(new URL('../test/fixtures/lifecycle-document.graphml', import.meta.url));

async function records(page) {
  return page.evaluate(() => window.ravenroot.documents().map(document_ => ({
    id: document_.id,
    name: document_.name,
    displayName: document_.displayName,
    dirty: document_.history.isDirty(),
    active: document_.id === window.ravenroot.workspace.activeId,
    format: document_.graph?.format,
  })));
}

async function makeActiveDirty(page, name = 'Review step') {
  if (await page.locator('#btn-modify').getAttribute('aria-pressed') !== 'true') {
    await page.locator('#btn-modify').click();
  }
  await page.locator('#btn-add-node').click();
  await page.locator('#node-editor input[name="name"]').fill(name);
  await page.locator('#node-editor button[type="submit"]').click();
  await expect(page.locator('#dirty-state')).toHaveText('unsaved changes');
}

async function openGraphMl(page, name = 'orders.graphml') {
  await page.locator('#file-inp').setInputFiles({
    name,
    mimeType: 'application/xml',
    buffer: graphMl,
  });
  await expect.poll(async () => (await records(page)).at(-1)?.name).toBe(name);
}

test.describe('multi-document lifecycle', () => {
  test.use({ viewport: { width: 1280, height: 800 } });

  test('New preserves dirty work and the switcher reaches hidden documents by keyboard', async ({ page }) => {
    await page.goto('/');
    await makeActiveDirty(page);
    const [first] = await records(page);

    await page.locator('#btn-new').click();
    await expect.poll(() => records(page)).toEqual([
      expect.objectContaining({ id: first.id, displayName: 'untitled.graphml', dirty: true, active: false }),
      expect.objectContaining({ displayName: 'untitled-2.graphml', dirty: false, active: true }),
    ]);
    await expect(page.locator('#document-switcher')).toHaveAttribute(
      'aria-label',
      'untitled-2.graphml, current, document 2 of 2',
    );
    await expect(page.locator('#cy .doc-pane--shown')).toHaveCount(1);

    await page.locator('#document-switcher').click();
    const items = page.locator('[data-document-activate]');
    await expect(items.nth(1)).toBeFocused();
    await items.nth(1).press('ArrowUp');
    await expect(items.first()).toBeFocused();
    await items.first().press('Enter');

    await expect.poll(async () => (await records(page)).find(document_ => document_.active)?.id).toBe(first.id);
    await expect(page.locator(`#cy .doc-pane[data-document-id="${first.id}"]`)).toBeFocused();
    await expect(page.locator('#dirty-state')).toHaveText('unsaved changes');
  });

  test('application and command bars keep the switcher, runtime fields, and Play reachable', async ({ page }) => {
    await page.goto('/');
    await page.locator('#btn-new').click();

    await expect.poll(() => page.evaluate(() => {
      const geometry = id => {
        const element = document.getElementById(id);
        return { clientWidth: element.clientWidth, scrollWidth: element.scrollWidth };
      };
      return { topbar: geometry('topbar'), workflowbar: geometry('workflowbar') };
    })).toEqual({
      topbar: expect.objectContaining({ clientWidth: 1280, scrollWidth: 1280 }),
      workflowbar: expect.objectContaining({ clientWidth: 1280, scrollWidth: 1280 }),
    });

    for (const selector of ['#document-switcher', '#service-url', '#access-token',
      '#execution-payload', '#btn-play']) {
      const control = page.locator(selector);
      await expect(control).toBeVisible();
      await expect(control).toBeInViewport();
    }
    await expect(page.locator('#execution-payload')).toBeEditable();
    await expect(page.locator('#btn-play')).toBeEnabled();
    await page.locator('#execution-payload').fill('reachable payload');
    await expect(page.locator('#execution-payload')).toHaveValue('reachable payload');

    const runtimeBounds = await page.evaluate(() => [...document.querySelectorAll('.runtime-controls > *')]
      .map(element => {
        const bounds = element.getBoundingClientRect();
        return { left: bounds.left, right: bounds.right, top: bounds.top, bottom: bounds.bottom };
      }));
    for (const bounds of runtimeBounds) {
      expect(bounds.left).toBeGreaterThanOrEqual(0);
      expect(bounds.right).toBeLessThanOrEqual(1280);
      expect(bounds.top).toBeGreaterThanOrEqual(0);
      expect(bounds.bottom).toBeLessThanOrEqual(800);
    }
  });

  test('Open and stage drop add real GraphML documents while parse failures are atomic', async ({ page }) => {
    await page.goto('/');
    await makeActiveDirty(page);
    await openGraphMl(page);
    await expect.poll(() => page.evaluate(() => window.cy.nodes().map(node => node.id()).sort()))
      .toEqual(['archive', 'end', 'review', 'start']);

    const beforeFailure = await records(page);
    const beforeElements = await page.evaluate(() => window.cy.elements().map(element => element.id()).sort());
    const alert = page.waitForEvent('dialog');
    await page.locator('#file-inp').setInputFiles({
      name: 'broken.graphml',
      mimeType: 'application/xml',
      buffer: Buffer.from('<graphml><broken>'),
    });
    await (await alert).accept();
    await expect.poll(() => records(page)).toEqual(beforeFailure);
    expect(await page.evaluate(() => window.cy.elements().map(element => element.id()).sort()))
      .toEqual(beforeElements);

    await page.evaluate(text => {
      const transfer = new DataTransfer();
      transfer.items.add(new File([text], 'dropped.graphml', { type: 'application/xml' }));
      document.getElementById('cy-wrap').dispatchEvent(new DragEvent('drop', {
        bubbles: true,
        cancelable: true,
        dataTransfer: transfer,
      }));
    }, graphMl.toString());
    await expect.poll(async () => (await records(page)).at(-1)?.name).toBe('dropped.graphml');
    await expect.poll(() => page.evaluate(() => window.cy.getElementById('review').length)).toBe(1);
  });

  test('duplicate display names are stable and saving a background document does not activate it', async ({ page }) => {
    await page.goto('/');
    await openGraphMl(page);
    await openGraphMl(page);
    let docs = await records(page);
    expect(docs.map(document_ => document_.displayName)).toEqual([
      'untitled.graphml', 'orders.graphml', 'orders.graphml (2)',
    ]);

    const activeId = docs[2].id;
    const backgroundId = docs[1].id;
    await page.evaluate(id => window.ravenroot.activateDocument(id), backgroundId);
    await makeActiveDirty(page, 'Background edit');
    await page.evaluate(id => window.ravenroot.activateDocument(id), activeId);

    await page.locator('#document-switcher').click();
    const backgroundClose = page.locator(`[data-document-close="${backgroundId}"]`);
    await backgroundClose.click();
    await expect(page.locator('#unsaved-document-dialog')).toHaveAttribute('open', '');
    await expect(page.locator('[data-unsaved-action="cancel"]')).toBeFocused();
    await page.locator('#unsaved-document-dialog').press('Escape');
    await expect(page.locator('#unsaved-document-dialog')).not.toHaveAttribute('open', '');
    await expect(backgroundClose).toBeFocused();

    await backgroundClose.click();
    const downloadPromise = page.waitForEvent('download');
    await page.locator('[data-unsaved-action="save"]').click();
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toBe('orders.graphml');
    await expect.poll(async () => (await records(page)).find(document_ => document_.active)?.id).toBe(activeId);
    docs = await records(page);
    expect(docs.map(document_ => document_.displayName)).toEqual(['untitled.graphml', 'orders.graphml (2)']);

    await openGraphMl(page);
    expect((await records(page)).map(document_ => document_.displayName))
      .toEqual(['untitled.graphml', 'orders.graphml (2)', 'orders.graphml (3)']);
  });

  test('dirty close supports Discard, Save and Close, and the final empty workspace', async ({ page }) => {
    await page.goto('/');
    await makeActiveDirty(page, 'First edit');
    const [first] = await records(page);
    await page.locator('#btn-new').click();
    await makeActiveDirty(page, 'Second edit');
    const second = (await records(page))[1];

    await page.locator('#document-switcher').click();
    await page.locator(`[data-document-close="${first.id}"]`).click();
    await page.locator('[data-unsaved-action="discard"]').click();
    await expect.poll(() => records(page)).toEqual([
      expect.objectContaining({ id: second.id, active: true, dirty: true }),
    ]);
    await expect(page.locator('#document-switcher')).toBeFocused();

    await page.locator('#document-switcher').click();
    await page.locator(`[data-document-close="${second.id}"]`).click();
    const downloadPromise = page.waitForEvent('download');
    await page.locator('[data-unsaved-action="save"]').click();
    expect((await downloadPromise).suggestedFilename()).toBe('untitled-2.graphml');
    await expect.poll(() => records(page)).toEqual([]);
    await expect(page.locator('#btn-new')).toBeFocused();
    await expect(page.locator('#document-switcher')).toBeDisabled();
    await expect(page.locator('#graph-title')).toHaveText('No graph loaded');

    await page.locator('#btn-new').click();
    await expect.poll(async () => (await records(page)).at(-1)?.displayName).toBe('untitled-3.graphml');
  });

  test('Graphify is a new view-only document and replace parses before prompting or mutation', async ({ page }) => {
    await page.goto('/');
    await makeActiveDirty(page);
    const before = await records(page);

    const error = await page.evaluate(() => {
      try {
        window.ravenroot.replaceActiveDocumentFromText('<graphml><broken>', 'bad.graphml');
        return null;
      } catch (caught) {
        return caught.message;
      }
    });
    expect(error).toBeTruthy();
    expect(await records(page)).toEqual(before);
    await expect(page.locator('#unsaved-document-dialog')).not.toHaveAttribute('open', '');

    await page.evaluate(text => {
      window.ravenroot.replaceActiveDocumentFromText(text, 'replacement.graphml');
    }, graphMl.toString());
    await expect(page.locator('#unsaved-document-dialog')).toHaveAttribute('open', '');
    // A valid replacement is still target-specific: parsing did not allocate a second record, and
    // the dirty target remains intact until the user resolves its prompt.
    expect(await records(page)).toEqual(before);
    await page.locator('[data-unsaved-action="discard"]').click();
    await expect.poll(() => records(page)).toEqual([
      expect.objectContaining({ name: 'replacement.graphml', dirty: false, active: true }),
    ]);
    await expect.poll(() => page.evaluate(() => window.cy.getElementById('review').length)).toBe(1);

    const graphify = JSON.stringify({
      nodes: [{ id: 'source-file', label: 'Source file', type: 'file' }],
      edges: [],
    });
    await page.locator('#file-inp').setInputFiles({
      name: 'codebase.json',
      mimeType: 'application/json',
      buffer: Buffer.from(graphify),
    });
    await expect.poll(async () => (await records(page)).at(-1)?.format).toBe('graphify');
    await expect(page.locator('#btn-modify')).toBeDisabled();
    await expect(page.locator('#btn-export')).toBeDisabled();
    expect((await records(page))[0]).toEqual(expect.objectContaining({
      name: 'replacement.graphml', dirty: false, format: 'graphml',
    }));
  });

  test('replace rejects N+1 local bytes before constructing a FileReader', async ({ page }) => {
    await page.addInitScript(() => {
      const original = FileReader.prototype.readAsText;
      window.__ravenrootFileReads = 0;
      FileReader.prototype.readAsText = function measuredReadAsText(...args) {
        window.__ravenrootFileReads += 1;
        return original.apply(this, args);
      };
    });
    await page.route('**/v1/configuration', route => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ schemaVersion: 1, graphDocumentMaxBytes: 64 }),
    }));
    await page.goto('/');
    await expect.poll(() => page.evaluate(() => {
      try {
        return window.ravenroot.graphDocumentByteLimit();
      } catch {
        return null;
      }
    })).toBe(64);

    let message = '';
    page.once('dialog', async dialog => {
      message = dialog.message();
      await dialog.dismiss();
    });
    await page.locator('#replace-file-inp').setInputFiles({
      name: 'oversized.graphml',
      mimeType: 'application/graphml+xml',
      buffer: Buffer.from('x'.repeat(65)),
    });

    await expect.poll(() => message).toContain('document exceeds the configured byte limit');
    await expect.poll(() => page.evaluate(() => window.__ravenrootFileReads)).toBe(0);
  });

  test('malformed runtime configuration fails closed before opening a local file', async ({ page }) => {
    await page.addInitScript(() => {
      const original = FileReader.prototype.readAsText;
      window.__ravenrootFileReads = 0;
      FileReader.prototype.readAsText = function measuredReadAsText(...args) {
        window.__ravenrootFileReads += 1;
        return original.apply(this, args);
      };
    });
    await page.route('**/v1/configuration', route => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ schemaVersion: 1, graphDocumentMaxBytes: 0 }),
    }));
    await page.goto('/');
    const before = await records(page);

    let message = '';
    page.once('dialog', async dialog => {
      message = dialog.message();
      await dialog.dismiss();
    });
    await page.locator('#file-inp').setInputFiles({
      name: 'valid.graphml',
      mimeType: 'application/graphml+xml',
      buffer: graphMl,
    });

    await expect.poll(() => message).toContain(
      'Graph document loading is unavailable until the connected service returns valid configuration',
    );
    await expect.poll(() => page.evaluate(() => window.__ravenrootFileReads)).toBe(0);
    expect(await records(page)).toEqual(before);
  });
});
