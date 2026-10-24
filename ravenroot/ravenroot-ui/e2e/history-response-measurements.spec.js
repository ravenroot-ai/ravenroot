import { expect, test } from '@playwright/test';
import { createServer } from 'node:http';
import { measureHistoryResponses } from './history-response-measurements.mjs';

async function streamingServer(page, run) {
  let response;
  const server = createServer((request, reply) => {
    if (request.url.startsWith('/history?')) {
      response = reply;
      reply.writeHead(200, { 'Content-Type': 'application/json' }); reply.flushHeaders();
    } else { reply.end('<!doctype html><title>Measurement lifecycle</title>'); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const origin = `http://127.0.0.1:${server.address().port}`;
  try {
    await page.goto(origin);
    const fetchHeaders = async () => {
      const headers = page.waitForResponse(value => new URL(value.url()).pathname === '/history');
      await page.evaluate(() => { window.bodyResult = fetch('/history?view=visits&limit=1').then(value => value.text()).catch(error => error.name); });
      await headers; return response;
    };
    await run({ origin, fetchHeaders });
  } finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
}

test('history measurements drain a real held HTTP body before later navigation and retired reads', async ({ page }) => {
  await streamingServer(page, async ({ origin, fetchHeaders }) => {
    const measurements = measureHistoryResponses(page), reply = await fetchHeaders();
    const stopped = measurements.stop(); let drained = false;
    stopped.then(() => { drained = true; }, () => { drained = true; });
    await page.evaluate(() => document.readyState); expect(drained).toBe(false);
    const body = '{"items":[{"id":"original"}]}'; reply.end(body);
    const pages = await stopped; expect(pages).toHaveLength(1);
    expect(pages[0]).toMatchObject({ rows: 1, bytes: Buffer.byteLength(body), requestLimit: 1, status: 200 });
    expect(pages[0].duration).toBeGreaterThanOrEqual(0);
    await page.evaluate(() => window.bodyResult);
    // A later reader may legitimately be retired while its body is still in flight.
    await fetchHeaders(); await page.goto(`${origin}/other`);
    expect(measurements.pages).toEqual(pages); expect(pages).toHaveLength(1);
  });
});

test('a measured HTTP body failure is observed before navigation and rejects the explicit barrier', async ({ page }) => {
  await streamingServer(page, async ({ fetchHeaders }) => {
    const measurements = measureHistoryResponses(page), reply = await fetchHeaders();
    reply.destroy();
    await page.evaluate(() => window.bodyResult);
    await expect(measurements.stop()).rejects.toMatchObject({ name: 'AggregateError', errors: [expect.any(Error)] });
    expect(measurements.pages).toEqual([]);
  });
});
