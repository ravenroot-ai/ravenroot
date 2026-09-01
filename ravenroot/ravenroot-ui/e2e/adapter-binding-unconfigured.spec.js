import { expect, test } from '@playwright/test';

// Browser coverage for UI-07: the SERVER accepts a node whose adapter-id property is absent or
// empty — the graph builds, and the node refuses only when execution actually reaches it. The EDITOR
// must therefore omit the native HTML `required` attribute for those properties; otherwise, in a
// form without `novalidate`, the browser would block a graph that the server accepts. These tests
// drive the real browser against the real built editor and assert what the user can actually do.
//
// `adapterBinding` is the catalog flag that controls this behavior (server:
// NodePropertyDescriptor#adapterBinding, exposed through `/v1/node-types`). The catalog deliberately
// includes a property called `secretId` on a behavior called `mystery-adapter`, so a behavior-name
// allow-list would fail this suite and reintroduce the editor/server divergence covered by SEC-09.
//
// The adapter-bound fixture is a bundle-supplied `acme-agent`, matching the plugin-bundle route while
// the core supplies `llm-prompt`. The rule does not depend on either name: the adjacent
// `mystery-adapter` case ensures that the editor reads the flag rather than a behavior name. This is
// also the browser proof cited by `src/generative-capability.js`.

const CATALOG = JSON.stringify([
  // Control fixture: an ordinary required property with no adapter binding remains blocked
  // client-side by the browser's native validation.
  {
    behavior: 'template', displayName: 'Template', category: 'core', description: 'Renders a template',
    visualType: 'flow', agentic: false, capabilities: [],
    properties: [
      { name: 'templateBody', displayName: 'Template body', type: 'STRING', required: true,
        description: 'The template source', defaultValue: '', allowedValues: [], adapterBinding: false },
    ],
  },
  // The real-world scenario, represented as a bundle-supplied node.
  {
    behavior: 'acme-agent', displayName: 'Acme agent', category: 'ai', description: 'Runs an agent',
    visualType: 'ai', agentic: true, capabilities: ['ai'],
    properties: [
      { name: 'runtime', displayName: 'Agent runtime', type: 'STRING', required: true,
        description: 'The deployment-configured agent runtime', defaultValue: '', allowedValues: [],
        adapterBinding: true },
    ],
  },
  // A behavior name the UI cannot plausibly special-case by accident proves that the editor reads
  // the catalog flag rather than a local behavior-name allow-list.
  {
    behavior: 'mystery-adapter', displayName: 'Mystery adapter node', category: 'core',
    description: 'A synthetic behavior with an adapter-bound property under an arbitrary name',
    visualType: 'actor', agentic: false, capabilities: [],
    properties: [
      { name: 'secretId', displayName: 'Secret id', type: 'STRING', required: true,
        description: 'Names a deployment-configured secret', defaultValue: '', allowedValues: [],
        adapterBinding: true },
    ],
  },
]);

async function serveCatalogAtBootOrigin(page) {
  await page.route('**/v1/node-types', route => route.fulfill({
    status: 200,
    contentType: 'application/json; charset=utf-8',
    body: CATALOG,
  }));
  await page.route('**/v1/events', route => route.fulfill({ status: 204, body: '' }));
}

async function startEditableWorkflowWithCatalog(page) {
  await serveCatalogAtBootOrigin(page);
  await page.goto('/');
  await page.locator('#btn-modify').click();
  await expect(page.locator('#btn-modify')).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('[data-catalog-add="acme-agent"]')).toBeVisible();
}

function nodeIds(page) {
  return page.evaluate(() => window.cy.$('node').map(element => element.id()).sort());
}

// ── Regression control: unrelated to adapterBinding, must behave identically before and after ──────

test('a node type with an ordinary required property (no adapter binding) is still blocked client-side, exactly as before', async ({ page }) => {
  await startEditableWorkflowWithCatalog(page);
  const before = await nodeIds(page);

  await page.locator('[data-catalog-add="template"]').click();
  const field = page.locator('[data-catalog-property="templateBody"]');
  await expect(field).toBeVisible();
  await expect(field).toHaveAttribute('required', '');
  await expect(page.locator('.catalog-property label')).toContainText('*');

  // Leave templateBody blank and try to save. Native HTML validation must refuse the submit before
  // the JS submit handler ever runs, so the node model must be completely unchanged.
  await page.locator('#node-editor button[type="submit"]').click();
  await page.waitForTimeout(200);
  await expect.poll(() => nodeIds(page)).toEqual(before);
  await expect(field).toHaveJSProperty('validity.valid', false);
});

// ── The defect: an adapter-bound property must not block saving, under any behavior name ───────────

for (const [behavior, propertyName] of [['acme-agent', 'runtime'], ['mystery-adapter', 'secretId']]) {
  test(`a "${behavior}" node with an empty adapter-bound "${propertyName}" property is saveable from the editor`, async ({ page }) => {
    await startEditableWorkflowWithCatalog(page);
    const before = await nodeIds(page);

    await page.locator(`[data-catalog-add="${behavior}"]`).click();
    const field = page.locator(`[data-catalog-property="${propertyName}"]`);
    await expect(field).toBeVisible();

    // The property is still schema-required (adapterBinding implies required), so the visual "*"
    // marker must survive — the author should still be prompted to fill it in.
    await expect(page.locator('.catalog-property label')).toContainText('*');
    // But the browser must not enforce it natively: `adapterBinding` means "unconfigured, not
    // invalid" — the server admits the empty value and only refuses at execution.
    const hasNativeRequired = await field.evaluate(el => el.hasAttribute('required'));
    expect(hasNativeRequired).toBe(false);

    // Leave the field blank and save: drag the node
    // onto the canvas and trying to save without configuring the adapter.
    await page.locator('#node-editor button[type="submit"]').click();

    await expect.poll(() => nodeIds(page)).toHaveLength(before.length + 1);

    // Distinguish "not configured" from "required and missing": some visible signal must mark the
    // node/field as unconfigured rather than as a validation error, and it must not be the native
    // :invalid state (which is reserved for genuinely required-and-missing fields, see the
    // regression test above).
    await expect(field).toHaveJSProperty('validity.valid', true);
    const container = page.locator('.catalog-property').filter({ has: field });
    await expect(container).toHaveClass(/unconfigured/);
    await expect(container).toContainText(/unconfigured|not configured|will refuse/i);
  });
}
