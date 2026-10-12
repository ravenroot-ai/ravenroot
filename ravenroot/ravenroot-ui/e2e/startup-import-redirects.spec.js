import { createServer } from 'node:http';

import { expect, test as base } from '@playwright/test';

import { SERVICE_ORIGIN, SERVICE_PORT, UI_ORIGIN } from './ports.mjs';

const GRAPHML = '<?xml version="1.0"?><graphml xmlns="http://graphml.graphdrawing.org/xmlns">'
  + '<graph id="redirect-target" edgedefault="directed"><node id="imported"/></graph></graphml>';
const CONFIGURATION = { schemaVersion: 1, graphDocumentMaxBytes: 1024 * 1024 };
const TOKEN = 'redirect-test-bearer';

// Use a real destination server: the oracle counts actual contact, not just fetch options. Its
// origin is allowed by the fixture's existing CSP; a positive control also proves CORS reachability.
const test = base.extend({
  redirectTarget: async ({}, use) => {
    const requests = [];
    const server = createServer((request, response) => {
      requests.push({ url: request.url, authorization: request.headers.authorization || '' });
      response.writeHead(200, { 'Content-Type': 'application/graphml+xml',
        'Access-Control-Allow-Origin': '*', 'Cache-Control': 'no-store' });
      response.end(GRAPHML);
    });
    await new Promise((resolve, reject) => {
      server.once('error', reject);
      server.listen(SERVICE_PORT, '127.0.0.1', resolve);
    });
    const url = `${SERVICE_ORIGIN}/private/final.graphml?signature=target-secret`;
    const source = { requests: [], direct: false, status: 307 };
    const sourceServer = createServer((request, response) => {
      source.requests.push(request.headers.authorization || '');
      response.writeHead(source.direct ? 200 : source.status, {
        'Access-Control-Allow-Origin': '*', 'Cache-Control': 'no-store',
        ...(source.direct ? { 'Content-Type': 'application/graphml+xml' } : { Location: url }),
      });
      response.end(source.direct ? GRAPHML : '');
    });
    try {
      await new Promise((resolve, reject) => {
        sourceServer.once('error', reject);
        sourceServer.listen(0, '127.0.0.1', resolve);
      });
      source.origin = `http://127.0.0.1:${sourceServer.address().port}`;
      await use({ requests, url, source });
    } finally {
      sourceServer.closeAllConnections();
      server.closeAllConnections();
      await Promise.all([new Promise(resolve => sourceServer.close(resolve)),
        new Promise(resolve => server.close(resolve))]);
    }
  },
});

async function workspaceEvidence(page) {
  return page.evaluate(() => ({
    activeId: window.ravenroot.workspace.activeId,
    documents: window.ravenroot.documents().map(document_ => ({
      id: document_.id, incarnation: document_.incarnation, name: document_.name,
      graph: window.ravenroot.serializeGraphML(document_.graph),
      revision: document_.history.revision(), dirty: document_.history.isDirty(),
      authoring: document_.authoring,
    })),
  }));
}

for (const scenario of [
  { name: 'same-origin redirect to an external origin', external: false, status: 302, recover: true },
  { name: 'confirmed external origin redirect to another external origin',
    external: true, status: 307, recover: false },
]) {
  test(`${scenario.name} stops before contacting its target`, async ({ page, context, redirectTarget }) => {
    const origin = scenario.external ? redirectTarget.source.origin : UI_ORIGIN;
    const sourceRequests = redirectTarget.source.requests;
    const source = `${origin}/redirect.graphml?signature=source-secret#private-fragment`;
    if (scenario.external) {
      // The intercepted navigation has no network address-space metadata. Grant the browser's
      // loopback permission for these local fixtures; CSP and CORS still apply to both origins.
      await context.grantPermissions(['local-network-access'], { origin: UI_ORIGIN });
      // Admit exactly the additional source origin in this scenario, leaving the target's existing
      // CSP entry intact. This prevents a CSP block from masquerading as the consent guard.
      await page.route(`${UI_ORIGIN}/?*`, async route => {
        const response = await route.fetch();
        const headers = response.headers();
        headers['content-security-policy'] = headers['content-security-policy']
          .replace("connect-src 'self'", `connect-src 'self' ${origin}`);
        await route.fulfill({ response, headers });
      });
    }
    await page.route('**/v1/configuration', route => route.fulfill(
      route.request().headers().authorization === `Bearer ${TOKEN}`
        ? { status: 200, contentType: 'application/json', body: JSON.stringify(CONFIGURATION) }
        : { status: 401, contentType: 'application/json', body: '{"error":"authentication required"}' },
    ));
    await page.route('**/v1/node-types', route => route.fulfill({
      status: 200, contentType: 'application/json', body: '[]',
    }));
    await page.route('**/v1/events**', route => route.fulfill({ status: 204, body: '' }));
    if (!scenario.external) await page.route(`${origin}/redirect.graphml?*`, route => {
      sourceRequests.push(route.request().headers().authorization || '');
      return route.fulfill(redirectTarget.source.direct
        ? { status: 200, headers: { 'Access-Control-Allow-Origin': '*' },
          contentType: 'application/graphml+xml', body: GRAPHML }
        : { status: scenario.status, headers: { 'Access-Control-Allow-Origin': '*',
          Location: redirectTarget.url }, body: '' });
    });
    await page.goto(`/?file=${encodeURIComponent(source)}`);
    await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'waiting-authentication');
    const before = await workspaceEvidence(page);
    await page.locator('#access-token').fill(TOKEN);
    await page.locator('#btn-authenticate').click();
    if (scenario.external) {
      await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'waiting-origin-confirmation');
      expect(sourceRequests).toEqual([]);
      expect(redirectTarget.requests).toEqual([]);
      await page.locator('#startup-import-confirm').click();
    }

    await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'failed');
    expect(redirectTarget.requests).toEqual([]);
    expect(sourceRequests).toEqual(['']);
    expect(await workspaceEvidence(page)).toEqual(before);
    await expect(page.locator('#startup-import-status')).toHaveText(
      'The graph URL redirects to another location. Use the final file URL directly and confirm its origin if prompted.',
    );
    await expect(page.locator('#startup-import')).not.toContainText('source-secret');
    await expect(page.locator('#startup-import')).not.toContainText('target-secret');
    await expect(page.locator('#startup-import')).not.toContainText('private-fragment');
    await expect(page.locator('#startup-import')).not.toContainText(TOKEN);
    await expect(page.locator('#startup-import-retry')).toBeVisible();
    await expect(page.locator('#startup-import-cancel')).toBeVisible();

    await page.locator('#startup-import-retry').click();
    await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'failed');
    expect(sourceRequests).toEqual(['', '']);
    expect(redirectTarget.requests).toEqual([]);
    expect(await workspaceEvidence(page)).toEqual(before);

    if (scenario.recover) {
      // A deliberately retried source can recover when it serves the file directly.
      redirectTarget.source.direct = true;
      await page.locator('#startup-import-retry').click();
      await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'completed');
      expect(sourceRequests).toEqual(['', '', '']);
      const after = await workspaceEvidence(page);
      expect(after.documents).toHaveLength(1);
      expect(after.activeId).toBe(before.activeId);
      expect(after.documents[0].name).toBe('redirect.graphml');
      expect(redirectTarget.requests).toEqual([]);
      // The exact target is accessible. Zero earlier requests therefore came from redirect policy,
      // not an unreachable server or a CSP/CORS failure. This is an explicit test-only fetch.
      expect(await page.evaluate(url => fetch(url).then(response => response.text()), redirectTarget.url))
        .toBe(GRAPHML);
    } else {
      await page.locator('#startup-import-cancel').click();
      await page.locator('#service-url').evaluate(element => {
        element.dispatchEvent(new Event('change', { bubbles: true }));
      });
      await expect.poll(() => page.evaluate(() =>
        window.ravenroot.workspacePersistence().authority.state)).toBe('ready');
      await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'cancelled');
      await expect(page.locator('#startup-import-retry')).toBeHidden();
      expect(sourceRequests).toEqual(['', '']);
      expect(redirectTarget.requests).toEqual([]);
      expect(await workspaceEvidence(page)).toEqual(before);

      // Following the guidance starts a new import and requires the final origin's own consent.
      await page.goto(`/?file=${encodeURIComponent(redirectTarget.url)}`);
      await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'waiting-authentication');
      await page.locator('#access-token').fill(TOKEN);
      await page.locator('#btn-authenticate').click();
      await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'waiting-origin-confirmation');
      expect(redirectTarget.requests).toEqual([]);
      await page.locator('#startup-import-confirm').click();
      await expect(page.locator('#startup-import')).toHaveAttribute('data-phase', 'completed');
      const after = await workspaceEvidence(page);
      expect(after.documents).toHaveLength(1);
      expect(after.documents[0].name).toBe('final.graphml');
    }
    expect(redirectTarget.requests).toEqual([
      { url: '/private/final.graphml?signature=target-secret', authorization: '' },
    ]);
  });
}
