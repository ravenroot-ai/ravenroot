import { expect, test } from '@playwright/test';

const frame = payload => `event: execution\ndata: ${JSON.stringify(payload)}\n\n`;

async function installDelayedRuntime(page, mode) {
  let postCount = 0;
  let postedGraphMl = '';
  let releaseSubmission;
  let releaseEvents;
  const submissionGate = new Promise(resolve => { releaseSubmission = resolve; });
  const eventGate = new Promise(resolve => { releaseEvents = resolve; });
  const executionId = `${mode}-origin-execution`;
  const graphVersion = `${mode}-origin-version`;

  await page.route('**/v1/node-types', route => route.fulfill({
    status: 200, contentType: 'application/json', body: '[]',
  }));
  await page.route('**/v1/events**', async route => {
    const body = await eventGate;
    return route.fulfill({ status: 200, contentType: 'text/event-stream', body });
  });
  await page.route('**/v1/executions**', async route => {
    if (route.request().method() === 'POST') {
      postCount += 1;
      postedGraphMl = route.request().postData() || '';
      await submissionGate;
      return route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ executionId, graphVersion, processInstanceId: `${mode}-process`,
          executionPolicy: mode === 'run' ? 'RUN' : 'TEST_PASSTHROUGH' }),
      });
    }
    return route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ status: 'RUNNING', executionId }),
    });
  });

  return {
    executionId,
    graphVersion,
    get postCount() { return postCount; },
    get postedGraphMl() { return postedGraphMl; },
    releaseSubmission,
    releaseEvents: () => releaseEvents(frame({
      type: 'NODE_STARTED', executionId, graphVersion, processInstanceId: `${mode}-process`,
      nodeId: 'dosomething', activeInstances: 1,
    })),
  };
}

for (const mode of ['test', 'run']) {
  test(`${mode === 'test' ? 'Test' : 'Run'} keeps a delayed execution on its originating editor document`, async ({ page }) => {
    const runtime = await installDelayedRuntime(page, mode);
    await page.goto('/');
    if (mode === 'run') await page.evaluate(() => { window.confirm = () => true; });

    await page.locator('#btn-modify').click();
    await page.evaluate(() => { window.cy.getElementById('dosomething').select(); });
    const name = page.locator('#node-editor input[name="name"]');
    await name.fill(`Dirty ${mode} origin`);
    await name.press('Tab');
    await expect(page.locator('#dirty-state')).toHaveText('unsaved changes');

    const before = await page.evaluate(() => {
      const owner = window.ravenroot.activeDocument();
      owner.cy.viewport({ zoom: 0.83, pan: { x: 91, y: 47 } });
      owner.cy.getElementById('dosomething').select();
      window.__executionOrigin = {
        owner, graph: owner.graph, history: owner.history, cy: owner.cy,
        provenance: JSON.stringify(owner.provenance),
      };
      return {
        id: owner.documentId, incarnation: owner.incarnation, mode: owner.mode,
        documentCount: window.ravenroot.documents().length,
        historyDepth: owner.history.depth(), dirty: owner.history.isDirty(),
        selected: owner.cy.$(':selected').map(element => element.id()),
        zoom: owner.cy.zoom(), pan: owner.cy.pan(),
      };
    });

    await page.locator(mode === 'run' ? '#btn-run' : '#btn-play').click();
    await expect.poll(() => runtime.postCount).toBe(1);
    expect(runtime.postedGraphMl).toContain(`Dirty ${mode} origin`);

    const sibling = await page.evaluate(() => window.ravenroot.openDocument({ name: 'sibling.graphml' }));
    expect(await page.evaluate(() => window.ravenroot.activeDocument().documentId)).toBe(sibling);
    runtime.releaseSubmission();
    await expect.poll(() => page.evaluate(id =>
      window.ravenroot.workspace.find(id)?.execution.executionId, before.id)).toBe(runtime.executionId);

    expect(await page.evaluate(([originId, siblingId]) => {
      const origin = window.ravenroot.workspace.find(originId);
      const refs = window.__executionOrigin;
      return {
        documentCount: window.ravenroot.documents().length,
        activeId: window.ravenroot.activeDocument().documentId,
        sameOwner: origin === refs.owner,
        sameGraph: origin.graph === refs.graph,
        sameHistory: origin.history === refs.history,
        sameRenderer: origin.cy === refs.cy,
        incarnation: origin.incarnation,
        mode: origin.mode,
        provenance: JSON.stringify(origin.provenance),
        historyDepth: origin.history.depth(),
        dirty: origin.history.isDirty(),
        selected: origin.cy.$(':selected').map(element => element.id()),
        zoom: origin.cy.zoom(),
        pan: origin.cy.pan(),
        siblingExecution: window.ravenroot.workspace.find(siblingId).execution.executionId,
      };
    }, [before.id, sibling])).toEqual({
      documentCount: before.documentCount + 1,
      activeId: sibling,
      sameOwner: true,
      sameGraph: true,
      sameHistory: true,
      sameRenderer: true,
      incarnation: before.incarnation,
      mode: before.mode,
      provenance: await page.evaluate(() => window.__executionOrigin.provenance),
      historyDepth: before.historyDepth,
      dirty: before.dirty,
      selected: before.selected,
      zoom: before.zoom,
      pan: before.pan,
      siblingExecution: null,
    });

    // Editing may remove a node after its immutable submission snapshot was accepted. A delayed
    // event for that old node still belongs to the origin, but must be harmless to its current graph.
    await page.evaluate(originId => {
      const origin = window.ravenroot.workspace.find(originId);
      origin.cy.getElementById('dosomething').remove();
      origin.graph.nodes = origin.graph.nodes.filter(node => node.id !== 'dosomething');
      delete origin.graph.nodeMap.dosomething;
    }, before.id);
    runtime.releaseEvents();
    await expect.poll(() => page.evaluate(id =>
      window.ravenroot.workspace.find(id)?.execution.events.length, before.id)).toBe(1);
    expect(await page.evaluate(([originId, siblingId]) => ({
      activeId: window.ravenroot.activeDocument().documentId,
      originEvents: window.ravenroot.workspace.find(originId).execution.events.length,
      siblingEvents: window.ravenroot.workspace.find(siblingId).execution.events.length,
    }), [before.id, sibling])).toEqual({ activeId: sibling, originEvents: 1, siblingEvents: 0 });
  });
}
