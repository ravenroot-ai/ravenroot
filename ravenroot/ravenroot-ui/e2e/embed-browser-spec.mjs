import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

const parentOrigin = process.env.RR_EMBED_PARENT_ORIGIN;
const viewerOrigin = process.env.RR_EMBED_VIEWER_ORIGIN;
const foreignOrigin = process.env.RR_EMBED_FOREIGN_ORIGIN;

const rgb = value => value.match(/[\d.]+/gu).slice(0, 3).map(Number);
const luminance = value => rgb(value).map(component => component / 255)
  .map(component => component <= 0.04045 ? component / 12.92 : ((component + 0.055) / 1.055) ** 2.4)
  .reduce((sum, component, index) => sum + component * [0.2126, 0.7152, 0.0722][index], 0);
const contrast = (foreground, background) => {
  const values = [luminance(foreground), luminance(background)].sort((a, b) => b - a);
  return (values[0] + 0.05) / (values[1] + 0.05);
};

for (const scenario of [
  { path: '/theme-light', system: 'dark', expected: 'light' },
  { path: '/theme-dark', system: 'light', expected: 'dark' },
  { path: '/theme-auto-light', system: 'light', expected: 'light' },
  { path: '/theme-auto-dark', system: 'dark', expected: 'dark' },
]) {
  test(`sets ${scenario.expected} before bootstrap for ${scenario.path}`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: scenario.system });
    let releaseBootstrap;
    const gate = new Promise(resolve => { releaseBootstrap = resolve; });
    await page.route('**/embed-bootstrap.js', async route => {
      await gate;
      await route.continue();
    });
    try {
      await page.goto(scenario.path, { waitUntil: 'commit' });
      await expect.poll(() => page.frames().some(frame => frame.url().startsWith(viewerOrigin)), {
        timeout: 10_000,
      }).toBe(true);
      const viewer = page.frames().find(frame => frame.url().startsWith(viewerOrigin));
      await expect.poll(() => viewer.evaluate(() => {
        const style = getComputedStyle(document.documentElement);
        return style.getPropertyValue('--surface-canvas').trim();
      })).not.toBe('');
      const before = await viewer.evaluate(() => ({
        theme: document.documentElement.dataset.theme || null,
        scheme: getComputedStyle(document.documentElement).colorScheme,
        foreground: getComputedStyle(document.documentElement).color,
        background: getComputedStyle(document.documentElement).backgroundColor,
      }));
      expect(before.scheme).toBe(scenario.expected);
      expect(before.theme).toBe(scenario.path.includes('auto') ? null : scenario.expected);
      expect(contrast(before.foreground, before.background)).toBeGreaterThanOrEqual(4.5);
      releaseBootstrap();
      await expect.poll(() => viewer.evaluate(() => document.documentElement.dataset.theme))
        .toBe(scenario.expected);
      const after = await viewer.evaluate(async () => ({
        foreground: getComputedStyle(document.documentElement).color,
        background: getComputedStyle(document.documentElement).backgroundColor,
        storage: {
          cookie: document.cookie,
          local: localStorage.length,
          session: sessionStorage.length,
          databases: (await indexedDB.databases()).length,
          caches: (await window.caches.keys()).length,
          serviceWorkers: (await navigator.serviceWorker.getRegistrations()).length,
        },
      }));
      expect(after.foreground).toBe(before.foreground);
      expect(after.background).toBe(before.background);
      expect(after.storage).toEqual({
        cookie: '', local: 0, session: 0, databases: 0, caches: 0, serviceWorkers: 0,
      });
      const accessibility = await new AxeBuilder({ page })
        .include(['#viewer', 'html'])
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze();
      expect(accessibility.violations).toEqual([]);
    } finally {
      releaseBootstrap();
    }
  });
}

test('rejects a malformed bootstrap theme before HELLO, key generation, or fetch', async ({ page }) => {
  await page.addInitScript(() => {
    window.__themeNetworkStarted = false;
    const originalFetch = window.fetch;
    window.fetch = (...args) => { window.__themeNetworkStarted = true; return originalFetch(...args); };
    const originalGenerateKey = crypto.subtle.generateKey.bind(crypto.subtle);
    crypto.subtle.generateKey = (...args) => {
      window.__themeNetworkStarted = true;
      return originalGenerateKey(...args);
    };
  });
  await page.goto('/theme-invalid');
  await expect.poll(() => page.frames().some(frame => frame.url().startsWith(viewerOrigin)), {
    timeout: 10_000,
  }).toBe(true);
  const viewer = page.frames().find(frame => frame.url().startsWith(viewerOrigin));
  await expect(viewer.locator('#ravenroot-embed-viewer')).toHaveAttribute('data-viewer-state', 'error');
  await expect(viewer.locator('[data-viewer-status]')).toHaveText('The graph could not be displayed.');
  expect(await viewer.evaluate(() => window.__themeNetworkStarted)).toBe(false);
  expect(await page.evaluate(() => window.embedHello)).toBeNull();
});

test('isolates the real bootstrap and projection flow across three origins', async ({ page, request }) => {
  const consoleMessages = [];
  const pageErrors = [];
  const requestedUrls = [];
  const embedPosts = new Map();
  const embedPostCounts = new Map();
  page.on('console', (message) => consoleMessages.push(message.text()));
  page.on('pageerror', (error) => pageErrors.push(error.message));
  page.on('request', (networkRequest) => {
    requestedUrls.push(networkRequest.url());
    if (networkRequest.method() === 'POST'
        && ['/v1/embed/exchange', '/v1/embed/projection'].some((path) => networkRequest.url().endsWith(path))) {
      embedPosts.set(new URL(networkRequest.url()).pathname, {
        body: networkRequest.postData(),
        headers: networkRequest.allHeaders(),
      });
      const path = new URL(networkRequest.url()).pathname;
      embedPostCounts.set(path, (embedPostCounts.get(path) ?? 0) + 1);
    }
  });
  await page.addInitScript(() => {
    const originalFetch = window.fetch;
    const originalGenerateKey = crypto.subtle.generateKey.bind(crypto.subtle);
    window.__embedFetchOptions = [];
    window.__embedKeyIsolation = [];
    window.fetch = (input, options) => {
      window.__embedFetchOptions.push({
        path: String(input),
        credentials: options?.credentials,
        cache: options?.cache,
        referrerPolicy: options?.referrerPolicy,
      });
      return originalFetch(input, options);
    };
    crypto.subtle.generateKey = async (algorithm, extractable, usages) => {
      const keyPair = await originalGenerateKey(algorithm, extractable, usages);
      window.__embedKeyIsolation.push({
        requestedExtractable: extractable,
        privateExtractable: keyPair.privateKey?.extractable,
        publicExtractable: keyPair.publicKey?.extractable,
      });
      return keyPair;
    };
  });

  let launchResponse;
  page.on('response', (response) => {
    if (new URL(response.url()).pathname === '/v1/embed/launch') launchResponse = response;
  });
  await page.goto('/');
  const viewerElement = page.locator('#viewer');
  await expect(viewerElement).toHaveAttribute('sandbox', 'allow-scripts allow-same-origin');
  await expect.poll(() => page.evaluate(() => window.embedHello)).not.toBeNull();
  const viewer = page.frames().find((frame) => frame.url().startsWith(viewerOrigin));
  expect(viewer).toBeDefined();
  expect(viewer.url()).toBe(`${viewerOrigin}/v1/embed/launch`);
  const assertNoViewerWork = async () => {
    await page.waitForTimeout(50);
    expect(await viewer.evaluate(() => window.__embedKeyIsolation)).toEqual([]);
    expect(await viewer.evaluate(() => window.__embedFetchOptions)).toEqual([]);
    expect(embedPostCounts.get('/v1/embed/exchange') ?? 0).toBe(0);
    expect(embedPostCounts.get('/v1/embed/projection') ?? 0).toBe(0);
  };
  await assertNoViewerWork();
  expect(embedPostCounts.get('/v1/embed/exchange') ?? 0).toBe(0);
  expect(embedPostCounts.get('/v1/embed/projection') ?? 0).toBe(0);
  expect(consoleMessages).toEqual([]);
  expect(pageErrors).toEqual([]);

  const ack = await page.evaluate(() => ({
    protocolVersion: window.embedHello.protocolVersion,
    channelId: window.embedHello.channelId,
    correlationId: window.embedHello.correlationId,
    direction: 'parent-to-viewer',
    type: 'ACK',
  }));
  expect(await page.evaluate(() => window.acknowledgeBackend())).toBe(200);
  await assertNoViewerWork();

  const malformedAcknowledgements = [
    { channelId: `${ack.channelId}-wrong` },
    { correlationId: `${ack.correlationId}-wrong` },
    { direction: 'viewer-to-parent' },
    { type: 'READY' },
    { protocolVersion: 'ravenroot.embed/0' },
  ];
  for (const overrides of malformedAcknowledgements) {
    await page.evaluate((messageOverrides) => {
      window.postViewerAcknowledgement(messageOverrides);
    }, overrides);
    await assertNoViewerWork();
  }

  await page.evaluate(({ message, target }) => {
    document.getElementById('viewer').contentWindow.postMessage(message, target);
  }, { message: ack, target: foreignOrigin });
  await assertNoViewerWork();
  await page.evaluate(({ message, target }) => {
    document.getElementById('foreign').contentWindow.postMessage(
      { command: 'CONTACT_VIEWER', message }, target);
  }, { message: ack, target: foreignOrigin });
  await assertNoViewerWork();
  await page.evaluate(({ message, target }) => {
    document.getElementById('sibling').contentWindow.postMessage(
      { command: 'CONTACT_VIEWER', message }, target);
  }, { message: ack, target: parentOrigin });
  await assertNoViewerWork();
  await page.evaluate(({ message, target }) => {
    document.getElementById('viewer').contentWindow.postMessage({ ...message, extra: true }, target);
  }, { message: ack, target: viewerOrigin });
  await assertNoViewerWork();
  await page.evaluate(({ message, target }) => {
    const channel = new MessageChannel();
    document.getElementById('viewer').contentWindow.postMessage(message, target, [channel.port1]);
    channel.port2.close();
  }, { message: ack, target: viewerOrigin });
  await assertNoViewerWork();

  await page.evaluate(() => window.postViewerAcknowledgement());
  await expect.poll(() => page.evaluate(() => window.embedMessages.find(
    (message) => message.type === 'READY') ?? null)).not.toBeNull();
  await expect(viewer.locator('#ravenroot-embed-viewer')).toHaveAttribute('data-viewer-state', 'ready');
  await expect(viewer.locator('[data-viewer-status]')).toHaveText('Graph ready.');
  await expect(viewer.locator('[data-viewer-alternative] > li')).toHaveCount(3);
  await expect(viewer.locator('[data-viewer-alternative]')).toContainText('start');
  await expect(viewer.locator('[data-viewer-alternative]')).toContainText('Behavior');
  expect(await viewer.locator('[data-viewer-canvas] canvas').count()).toBeGreaterThan(0);
  const shell = viewer.locator('#ravenroot-embed-viewer');
  await expect(shell).toHaveAttribute('data-viewer-renderer', 'cyto');
  const minimap = viewer.locator('[data-viewer-minimap]');
  await expect(minimap).toBeVisible();
  await minimap.focus();
  await minimap.press('ArrowRight');
  await minimap.press('Home');
  await minimap.press('Escape');
  await expect(viewer.locator('[data-viewer-canvas]')).toBeFocused();

  const mode = viewer.locator('[data-viewer-mode]');
  await expect(viewer.locator('#ravenroot-embed-viewer'))
    .toHaveAttribute('data-viewer-elastic-policy', 'available');
  for (const [value, label] of [['n8n', 'N8N'], ['elastic', 'Elastic'], ['cyto', 'Cyto']]) {
    await mode.selectOption(value);
    await expect(shell).toHaveAttribute('data-viewer-renderer', value);
    await expect(viewer.locator('[data-viewer-status]')).toHaveText(`${label} view ready.`);
    if (value === 'elastic') {
      const elastic = viewer.locator('[data-viewer-elastic]');
      await expect(elastic).toBeVisible();
      await expect(elastic.locator('.d3-nodes circle')).toHaveCount(3);
      await expect(elastic.locator('.d3-edges path')).toHaveCount(2);
      await expect(minimap).toBeHidden();
      await expect(minimap).toHaveAttribute('tabindex', '-1');
      await expect(minimap).toHaveAttribute('aria-hidden', 'true');
      expect(await viewer.evaluate(() => {
        const overview = document.querySelector('[data-viewer-minimap]');
        return ['ArrowRight', 'Home'].map(key => {
          const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
          overview.dispatchEvent(event);
          return event.defaultPrevented;
        });
      })).toEqual([false, false]);
    } else {
      await expect(viewer.locator('[data-viewer-elastic]')).toBeHidden();
      expect(await viewer.locator('[data-viewer-canvas] canvas').count()).toBeGreaterThan(0);
      await expect(minimap).toBeVisible();
      await expect(minimap).toHaveAttribute('tabindex', '0');
      await expect(minimap).not.toHaveAttribute('aria-hidden');
      if (value === 'cyto') {
        expect(await viewer.evaluate(() => {
          const overview = document.querySelector('[data-viewer-minimap]');
          const event = new KeyboardEvent('keydown', {
            key: 'ArrowRight', bubbles: true, cancelable: true,
          });
          overview.dispatchEvent(event);
          return event.defaultPrevented;
        })).toBe(true);
      }
    }
  }

  await page.emulateMedia({ media: 'print' });
  expect(await viewer.evaluate(() => ({
    canvas: getComputedStyle(document.querySelector('[data-viewer-canvas]')).display,
    controls: getComputedStyle(document.querySelector('.embed-viewer-controls')).display,
    minimap: getComputedStyle(document.querySelector('[data-viewer-minimap]')).display,
    alternative: getComputedStyle(document.querySelector('.embed-viewer-alternative')).display,
    alternativePosition: getComputedStyle(document.querySelector('.embed-viewer-alternative')).position,
    alternativeOverflow: getComputedStyle(document.querySelector('.embed-viewer-alternative')).overflow,
    relationshipText: document.querySelector('[data-viewer-alternative]').textContent,
  }))).toMatchObject({
    canvas: 'none',
    controls: 'none',
    minimap: 'none',
    alternative: 'block',
    alternativePosition: 'static',
    alternativeOverflow: 'visible',
    relationshipText: expect.stringContaining('start'),
  });
  await page.emulateMedia({ media: 'screen' });

  await page.evaluate(origin => {
    const frame = document.createElement('iframe');
    frame.id = 'failure-viewer';
    frame.sandbox = 'allow-scripts allow-same-origin';
    frame.src = `${origin}/failure-viewer`;
    document.body.append(frame);
  }, viewerOrigin);
  await expect.poll(async () => {
    const failureFrame = page.frames().find(candidate => candidate.url().endsWith('/failure-viewer'));
    return failureFrame ? failureFrame.evaluate(() => Boolean(window.__failureEvidence)) : false;
  }).toBe(true);
  const failureFrame = page.frames().find(candidate => candidate.url().endsWith('/failure-viewer'));
  expect(await failureFrame.evaluate(() => window.__failureEvidence)).toEqual({
    observers: expect.any(Number),
    disconnected: expect.any(Number),
    listenerAdds: expect.any(Number),
    listenerRemoves: expect.any(Number),
    state: 'error',
    rootState: 'error',
    status: 'The graph could not be displayed.',
    immediateFrames: {
      requested: expect.any(Number),
      cancelled: expect.any(Number),
      executed: 0,
      pending: expect.any(Number),
    },
    pendingFrames: 0,
    executedFrames: expect.any(Number),
    elasticPresent: false,
    alternativeChildren: 0,
    focusBoundaries: 0,
    lateState: 'error',
  });
  expect(await failureFrame.evaluate(() => window.__failureEvidence.then(result =>
    result.observers > 0 && result.disconnected === result.observers
      && result.listenerRemoves >= result.listenerAdds
      && result.immediateFrames.requested > 0
      && result.immediateFrames.cancelled > 0))).toBe(true);

  const csp = (await launchResponse.allHeaders())['content-security-policy'];
  expect(csp).toContain(`frame-ancestors ${parentOrigin}`);
  expect(csp).toContain('sandbox allow-scripts allow-same-origin');

  const fetchOptions = await viewer.evaluate(() => window.__embedFetchOptions);
  expect(fetchOptions).toEqual([
    { path: '/v1/embed/exchange', credentials: 'omit', cache: 'no-store', referrerPolicy: 'no-referrer' },
    { path: '/v1/embed/projection', credentials: 'omit', cache: 'no-store', referrerPolicy: 'no-referrer' },
  ]);
  expect(await viewer.evaluate(() => window.__embedKeyIsolation)).toEqual([{
    requestedExtractable: false,
    privateExtractable: false,
    publicExtractable: true,
  }]);
  const storage = await viewer.evaluate(async () => ({
    cookie: document.cookie,
    local: localStorage.length,
    session: sessionStorage.length,
    databases: (await indexedDB.databases()).length,
    caches: (await window.caches.keys()).length,
    serviceWorkers: (await navigator.serviceWorker.getRegistrations()).length,
  }));
  expect(storage).toEqual({
    cookie: '', local: 0, session: 0, databases: 0, caches: 0, serviceWorkers: 0,
  });
  await expect(viewer.locator('body')).toContainText('Graph view');
  await expect(viewer.locator('body')).not.toContainText('browser-secret-graph');

  const isolation = await page.evaluate(() => {
    const child = document.getElementById('viewer').contentWindow;
    let domBlocked = false;
    try { void child.document.body; } catch (error) { domBlocked = error.name === 'SecurityError'; }
    return { windowProxyPresent: child !== null, domBlocked };
  });
  expect(isolation).toEqual({ windowProxyPresent: true, domBlocked: true });
  const s2sAckReplayStatus = await page.evaluate(async () => (await fetch('/__embed-ack', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(window.embedHello),
    credentials: 'omit',
    cache: 'no-store',
    referrerPolicy: 'no-referrer',
  })).status);
  expect(s2sAckReplayStatus).toBe(403);
  await page.evaluate(({ message, target }) => {
    document.getElementById('viewer').contentWindow.postMessage(message, target);
  }, { message: ack, target: viewerOrigin });
  await page.waitForTimeout(100);
  expect(embedPostCounts.get('/v1/embed/exchange')).toBe(1);
  expect(embedPostCounts.get('/v1/embed/projection')).toBe(1);

  const ready = await page.evaluate(() => window.embedMessages.find((message) => message.type === 'READY'));
  const ping = {
    protocolVersion: 'ravenroot.embed/1',
    channelId: ready.channelId,
    correlationId: 'valid-parent-correlation',
    direction: 'parent-to-viewer',
    type: 'PING',
  };
  await page.evaluate(({ message, target }) => {
    document.getElementById('viewer').contentWindow.postMessage(message, target);
  }, { message: ping, target: viewerOrigin });
  await expect.poll(() => page.evaluate(() => window.embedMessages.some(
    (message) => message.type === 'PONG'
      && message.correlationId === 'valid-parent-correlation'))).toBe(true);

  await page.evaluate(({ message, target }) => {
    document.getElementById('foreign').contentWindow.postMessage(
      { command: 'CONTACT_VIEWER', message }, target);
  }, { message: { ...ping, correlationId: 'foreign-correlation' }, target: foreignOrigin });
  await page.waitForTimeout(100);
  expect(await page.evaluate(() => window.embedMessages.some(
    (message) => message.correlationId === 'foreign-correlation'))).toBe(false);
  const foreign = page.frames().find((frame) => frame.url().startsWith(foreignOrigin));
  expect(await foreign.evaluate(() => {
    try { void parent.frames.viewer.document.body; return false; }
    catch (error) { return error.name === 'SecurityError'; }
  })).toBe(true);

  const exchangePost = embedPosts.get('/v1/embed/exchange');
  const projectionPost = embedPosts.get('/v1/embed/projection');
  expect(exchangePost).toBeDefined();
  expect(projectionPost).toBeDefined();
  const exchangeBody = JSON.parse(exchangePost.body);
  const projectionBody = JSON.parse(projectionPost.body);
  expect(exchangeBody.jti).not.toBe(projectionBody.jti);
  expect(exchangeBody.nonce).not.toBe(projectionBody.nonce);
  const projectionHeaders = await projectionPost.headers;
  const replayStatus = await viewer.evaluate(async ({ body, authorization }) => (await fetch(
    '/v1/embed/projection', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: authorization },
      body,
      credentials: 'omit',
      cache: 'no-store',
      referrerPolicy: 'no-referrer',
    })).status, { body: projectionPost.body, authorization: projectionHeaders.authorization });
  expect(replayStatus).toBe(403);

  const observed = await (await request.get(`${parentOrigin}/__observations`)).json();
  expect(observed.length).toBeGreaterThanOrEqual(3);
  expect(observed.every((entry) => !entry.cookiePresent && !entry.refererPresent
    && entry.originExact && entry.secFetchSite === 'same-origin')).toBe(true);
  expect(observed.some((entry) => entry.path === '/v1/embed/projection' && entry.bearerPresent)).toBe(true);

  const health = await request.get(`${viewerOrigin}/health`);
  expect(health.headers()['x-frame-options']).toBe('DENY');
  const parentText = await page.locator('body').innerText();
  expect(parentText).not.toContain('browser-secret-graph');
  expect(JSON.stringify(await page.evaluate(() => window.embedMessages))).not.toContain('browser-secret-graph');
  expect(requestedUrls.every((url) => !url.includes('browser-secret-graph'))).toBe(true);
  expect(requestedUrls.filter((url) => !new URL(url).pathname.endsWith('/v1/embed/launch'))
    .every((url) => new URL(url).search === '')).toBe(true);
  expect(consoleMessages.every((message) => !message.includes('browser-secret-graph'))).toBe(true);
  expect(pageErrors).toEqual([]);
});
