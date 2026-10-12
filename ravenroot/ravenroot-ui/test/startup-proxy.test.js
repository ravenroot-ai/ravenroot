// @vitest-environment node
import { afterEach, expect, it, vi } from 'vitest';
import { createServer as httpServer } from 'node:http';
import { createServer } from 'vite';
const servers = [];
afterEach(async () => {
  vi.unstubAllEnvs();
  for (const server of servers.splice(0)) {
    if (server.httpServer) await server.close();
    else { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
  }
});
it('Vite startup env routes the public same-origin service without publishing private upstream or token', async () => {
  let seen;
  const backend = httpServer((req, res) => { seen = { path: req.url, auth: req.headers.authorization }; res.end('backend'); });
  servers.push(backend); await new Promise(resolve => backend.listen(0, '127.0.0.1', resolve));
  const upstream = `http://127.0.0.1:${backend.address().port}`;
  vi.stubEnv('RAVENROOT_SERVICE_URL', upstream); vi.stubEnv('RAVENROOT_UI_BACKEND_TOKEN', 'never-vite-token-canary');
  const vite = await createServer({ configFile: 'vite.config.js', server: { host: '127.0.0.1', port: 0 }, logLevel: 'silent' });
  servers.push(vite); await vite.listen(); const ui = `http://127.0.0.1:${vite.httpServer.address().port}`;
  const html = await (await fetch(ui)).text(); expect(html).toContain('id="service-url" value=""'); expect(html).not.toContain(upstream); expect(html).not.toContain('never-vite-token-canary');
  await fetch(ui + '/v1/runtime'); expect(seen).toEqual({ path: '/v1/runtime', auth: undefined });
  await fetch(ui + '/v1/events?include=diagnostics', { headers: { Authorization: 'Bearer browser-token' } });
  expect(seen).toEqual({ path: '/v1/events?include=diagnostics', auth: 'Bearer browser-token' });
  await fetch(ui + '/health'); expect(seen.path).toBe('/health');
});
it('Vite unset startup env retains its existing loopback 8080 upstream and no server token behavior', async () => {
  vi.stubEnv('RAVENROOT_SERVICE_URL', ''); vi.resetModules();
  const { default: config } = await import('../vite.config.js');
  expect(config.server.proxy['/v1']).toBe('http://127.0.0.1:8080');
  expect(config.server.proxy['/health']).toBe('http://127.0.0.1:8080');
  expect(config).not.toHaveProperty('define');
});
