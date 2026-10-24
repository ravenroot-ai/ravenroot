import { expect, test } from '@playwright/test';
import { execFileSync } from 'node:child_process';

import { ENGLISH_MESSAGES } from '../src/ui-text.js';
import { UI_ORIGIN } from './ports.mjs';

const mapMessages = transform => Object.fromEntries(Object.entries(ENGLISH_MESSAGES).map(([key, message]) => [
  key,
  typeof message === 'string'
    ? transform(message, key)
    : Object.fromEntries(Object.entries(message).map(([category, value]) => [category, transform(value, key)])),
]));

const pseudo = (locale, direction, marker) => ({
  schema: 'ravenroot.ui-catalog/v1',
  locale,
  direction,
  messages: mapMessages(value => `${marker}${value}${marker}`),
});

const italian = pseudo('it', 'ltr', '⟦IT⟧');
italian.messages['workspace.template.app.name'] = '<img src=x onerror="globalThis.__catalogExecuted=true">Nome';
italian.messages['workspace.template.app.registered.name.or.future.behavior'] = '" autofocus onfocus="globalThis.__catalogExecuted=true';
Object.assign(italian.messages, {
  'workspace.node.palette.action.saveSelected': '⟦IT-SAVE⟧',
  'workspace.node.palette.view.rename': '⟦IT-RENAME⟧',
  'workspace.node.palette.view.delete': '⟦IT-DELETE⟧',
  'workspace.node.palette.view.in': '{label} ⟦IT-IN⟧ {palette}',
  'workspace.panel.show': '⟦IT-SHOW⟧ {name}',
  'workspace.app.close': '⟦IT-CLOSE⟧ {name}',
  'workspace.program.history.entry': '⟦IT-ENTRY⟧ {phase}|{revision}|{state}',
  'workspace.program.history.revision': '⟦IT-REV⟧ {revision}',
  'workspace.program.history.state.failed': '⟦IT-FAILED⟧',
  'workspace.workflow.validation.oneStart': '⟦IT-ONE-START⟧',
  'workspace.workflow.validation.edgeId': '⟦IT-EDGE-ID⟧ {reason}',
  'workspace.edge.validation.stableId.nonblank': '⟦IT-NONBLANK⟧',
});
// Two complete synthetic catalogs share the production 512 KiB configuration ceiling.
// Keep long RTL labels while omitting redundant English prose from long messages.
const arabic = { schema: 'ravenroot.ui-catalog/v1', locale: 'ar', direction: 'rtl',
  messages: mapMessages(value => '⟦نص واجهة طويل للاختبار⟧'
    + (value.length > 40 ? (value.match(/\{[A-Za-z][A-Za-z0-9_]*\}/gu) || []).join(' ') : value)),
};
const german = pseudo('de', 'ltr', '⟦DE⟧');
Object.assign(german.messages, {
  'workspace.node.palette.action.saveSelected': 'AusgewähltenKonfigurationsknotenInDerPersönlichenWiederverwendungspaletteSpeichern',
  'workspace.node.palette.view.rename': 'PersönlicheWiederverwendungspaletteUmbenennen',
  'workspace.node.palette.view.delete': 'PersönlicheWiederverwendungspaletteDauerhaftLöschen',
});
Object.assign(arabic.messages, {
  'workspace.node.palette.action.saveSelected': 'حفظ العقدة المحددة في لوحة إعادة الاستخدام الشخصية الطويلة للغاية',
  'workspace.node.palette.view.rename': 'إعادة تسمية لوحة إعادة الاستخدام الشخصية باسم عربي طويل للغاية',
  'workspace.node.palette.view.delete': 'حذف لوحة إعادة الاستخدام الشخصية نهائياً بعد التأكيد',
});

const configuration = {
  schema: 'ravenroot.ui-locales/v1',
  defaultLocale: 'en',
  catalogs: [italian, arabic],
};

async function configure(page, body = configuration) {
  await page.route('**/ui-locales.json', route => route.fulfill({
    contentType: 'application/json', body: JSON.stringify(body),
  }));
}

async function openLanguageDialog(page, locale = 'en') {
  const marker = { en: '', it: '⟦IT⟧', de: '⟦DE⟧', ar: '⟦نص واجهة طويل للاختبار⟧' }[locale];
  await page.locator('#menu-view').click();
  await page.getByRole('menuitem', { name: `${marker}Language…${marker}` }).click();
}

async function createDirtyDraft(page) {
  await page.locator('#btn-new').click();
  await page.locator('#btn-modify').click();
  await page.locator('#btn-add-node').click();
  await page.locator('#node-editor input[name="name"]').fill('Saved before locale switch');
  await page.locator('#node-editor button[type="submit"]').click();
  await expect(page.locator('#dirty-state')).toHaveText('unsaved changes');
  await page.evaluate(() => { window.cy.nodes().last().select(); });
  await expect(page.locator('#node-editor')).toBeVisible();
  await page.locator('#node-editor input[name="name"]').fill('Unsubmitted localized draft');
  return page.evaluate(() => ({
    documentId: window.ravenroot.activeDocument().id,
    displayName: window.ravenroot.activeDocument().displayName,
    nodes: window.cy.nodes().length,
    selected: window.cy.nodes(':selected').map(node => node.id()),
  }));
}

test('keeps every localized palette action usable inside a 360px panel in LTR and RTL', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 360, height: 1400 });
  await configure(page, { schema: 'ravenroot.ui-locales/v1', defaultLocale: 'de',
    catalogs: [german, arabic] });
  await page.goto('/');
  await page.locator('#btn-new').evaluate(button => button.click());
  await page.locator('#btn-modify').evaluate(button => button.click());
  await page.evaluate(() => {
    window.cy.nodes().first().select();
    localStorage.setItem('ravenroot.ui.node-palettes.open.v1', JSON.stringify(['palette-machine-id']));
    window.ravenroot._renderPersonalPalettesForTest({ pending: false, error: '',
      palettes: [{ id: 'palette-machine-id', name: 'Palette Ω', version: 7 }],
      templates: [{ id: 'template-machine-id', paletteId: 'palette-machine-id',
        name: 'Auteur Vorlage اسم مؤلف', version: 11, node: { id: 'authored-node' } }],
    });
  });
  const palette = page.locator('.node-palette[data-palette-id="palette-machine-id"]');
  const authored = async () => palette.evaluate(element => ({
    paletteId: element.dataset.paletteId,
    paletteName: element.querySelector('summary').textContent,
    templateName: element.querySelector('.node-template-insert').textContent,
    templateId: element.querySelector('.node-template-insert')?.closest('.node-template')
      ?.querySelector('.node-template-insert')?.textContent,
  }));
  const identity = await authored();
  expect(identity).toMatchObject({ paletteId: 'palette-machine-id', paletteName: 'Palette Ω',
    templateName: 'Auteur Vorlage اسم مؤلف' });

  const assertActions = async (locale, names) => {
    await expect(page.locator('html')).toHaveAttribute('lang', locale);
    const buttons = palette.locator('.palette-actions button');
    await expect(buttons).toHaveCount(3);
    await expect(buttons).toHaveText(names);
    const geometry = await palette.evaluate(element => {
      const panel = element.closest('.panel');
      const paletteBounds = element.getBoundingClientRect();
      const panelBounds = panel.getBoundingClientRect();
      const actions = element.querySelector('.palette-actions');
      return {
        viewportWidth: innerWidth,
        rootFits: document.documentElement.scrollWidth <= innerWidth,
        actionsFits: actions.scrollWidth <= actions.clientWidth,
        buttons: [...actions.querySelectorAll('button')].map(button => {
          const bounds = button.getBoundingClientRect();
          return { left: bounds.left, right: bounds.right, width: bounds.width,
            paletteLeft: paletteBounds.left, paletteRight: paletteBounds.right,
            panelLeft: panelBounds.left, panelRight: panelBounds.right };
        }),
      };
    });
    expect(geometry.rootFits).toBe(true);
    expect(geometry.actionsFits).toBe(true);
    for (const bounds of geometry.buttons) {
      expect(bounds.width).toBeGreaterThan(0);
      expect(bounds.left).toBeGreaterThanOrEqual(bounds.paletteLeft - 0.5);
      expect(bounds.right).toBeLessThanOrEqual(bounds.paletteRight + 0.5);
      expect(bounds.left).toBeGreaterThanOrEqual(bounds.panelLeft - 0.5);
      expect(bounds.right).toBeLessThanOrEqual(bounds.panelRight + 0.5);
      expect(bounds.left).toBeGreaterThanOrEqual(0);
      expect(bounds.right).toBeLessThanOrEqual(geometry.viewportWidth);
    }
    for (let index = 0; index < names.length; index += 1) {
      const button = buttons.nth(index);
      await button.focus();
      await expect(button).toBeFocused();
      const dialogs = [];
      page.once('dialog', async dialog => { dialogs.push(dialog.type()); await dialog.dismiss(); });
      await button.click();
      await expect.poll(() => dialogs.length).toBe(1);
    }
  };

  const germanNames = [german.messages['workspace.node.palette.action.saveSelected'],
    german.messages['workspace.node.palette.view.rename'], german.messages['workspace.node.palette.view.delete']];
  await assertActions('de', germanNames);
  const germanScreenshot = testInfo.outputPath('narrow-palette-de.png');
  await palette.screenshot({ path: germanScreenshot });
  await testInfo.attach('narrow German palette', { path: germanScreenshot, contentType: 'image/png' });

  await openLanguageDialog(page, 'de');
  await page.locator('#language-selection').selectOption('ar');
  await page.locator('.language-dialog button[type="submit"]').click();
  const arabicNames = [arabic.messages['workspace.node.palette.action.saveSelected'],
    arabic.messages['workspace.node.palette.view.rename'], arabic.messages['workspace.node.palette.view.delete']];
  await assertActions('ar', arabicNames);
  expect(await authored()).toEqual(identity);
  const arabicScreenshot = testInfo.outputPath('narrow-palette-ar.png');
  await palette.screenshot({ path: arabicScreenshot });
  await testInfo.attach('narrow Arabic palette', { path: arabicScreenshot, contentType: 'image/png' });
});

test('contains a long localized palette at the desktop minimum panel width', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await configure(page, { schema: 'ravenroot.ui-locales/v1', defaultLocale: 'de', catalogs: [german] });
  await page.goto('/');
  await page.locator('[data-layout-splitter="left"]').focus();
  await page.keyboard.press('Home');
  await page.evaluate(() => {
    localStorage.setItem('ravenroot.ui.node-palettes.open.v1', JSON.stringify(['palette-machine-id']));
    window.ravenroot._renderPersonalPalettesForTest({ pending: false, error: '',
      palettes: [{ id: 'palette-machine-id', name: 'Palette Ω', version: 7 }],
      templates: [{ id: 'template-machine-id', paletteId: 'palette-machine-id',
        name: 'Auteur Vorlage MitEinemSehrLangenZusammengesetztenNamen اسم مؤلف طويل للغاية',
        version: 11, node: { id: 'authored-node' } }],
    });
  });
  const palette = page.locator('.node-palette[data-palette-id="palette-machine-id"]');
  const geometry = await palette.evaluate(element => {
    const bounds = node => { const box = node.getBoundingClientRect(); return { left: box.left,
      right: box.right, width: box.width }; };
    const panel = element.closest('.panel');
    const template = element.querySelector('.node-template');
    const panelBounds = bounds(panel);
    return { viewportWidth: innerWidth, panel: panelBounds, palette: bounds(element),
      template: { ...bounds(template), clientWidth: template.clientWidth,
        scrollWidth: template.scrollWidth }, documentScrollWidth: document.documentElement.scrollWidth,
      controls: [...element.querySelectorAll('.palette-actions button, .node-template button')]
        .map(node => ({ text: node.textContent, ...bounds(node) })),
    };
  });
  expect(geometry.panel.width).toBeLessThan(200);
  expect(geometry.documentScrollWidth).toBeLessThanOrEqual(geometry.viewportWidth);
  expect(geometry.template.scrollWidth).toBeLessThanOrEqual(geometry.template.clientWidth);
  for (const control of geometry.controls) {
    expect(control.width).toBeGreaterThan(0);
    expect(control.left).toBeGreaterThanOrEqual(geometry.palette.left - 0.5);
    expect(control.right).toBeLessThanOrEqual(geometry.palette.right + 0.5);
    expect(control.left).toBeGreaterThanOrEqual(geometry.panel.left - 0.5);
    expect(control.right).toBeLessThanOrEqual(geometry.panel.right + 0.5);
  }
  const screenshot = testInfo.outputPath('desktop-minimum-palette-de.png');
  await palette.screenshot({ path: screenshot });
  await testInfo.attach('desktop minimum German palette', { path: screenshot,
    contentType: 'image/png' });
});

test('switches a complete catalog live while preserving a dirty selected document and open form', async ({ page }, testInfo) => {
  await configure(page);
  await page.goto('/');
  const before = await createDirtyDraft(page);
  expect(before.selected.length).toBeGreaterThan(0);

  await openLanguageDialog(page);
  await page.locator('#language-selection').selectOption('it');

  await expect(page.locator('html')).toHaveAttribute('lang', 'it');
  await expect(page.locator('html')).toHaveAttribute('dir', 'ltr');
  await expect(page.locator('#menu-file')).toHaveText('⟦IT⟧File⟦IT⟧');
  await expect(page.locator('#language-dialog-title')).toHaveText('⟦IT⟧Interface language⟦IT⟧');
  await expect(page.locator('#language-selection')).toHaveValue('it');
  await expect(page.locator('#leg-nodes .li-label').first()).toHaveText('⟦IT⟧Start⟦IT⟧');
  await expect(page.locator('#node-catalog .catalog-empty')).toContainText('⟦IT⟧');
  await expect(page.locator('#activity-log .activity-title').last()).toContainText('⟦IT⟧');
  await expect(page.locator('#btn-program-approve')).toHaveText('⟦IT⟧Approve program batch⟦IT⟧');
  await expect(page.locator('#minimap-label')).toHaveText(`⟦IT⟧Overview — ${before.displayName}⟦IT⟧`);
  await expect(page.locator('#minimap-canvas')).toHaveAttribute('aria-label', /⟦IT⟧Design⟦IT⟧/u);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  expect(await page.locator('#btn-program-approve').evaluate(button => {
    const bounds = button.getBoundingClientRect();
    return bounds.left >= 0 && bounds.right <= innerWidth && bounds.width >= 160;
  })).toBe(true);
  await expect(page.locator('#node-editor input[name="name"]')).toHaveValue('Unsubmitted localized draft');
  await expect(page.locator('#dirty-state')).toContainText('unsaved changes');
  await expect.poll(() => page.evaluate(() => ({
    documentId: window.ravenroot.activeDocument().id,
    displayName: window.ravenroot.activeDocument().displayName,
    nodes: window.cy.nodes().length,
    selected: window.cy.nodes(':selected').map(node => node.id()),
  }))).toEqual(before);

  await page.getByRole('button', { name: '⟦IT⟧Close⟦IT⟧' }).click();
  await expect(page.locator('#node-editor label').filter({ hasText: '<img src=x' })).toBeVisible();
  await expect(page.locator('#node-editor img')).toHaveCount(0);
  await expect(page.locator('#node-editor input[name="behavior"]')).toHaveAttribute(
    'placeholder', '" autofocus onfocus="globalThis.__catalogExecuted=true',
  );
  expect(await page.evaluate(() => globalThis.__catalogExecuted)).toBeUndefined();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);

  const screenshot = testInfo.outputPath('complete-catalog-live-switch.png');
  await page.screenshot({ path: screenshot });
  await testInfo.attach('complete catalog live switch', { path: screenshot, contentType: 'image/png' });

  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('lang', 'it');
  await openLanguageDialog(page, 'it');
  await expect(page.locator('#language-selection')).toHaveValue('it');
});

test('switches every structural presentation surface live while preserving authored and machine values', async ({ page }, testInfo) => {
  await configure(page);
  await page.route('**/v1/configuration', route => route.fulfill({
    contentType: 'application/json', body: JSON.stringify({ schemaVersion: 1, graphDocumentMaxBytes: 10 * 1024 * 1024 }),
  }));
  await page.route('**/v1/node-types', route => route.fulfill({ contentType: 'application/json', body: '[]' }));
  await page.route('**/v1/events**', route => route.fulfill({ contentType: 'text/event-stream', body: '' }));
  await page.goto('/');
  await expect(page.locator('#node-catalog')).toContainText('empty node catalog');
  const identity = await page.evaluate(() => {
    const owner = window.ravenroot.activeDocument();
    const start = owner.graph.nodes.find(node => node.kind === 'START');
    const duplicateStart = structuredClone(start);
    duplicateStart.id = 'duplicate-start-machine-id';
    duplicateStart.name = 'Auteur duplicate start';
    owner.graph.nodes.push(duplicateStart);
    owner.graph.nodeMap[duplicateStart.id] = duplicateStart;
    owner.graph.edges[0].id = ' ';
    const activeId = owner.id;
    const documentId = window.ravenroot.openDocument({ name: 'Auteur workflow.graphml' });
    window.ravenroot.activateDocument(activeId);
    window.ravenroot._renderPersonalPalettesForTest({
      pending: false, error: '',
      palettes: [{ id: 'palette-machine-id', name: 'Palette Ω' }], templates: [],
    });
    window.ravenroot._showReadOnlyProgramReadinessForTest({ id: 'program-machine-id', name: 'Auteur program' }, {
      phase: 'FAILED', revision: 17, detail: 'Opaque provider diagnostic', updatedAt: '2026-10-17T00:00:00Z',
    });
    return { documentId };
  });

  const palette = page.locator('.node-palette[data-palette-id="palette-machine-id"]');
  await expect(palette).toBeVisible();
  await palette.locator('summary').click();
  for (const action of ['Save selected node', 'Rename', 'Delete']) {
    const button = palette.getByRole('button', { name: action, exact: true });
    await expect(button).toHaveText(action);
    await expect(button).toHaveAttribute('data-tooltip', `${action} in Palette Ω`);
  }
  const rails = page.locator('.rail-panel-btn');
  const railCount = await rails.count();
  expect(railCount).toBeGreaterThan(1);
  for (let index = 0; index < railCount; index += 1) {
    const rail = rails.nth(index);
    await expect(rail).toHaveAttribute('aria-label', /^Show .+ panel$/u);
    expect(await rail.getAttribute('data-tooltip')).toBe(await rail.getAttribute('aria-label'));
  }
  const timeline = page.locator('.program-timeline li');
  await expect(timeline).toContainText('FAILED · revision 17 · failed');
  await page.locator('#document-switcher').click();
  const close = page.locator(`[data-document-close="${identity.documentId}"]`);
  await expect(close).toHaveAttribute('aria-label', 'Close Auteur workflow.graphml');
  await expect(close).toHaveAttribute('title', 'Close Auteur workflow.graphml');
  await page.keyboard.press('Escape');

  await openLanguageDialog(page);
  await page.locator('#language-selection').selectOption('it');
  await expect(page.locator('html')).toHaveAttribute('lang', 'it');
  for (const [english, localized] of [['Save selected node', '⟦IT-SAVE⟧'], ['Rename', '⟦IT-RENAME⟧'], ['Delete', '⟦IT-DELETE⟧']]) {
    const button = palette.getByRole('button', { name: localized, exact: true });
    await expect(button).toHaveText(localized);
    await expect(button).toHaveAttribute('data-tooltip', `${localized} ⟦IT-IN⟧ Palette Ω`);
    await expect(palette).not.toContainText(`⟦IT⟧${english}⟦IT⟧`);
  }
  for (let index = 0; index < railCount; index += 1) {
    const rail = rails.nth(index);
    await expect(rail).toHaveAttribute('aria-label', /^⟦IT-SHOW⟧ /u);
    expect(await rail.getAttribute('data-tooltip')).toBe(await rail.getAttribute('aria-label'));
  }
  await expect(timeline).toHaveText('⟦IT-ENTRY⟧ FAILED|⟦IT-REV⟧ 17|⟦IT-FAILED⟧');
  await expect(timeline).toHaveAttribute('data-program-phase', 'FAILED');
  await expect(timeline).toHaveAttribute('data-state', 'failed');
  const programIdentity = await timeline.evaluate(element => ({
    programPhase: element.dataset.programPhase, programState: element.dataset.state,
  }));
  await expect(page.locator('.program-status')).toHaveText('Opaque provider diagnostic');
  await page.locator('.language-dialog button[type="submit"]').click();
  const programScreenshot = testInfo.outputPath('structural-program-live-switch.png');
  await page.screenshot({ path: programScreenshot, fullPage: true });
  await testInfo.attach('structural program live switch', { path: programScreenshot, contentType: 'image/png' });

  await page.locator('#document-switcher').click();
  await expect(close).toHaveAttribute('aria-label', '⟦IT-CLOSE⟧ Auteur workflow.graphml');
  await expect(close).toHaveAttribute('title', '⟦IT-CLOSE⟧ Auteur workflow.graphml');
  await page.keyboard.press('Escape');

  await openLanguageDialog(page, 'it');
  await page.locator('#language-selection').selectOption('en');
  await page.locator('.language-dialog button[type="submit"]').click();
  await page.evaluate(() => window.ravenroot._renderWorkflowValidationForTest());
  await expect(page.locator('.validation-list li')).toHaveText([
    'The graph must contain exactly one START node', 'Edge id must be a nonblank string',
  ]);
  await openLanguageDialog(page);
  await page.locator('#language-selection').selectOption('it');
  await expect(page.locator('.validation-list li')).toHaveText(['⟦IT-ONE-START⟧', '⟦IT-EDGE-ID⟧ ⟦IT-NONBLANK⟧']);
  await page.locator('.language-dialog button[type="submit"]').click();
  const validationScreenshot = testInfo.outputPath('structural-validation-live-switch.png');
  await page.screenshot({ path: validationScreenshot, fullPage: true });
  await testInfo.attach('structural validation live switch', { path: validationScreenshot, contentType: 'image/png' });
  expect(await page.evaluate(documentId => ({
    paletteId: document.querySelector('.node-palette')?.dataset.paletteId,
    railPanel: document.querySelector('.rail-panel-btn')?.dataset.railPanel,
    documentId: document.querySelector(`[data-document-close="${CSS.escape(documentId)}"]`)?.dataset.documentClose,
  }), identity.documentId)).toEqual({
    paletteId: 'palette-machine-id', railPanel: expect.any(String), documentId: identity.documentId,
  });
  expect(programIdentity).toEqual({ programPhase: 'FAILED', programState: 'failed' });
  await expect(palette.locator('summary')).toHaveText('Palette Ω');
});

test('applies exact-to-base-to-English fallback and rejects malformed configuration', async ({ page }) => {
  await configure(page, {
    schema: 'ravenroot.ui-locales/v1',
    defaultLocale: 'fr-CA',
    catalogs: [{
      schema: 'ravenroot.ui-catalog/v1', locale: 'fr',
      messages: { 'shell.menu-file.text': 'Fichier' },
    }],
  });
  await page.goto('/');
  await expect(page.locator('html')).toHaveAttribute('lang', 'fr-CA');
  await expect(page.locator('#menu-file')).toHaveText('Fichier');
  await expect(page.locator('#menu-view')).toHaveText('View');

  await page.unroute('**/ui-locales.json');
  await configure(page, { schema: 'wrong', defaultLocale: 'ar', catalogs: [arabic] });
  await page.evaluate(() => localStorage.clear());
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('lang', /^en(?:-|$)/u);
  await expect(page.locator('#menu-file')).toHaveText('File');
});

test('supports long RTL labels without losing the language control', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 900, height: 720 });
  await configure(page);
  await page.goto('/');
  await openLanguageDialog(page);
  await page.locator('#language-selection').selectOption('ar');
  await expect(page.locator('html')).toHaveAttribute('lang', 'ar');
  await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
  await expect(page.locator('#language-selection')).toBeVisible();
  await expect(page.locator('#language-selection')).toBeInViewport();
  await expect(page.locator('#menu-file')).toContainText('نص واجهة طويل للاختبار');
  expect(await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    viewportWidth: innerWidth,
  }))).toEqual({ scrollWidth: 900, viewportWidth: 900 });
  const dialogBox = await page.locator('.language-dialog').boundingBox();
  expect(dialogBox.x).toBeGreaterThanOrEqual(0);
  expect(dialogBox.x + dialogBox.width).toBeLessThanOrEqual(900);
  for (const control of await page.locator('.language-actions .btn').all()) {
    const box = await control.boundingBox();
    expect(box.width).toBeGreaterThan(0);
    expect(box.x).toBeGreaterThanOrEqual(dialogBox.x);
    expect(box.x + box.width).toBeLessThanOrEqual(dialogBox.x + dialogBox.width + 1);
  }
  const screenshot = testInfo.outputPath('rtl-long-labels.png');
  await page.screenshot({ path: screenshot });
  await testInfo.attach('RTL long labels', { path: screenshot, contentType: 'image/png' });
});

test('keeps independent embedded localization contexts isolated across live changes', async ({ page }) => {
  const catalogs = { it: italian, ar: arabic };
  await page.route('**/embed-localization-frame*', route => {
    const url = new URL(route.request().url());
    const locale = url.searchParams.get('locale');
    const encoded = encodeURIComponent(JSON.stringify(catalogs[locale]));
    route.fulfill({ contentType: 'text/html', body: `<!doctype html><html><body>
      <p id="label" data-i18n="shell.menu-file.text">File</p>
      <script type="module">
        import { createEmbedLocalization } from '/embed-viewer.js';
        const catalog = JSON.parse(decodeURIComponent(${JSON.stringify(encoded)}));
        window.localization = createEmbedLocalization({ locale: ${JSON.stringify(locale)}, catalog, document });
        document.body.dataset.ready = 'true';
      </script></body></html>` });
  });
  await page.setContent(`<iframe id="one" src="${UI_ORIGIN}/embed-localization-frame?locale=it"></iframe>
    <iframe id="two" src="${UI_ORIGIN}/embed-localization-frame?locale=ar"></iframe>`);
  const one = page.frameLocator('#one');
  const two = page.frameLocator('#two');
  await expect(one.locator('body')).toHaveAttribute('data-ready', 'true');
  await expect(two.locator('body')).toHaveAttribute('data-ready', 'true');
  await expect(one.locator('#label')).toHaveText('⟦IT⟧File⟦IT⟧');
  await expect(two.locator('#label')).toContainText('نص واجهة طويل للاختبار');
  const firstFrame = page.frames().find(frame => frame.url().includes('locale=it'));
  await firstFrame.evaluate(() => window.localization.setLocale('en'));
  await expect(one.locator('#label')).toHaveText('File');
  await expect(two.locator('#label')).toContainText('نص واجهة طويل للاختبار');
});

test('validates and applies locale metadata before the viewer module can fail', async ({ page }) => {
  const catalogs = {
    ar: { schema: 'ravenroot.ui-catalog/v1', locale: 'ar', direction: 'rtl',
      messages: { 'embed.state.error': 'تعذر تحميل العارض.' } },
    az: { schema: 'ravenroot.ui-catalog/v1', locale: 'az', direction: 'rtl',
      messages: { 'embed.state.error': 'Baxış yüklənmədi.', 'embed.state.expired': 'Baxış vaxtı bitdi.' } },
    invalid: { schema: 'ravenroot.ui-catalog/v1', locale: 'az', direction: 'rtl',
      messages: { 'embed.state.unknown': 'Etibarsız' } },
    unrelated: { schema: 'ravenroot.ui-catalog/v1', locale: 'it', direction: 'rtl',
      messages: { 'embed.state.error': 'Non deve apparire.' } },
  };
  const launch = ({ locale = 'az-IR', catalog = catalogs.az, channelId = 'channel',
    expiresAt = '2099-01-01T00:00:00.000Z' } = {}) => `<!doctype html><html lang="en"><body>
    <main id="ravenroot-embed-viewer"><p data-viewer-status data-i18n-error="Viewer unavailable."
      data-i18n-expired="Viewer session expired."></p></main>
    <script id="ravenroot-embed-bootstrap" type="application/json">${JSON.stringify({
      acknowledgementId: 'ack', challenge: 'challenge', channelId, exchangeId: 'exchange',
      expiresAt, grantRevision: '1', parentOrigin: UI_ORIGIN,
      theme: null, viewerOrigin: UI_ORIGIN, locale, catalog: JSON.stringify(catalog),
    })}</script><script src="/embed-bootstrap.js"></script></body></html>`;
  await page.route('**/embed-viewer.js', route => route.abort('failed'));
  await page.route('**/early-embed*', route => {
    const kind = new URL(route.request().url()).searchParams.get('case');
    const variants = {
      ar: { locale: 'ar', catalog: catalogs.ar }, az: {}, invalid: { catalog: catalogs.invalid },
      unrelated: { catalog: catalogs.unrelated },
      expired: { expiresAt: '2020-01-01T00:00:00.000Z', channelId: 'expired-channel' },
    };
    route.fulfill({ contentType: 'text/html', body: launch(variants[kind]) });
  });
  await page.route('**/malformed-embed', route => route.fulfill({ contentType: 'text/html', body: `<!doctype html><html><body>
    <main id="ravenroot-embed-viewer"><p data-viewer-status data-i18n-error="Viewer unavailable."></p></main>
    <script id="ravenroot-embed-bootstrap" type="application/json">{"invalid":true}</script>
    <script src="/embed-bootstrap.js"></script></body></html>` }));
  await page.goto('/');
  await page.evaluate(() => { window.__embedMessages = []; addEventListener('message', event => {
    window.__embedMessages.push(event.data);
  }); });
  await page.setContent(`<iframe id="ar" src="${UI_ORIGIN}/early-embed?case=ar&ticket=secret"></iframe>
    <iframe id="az" src="${UI_ORIGIN}/early-embed?case=az&ticket=secret"></iframe>
    <iframe id="invalid" src="${UI_ORIGIN}/early-embed?case=invalid"></iframe>
    <iframe id="unrelated" src="${UI_ORIGIN}/early-embed?case=unrelated"></iframe>
    <iframe id="expired" src="${UI_ORIGIN}/early-embed?case=expired&ticket=secret"></iframe>
    <iframe id="malformed" src="${UI_ORIGIN}/malformed-embed"></iframe>`);
  await expect(page.frameLocator('#ar').locator('[data-viewer-status]')).toHaveText('تعذر تحميل العارض.');
  await expect(page.frameLocator('#ar').locator('html')).toHaveAttribute('lang', 'ar');
  await expect(page.frameLocator('#ar').locator('html')).toHaveAttribute('dir', 'rtl');
  await expect(page.frameLocator('#az').locator('[data-viewer-status]')).toHaveText('Baxış yüklənmədi.');
  await expect(page.frameLocator('#az').locator('html')).toHaveAttribute('lang', 'az-IR');
  await expect(page.frameLocator('#az').locator('html')).toHaveAttribute('dir', 'rtl');
  await expect(page.frameLocator('#expired').locator('[data-viewer-status]')).toHaveText('Baxış vaxtı bitdi.');
  await expect(page.frameLocator('#expired').locator('html')).toHaveAttribute('lang', 'az-IR');
  await expect(page.frameLocator('#expired').locator('html')).toHaveAttribute('dir', 'rtl');
  for (const id of ['invalid', 'unrelated']) {
    await expect(page.frameLocator(`#${id}`).locator('[data-viewer-status]')).toHaveText('Viewer unavailable.');
    await expect(page.frameLocator(`#${id}`).locator('html')).toHaveAttribute('lang', 'en');
  }
  await expect(page.frameLocator('#malformed').locator('[data-viewer-status]')).toHaveText('Viewer unavailable.');
  for (const id of ['ar', 'az', 'expired']) {
    await expect.poll(() => page.frameLocator(`#${id}`).locator('html')
      .evaluate(() => location.search)).toBe('');
  }
  expect(await page.evaluate(() => window.__embedMessages
    .filter(message => message?.channelId === 'expired-channel').length)).toBe(0);
});

test('keeps server English when the pre-viewer localization module cannot load', async ({ page }) => {
  const arabicFailure = { schema: 'ravenroot.ui-catalog/v1', locale: 'ar', direction: 'rtl',
    messages: { 'embed.state.error': 'تعذر تحميل العارض.' } };
  await page.route('**/embed-localization.js', route => route.abort('failed'));
  await page.route('**/missing-localization', route => route.fulfill({ contentType: 'text/html',
    body: `<!doctype html><html lang="en"><body><main id="ravenroot-embed-viewer">
      <p data-viewer-status data-i18n-error="Viewer unavailable."></p></main>
      <script id="ravenroot-embed-bootstrap" type="application/json">${JSON.stringify({
        acknowledgementId: 'ack', challenge: 'challenge', channelId: 'channel', exchangeId: 'exchange',
        expiresAt: '2099-01-01T00:00:00.000Z', grantRevision: '1', parentOrigin: UI_ORIGIN,
        theme: null, viewerOrigin: UI_ORIGIN, locale: 'ar', catalog: JSON.stringify(arabicFailure),
      })}</script><script src="/embed-bootstrap.js"></script></body></html>`,
  }));
  await page.setContent(`<iframe id="missing" src="${UI_ORIGIN}/missing-localization"></iframe>`);
  await expect(page.frameLocator('#missing').locator('[data-viewer-status]')).toHaveText('Viewer unavailable.');
  await expect(page.frameLocator('#missing').locator('html')).toHaveAttribute('lang', 'en');
  await expect(page.frameLocator('#missing').locator('html')).not.toHaveAttribute('dir', 'rtl');
});

// These flows exercise the product controllers through the shipped page, including messages
// created before a change. Package names and authored graph values remain verbatim.
test('repaints extension framing, deployment statuses, runner controls, form errors', async ({ page }, testInfo) => {
  const errors = []; page.on('pageerror', error => errors.push(error.stack));
  await configure(page);
  await page.goto('/');
  await page.locator('#btn-new').click();
  await page.locator('#drawing-model-package-input').setInputFiles(
    new URL('../public/examples/frontend-plugins/textbook-automata/', import.meta.url).pathname);
  await expect(page.locator('#drawing-model-select')).toContainText('Textbook DFA/NFA');
  await expect(page.locator('#activity-log')).toContainText('Installed frontend package');
  await page.locator('#btn-modify').click();
  await page.locator('#btn-add-node').click();
  const form = page.locator('#node-editor');
  const duplicate = await page.evaluate(() => window.cy.nodes()[0].id());
  await form.locator('[name="id"]').fill(duplicate);
  await form.locator('button[type="submit"]').click();
  await expect(form.locator('.validation-list')).toContainText('already exists');
  await openLanguageDialog(page);
  expect(await form.evaluate(node => ({ error: typeof node._localizedError, render: typeof node._localeRender }))).toEqual({ error: 'function', render: 'function' });
  await page.locator('#language-selection').selectOption('it');
  expect(errors).toEqual([]);
  await expect(form.locator('.validation-list')).toContainText('⟦IT⟧');
  await expect(page.locator('#activity-log')).toContainText('⟦IT⟧Installed frontend package');
  await expect(page.locator('#drawing-model-select option').first()).toContainText('⟦IT⟧');
  await page.locator('.language-dialog button[type="submit"]').click();
  await page.locator('.drawing-model-actions').evaluate(node => { node.open = true; });
  await page.locator('#drawing-model-manage').click();
  await expect(page.locator('#drawing-model-package-list')).toContainText('Textbook automata');
  await expect(page.locator('#drawing-model-package-list button').first()).toContainText('⟦IT⟧');
  await page.locator('[data-drawing-model-package-close]').click();
  await page.locator('#menu-run').click();
  await page.getByRole('menuitem', { name: /Deployments/ }).click();
  await page.locator('#deployment-id-input').fill('');
  await page.locator('#deployment-register').click();
  await expect(page.locator('#deployment-id-error')).toContainText('⟦IT⟧');
  await page.locator('#deployment-id-input').fill('user-owned-id');
  await page.locator('#deployment-register').click();
  await expect(page.locator('#deployment-status')).toContainText('⟦IT⟧');
  // Drive the same select change handler while the deployment dialog remains mounted.
  await page.locator('#language-selection').evaluate(node => { node.value = 'ar'; node.dispatchEvent(new Event('change', { bubbles: true })); });
  await expect(page.locator('#deployment-status')).toContainText('⟦نص واجهة طويل للاختبار⟧');
  await expect(page.locator('#deployment-id-input')).toHaveValue('user-owned-id');
  await page.locator('#deployments-dialog').evaluate(node => node.close());
  await page.locator('#menu-run').click();
  await page.getByRole('menuitem', { name: /Agents and runners/ }).click();
  await expect(page.locator('#runner-dialog h2')).toContainText('⟦نص واجهة طويل للاختبار⟧');
  await page.locator('#language-selection').evaluate(node => { node.value = 'it'; node.dispatchEvent(new Event('change', { bubbles: true })); });
  await expect(page.locator('#runner-dialog h2')).toContainText('⟦IT⟧');
  await page.locator('#runner-dialog').evaluate(node => node.close());
  await page.screenshot({ path: testInfo.outputPath('localized-controller-flows.png'), fullPage: true });
});

test('localizes native lifecycle prompts after a live change without altering target identity', async ({ page }) => {
  await configure(page);
  await page.route('**/v1/deployments**', route => route.fulfill({
    contentType: 'application/json', body: JSON.stringify({ deployments: [{
      deploymentId: 'author-owned-target', tenantId: 'tenant-a', state: 'READY', sourceCount: 0,
      graphVersion: 'graph-v1', scope: 'LOCAL_PROCESS', diagnostic: null, continuity: 'DURABLE',
      deploymentGeneration: 1, deploymentRevision: 1, desiredState: 'RUNNING', observedState: 'READY', recoveryFailure: null,
      lifecycleCapabilities: { contractVersion: 1, scope: 'DEPLOYMENT', commands: [{
        command: 'STOP', available: true, reasonRequired: true, unavailableReason: null,
      }] },
    }] }),
  }));
  await page.goto('/');
  await openLanguageDialog(page);
  await page.locator('#language-selection').selectOption('it');
  await page.locator('.language-dialog button[type="submit"]').click();
  await page.locator('#menu-run').click();
  await page.getByRole('menuitem', { name: /Deployments/ }).click();
  const prompts = [];
  page.once('dialog', async dialog => { prompts.push({ type: dialog.type(), message: dialog.message() }); await dialog.accept(''); });
  await page.locator('[data-deployment-action="stop"]').click();
  expect(prompts).toHaveLength(1);
  expect(prompts[0].type).toBe('prompt');
  expect(prompts[0].message).toContain('⟦IT⟧');
  expect(prompts[0].message).toContain('author-owned-target');
  await expect(page.locator('#deployment-status')).toContainText('⟦IT⟧');
});


test('repaints a retained runner validation error after a live catalog change', async ({ page }) => {
  await configure(page);
  await page.route('**/v1/runner-plane/catalog', route => route.fulfill({ contentType: 'application/json', body: '{"items":null}' }));
  await page.goto('/');
  await openLanguageDialog(page);
  await page.locator('.language-dialog button[type="submit"]').click();
  await page.locator('#menu-run').click();
  await page.getByRole('menuitem', { name: /Agents and runners/ }).click();
  await expect(page.locator('#runner-dialog')).toContainText('Unsupported runner catalogue');
  await page.locator('#language-selection').evaluate(node => { node.value = 'it'; node.dispatchEvent(new Event('change', { bubbles: true })); });
  await expect(page.locator('#runner-dialog')).toContainText('⟦IT⟧Unsupported runner catalogue⟦IT⟧');
  await page.locator('#language-selection').evaluate(node => { node.value = 'ar'; node.dispatchEvent(new Event('change', { bubbles: true })); });
  await expect(page.locator('#runner-dialog')).toContainText('⟦نص واجهة طويل للاختبار⟧Unsupported runner catalogue');
});

test('repaints a startup URL rejection after a live catalog change', async ({ page }) => {
  await configure(page);
  await page.goto('/?file=' + encodeURIComponent('file:///author-owned.graphml'));
  await expect(page.locator('#startup-import-status')).toHaveText('The graph URL must use HTTP or HTTPS.');
  await openLanguageDialog(page);
  await page.locator('#language-selection').selectOption('it');
  await expect(page.locator('#startup-import-status')).toHaveText('⟦IT⟧The graph URL must use HTTP or HTTPS.⟦IT⟧');
  await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'failed');
});


test('imports and persists the actual complete CLI catalog through the language dialog', async ({ page }) => {
  const output = execFileSync(process.execPath,
    [new URL('../scripts/export-ui-catalog.mjs', import.meta.url).pathname, 'it']);
  await configure(page, { schema: 'ravenroot.ui-locales/v1', defaultLocale: 'en', catalogs: [] });
  await page.goto('/');
  await openLanguageDialog(page);
  await page.locator('.language-dialog input[type="file"]').setInputFiles({
    name: 'it.json', mimeType: 'application/json', buffer: output,
  });
  await expect(page.locator('html')).toHaveAttribute('lang', 'it');
  await expect(page.locator('.language-current-source')).toContainText('Imported');
  await expect(page.locator('.language-status')).toContainText('it');
  await expect(page.locator('#menu-file')).toHaveText('File');
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('lang', 'it');
  await page.locator('#menu-view').click();
  await page.getByRole('menuitem', { name: 'Language…' }).click();
  await expect(page.locator('.language-current-source')).toContainText('Imported');
  await expect(page.locator('#language-selection')).toHaveValue('it');
});

test('preserves declared versus derived direction through IndexedDB catalog round trips', async ({ page }) => {
  const stored = () => page.evaluate(() => new Promise((resolve, reject) => {
    const request = indexedDB.open('ravenroot-ui-catalogs-v1', 1);
    request.onerror = () => reject(request.error);
    request.onsuccess = () => {
      const database = request.result;
      const read = database.transaction('catalogs').objectStore('catalogs').get('az');
      read.onerror = () => reject(read.error);
      read.onsuccess = () => { resolve(read.result); database.close(); };
    };
  }));
  const upload = async catalog => {
    await openLanguageDialog(page);
    await page.locator('.language-dialog input[type="file"]').setInputFiles({
      name: 'az.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(catalog)),
    });
    // The import chooses its base locale after the IndexedDB write completes.
    await expect(page.locator('.language-status')).toHaveText('Imported az.');
  };
  await configure(page, { schema: 'ravenroot.ui-locales/v1', defaultLocale: 'en', catalogs: [] });
  await page.goto('/');
  const explicit = { schema: 'ravenroot.ui-catalog/v1', locale: 'az', direction: 'rtl',
    messages: { 'shell.menu-file.text': 'Fayl' } };
  await upload(explicit);
  await page.evaluate(() => localStorage.setItem('ravenroot.ui.locale.v1', 'az-IR'));
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('lang', 'az-IR');
  await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
  await expect(page.locator('#menu-file')).toHaveText('Fayl');
  expect(await stored()).toMatchObject({ locale: 'az', direction: 'rtl' });

  await openLanguageDialog(page);
  await page.locator('.language-dialog .danger').click();
  await page.locator('.language-dialog button[type="submit"]').click();
  const omitted = { schema: 'ravenroot.ui-catalog/v1', locale: 'az',
    messages: { 'shell.menu-file.text': 'Fayl' } };
  await upload(omitted);
  await page.evaluate(() => localStorage.setItem('ravenroot.ui.locale.v1', 'az-Arab-IR'));
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('lang', 'az-Arab-IR');
  await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
  expect(await stored()).not.toHaveProperty('direction');
});

test('represents and removes a regional preference backed by an imported base catalog', async ({ page }) => {
  const output = execFileSync(process.execPath,
    [new URL('../scripts/export-ui-catalog.mjs', import.meta.url).pathname, 'fr']);
  await configure(page, { schema: 'ravenroot.ui-locales/v1', defaultLocale: 'en', catalogs: [] });
  await page.goto('/');
  await openLanguageDialog(page);
  await page.locator('.language-dialog input[type="file"]').setInputFiles({
    name: 'fr.json', mimeType: 'application/json', buffer: output,
  });
  await expect(page.locator('html')).toHaveAttribute('lang', 'fr');
  await page.evaluate(() => localStorage.setItem('ravenroot.ui.locale.v1', 'fr-CA'));
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('lang', 'fr-CA');
  await openLanguageDialog(page);
  await expect(page.locator('#language-selection')).toHaveValue('fr-CA');
  await expect(page.locator('.language-current-source')).toContainText('Imported');
  const remove = page.locator('.language-dialog .danger');
  await expect(remove).toBeEnabled();
  await remove.click();
  await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  await expect(page.locator('#language-selection')).toHaveValue('');
  await expect(page.locator('.language-current-source')).toContainText('Built in');
  await expect.poll(() => page.evaluate(() => localStorage.getItem('ravenroot.ui.locale.v1')))
    .toBeNull();
});

test('restores a configured base catalog after removing its imported override', async ({ page }) => {
  const output = execFileSync(process.execPath,
    [new URL('../scripts/export-ui-catalog.mjs', import.meta.url).pathname, 'fr']);
  await configure(page, { schema: 'ravenroot.ui-locales/v1', defaultLocale: 'en', catalogs: [{
    schema: 'ravenroot.ui-catalog/v1', locale: 'fr',
    messages: { 'commands.file.open.label': 'Ouvrir configuré…' },
  }] });
  await page.goto('/');
  await openLanguageDialog(page);
  await page.locator('.language-dialog input[type="file"]').setInputFiles({
    name: 'fr.json', mimeType: 'application/json', buffer: output,
  });
  await expect(page.locator('.language-status')).toHaveText('Imported fr.');
  await page.evaluate(() => localStorage.setItem('ravenroot.ui.locale.v1', 'fr-CA'));
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('lang', 'fr-CA');
  await openLanguageDialog(page);
  await expect(page.locator('#language-selection')).toHaveValue('fr-CA');
  await expect(page.locator('.language-current-source')).toContainText('Imported');
  await page.locator('.language-dialog .danger').click();
  await expect(page.locator('html')).toHaveAttribute('lang', 'fr-CA');
  await expect(page.locator('#language-selection')).toHaveValue('fr-CA');
  await expect(page.locator('.language-current-source')).toContainText('Configured');
  await page.locator('.language-dialog button[type="submit"]').click();
  await page.locator('#menu-file').click();
  await expect(page.getByRole('menuitem', { name: 'Ouvrir configuré…' })).toBeVisible();
  await expect.poll(() => page.evaluate(() => localStorage.getItem('ravenroot.ui.locale.v1')))
    .toBe('fr-CA');
});
