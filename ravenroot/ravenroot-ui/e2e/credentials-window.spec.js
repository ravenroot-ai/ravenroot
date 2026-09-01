import { expect, test } from '@playwright/test';

// The Credentials window.
//
// Three things can only be checked here, in a real browser against the real page.
//
// The first is that the window is REACHABLE — through the menu, as a modal, with the rest of the
// application behind it. The second is that a value typed into it LEAVES NO TRACE: the field is
// cleared, the list shows a label and a reference and nothing else, and the string is nowhere in the
// document afterwards. Neither is provable from a unit test, because both are statements about the
// whole page rather than about one module's output. The third is the node inspector's
// SECRET_REFERENCE control, which only becomes a real `<select>` of real options once a catalog, a
// document and a listing exist together.
//
// The routes are stubbed with `page.route`, per test, rather than taught to
// `e2e/ui-fixture-server.mjs`: that fixture 404s every `/v1` route, and a shared stub answering this
// one for every spec would quietly change what the other specs are testing.

const CREDENTIALS = '**/v1/credentials';
const API_KEY = 'sk-live-9f3c0b7e-typed-once-and-never-seen-again';
const REFERENCE = 'rrc_0123456789abcdef0123456789abcdef';

const property = (name, type, options = {}) => ({
  name, displayName: options.displayName || name, type, required: false,
  description: options.description || '', defaultValue: '',
  allowedValues: [], adapterBinding: false, visibleWhen: null, requiredWhen: null,
});

const CATALOG = [{
  behavior: 'secret.consumer', displayName: 'Secret consumer', category: 'Tests',
  visualType: 'actor', agentic: false, capabilities: [],
  properties: [
    property('endpoint', 'STRING', { displayName: 'Endpoint' }),
    property('credentialRef', 'SECRET_REFERENCE', { displayName: 'Credential' }),
  ],
}];

/**
 * A service that really stores: the POST appends, and the following GET returns what was appended.
 * A stub that answered a fixed list would let a broken save look exactly like a working one.
 */
async function withCredentialService(page, { seed = [] } = {}) {
  const held = [...seed];
  const posted = [];
  await page.route(CREDENTIALS, async route => {
    const request = route.request();
    if (request.method() === 'POST') {
      const body = JSON.parse(request.postData() || '{}');
      posted.push(body);
      const created = {
        reference: `${REFERENCE}${held.length}`,
        label: body.label,
        scheme: body.scheme,
        username: body.username || '',
        createdAt: '2026-08-28T09:15:00Z',
      };
      held.push(created);
      await route.fulfill({
        status: 201, contentType: 'application/json; charset=utf-8', body: JSON.stringify(created),
      });
      return;
    }
    await route.fulfill({
      status: 200, contentType: 'application/json; charset=utf-8',
      body: JSON.stringify({ credentials: held }),
    });
  });
  return { posted, held };
}

const openCredentials = async page => {
  await page.locator('#menu-run').click();
  await page.getByRole('menuitem', { name: 'Credentials…' }).click();
  await expect(page.locator('#credentials-dialog')).toHaveAttribute('open', '');
};

// An implausible separator, so a value split across the markup and the live control values cannot be
// joined into an accidental match. WRITTEN AS AN ESCAPE, NEVER AS A LITERAL NUL BYTE: two literal
// ones here made git classify this file as binary, so it would never have shown a diff -- on the one
// spec in the suite whose whole job is to prove a credential does not leak. The runtime value is
// identical; the escaped form keeps the file text-readable and diffable.
const SEPARATOR = '\u0000';

/**
 * Everything a person could read or copy out of the page: rendered text, the full markup, and every
 * form control's LIVE value — which `page.content()` alone does not show, because a value set by
 * script never appears as an attribute.
 */
async function everythingReadable(page) {
  // The separator is PASSED IN, not closed over: the arrow below is serialised and evaluated inside
  // the browser, where this module's bindings do not exist. Closing over the separator instead
  // produces a ReferenceError in the page -- caught, because
  // Playwright rejects on it and the spec goes red. Worth the comment anyway: the failure names the
  // separator, not the closure, so the next person to touch this reads it as a bad separator value.
  const live = await page.evaluate(separator =>
    [...document.querySelectorAll('input, textarea, select')]
      .map(control => control.value).join(separator), SEPARATOR);
  return `${await page.content()}${SEPARATOR}${live}`;
}

test.describe('the credentials window', () => {
  test('is reached from the Run menu and takes the screen as a modal', async ({ page }) => {
    await withCredentialService(page);
    await page.goto('/');

    await expect(page.locator('#credentials-dialog')).not.toHaveAttribute('open', '');
    await openCredentials(page);

    // A real modal: the backdrop makes what is behind it inert, which is what separates this window
    // from the command bar rather than merely placing it elsewhere.
    expect(await page.evaluate(() =>
      document.getElementById('credentials-dialog').matches(':modal'))).toBe(true);
    await expect(page.locator('#credential-value')).toHaveAttribute('type', 'password');

    await page.locator('#credential-close').click();
    await expect(page.locator('#credentials-dialog')).not.toHaveAttribute('open', '');
  });

  test('stores a typed API key, shows the label it was given, and leaves the value nowhere in the page',
    async ({ page }) => {
      const service = await withCredentialService(page);
      await page.goto('/');
      await openCredentials(page);

      await page.locator('#credential-label').fill('Weather API');
      await page.locator('#credential-scheme').selectOption('api-key');
      // The one place in this suite where a credential value is typed, which is the criterion.
      await page.locator('#credential-value').fill(API_KEY);
      await page.locator('#credential-save').click();

      // What the author asked for is now listed, by the name they chose.
      const item = page.locator('#credential-list .credential-item');
      await expect(item).toHaveCount(1);
      await expect(item.locator('b')).toHaveText('Weather API');
      await expect(item.locator('.credential-reference')).toHaveText(`${REFERENCE}0`);
      await expect(page.locator('#credential-status')).toContainText('Stored “Weather API”');

      // It really was sent, once, in the body — and with no reference proposed.
      expect(service.posted).toHaveLength(1);
      expect(service.posted[0]).toEqual({ label: 'Weather API', scheme: 'api-key', value: API_KEY });

      // AND IT IS GONE. The field first, then the whole document — text, markup and every live
      // control value.
      await expect(page.locator('#credential-value')).toHaveValue('');
      expect(await everythingReadable(page)).not.toContain(API_KEY);
      // Not masked either: there is nothing here to mask, so nothing pretends there is.
      await expect(page.locator('#credential-list')).not.toContainText('•');

      // Still absent after the window is closed and reopened, which is where a form that "helpfully"
      // restored state would show it.
      await page.locator('#credential-close').click();
      await openCredentials(page);
      await expect(page.locator('#credential-value')).toHaveValue('');
      expect(await everythingReadable(page)).not.toContain(API_KEY);
    });

  test('asks for a username only for the kind that has one', async ({ page }) => {
    await withCredentialService(page);
    await page.goto('/');
    await openCredentials(page);

    await expect(page.locator('#credential-username-field')).toBeHidden();
    await page.locator('#credential-scheme').selectOption('basic');
    await expect(page.locator('#credential-username-field')).toBeVisible();
    await page.locator('#credential-scheme').selectOption('oauth-token');
    await expect(page.locator('#credential-username-field')).toBeHidden();
  });

  test('says what it is for, and how it is not the token in the command bar', async ({ page }) => {
    await withCredentialService(page);
    await page.goto('/');
    await openCredentials(page);

    await expect(page.locator('#credential-scope')).toContainText('command bar');
    // The bar names the service token explicitly and still carries every existing control.
    await page.locator('#credential-close').click();
    await expect(page.locator('.token-field span')).toHaveText('Service token');
    for (const selector of ['#service-url', '#access-token', '#btn-authenticate', '#btn-revoke',
      '#runtime-connection', '#execution-payload']) {
      await expect(page.locator(selector)).toHaveCount(1);
    }
  });
});

test.describe('the node inspector chooses a credential and never takes one', () => {
  const openWithCatalog = async page => {
    await page.route('**/v1/node-types', route => route.fulfill({
      status: 200, contentType: 'application/json; charset=utf-8', body: JSON.stringify(CATALOG),
    }));
    await page.route('**/v1/events', route =>
      route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' }));
    await page.goto('/');
    await expect(page.locator('#node-catalog')).toContainText('Secret consumer');
  };

  const selectConfiguredNode = async (page, values = {}) => {
    await page.locator('#btn-modify').click();
    await page.evaluate(declared => {
      const owner = window.ravenroot.activeDocument();
      const node = owner.graph.nodeMap.dosomething;
      node.kind = 'BEHAVIOR';
      node.behavior = 'secret.consumer';
      node.properties = { ...declared };
      node.propertyTypes = Object.fromEntries(Object.keys(declared).map(key => [key, 'string']));
      owner.cy.$(':selected').unselect();
      owner.cy.getElementById('dosomething').select();
    }, values);
    await expect(page.locator('#node-editor')).toBeVisible();
  };

  const secretControl = page => page.locator('[data-catalog-property="credentialRef"]');

  test('offers the author\'s own credentials by label, carrying the reference as the value',
    async ({ page }) => {
      await withCredentialService(page, {
        seed: [{
          reference: REFERENCE, label: 'Weather API', scheme: 'api-key', username: '',
          createdAt: '2026-08-28T09:15:00Z',
        }],
      });
      await openWithCatalog(page);
      await selectConfiguredNode(page);

      const control = secretControl(page);
      // A SELECT, in a document where the old build rendered a text input. This is the criterion.
      expect(await control.evaluate(element => element.tagName)).toBe('SELECT');
      await expect(page.locator('input[data-catalog-property="credentialRef"]')).toHaveCount(0);

      await expect(control.locator('option')).toHaveText(['Not selected', 'Weather API']);
      expect(await control.locator('option').nth(1).getAttribute('value')).toBe(REFERENCE);
      // Nothing is pre-chosen: opening a node and saving must not declare a credential nobody picked.
      await expect(control).toHaveValue('');
    });

  test('a credential stored while a node is open becomes choosable without losing what was typed',
    async ({ page }) => {
      await withCredentialService(page);
      await openWithCatalog(page);
      await selectConfiguredNode(page);

      // Something half-typed in a sibling field, which a re-render of the inspector would destroy.
      await page.locator('[data-catalog-property="endpoint"]').fill('https://half.typed.example');
      await expect(secretControl(page).locator('option')).toHaveText(['Not selected']);

      await openCredentials(page);
      await page.locator('#credential-label').fill('Weather API');
      await page.locator('#credential-value').fill(API_KEY);
      await page.locator('#credential-save').click();
      await expect(page.locator('#credential-list .credential-item')).toHaveCount(1);
      await page.locator('#credential-close').click();

      await expect(secretControl(page).locator('option')).toHaveText(['Not selected', 'Weather API']);
      await expect(page.locator('[data-catalog-property="endpoint"]'))
        .toHaveValue('https://half.typed.example');
      expect(await everythingReadable(page)).not.toContain(API_KEY);
    });

  test('a reference the author does not hold is preserved rather than dropped on save',
    async ({ page }) => {
      const FOREIGN = 'rrc_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';
      await withCredentialService(page, {
        seed: [{
          reference: REFERENCE, label: 'Weather API', scheme: 'api-key', username: '',
          createdAt: '2026-08-28T09:15:00Z',
        }],
      });
      await openWithCatalog(page);
      // An imported graph: the node names a credential this author has never held.
      await selectConfiguredNode(page, { credentialRef: FOREIGN });

      const control = secretControl(page);
      await expect(control).toHaveValue(FOREIGN);
      await expect(control.locator('option[selected]')).toContainText('not one of your credentials');

      // Saving the form untouched writes the same reference back, byte for byte.
      await page.locator('#node-editor button[type="submit"]').click();
      expect(await page.evaluate(() =>
        window.ravenroot.activeDocument().graph.nodeMap.dosomething.properties.credentialRef))
        .toBe(FOREIGN);
    });
});
