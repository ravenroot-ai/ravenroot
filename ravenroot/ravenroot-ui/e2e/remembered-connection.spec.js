import { clickCommand } from './command-actions.mjs';
import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { readFile } from 'node:fs/promises';
import { createServer as httpServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import { configuration, createServer } from '../../../ui-server/server.mjs';
import { UI_ORIGIN, SERVICE_ORIGIN } from './ports.mjs';
const KEY = 'ravenroot.ui.connection';
const TOKEN = 'browser-opt-in-canary-555';
const configurationBody = { schemaVersion: 1, graphDocumentMaxBytes: 10 * 1024 * 1024 };
async function fakeRuntime(page, { status = () => 200, requests = [] } = {}) {
  await page.route('**/v1/**', async route => {
    const request = route.request(); requests.push({ url: request.url(), token: request.headers().authorization });
    const path = new URL(request.url()).pathname;
    const headers = { 'Access-Control-Allow-Origin': UI_ORIGIN, 'Access-Control-Allow-Headers': 'authorization', 'Access-Control-Allow-Methods': 'GET' };
    if (request.method() === 'OPTIONS') return route.fulfill({ status: 204, headers });
    const code = status(path);
    if (code !== 200) return route.fulfill({ status: code, json: {}, headers });
    if (path.endsWith('/configuration')) return route.fulfill({ json: configurationBody, headers });
    if (path.endsWith('/node-types')) return route.fulfill({ json: [], headers });
    if (path.endsWith('/events')) return route.fulfill({ status: 200, contentType: 'text/event-stream', body: ': ready\n\n', headers });
    return route.fulfill({ status: 404, json: {}, headers });
  });
}
async function authenticate(page, token = TOKEN) {
  await page.locator('#access-token').fill(token); await page.locator('#btn-authenticate').click();
}
async function savedRecord(page) { return page.evaluate(key => JSON.parse(localStorage.getItem(key)), KEY); }
async function assertStorageBoundary(page, allowed = true) {
  const stored = await page.evaluate(() => Object.fromEntries(Object.keys(localStorage).map(key => [key, localStorage.getItem(key)])));
  for (const [key, value] of Object.entries(stored)) {
    if (allowed && key === KEY) expect(JSON.parse(value)).toEqual({ version: 1, service: UI_ORIGIN, token: TOKEN });
    else expect(value).not.toContain(TOKEN);
    expect(key).not.toContain(TOKEN);
  }
}
test('opt-in is accessible, bound, survives reopening, and Forget keeps only service', async ({ page, context }, testInfo) => {
  const requests = []; await fakeRuntime(page, { requests }); await page.goto('/'); await authenticate(page);
  const dialog = page.getByRole('dialog', { name: 'Remember this connection?' }); await expect(dialog).toBeVisible();
  await expect(page.getByRole('button', { name: 'Keep in memory', exact: true })).toBeFocused();
  expect((await new AxeBuilder({ page }).include('#remember-connection-dialog').analyze()).violations).toEqual([]);
  await page.screenshot({ path: testInfo.outputPath('remember-dialog.png') });
  await page.keyboard.press('Tab'); await expect(page.getByRole('button', { name: 'Remember', exact: true })).toBeFocused();
  await page.keyboard.press('Enter'); await expect(dialog).toBeHidden(); await expect(page.locator('#btn-authenticate')).toBeFocused();
  await expect.poll(() => savedRecord(page)).toEqual({ version: 1, service: UI_ORIGIN, token: TOKEN });
  await expect(page.locator('#access-token')).toHaveValue(''); await expect(page.locator('#access-token')).toHaveAttribute('placeholder', 'saved in this browser');
  await assertStorageBoundary(page);
  const downloadPending = page.waitForEvent('download');
  await clickCommand(page, 'file.save');
  const download = await downloadPending;
  expect(await readFile(await download.path(), 'utf8')).not.toContain(TOKEN);
  expect(await page.evaluate(() => JSON.stringify(window.ravenroot.workspacePersistence()))).not.toContain(TOKEN);
  // The red control proves an unauthorized write is detected even with one legitimate stored token.
  await page.evaluate(token => localStorage.setItem('rr-control-token', token), TOKEN);
  await expect(assertStorageBoundary(page)).rejects.toThrow(); await page.evaluate(() => localStorage.removeItem('rr-control-token'));
  await page.reload(); await expect(page.locator('#btn-revoke')).toBeEnabled(); await expect(dialog).toBeHidden();
  const reopened = await context.newPage(); await fakeRuntime(reopened); await reopened.goto('/');
  await expect(reopened.locator('#access-token')).toHaveAttribute('placeholder', 'saved in this browser');
  await expect(reopened.getByRole('dialog', { name: 'Remember this connection?' })).toBeHidden(); await reopened.close();
  requests.length = 0; await page.locator('#service-url').fill('/other'); await page.locator('#service-url').press('Tab');
  await expect.poll(() => requests.some(request => request.url.includes('/other/v1/'))).toBe(true);
  expect(requests.filter(request => request.url.includes('/other/v1/')).every(request => !request.token)).toBe(true);
  await page.locator('#service-url').fill(UI_ORIGIN); await page.locator('#service-url').press('Tab');
  await expect(page.locator('#btn-revoke')).toBeEnabled(); await page.locator('#btn-revoke').click();
  await expect.poll(() => savedRecord(page)).toEqual({ version: 1, service: UI_ORIGIN, token: null });
  await page.reload(); await expect(page.locator('#btn-revoke')).toBeDisabled(); await expect(page.locator('#service-url')).toHaveValue(UI_ORIGIN);
  await assertStorageBoundary(page, false);
});
test('Escape declines once and restores focus; retry never reopens consent', async ({ page }) => {
  await fakeRuntime(page); await page.goto('/'); await authenticate(page);
  await expect(page.locator('#remember-connection-dialog')).toBeVisible(); await page.keyboard.press('Escape');
  await expect(page.locator('#remember-connection-dialog')).toBeHidden(); await expect(page.locator('#btn-authenticate')).toBeFocused();
  await expect(page.locator('#btn-revoke')).toBeEnabled(); await authenticate(page);
  await expect(page.locator('#remember-connection-dialog')).toBeHidden(); await assertStorageBoundary(page, false);
});
for (const code of [401, 403, 'network']) test(`restored ${code} retains only the appropriate browser state`, async ({ page }) => {
  let status = 200; await fakeRuntime(page, { status: () => status }); await page.goto('/'); await authenticate(page);
  await page.getByRole('button', { name: 'Remember', exact: true }).click(); await expect.poll(() => savedRecord(page).then(record => record?.token)).toBe(TOKEN);
  if (code === 'network') { await page.unroute('**/v1/**'); await page.route('**/v1/**', route => route.abort()); }
  else status = code;
  await page.reload();
  if (code === 401) {
    await expect.poll(() => savedRecord(page).then(record => record?.token)).toBeNull();
    await expect.poll(() => page.evaluate(() => window.ravenroot.workspacePersistence().authority.state)).toBe('revoked');
    await expect(page.locator('#btn-revoke')).toBeDisabled();
  }
  else { await expect(page.locator('#btn-revoke')).toBeEnabled(); expect((await savedRecord(page)).token).toBe(TOKEN); }
  await expect(page.locator('#service-url')).toHaveValue(UI_ORIGIN); await expect(page.locator('#remember-connection-dialog')).toBeHidden();
});
test('restored external credential waits for origin confirmation and Authenticate works with blank field', async ({ page }) => {
  const requests = []; await fakeRuntime(page, { requests });
  await page.addInitScript(({ key, service, token }) => localStorage.setItem(key, JSON.stringify({ version: 1, service, token })), { key: KEY, service: SERVICE_ORIGIN, token: TOKEN });
  await page.goto('/'); await expect(page.locator('#runtime-connection')).toHaveAttribute('title', /Confirm the external/);
  expect(requests).toEqual([]); await expect(page.locator('#access-token')).toHaveValue('');
  page.once('dialog', dialog => dialog.accept()); await page.locator('#btn-authenticate').click();
  await expect.poll(() => requests.some(request => request.token === `Bearer ${TOKEN}`)).toBe(true);
  await expect(page.locator('#remember-connection-dialog')).toBeHidden();
});

for (const prefix of ['', '/public']) test(`five browsers auto-connect with server-only shared token ${prefix || '/'}`, async ({ browser }) => {
  const serverToken = 'server-never-browser-canary-555'; const requests = [];
  let expire = false;
  const backend = httpServer((req, res) => {
    requests.push({ path: req.url, auth: req.headers.authorization });
    if (expire || ![`Bearer ${serverToken}`, 'Bearer personal-token'].includes(req.headers.authorization)) { res.writeHead(401).end('{}'); return; }
    if (req.url === '/v1/configuration') { res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(configurationBody)); return; }
    if (req.url === '/v1/node-types') { res.writeHead(200, { 'Content-Type': 'application/json' }).end('[]'); return; }
    if (req.url.startsWith('/v1/events')) { res.writeHead(200, { 'Content-Type': 'text/event-stream' }); res.write(': ready\n\n'); return; }
    res.writeHead(404).end('{}');
  });
  const listen = server => new Promise(resolve => server.listen(0, '127.0.0.1', () => resolve(`http://127.0.0.1:${server.address().port}`)));
  const upstream = await listen(backend); const proxy = createServer(configuration({ RAVENROOT_UI_ROOT: fileURLToPath(new URL('../dist/', import.meta.url)), RAVENROOT_UI_PREFIX: prefix, RAVENROOT_UI_BACKEND_URL: upstream, RAVENROOT_UI_BACKEND_TOKEN: serverToken }));
  const ui = await listen(proxy); const contexts = [];
  try {
    for (let i = 0; i < 5; i += 1) {
      const context = await browser.newContext(); contexts.push(context); const page = await context.newPage(); await page.goto(ui + prefix + '/');
      await expect(page.locator('#runtime-connection')).toContainText('connected');
      await expect(page.locator('#remember-connection-dialog')).toBeHidden(); await expect(page.locator('#btn-revoke')).toBeDisabled();
      await expect(page.locator('#access-token')).toHaveValue('');
      expect(await page.evaluate(() => JSON.stringify(localStorage))).not.toContain(serverToken);
      expect(await page.content()).not.toContain(serverToken);
      await page.locator('#menu-run').click();
      // Connected authority exposes the runtime-backed windows without a local credential.
      await expect(page.getByRole('menuitem', { name: 'Deployments…', exact: true })).toHaveAttribute('aria-disabled', 'false');
      await page.keyboard.press('Escape');
    }
    expect(requests.filter(request => request.path === '/v1/node-types').length).toBeGreaterThanOrEqual(5);
    expect(requests.every(request => request.auth === 'Bearer ' + serverToken)).toBe(true);
    const page = contexts[0].pages()[0];
    // A personal token can be forgotten; the proxy default is available again afterward.
    await page.locator('#access-token').fill('personal-token'); await page.locator('#btn-authenticate').click();
    await expect.poll(() => requests.some(request => request.auth === 'Bearer personal-token')).toBe(true);
    await page.getByRole('button', { name: 'Remember', exact: true }).click();
    await expect(page.locator('#btn-revoke')).toBeEnabled();
    await page.locator('#btn-revoke').click();
    await expect(page.locator('#btn-revoke')).toBeDisabled();
    await expect(page.locator('#runtime-connection')).toContainText('connected');
    expect((await savedRecord(page)).token).toBeNull();
    await expect(page.locator('#remember-connection-dialog')).toBeHidden();
    const beforeRejected = requests.length;
    await page.locator('#access-token').fill('rejected-personal'); await page.locator('#btn-authenticate').click();
    await expect(page.locator('#runtime-connection')).toContainText('authentication required');
    expect(requests.slice(beforeRejected).every(request => request.auth === 'Bearer rejected-personal')).toBe(true);
    await expect.poll(() => page.evaluate(() => window.ravenroot.workspacePersistence().authority.state)).toBe('revoked');
    // No retry of a rejected request with the default identity; an explicit reopen can use it.
    await page.reload(); await expect(page.locator('#runtime-connection')).toContainText('connected');
    await page.evaluate(() => localStorage.clear()); await page.reload(); await expect(page.locator('#runtime-connection')).toContainText('connected');
    expire = true; await page.reload(); await expect(page.locator('#runtime-connection')).toContainText('authentication required');
    await expect(page.locator('#runtime-connection')).toHaveAttribute('title', /operator.*restart/);
    await expect(page.locator('#remember-connection-dialog')).toBeHidden();
  } finally {
    for (const context of contexts) await context.close();
    proxy.closeAllConnections(); backend.closeAllConnections(); await Promise.all([new Promise(resolve => proxy.close(resolve)), new Promise(resolve => backend.close(resolve))]);
  }
});
