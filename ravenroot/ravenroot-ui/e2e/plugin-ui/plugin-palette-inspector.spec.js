import { expect, test } from '@playwright/test';

// PLAT-12 and QA-11 require proof of the path from catalog response to a visible palette entry and
// its Inspector panel for a PLUGIN's own
// node types. The bundle's behaviors already reach `/v1/node-types`;
// the architectural argument that the palette needed no UI change because it is server-driven is
// correct but is not this test. This file is.
//
// Run via `scripts/verify-plugin-palette-ui.sh`, never directly with `npx playwright test`: that
// script builds `ravenroot.jar` and a real `ravenroot-mail` bundle, installs it, and invokes this
// spec TWICE against `playwright.plugin-ui.config.js` (which starts the real JVM as its `webServer`)
// — once with the bundle enabled (`RR_EXPECT_MAIL_ENABLED=1`) and once installed but NOT enabled
// (`RR_EXPECT_MAIL_ENABLED=0`). Both runs execute the SAME assertions below; which branch fires
// depends only on what the live server's own `/v1/node-types` says, fetched fresh in every run
// rather than pinned to a captured fixture — so this test cannot go stale against a descriptor that
// changes shape later, and it cannot pass by accident: the disabled run is the red half, asserting
// ABSENCE, not merely "the enabled run was not exercised this time".
//
// `RR_EXPECT_MAIL_ENABLED` also drives an assertion, not just a comment: if the live catalog and the
// expected mode disagree, the test fails loudly rather than silently reporting on whichever mode
// happened to be running — that mismatch is exactly the kind of thing a red-half-only-in-spirit test
// would hide.

const expectMailEnabled = process.env.RR_EXPECT_MAIL_ENABLED === '1';
const MAIL_BEHAVIORS = ['mail.send', 'mail.imap.query', 'mail.imap.consume', 'mail.imap.move', 'mail.imap.delete'];

test('a real installed bundle changes the palette and the Inspector only when RAVENROOT_ENABLED_PLUGINS names it', async ({ page, request, baseURL }) => {
  // Ground truth: the same endpoint the palette itself fetches, read independently here so the
  // assertions below are checked against what the live server actually said, not against an
  // assumption of what it should say.
  const catalogResponse = await request.get(`${baseURL}/v1/node-types`);
  expect(catalogResponse.ok()).toBe(true);
  const catalog = await catalogResponse.json();
  expect(Array.isArray(catalog)).toBe(true);
  expect(catalog.length).toBeGreaterThan(0); // never vacuously true: the built-in catalog is never empty

  const catalogBehaviors = new Set(catalog.map(entry => entry.behavior));
  const mailPresentInCatalog = MAIL_BEHAVIORS.every(behavior => catalogBehaviors.has(behavior));

  // The mode this run was asked to prove must match what the server actually did. A mismatch here
  // means the harness itself is wired wrong (wrong RAVENROOT_ENABLED_PLUGINS, wrong install dir) —
  // failing on that distinctly is more useful than the palette assertions failing for the same
  // underlying reason with a less specific message.
  expect(mailPresentInCatalog, expectMailEnabled
    ? 'expected the mail bundle to be enabled and its behaviors present in /v1/node-types'
    : 'expected the mail bundle to be installed but NOT enabled, so its behaviors must be absent from /v1/node-types')
    .toBe(expectMailEnabled);

  await page.goto('/');

  // Same-origin boot: `#service-url` is left empty and the client resolves that to its own origin,
  // exactly like `node-catalog-loopback.spec.js`'s boot-path cases. Nothing is typed or clicked to
  // make the catalogue request happen.
  const catalogContainer = page.locator('#node-catalog');
  await expect(catalogContainer.locator('.catalog-empty')).toHaveCount(0);
  await expect(page.locator('#service-url')).toHaveValue('');

  if (!expectMailEnabled) {
    // RED HALF: the bundle is installed on disk (proven by the catalog assertion above having a
    // real installed bundle to be absent) but not named in RAVENROOT_ENABLED_PLUGINS. Presence on
    // disk must never be enough on its own — the palette must show neither behavior.
    for (const behavior of MAIL_BEHAVIORS) {
      await expect(catalogContainer.locator(`[data-catalog-add="${behavior}"]`)).toHaveCount(0);
    }
    // The palette is not broken wholesale: built-in node types are still there.
    await expect(catalogContainer.locator('.catalog-item')).not.toHaveCount(0);
    return;
  }

  // GREEN HALF: all of the bundle's behaviors are visible catalog entries, driven entirely by the
  // server response — no UI code names "mail" anywhere.
  for (const behavior of MAIL_BEHAVIORS) {
    const descriptor = catalog.find(entry => entry.behavior === behavior);
    const item = catalogContainer.locator(`[data-catalog-add="${behavior}"]`);
    await expect(item).toBeVisible();
    await expect(item).toContainText(descriptor.displayName);
    await expect(item).toContainText(descriptor.category);
  }

  // Inspector: click the richer of the two (mail.send, whose descriptor declares more than twenty
  // properties) and check the Inspector's property fields are exactly what the descriptor names —
  // not a hardcoded guess at what a mail node "should" have.
  const sendDescriptor = catalog.find(entry => entry.behavior === 'mail.send');
  expect(sendDescriptor.properties.length, 'this assertion is only meaningful if the real descriptor actually declares properties')
    .toBeGreaterThan(0);

  await catalogContainer.locator('[data-catalog-add="mail.send"]').click();

  await expect(page.locator('#info-title')).toContainText(sendDescriptor.displayName);
  const form = page.locator('#node-editor');
  await expect(form).toBeVisible();
  await expect(page.locator('#catalog-description')).toContainText(sendDescriptor.description);

  for (const property of sendDescriptor.properties) {
    await expect(page.locator(`#catalog-properties [data-catalog-property="${property.name}"]`), `Inspector must expose a control for descriptor property "${property.name}"`)
      .toHaveCount(1);
  }
  // No control outside the descriptor's own property list — the field count must match exactly, or
  // the Inspector would be proven to render something other than this descriptor.
  await expect(page.locator('#catalog-properties [data-catalog-property]')).toHaveCount(sendDescriptor.properties.length);

  // The consumer's conditional Inspector field is descriptor-driven as well: previewChars is inert
  // and hidden in metadata mode, then appears only when the descriptor's contentMode condition holds.
  const consumeDescriptor = catalog.find(entry => entry.behavior === 'mail.imap.consume');
  await catalogContainer.locator('[data-catalog-add="mail.imap.consume"]').click();
  await expect(page.locator('#info-title')).toContainText(consumeDescriptor.displayName);
  const previewChars = page.locator('[data-catalog-property="previewChars"]');
  const previewWrapper = previewChars.locator('xpath=ancestor::div[contains(@class,"catalog-property")]');
  await expect(previewWrapper).toBeHidden();
  await page.locator('[data-catalog-property="contentMode"]').selectOption('preview');
  await expect(previewWrapper).toBeVisible();
  await page.locator('[data-catalog-property="contentMode"]').selectOption('metadata');
  await expect(previewWrapper).toBeHidden();
});
