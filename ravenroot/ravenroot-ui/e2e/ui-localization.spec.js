import { expect, test } from '@playwright/test';

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
const arabic = pseudo('ar', 'rtl', '⟦نص واجهة طويل للاختبار⟧');

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
  const marker = locale === 'en' ? '' : locale === 'it' ? '⟦IT⟧' : '⟦نص واجهة طويل للاختبار⟧';
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
    nodes: window.cy.nodes().length,
    selected: window.cy.nodes(':selected').map(node => node.id()),
  }));
}

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
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  expect(await page.locator('#btn-program-approve').evaluate(button => {
    const bounds = button.getBoundingClientRect();
    return bounds.left >= 0 && bounds.right <= innerWidth && bounds.width >= 160;
  })).toBe(true);
  await expect(page.locator('#node-editor input[name="name"]')).toHaveValue('Unsubmitted localized draft');
  await expect(page.locator('#dirty-state')).toContainText('unsaved changes');
  await expect.poll(() => page.evaluate(() => ({
    documentId: window.ravenroot.activeDocument().id,
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

test('localizes module-load failure early and keeps malformed bootstrap safe', async ({ page }) => {
  const early = {
    schema: 'ravenroot.ui-catalog/v1', locale: 'it',
    messages: { 'embed.state.error': 'Errore iniziale localizzato.' },
  };
  await page.route('**/embed-viewer.js', route => route.abort('failed'));
  await page.route('**/early-embed', route => route.fulfill({ contentType: 'text/html', body: `<!doctype html><html><body>
    <main id="ravenroot-embed-viewer"><p data-viewer-status data-i18n-error="Viewer unavailable."></p></main>
    <script id="ravenroot-embed-bootstrap" type="application/json">${JSON.stringify({
      acknowledgementId: 'ack', challenge: 'challenge', channelId: 'channel', exchangeId: 'exchange',
      expiresAt: '2099-01-01T00:00:00.000Z', grantRevision: '1', parentOrigin: UI_ORIGIN,
      theme: null, viewerOrigin: UI_ORIGIN, locale: 'it', catalog: JSON.stringify(early),
    })}</script><script src="/embed-bootstrap.js"></script></body></html>` }));
  await page.route('**/malformed-embed', route => route.fulfill({ contentType: 'text/html', body: `<!doctype html><html><body>
    <main id="ravenroot-embed-viewer"><p data-viewer-status data-i18n-error="Viewer unavailable."></p></main>
    <script id="ravenroot-embed-bootstrap" type="application/json">{"invalid":true}</script>
    <script src="/embed-bootstrap.js"></script></body></html>` }));
  await page.setContent(`<iframe id="early" src="${UI_ORIGIN}/early-embed"></iframe>
    <iframe id="malformed" src="${UI_ORIGIN}/malformed-embed"></iframe>`);
  await expect(page.frameLocator('#early').locator('[data-viewer-status]')).toHaveText('Errore iniziale localizzato.');
  await expect(page.frameLocator('#malformed').locator('[data-viewer-status]')).toHaveText('Viewer unavailable.');
});
