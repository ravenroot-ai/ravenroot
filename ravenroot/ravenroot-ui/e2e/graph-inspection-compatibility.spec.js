import { expect, test } from '@playwright/test';

const EXECUTION_ID = '11111111-1111-4111-8111-111111111111';

function json(route, status, body) {
  return route.fulfill({ status, contentType: 'application/json; charset=utf-8',
    body: JSON.stringify(body) });
}

async function installRuntime(page, inspection) {
  const starts = [];
  await page.route('**/v1/node-types', route => json(route, 200, []));
  await page.route('**/v1/events**', route => route.fulfill({
    status: 200, contentType: 'text/event-stream; charset=utf-8', body: '',
  }));
  await page.route('**/v1/graphs/inspect**', route => json(route, inspection.status, inspection.body));
  await page.route('**/v1/executions**', route => {
    const request = route.request();
    const url = new URL(request.url());
    if (request.method() === 'POST' && url.pathname === '/v1/executions') {
      starts.push(request.postData() || '');
      return json(route, 200, {
        executionId: EXECUTION_ID, graphVersion: 'graph-v1', executionPolicy: 'TEST_PASSTHROUGH',
      });
    }
    return json(route, 200, { status: 'RUNNING', executionId: EXECUTION_ID });
  });
  return starts;
}

async function submitTest(page) {
  await page.goto('/');
  await expect(page.locator('#btn-play')).toBeEnabled();
  await page.locator('#btn-play').click();
}

test('an unavailable optional inspection still reaches authoritative execution admission', async ({ page }) => {
  const starts = await installRuntime(page, {
    status: 404, body: { error: 'ROUTE_NOT_FOUND' },
  });

  await submitTest(page);

  await expect.poll(() => starts.length).toBe(1);
  expect(starts[0]).toContain('<graphml');
  await expect(page.locator('#activity-log')).not.toContainText('Graph inspection unavailable');
});

test('an N-1 valid inspection response without findings still starts', async ({ page }) => {
  const starts = await installRuntime(page, {
    status: 200,
    body: { nodes: 3, edges: 2, startNodes: 1, endNodes: 1, valid: true, violations: [] },
  });

  await submitTest(page);

  await expect.poll(() => starts.length).toBe(1);
  expect(starts[0]).toContain('<graphml');
});

test('a malformed optional inspection response still reaches authoritative execution admission', async ({ page }) => {
  const starts = await installRuntime(page, {
    status: 200,
    body: { valid: true, violations: [], findings: { reason: 'INVALID_STRUCTURE' } },
  });

  await submitTest(page);

  await expect.poll(() => starts.length).toBe(1);
  expect(starts[0]).toContain('<graphml');
});

test('an explicit structured inspection refusal blocks the mutation', async ({ page }) => {
  const starts = await installRuntime(page, {
    status: 200,
    body: {
      nodes: 3, edges: 2, startNodes: 1, endNodes: 1, valid: false,
      violations: ['invalid structure'],
      findings: [{
        contract: 'ravenroot.graph-admission/1', phase: 'SEMANTIC_STRUCTURE',
        reason: 'INVALID_STRUCTURE', incidentId: 'incident:0123456789abcdef',
      }],
    },
  });

  await submitTest(page);

  const refusal = page.locator('#activity-log .activity-entry.failed')
    .filter({ has: page.locator('.activity-title', { hasText: 'Graph admission refused' }) });
  await expect(refusal).toHaveCount(1);
  await expect(refusal).toContainText('INVALID_STRUCTURE');
  expect(starts).toEqual([]);
});
