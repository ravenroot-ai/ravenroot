import { expect, test } from '@playwright/test';

test('frontend plugin runs in an opaque network-denied sandbox and returns validated geometry', async ({ page }) => {
  await page.goto('/');
  const result = await page.evaluate(async () => {
    const createFrontendPluginSandbox = window.ravenroot._createFrontendPluginSandboxForTest;
    const source = `export default {
      layout(snapshot) { return { schema: 'ravenroot.layout-result/v1', positions: { q: { x: 220, y: 250 } } }; },
      render({ layout }) { return { schema: 'ravenroot.scene/v1', width: 800, height: 500,
        elements: [{ type: 'circle', id: 'q', role: 'state', x: layout.positions.q.x, y: layout.positions.q.y, r: 46 }] }; },
      async network() { await fetch('https://example.com/'); return 'unexpected'; }
    }`;
    const sandbox = createFrontendPluginSandbox(source, { timeout: 2000 });
    const layout = await sandbox.layout({ states: [] });
    const scene = await sandbox.render({ layout });
    let networkError = '';
    try { await sandbox.element.contentWindow.postMessage; await sandbox.layout({}); await sandbox.render({ layout });
      await new Promise((resolve, reject) => {
        const id = 987654;
        const listener = event => {
          if (event.data?.channel !== 'ravenroot-plugin-v1' || event.data.id !== id) return;
          removeEventListener('message', listener);
          event.data.type === 'error' ? reject(new Error(event.data.error)) : resolve(event.data.result);
        };
        addEventListener('message', listener);
        sandbox.element.contentWindow.postMessage({ channel: 'ravenroot-plugin-v1', type: 'invoke', id, method: 'network', input: {} }, '*');
      });
    } catch (error) { networkError = error.message; }
    const sandboxAttribute = sandbox.element.getAttribute('sandbox');
    sandbox.destroy();
    return { layout, scene, networkError, sandboxAttribute };
  });
  expect(result.layout.positions.q).toEqual({ x: 220, y: 250 });
  expect(result.scene.elements[0]).toMatchObject({ x: 220, y: 250, r: 46 });
  expect(result.networkError).toBeTruthy();
  expect(result.sandboxAttribute).toBe('allow-scripts');
});

test('sandbox startup rejects invalid modules and missing bootstrap pages within the budget', async ({ page }) => {
  await page.goto('/');
  const errors = await page.evaluate(async () => {
    const createFrontendPluginSandbox = window.ravenroot._createFrontendPluginSandboxForTest;
    const invalid = createFrontendPluginSandbox('export default {', { timeout: 500 });
    let invalidError = '';
    try { await invalid.layout({}); } catch (error) { invalidError = error.message; }
    const missing = createFrontendPluginSandbox('export default {}', { timeout: 150,
      sandboxUrl: '/frontend-plugin-sandbox-missing.html' });
    let missingError = '';
    try { await missing.layout({}); } catch (error) { missingError = error.message; }
    return { invalidError, invalidConnected: invalid.element.isConnected,
      missingError, missingConnected: missing.element.isConnected };
  });
  expect(errors.invalidError).toBeTruthy();
  expect(errors.invalidConnected).toBe(false);
  expect(errors.missingError).toMatch(/did not start/);
  expect(errors.missingConnected).toBe(false);
});
