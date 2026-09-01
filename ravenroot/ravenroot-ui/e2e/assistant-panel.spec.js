import { expect, test } from '@playwright/test';
import { readFile } from 'node:fs/promises';

// The authoring assistant panel (ADR 0025).
//
// Two things can only be checked here. The first is the panel's behaviour when THE ASSISTANT
// SERVICE DOES NOT EXIST, which is the state of this repository: the fixture server 404s
// `/v1/assistant`, so the inert path below is the real product path, not a simulation of one. The
// second is everything that needs a live DOM — focus stability while a turn arrives, the error
// actually bound to the textarea, the live region's children only ever being appended.
//
// The READY cases stub the service with `page.route`, per test, rather than teaching
// `ui-fixture-server.mjs` about the assistant: a shared fixture that answered these routes for
// every spec would also quietly change the inert test below into a fiction.

const STATUS = '**/v1/assistant';
const MESSAGES = '**/v1/assistant/messages';
const NODE_TYPES = '**/v1/node-types';

const signedIn = async page => page.route(STATUS, route => route.fulfill({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ configured: true, allowlisted: true, signedIn: true, provider: 'Example Model' }),
}));

const replies = async (page, text = 'Node 3 is the bottleneck.') => page.route(MESSAGES,
  route => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify({ text, model: 'example-1' }),
  }));

const panel = page => page.locator('.panel[data-panel-id="assistant"]');

const proposalCatalog = async page => page.route(NODE_TYPES, route => route.fulfill({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify([{
    behavior: 'template', displayName: 'Template', category: 'core', visualType: 'flow',
    properties: [{ name: 'template', type: 'STRING', required: true }],
    outcomes: [{ name: 'continue', fromProperty: '' }],
  }]),
}));

// THE PANEL SHIPS CLOSED, and that is a measured decision rather than an oversight: a second open
// `unbounded` panel in the right column reproduces the node-editor overflow that the closed default
// prevents. So every
// test below opens it the way a user does — one click on the rail mark. See the `defaultClosed`
// comment in `src/panel-layout.js`.
const openAssistant = async page => {
  await page.locator('[data-rail-panel="assistant"]').click();
  await expect(panel(page)).toBeVisible();
};

// A graph, so the context classes have something real to attach.
const withGraph = async page => {
  await page.locator('#btn-new').click();
  await page.waitForTimeout(300);
};

const serializedGraphMl = async page => {
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.locator('#btn-export').click(),
  ]);
  return readFile(await download.path(), 'utf8');
};

const askForProposal = async (page, prompt, proposalId) => {
  await page.locator('#assistant-draft').fill(prompt);
  await page.locator('#assistant-send').click();
  const card = page.locator(`.assistant-proposal[data-proposal-id="${proposalId}"]`);
  await expect(card).toBeVisible();
  return card;
};

test.describe('the panel is part of the right column', () => {
  // Closed by default for a measured reason documented in `panel-layout.js`, not remeasured here:
  // opening this panel beside the Modify-mode node editor overflows the right column, and that
  // overflow itself — not unreachable commands — is why it ships closed. This test only
  // checks that the closed state is real and discoverable, not the overflow.
  test('ships closed, leaving the right column to the Inspector as measured', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.goto('/');
    await expect(panel(page)).toBeHidden();
    await expect(page.locator('.panel[data-panel-id="inspector"]')).toBeVisible();
    // Discoverable, not hidden: the rail mark names it and says it is closed.
    const rail = page.locator('.rail[data-rail-zone="right"] [data-rail-panel="assistant"]');
    await expect(rail).toBeVisible();
    await expect(rail).toHaveAttribute('aria-expanded', 'false');
    await expect(rail).toHaveAttribute('aria-label', 'Show Assistant panel');
  });

  test('sits under the Inspector once opened', async ({ page }) => {
    await page.goto('/');
    await openAssistant(page);

    const order = await page.evaluate(() => [...document.querySelectorAll('#info-body-zone .panel')]
      .filter(node => node.offsetParent !== null)
      .map(node => node.dataset.panelId));
    expect(order).toEqual(['inspector', 'assistant']);
  });

  test('closes and comes back through the rail, like every other panel', async ({ page }) => {
    await page.goto('/');
    await openAssistant(page);
    await page.locator('.panel[data-panel-id="assistant"] [data-action="panel-close"]').click();
    await expect(panel(page)).toBeHidden();
    await expect(page.locator('[data-rail-panel="assistant"]')).toHaveAttribute('aria-expanded', 'false');

    await page.locator('[data-rail-panel="assistant"]').click();
    await expect(panel(page)).toBeVisible();
  });

  test('appears in the Panels index, which is the route back for a closed panel', async ({ page }) => {
    await page.goto('/');
    await page.locator('.rail[data-rail-zone="right"] [data-action="panels-index"]').click();
    const entry = page.locator('#panels-index [data-index-panel="assistant"]');
    await expect(entry).toBeVisible();
    await entry.click();
    await expect(panel(page)).toBeVisible();
  });
});

// ── WITH NO MODEL CONFIGURED ─────────────────────────────────────────────────────────────────────
test.describe('with no assistant service in this deployment', () => {
  test('is rendered, inert, and names the reason rather than pooling it', async ({ page }) => {
    await page.goto('/');
    await openAssistant(page);
    const state = page.locator('#assistant-state');
    await expect(panel(page)).toBeVisible();
    await expect(state).toHaveAttribute('data-state', 'inert');
    // The distinguished reason: not "unavailable", and specifically not "sign in", which would
    // send the user hunting for a button that is not missing.
    await expect(state).toContainText('does not provide the assistant service');
    await expect(state).not.toContainText('Sign in');
  });

  test('disables the composer instead of accepting input it cannot send', async ({ page }) => {
    await page.goto('/');
    await openAssistant(page);
    await expect(page.locator('#assistant-draft')).toBeDisabled();
    await expect(page.locator('#assistant-send')).toBeDisabled();
  });

  test('sends nothing at all — no request leaves for the messages endpoint', async ({ page }) => {
    const attempts = [];
    page.on('request', request => {
      if (request.url().includes('/v1/assistant/messages')) attempts.push(request.url());
    });
    await page.goto('/');
    await openAssistant(page);
    await withGraph(page);
    // Force the submit the disabled control would prevent, which is the interesting case: the
    // refusal must live in the code, not only in the `disabled` attribute.
    await page.evaluate(() => document.getElementById('assistant-composer')
      .dispatchEvent(new Event('submit', { cancelable: true, bubbles: true })));
    await page.waitForTimeout(400);
    expect(attempts).toEqual([]);
    // And nothing was fabricated into the transcript in place of an answer.
    await expect(page.locator('#assistant-transcript .assistant-turn--assistant')).toHaveCount(0);
  });

  test('never announces anything assertively', async ({ page }) => {
    await page.goto('/');
    await openAssistant(page);
    const assertive = await page.evaluate(() => {
      const host = document.querySelector('.panel[data-panel-id="assistant"]');
      return host.querySelectorAll('[aria-live="assertive"], [role="alert"]').length;
    });
    expect(assertive).toBe(0);
  });
});

// ── THE CONTEXT CLAIM, OBSERVED IN THE REAL PRODUCT ──────────────────────────────────────────────
test.describe('the context chips are a claim about the payload', () => {
  test('shows every class, each attached or unavailable, never blank', async ({ page }) => {
    await page.goto('/');
    await openAssistant(page);
    await withGraph(page);
    const chips = page.locator('#assistant-chips .assistant-chip');
    await expect(chips).toHaveCount(6);
    const states = await chips.evaluateAll(nodes => nodes.map(node => node.dataset.state));
    expect(states.every(state => state === 'attached' || state === 'unavailable')).toBe(true);
    // A real graph is open, so that class must be attached — otherwise the chips are decorative.
    await expect(page.locator('#assistant-chips [data-context-class="graph"]'))
      .toHaveAttribute('data-state', 'attached');
  });

  test('the payload inspector contains exactly the classes the chips called attached', async ({ page }) => {
    await page.goto('/');
    await openAssistant(page);
    await withGraph(page);
    await page.locator('#assistant-payload-toggle').click();
    await expect(page.locator('#assistant-payload')).toBeVisible();

    const { attached, keys } = await page.evaluate(() => ({
      attached: [...document.querySelectorAll('#assistant-chips .assistant-chip')]
        .filter(node => node.dataset.state === 'attached')
        .map(node => node.dataset.contextClass).sort(),
      keys: Object.keys(JSON.parse(document.getElementById('assistant-payload').textContent)).sort(),
    }));
    // THE FALSIFIABLE CLAIM, in the shipped product: a chip reading `attached` for a class the
    // payload does not carry fails right here.
    expect(keys).toEqual(attached);
    expect(attached.length).toBeGreaterThan(0);
  });

  test('names the class that is unavailable and why, rather than dimming it silently', async ({ page }) => {
    await page.goto('/');
    await openAssistant(page);
    await withGraph(page);
    // `logs` is deliberately not wired: its tier-0 path is part of the assistant service, which
    // does not exist. The chip must SAY so.
    const logs = page.locator('#assistant-chips [data-context-class="logs"]');
    await expect(logs).toHaveAttribute('data-state', 'unavailable');
    await expect(logs).toContainText('unavailable');
  });
});

// ── THE LIVE REGION, WITH CONTENT ACTUALLY ARRIVING ──────────────────────────────────────────────
test.describe('when the service answers', () => {
  test.beforeEach(async ({ page }) => {
    await signedIn(page);
    await replies(page);
  });

  test('becomes ready and lets the author ask', async ({ page }) => {
    await page.goto('/');
    await openAssistant(page);
    await withGraph(page);
    await expect(page.locator('#assistant-state')).toHaveAttribute('data-state', /ready|degraded/);
    await expect(page.locator('#assistant-draft')).toBeEnabled();
  });

  test('KEEPS FOCUS IN THE COMPOSER while the answer arrives', async ({ page }) => {
    await page.goto('/');
    await openAssistant(page);
    await withGraph(page);
    const draft = page.locator('#assistant-draft');
    await draft.click();
    await draft.fill('why is this slow?');
    // Enter, which is how this is actually used: focus is in the field at the moment of sending,
    // so the field is where it must still be when the answer lands.
    await draft.press('Enter');

    await expect(page.locator('.assistant-turn--assistant')).toHaveCount(1);
    // A user typing their second question must not be thrown out of the field by the answer to
    // their first. Asserted AFTER the turn has landed, which is the only moment it could break.
    await expect(draft).toBeFocused();
    // And the composer stays usable throughout, so the next question can be typed immediately.
    await expect(draft).toBeEnabled();
  });

  // The other half of the same rule: focus is never DROPPED by a state change the user did not
  // make. Clicking Send puts focus on a button that must disable while the request is in flight;
  // dropping it to `<body>` there would restart a keyboard user at the top of the document.
  test('hands focus to the composer rather than to the body when Send disables itself', async ({ page }) => {
    await page.goto('/');
    await openAssistant(page);
    await withGraph(page);
    await page.locator('#assistant-draft').fill('why is this slow?');
    await page.locator('#assistant-send').click();

    await expect(page.locator('.assistant-turn--assistant')).toHaveCount(1);
    const landed = await page.evaluate(() => document.activeElement?.id || document.activeElement?.tagName);
    expect(landed).not.toBe('BODY');
    expect(landed).toBe('assistant-draft');
  });

  test('only ever APPENDS to the transcript — nothing is re-parented or regrouped', async ({ page }) => {
    await page.goto('/');
    await openAssistant(page);
    await withGraph(page);

    const ask = async text => {
      await page.locator('#assistant-draft').fill(text);
      await page.locator('#assistant-send').click();
      await page.waitForTimeout(250);
    };

    await ask('first question');
    // Tag the elements that exist now. If any of them is re-parented, replaced or reordered as the
    // next turn arrives, the identity check below fails.
    await page.evaluate(() => [...document.querySelectorAll('#assistant-transcript > *')]
      .forEach((node, index) => { node.dataset.witness = String(index); }));

    await ask('second question');

    const witnesses = await page.evaluate(() => {
      const children = [...document.querySelectorAll('#assistant-transcript > *')];
      return {
        total: children.length,
        // The originally tagged nodes are still the FIRST children, still in order, still direct
        // children of the region rather than wrapped in a group that appeared later.
        leading: children.slice(0, 3).map(node => node.dataset.witness),
        depth: children.every(node => node.parentElement.id === 'assistant-transcript'),
      };
    });
    // Three, not two: the first exchange is now `author, disclosure, assistant` because the
    // disclosure is admitted ahead of the first reply. The PROPERTY under test is untouched — all
    // three originally tagged nodes are still the leading children, in their original order, still
    // direct children — and checking all three keeps the check exactly as strong as it was.
    expect(witnesses.leading).toEqual(['0', '1', '2']);
    // The disclosure is one additional turn, emitted ONCE for the conversation, so the second
    // exchange adds two and not three, for a total of five.
    expect(witnesses.total).toBe(5);
    expect(witnesses.depth).toBe(true);
  });

  test('records beside each question which classes it actually sent', async ({ page }) => {
    await page.goto('/');
    await openAssistant(page);
    await withGraph(page);
    await page.locator('#assistant-draft').fill('what is in this graph?');
    await page.locator('#assistant-send').click();
    await expect(page.locator('.assistant-turn--author .assistant-turn-attached')).toContainText('graph');
  });

  // ── THE ERROR IS REACHABLE FROM THE CONTROL THAT CAUSED IT ─────────────────────────────────────
  test('binds a refusal to the textarea, politely', async ({ page }) => {
    await page.goto('/');
    await openAssistant(page);
    await withGraph(page);
    const draft = page.locator('#assistant-draft');
    await draft.fill('   ');
    await page.locator('#assistant-send').click();

    const error = page.locator('#assistant-error');
    await expect(error).toBeVisible();
    await expect(error).toHaveText('Type a question before sending.');
    await expect(error).toHaveAttribute('aria-live', 'polite');
    // Bound, not merely adjacent: a screen-reader user arriving at the field later still reaches it.
    await expect(draft).toHaveAttribute('aria-invalid', 'true');
    await expect(draft).toHaveAttribute('aria-errormessage', 'assistant-error');
    await expect(draft).toBeFocused();

    // Typing clears it, so `aria-invalid` never outlives the problem it describes.
    await draft.fill('a real question');
    await expect(error).toBeHidden();
    await expect(draft).not.toHaveAttribute('aria-invalid', 'true');
  });

  test('renders a reply as inert text and executes nothing in it', async ({ page }) => {
    await page.unroute(MESSAGES);
    await page.route(MESSAGES, route => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ text: '<img src=x onerror="window.__assistantXss=1">' }),
    }));
    await page.goto('/');
    await openAssistant(page);
    await withGraph(page);
    await page.locator('#assistant-draft').fill('tell me');
    await page.locator('#assistant-send').click();
    await expect(page.locator('.assistant-turn--assistant')).toHaveCount(1);

    // The model's output is text. It is escaped, it produced no element, and it ran nothing —
    // which matters most precisely because a reply can be shaped by user-authored graph content.
    expect(await page.evaluate(() => window.__assistantXss)).toBeUndefined();
    await expect(page.locator('.assistant-turn--assistant img')).toHaveCount(0);
    await expect(page.locator('.assistant-turn--assistant .assistant-turn-text'))
      .toContainText('onerror');
  });
});

test.describe('controlled graph proposals', () => {
  test.beforeEach(async ({ page }) => {
    await signedIn(page);
    await proposalCatalog(page);
  });

  test('previews two created nodes and their edge, applies once, then undoes and redoes once', async ({ page }) => {
    await page.route(MESSAGES, async route => {
      const request = route.request().postDataJSON();
      await route.fulfill({
        status: 200, contentType: 'application/json', body: JSON.stringify({
          text: 'I prepared a graph proposal.', model: 'example-1', truncated: false,
          proposal: {
            version: 1, id: 'browser-create', document: request.document,
            summary: 'Add two template steps',
            operations: [
              { op: 'create-node', ref: 'first', id: 'assistant-first', behavior: 'template',
                properties: [{ name: 'template', value: 'first' }] },
              { op: 'create-node', ref: 'second', id: 'assistant-second', behavior: 'template',
                properties: [{ name: 'template', value: 'second' }] },
              { op: 'create-edge', ref: 'link', id: 'assistant-link', source: { created: 'first' },
                destination: { created: 'second' }, outcome: 'continue' },
            ],
          },
        }),
      });
    });
    await page.goto('/');
    await openAssistant(page);
    await withGraph(page);
    await page.locator('#assistant-draft').fill('add two steps');
    await page.locator('#assistant-send').click();

    const card = page.locator('.assistant-proposal[data-proposal-id="browser-create"]');
    await expect(card).toBeVisible();
    await expect(card.locator('.assistant-proposal-changes li')).toHaveCount(3);
    await expect(card).toContainText('assistant-first');
    await expect(page.locator('#b-nodes')).toHaveText('4');
    await card.getByRole('button', { name: 'Apply proposal' }).click();
    await expect(card.locator('.assistant-proposal-status')).toHaveText('Applied as one undoable edit.');
    await expect(page.locator('#b-nodes')).toHaveText('6');
    await expect(page.locator('#b-edges')).toHaveText('4');
    await expect(page.locator('#btn-undo')).toHaveAttribute('title', /Assistant proposal/);

    await page.locator('#btn-undo').click();
    await expect(page.locator('#b-nodes')).toHaveText('4');
    await expect(page.locator('#b-edges')).toHaveText('3');

    await page.locator('#btn-redo').click();
    await expect(page.locator('#b-nodes')).toHaveText('6');
    await expect(page.locator('#b-edges')).toHaveText('4');
    await expect(page.locator('#btn-undo')).toHaveAttribute('title', /Assistant proposal/);
  });

  test('applies existing node and edge update/delete including position as one undoable edit', async ({ page }) => {
    let turn = 0;
    await page.route(MESSAGES, async route => {
      const request = route.request().postDataJSON();
      turn += 1;
      const setup = [
        { op: 'create-node', ref: 'a', id: 'assistant-a', behavior: 'template',
          position: { x: 210, y: 210 }, properties: [{ name: 'template', value: 'a' }] },
        { op: 'create-node', ref: 'b', id: 'assistant-b', behavior: 'template',
          position: { x: 390, y: 210 }, properties: [{ name: 'template', value: 'b' }] },
        { op: 'create-node', ref: 'c', id: 'assistant-c', behavior: 'template',
          position: { x: 570, y: 210 }, properties: [{ name: 'template', value: 'c' }] },
        { op: 'create-edge', ref: 'update', id: 'assistant-update',
          source: { created: 'a' }, destination: { created: 'b' }, outcome: 'continue' },
        { op: 'create-edge', ref: 'delete', id: 'assistant-delete',
          source: { created: 'b' }, destination: { created: 'c' }, outcome: 'continue' },
      ];
      const changes = [
        { op: 'update-node', target: { existing: 'assistant-a' }, name: 'Moved A',
          position: { x: 640, y: 480 }, properties: [{ name: 'template', value: 'after' }] },
        { op: 'update-edge', target: { existing: 'assistant-update' },
          destination: { existing: 'assistant-c' } },
        { op: 'delete-edge', target: { existing: 'assistant-delete' } },
        { op: 'delete-node', target: { existing: 'assistant-b' } },
      ];
      await route.fulfill({
        status: 200, contentType: 'application/json', body: JSON.stringify({
          text: 'Review this graph edit.', model: 'example-1', truncated: false,
          proposal: {
            version: 1, id: turn === 1 ? 'browser-setup' : 'browser-change',
            document: request.document, summary: turn === 1 ? 'Create fixtures' : 'Change fixtures',
            operations: turn === 1 ? setup : changes,
          },
        }),
      });
    });
    await page.goto('/');
    await openAssistant(page);
    await withGraph(page);

    const setup = await askForProposal(page, 'create three steps', 'browser-setup');
    await setup.getByRole('button', { name: 'Apply proposal' }).click();
    const change = await askForProposal(page, 'move, update, and delete', 'browser-change');
    await expect(change).toContainText('position 640, 480');
    await change.getByRole('button', { name: 'Apply proposal' }).click();

    await expect.poll(() => page.evaluate(() => {
      const graph = window.ravenroot.activeDocument().graph;
      const node = graph.nodeMap['assistant-a'];
      const edge = graph.edges.find(candidate => candidate.id === 'assistant-update');
      return {
        node: node && { name: node.name, x: node.ox, y: node.oy, template: node.properties.template },
        deletedNode: !graph.nodeMap['assistant-b'],
        edge: edge && { source: edge.source, target: edge.target },
        deletedEdge: !graph.edges.some(candidate => candidate.id === 'assistant-delete'),
        depth: window.ravenroot.activeDocument().history.depth(),
      };
    })).toEqual({
      node: { name: 'Moved A', x: 640, y: 480, template: 'after' },
      deletedNode: true,
      edge: { source: 'assistant-a', target: 'assistant-c' },
      deletedEdge: true,
      depth: 2,
    });

    await page.locator('#btn-undo').click();
    await expect.poll(() => page.evaluate(() => {
      const graph = window.ravenroot.activeDocument().graph;
      const node = graph.nodeMap['assistant-a'];
      const edge = graph.edges.find(candidate => candidate.id === 'assistant-update');
      return {
        node: { name: node.name, x: node.ox, y: node.oy, template: node.properties.template },
        restoredNode: Boolean(graph.nodeMap['assistant-b']),
        edgeTarget: edge.target,
        restoredEdge: graph.edges.some(candidate => candidate.id === 'assistant-delete'),
        depth: window.ravenroot.activeDocument().history.depth(),
      };
    })).toEqual({
      node: { name: 'Template', x: 210, y: 210, template: 'a' }, restoredNode: true,
      edgeTarget: 'assistant-b', restoredEdge: true, depth: 1,
    });

    await page.locator('#btn-redo').click();
    await expect.poll(() => page.evaluate(() => ({
      movedX: window.ravenroot.activeDocument().graph.nodeMap['assistant-a'].ox,
      deletedNode: !window.ravenroot.activeDocument().graph.nodeMap['assistant-b'],
      depth: window.ravenroot.activeDocument().history.depth(),
    }))).toEqual({ movedX: 640, deletedNode: true, depth: 2 });
  });

  test('reject compares exact serialized GraphML bytes and changes nothing', async ({ page }) => {
    await page.route(MESSAGES, async route => {
      const request = route.request().postDataJSON();
      await route.fulfill({
        status: 200, contentType: 'application/json', body: JSON.stringify({
          text: 'Review update and delete.', model: 'example-1', truncated: false,
          proposal: {
            version: 1, id: 'browser-reject', document: request.document,
            summary: 'Update one node and delete one edge',
            operations: [
              { op: 'update-edge', target: { existing: 'edge-start-dosomething' }, outcome: 'continue' },
              { op: 'delete-edge', target: { existing: 'edge-dosomething-error' } },
            ],
          },
        }),
      });
    });
    await page.goto('/');
    await openAssistant(page);
    await withGraph(page);
    const before = await serializedGraphMl(page);
    await page.locator('#assistant-draft').fill('update and delete');
    await page.locator('#assistant-send').click();

    const card = page.locator('.assistant-proposal[data-proposal-id="browser-reject"]');
    await expect(card).toContainText('Update edge edge-start-dosomething');
    await expect(card).toContainText('Delete edge edge-dosomething-error');
    await card.getByRole('button', { name: 'Reject' }).click();
    await expect(card.locator('.assistant-proposal-status')).toHaveText('Rejected. The graph was not changed.');
    await expect(page.locator('#b-nodes')).toHaveText('4');
    await expect(page.locator('#b-edges')).toHaveText('3');
    await expect(page.locator('#btn-undo')).toBeDisabled();
    expect(await serializedGraphMl(page)).toBe(before);
  });

  test('a pending proposal goes stale after real edit, undo, redo, switch, and replacement', async ({ page }) => {
    let turn = 0;
    await page.route(MESSAGES, async route => {
      const request = route.request().postDataJSON();
      turn += 1;
      await route.fulfill({
        status: 200, contentType: 'application/json', body: JSON.stringify({
          text: 'Review this deletion.', model: 'example-1', truncated: false,
          proposal: {
            version: 1, id: `browser-stale-${turn}`, document: request.document,
            summary: 'Delete one edge', operations: [
              { op: 'delete-edge', target: { existing: 'edge-start-dosomething' } },
            ],
          },
        }),
      });
    });
    await page.goto('/');
    await openAssistant(page);
    await withGraph(page);

    const afterEdit = await askForProposal(page, 'stale after edit', 'browser-stale-1');
    await page.locator('#btn-modify').click();
    await page.locator('#btn-add-node').click();
    await page.locator('#node-editor input[name="name"]').fill('Manual revision');
    await page.locator('#node-editor button[type="submit"]').click();
    await afterEdit.getByRole('button', { name: 'Apply proposal' }).evaluate(button => button.click());
    await expect(afterEdit.locator('.assistant-proposal-status')).toContainText('stale because the document changed');

    const afterUndo = await askForProposal(page, 'stale after undo', 'browser-stale-2');
    await page.locator('#btn-undo').click();
    await afterUndo.getByRole('button', { name: 'Apply proposal' }).evaluate(button => button.click());
    await expect(afterUndo.locator('.assistant-proposal-status')).toContainText('stale because the document changed');

    const afterRedo = await askForProposal(page, 'stale after redo', 'browser-stale-3');
    await page.locator('#btn-redo').click();
    await afterRedo.getByRole('button', { name: 'Apply proposal' }).evaluate(button => button.click());
    await expect(afterRedo.locator('.assistant-proposal-status')).toContainText('stale because the document changed');

    const afterSwitch = await askForProposal(page, 'stale after switch', 'browser-stale-4');
    await page.evaluate(() => window.ravenroot.openDocument({ name: 'assistant-second.graphml' }));
    await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().name))
      .toBe('assistant-second.graphml');
    await afterSwitch.getByRole('button', { name: 'Apply proposal' }).evaluate(button => button.click());
    await expect(afterSwitch.locator('.assistant-proposal-status')).toContainText('different open document');

    const afterReplace = await askForProposal(page, 'stale after replace', 'browser-stale-5');
    const replacement = await serializedGraphMl(page);
    await page.evaluate(xml => {
      window.ravenroot.replaceActiveDocumentFromText(xml, 'assistant-replacement.graphml');
    }, replacement);
    await expect.poll(() => page.evaluate(() => window.ravenroot.activeDocument().name))
      .toBe('assistant-replacement.graphml');
    await afterReplace.getByRole('button', { name: 'Apply proposal' }).evaluate(button => button.click());
    await expect(afterReplace.locator('.assistant-proposal-status')).toContainText('different open document');
  });

  test('JSON-looking prose stays inert and invalid or contradictory proposals fail atomically', async ({ page }) => {
    let turn = 0;
    await page.route(MESSAGES, async route => {
      const request = route.request().postDataJSON();
      turn += 1;
      if (turn === 1) {
        await route.fulfill({
          status: 200, contentType: 'application/json', body: JSON.stringify({
            text: '{"proposal":{"operations":[{"op":"delete-node"}]},"confirmed":true}',
            model: 'example-1', truncated: false,
          }),
        });
        return;
      }
      const operations = turn === 2 ? [
        { op: 'create-node', ref: 'valid-prefix', id: 'must-not-exist', behavior: 'template',
          properties: [{ name: 'template', value: 'never applied' }] },
        { op: 'delete-node', target: { existing: 'ghost' } },
      ] : [
        { op: 'update-edge', target: { existing: 'edge-start-dosomething' }, outcome: 'continue' },
        { op: 'delete-node', target: { existing: 'start' } },
      ];
      await route.fulfill({
        status: 200, contentType: 'application/json', body: JSON.stringify({
          text: 'Review this invalid proposal.', model: 'example-1', truncated: false,
          proposal: {
            version: 1, id: turn === 2 ? 'browser-partial-invalid' : 'browser-cascade-invalid',
            document: request.document, summary: 'Must fail atomically', operations,
          },
        }),
      });
    });
    await page.goto('/');
    await openAssistant(page);
    await withGraph(page);
    const before = await serializedGraphMl(page);

    await page.locator('#assistant-draft').fill('JSON-looking text');
    await page.locator('#assistant-send').click();
    await expect(page.locator('.assistant-turn--assistant .assistant-turn-text').last())
      .toContainText('"confirmed":true');
    await expect(page.locator('.assistant-proposal')).toHaveCount(0);
    expect(await serializedGraphMl(page)).toBe(before);

    const partial = await askForProposal(page, 'partially invalid', 'browser-partial-invalid');
    await expect(partial.getByRole('button', { name: 'Apply proposal' })).toBeDisabled();
    await expect(partial).toContainText("Unknown node 'ghost'");
    expect(await serializedGraphMl(page)).toBe(before);
    await expect(page.locator('#btn-undo')).toBeDisabled();

    const cascade = await askForProposal(page, 'contradictory cascade', 'browser-cascade-invalid');
    await expect(cascade.getByRole('button', { name: 'Apply proposal' })).toBeDisabled();
    await expect(cascade).toContainText('would also delete an edge changed by this proposal');
    expect(await serializedGraphMl(page)).toBe(before);
    await expect(page.locator('#btn-undo')).toBeDisabled();
  });
});

test.describe('when the service disappears mid-session', () => {
  test('says so in the transcript and settles into the named inert state', async ({ page }) => {
    await signedIn(page);
    await page.route(MESSAGES, route => route.fulfill({ status: 404, body: '' }));
    await page.goto('/');
    await openAssistant(page);
    await withGraph(page);
    await page.locator('#assistant-draft').fill('anything');
    await page.locator('#assistant-send').click();

    // A notice, not a fabricated answer.
    await expect(page.locator('.assistant-turn--notice')).toContainText('was not sent');
    await expect(page.locator('.assistant-turn--assistant')).toHaveCount(0);
    await expect(page.locator('#assistant-state')).toHaveAttribute('data-state', 'inert');

    // The composer has just been disabled underneath the user. Focus must have been handed to the
    // transcript — where that notice is — rather than dropped to the document body.
    const landed = await page.evaluate(() => document.activeElement?.id || document.activeElement?.tagName);
    expect(landed).toBe('assistant-transcript');
  });
});

test.describe('truthful assistant failure provenance', () => {
  test('distinguishes pre-egress input rejection from a post-egress invalid model proposal', async ({ page }) => {
    await signedIn(page);
    let requestCount = 0;
    await page.route(MESSAGES, async route => {
      requestCount += 1;
      const postEgress = requestCount === 2;
      const assistantReason = postEgress
        ? 'ASSISTANT_MODEL_PROPOSAL_INVALID'
        : 'ASSISTANT_INVALID_TURN';
      await route.fulfill({
        status: postEgress ? 409 : 400,
        contentType: 'application/json',
        body: JSON.stringify({
          contract: 'ravenroot.error/1',
          code: postEgress ? 'CONFLICT' : 'INVALID_REQUEST',
          message: postEgress ? 'raw prompt canary' : 'FIXTURE: generic assistant failure',
          error: postEgress ? 'raw tool arguments canary' : 'FIXTURE: generic assistant failure',
          assistantReason,
          correlationId: postEgress ? 'post-egress-ref' : 'pre-egress-ref',
        }),
      });
    });
    await page.goto('/');
    await openAssistant(page);
    await withGraph(page);

    await page.locator('#assistant-draft').fill('pre-egress fixture');
    await page.locator('#assistant-send').click();
    const notices = page.locator('.assistant-turn--notice .assistant-turn-text');
    await expect(notices).toHaveCount(1);
    await expect(notices.first()).toContainText('nothing was sent to the model');
    await expect(notices.first()).toContainText('pre-egress-ref');

    // A failed turn deliberately leaves the composer in its error state. Start a fresh panel
    // session so this assertion measures the second wire failure rather than bypassing that guard.
    await page.reload();
    await openAssistant(page);
    await withGraph(page);
    await page.locator('#assistant-draft').fill('add a log node');
    await page.locator('#assistant-send').click();
    await expect(notices).toHaveCount(1);
    await expect(notices.last()).toContainText('reached the model');
    await expect(notices.last()).toContainText('Nothing was applied');
    await expect(notices.last()).not.toContainText('nothing was sent to the model');
    await expect(notices.last()).toContainText('post-egress-ref');
    await expect(notices.last()).not.toContainText('raw prompt canary');
    await expect(notices.last()).not.toContainText('raw tool arguments canary');
    await expect(page.locator('.assistant-turn--assistant')).toHaveCount(0);
    await expect(page.locator('.assistant-proposal')).toHaveCount(0);
  });
});

// ── CROSS-DOCUMENT PROVENANCE ────────────────────────────────────────────────────────────────────
//
// The `events` tail lives on the DOCUMENT RECORD, not in a module-level buffer. This is the test
// A workspace-scoped buffer filtered only at write time would let File → New leave the chip reading
// "Event stream: attached" while
// carrying the previous graph's executionIds.
//
// It matters more than an ordinary state bug because `attachmentClaimViolations` cannot see it:
// that control constrains PRESENCE (chip says attached ⇔ payload carries the class), not
// PROVENANCE (the class is about THIS graph). It is a false-context defect invisible to the
// presence control — in a payload destined for a third-party model.
test.describe('the event tail belongs to its document', () => {
  const EXECUTION = 'exec-alpha-0001';

  // The runtime client reconnects to `/v1/events` after each stream ends, so a static SSE body is
  // re-delivered every second or so. That is what lets the events land AFTER the execution binding
  // exists, without needing a progressively streamed fixture.
  const withRun = async page => {
    await page.route('**/v1/executions**', route => route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ executionId: EXECUTION, graphVersion: null }),
    }));
    await page.route('**/v1/events**', route => route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      body: [
        `event: execution\ndata: ${JSON.stringify({ type: 'NODE_STARTED', executionId: EXECUTION, nodeId: 'alpha-node', occurredAt: new Date().toISOString(), detail: 'alpha detail' })}\n\n`,
        `event: execution\ndata: ${JSON.stringify({ type: 'EXECUTION_COMPLETED', executionId: EXECUTION, occurredAt: new Date().toISOString(), detail: 'alpha done' })}\n\n`,
      ].join(''),
    }));
  };

  const eventsChip = page => page.locator('#assistant-chips [data-context-class="events"]');
  const payloadText = async page => {
    await page.locator('#assistant-payload-toggle').click();
    const text = await page.locator('#assistant-payload').textContent();
    await page.locator('#assistant-payload-toggle').click();
    return text;
  };

  test('attaches document A events to A, and never carries them into document B', async ({ page }) => {
    await signedIn(page);
    await withRun(page);
    await page.goto('/');
    await withGraph(page);
    await openAssistant(page);

    // Bind a run to document A and let the reconnecting stream deliver its events into it.
    await page.locator('#btn-play').click();
    await expect(page.locator('#activity-log .activity-entry')).not.toHaveCount(0, { timeout: 15_000 });
    await expect(eventsChip(page)).toHaveAttribute('data-state', 'attached', { timeout: 15_000 });

    // CONTROL FIRST: the assertion below is worthless unless this state is reachable at all.
    const attachedPayload = await payloadText(page);
    expect(attachedPayload).toContain(EXECUTION);

    // ── R-7's CALL SITE, PINNED HERE AND NOWHERE ELSE ─────────────────────────────────────────
    //
    // The unit tests pin `runtimeEventProjection` — given an event, it drops `detail`. NOTHING
    // pinned that `handleRuntimeEvent` actually calls it. Reverting the wiring to the previous
    // inline object left 688/688 unit and 28/28 assistant e2e green with the leak fully restored,
    // which is my own argument about R-6 turned back on me: correct-but-unpinned is the bad
    // combination, because the failure mode is a reasonable edit rather than a bug.
    //
    // This is the assertion that pins it, and it belongs on DOCUMENT A's payload specifically.
    // The `not.toContain('alpha detail')` further down runs after the switch to document B, where
    // the events class is unavailable entirely — so it passes whether or not the projection is
    // applied, and cannot see this. Here the class IS attached and the fixture's `detail` IS in
    // the event, so the only reason the string is absent is that the projection ran.
    expect(attachedPayload).toContain('alpha-node');
    expect(attachedPayload, 'the event projection was bypassed — detail reached the payload')
      .not.toContain('alpha detail');

    // Now a second document, which is what File → New gives the author.
    await page.locator('#btn-new').click();
    await page.waitForTimeout(600);

    // The chip must stop claiming an event stream that describes a different graph...
    await expect(eventsChip(page)).toHaveAttribute('data-state', 'unavailable');
    await expect(eventsChip(page)).toContainText('nothing to attach yet');

    // ...and A's execution must appear nowhere in what B would send.
    const payload = await payloadText(page);
    expect(payload).not.toContain(EXECUTION);
    expect(payload).not.toContain('alpha-node');
    expect(payload).not.toContain('alpha detail');
  });
});

// ── THE ARTICLE 50 DISCLOSURE ─────────────────────────────────────────────────────────────
//
// The unit tests own the ordering rule as a property of the capability. These own the only thing
// they cannot: that the rule survives the trip through the real renderer into the real DOM, which
// is where the person the Regulation protects actually reads it.
test.describe('the artificial origin of model output is disclosed', () => {
  const ask = async (page, text) => {
    await page.locator('#assistant-draft').fill(text);
    await page.locator('#assistant-send').click();
    await page.waitForTimeout(250);
  };

  // Art. 50(5): "at the latest at the time of the first interaction". Asserted as DOM ORDER,
  // because that is what the reader experiences — a disclosure that renders after the reply, or
  // renders somewhere the reply does not point at, has not met the timing requirement whatever the
  // transcript array says.
  test('renders the disclosure ahead of the first reply, never after it', async ({ page }) => {
    await signedIn(page);
    await replies(page);
    await page.goto('/');
    await openAssistant(page);
    await withGraph(page);

    // The transcript is empty and no disclosure exists yet — there has been no exposure to
    // disclose. Establishing this first is what makes the assertion after the reply meaningful.
    await expect(page.locator('#assistant-disclosure')).toHaveCount(0);

    await ask(page, 'what is in this graph?');

    const disclosure = page.locator('#assistant-disclosure');
    await expect(disclosure).toHaveCount(1);
    await expect(disclosure).toContainText('generated by an AI model');

    // Positional, not merely present: every AI-origin turn comes after it.
    const order = await page.evaluate(() => {
      const children = [...document.querySelectorAll('#assistant-transcript > *')];
      return {
        disclosure: children.findIndex(node => node.id === 'assistant-disclosure'),
        firstReply: children.findIndex(node =>
          node.classList.contains('assistant-turn--assistant')),
      };
    });
    expect(order.disclosure).toBeGreaterThanOrEqual(0);
    expect(order.disclosure).toBeLessThan(order.firstReply);
  });

  // Art. 50(5) again: "conform to the applicable accessibility requirements". Adjacency is not
  // association — a screen-reader user arriving at a reply must get the disclosure WITH it.
  test('associates each reply with the disclosure programmatically', async ({ page }) => {
    await signedIn(page);
    await replies(page);
    await page.goto('/');
    await openAssistant(page);
    await withGraph(page);
    await ask(page, 'first');
    await ask(page, 'second');

    const wiring = await page.evaluate(() => {
      const children = [...document.querySelectorAll('#assistant-transcript > *')];
      const described = node => node.getAttribute('aria-describedby');
      return {
        replies: children.filter(node => node.classList.contains('assistant-turn--assistant'))
          .map(described),
        authors: children.filter(node => node.classList.contains('assistant-turn--author'))
          .map(described),
        // The id must be unique or `aria-describedby` resolves to whichever came first.
        ids: children.filter(node => node.id === 'assistant-disclosure').length,
        // And it must actually resolve to an element that exists.
        resolves: Boolean(document.getElementById('assistant-disclosure')),
      };
    });

    expect(wiring.replies).toEqual(['assistant-disclosure', 'assistant-disclosure']);
    // The user's own words are not AI output and must not be labelled as such.
    expect(wiring.authors).toEqual([null, null]);
    expect(wiring.ids).toBe(1);
    expect(wiring.resolves).toBe(true);
  });

  // The disclosure lands in the transcript, which is `aria-live="polite"` and `role="log"`. It must
  // not have an assertive region of its own, and it must not move focus. Both rules apply to every
  // arriving turn, including the disclosure.
  test('announces politely and leaves focus where the user put it', async ({ page }) => {
    await signedIn(page);
    await replies(page);
    await page.goto('/');
    await openAssistant(page);
    await withGraph(page);

    await page.locator('#assistant-draft').fill('why is this slow?');
    await page.locator('#assistant-draft').focus();
    await page.locator('#assistant-send').click();
    await page.waitForTimeout(400);

    expect(await page.evaluate(() => document.activeElement?.id)).toBe('assistant-draft');
    const region = page.locator('#assistant-transcript');
    await expect(region).toHaveAttribute('aria-live', 'polite');
    // No region of its own: it inherits the transcript's polite one. `role="alert"` here would
    // interrupt a screen-reader user mid-sentence at the exact moment they are least expecting it.
    expect(await page.locator('#assistant-disclosure').getAttribute('role')).toBeNull();
    expect(await page.locator('#assistant-disclosure').getAttribute('aria-live')).toBeNull();
  });

  // Present before any interaction at all, which is stronger than "at the latest".
  test('states the AI origin in the composer before a question is even typed', async ({ page }) => {
    await page.goto('/');
    await openAssistant(page);

    await expect(page.locator('#assistant-composer-help'))
      .toContainText('Replies are generated by an AI model');
  });

  // THE DOM HALF OF THE CAP EXCEPTION, WHICH NOTHING ELSE DRIVES.
  //
  // `renderAssistantTurn` mirrors `appendTurn`'s exception: it trims the oldest child that is NOT
  // the disclosure. No other test in this suite pushes the transcript past its 200-turn limit, so
  // removing that exception — trimming `firstElementChild` unconditionally — leaves the whole unit
  // suite green while the disclosure element is silently deleted out from under every reply that
  // points at it. `aria-describedby` then resolves to nothing, which is the accessibility half of
  // the obligation failing without a single assertion noticing.
  test('keeps the disclosure element, and its id resolving, past the transcript cap', async ({ page }) => {
    await signedIn(page);
    await replies(page);
    await page.goto('/');
    await openAssistant(page);

    // Driven through the real composer — `requestSubmit` on the shipped form, not a private hook —
    // so what is exercised is the path a user takes. Each turn is awaited because `assistantBusy`
    // makes a submit during a request a no-op, and a fire-and-forget loop would silently send far
    // fewer than it thinks.
    const rendered = await page.evaluate(async asks => {
      const draft = document.getElementById('assistant-draft');
      const form = document.getElementById('assistant-composer');
      const log = document.getElementById('assistant-transcript');
      for (let index = 0; index < asks; index += 1) {
        const before = log.children.length;
        draft.value = `question ${index}`;
        form.requestSubmit();
        for (let spin = 0; spin < 400 && log.children.length < before + 2; spin += 1) {
          await new Promise(resolve => setTimeout(resolve, 5));
        }
      }
      return log.children.length;
    }, 105);

    // The cap actually fired — without this the assertions below would pass on an untrimmed log.
    expect(rendered).toBe(200);

    const survival = await page.evaluate(() => {
      const replyNodes = [...document.querySelectorAll('.assistant-turn--assistant')];
      return {
        disclosures: document.querySelectorAll('.assistant-turn--disclosure').length,
        resolves: Boolean(document.getElementById('assistant-disclosure')),
        // The whole point: not one reply may point at an id that no longer exists.
        dangling: replyNodes.filter(node =>
          !document.getElementById(node.getAttribute('aria-describedby'))).length,
        replies: replyNodes.length,
      };
    });

    expect(survival.disclosures).toBe(1);
    expect(survival.resolves).toBe(true);
    expect(survival.replies).toBeGreaterThan(0);
    expect(survival.dangling).toBe(0);
  });
});

// ── THE TRANSCRIPT IS INERT, AND THIS IS THE TEST THAT KEEPS IT INERT ───────────────────────────
//
// Proposal effectors are isolated from transcript text. The renderer itself must also never become
// an egress channel.
//
// Graph content is user-authored and reaches the model. If the transcript rendered markdown images,
// auto-linked bare URLs, or built any element with a `src`, injected graph content could make the
// model encode attached context into a URL — and the BROWSER would fetch it. That is egress from
// a path the JVM-wide guard cannot see: no tool call, no effector, no consent
// toggle consulted, and the user reading a friendly answer has no way to notice.
//
// ADR 0025 requires model output to be "inert escaped text". These tests assert that behaviour, and
// the network assertion is the load-bearing one: it would catch a
// fetch by ANY mechanism, including one nobody thought to enumerate.
test.describe('the transcript renderer cannot fetch', () => {
  const BEACON = 'http://127.0.0.1:9/exfil';

  // Every shape that turns text into a request if some renderer decides to be helpful.
  const HOSTILE_REPLY = [
    `![alt](${BEACON}/markdown-image.png)`,
    `<img src="${BEACON}/html-image.png">`,
    `<iframe src="${BEACON}/frame"></iframe>`,
    `<object data="${BEACON}/object"></object>`,
    `<svg><image href="${BEACON}/svg"/></svg>`,
    `[a markdown link](${BEACON}/md-link)`,
    `<a href="${BEACON}/anchor">click me</a>`,
    `Plain bare URL: ${BEACON}/bare`,
    `<style>body{background:url(${BEACON}/css)}</style>`,
    `<video src="${BEACON}/video"></video>`,
  ].join('\n\n');

  test('renders a hostile reply without issuing a single request', async ({ page }) => {
    const escaped = [];
    page.on('request', request => {
      if (request.url().includes('/exfil')) escaped.push(request.url());
    });
    await signedIn(page);
    await page.route(MESSAGES, route => route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ text: HOSTILE_REPLY, model: 'example-1' }),
    }));
    await page.goto('/');
    await openAssistant(page);
    await withGraph(page);
    await page.locator('#assistant-draft').fill('summarise this graph');
    await page.locator('#assistant-draft').press('Enter');
    await expect(page.locator('.assistant-turn--assistant')).toHaveCount(1);
    await page.waitForTimeout(900);

    // THE CLAIM: nothing was fetched.
    expect(escaped, `the transcript fetched: ${escaped.join(', ')}`).toEqual([]);
  });

  test('produces no element that could fetch and no anchor to click', async ({ page }) => {
    await signedIn(page);
    await page.route(MESSAGES, route => route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ text: HOSTILE_REPLY, model: 'example-1' }),
    }));
    await page.goto('/');
    await openAssistant(page);
    await withGraph(page);
    await page.locator('#assistant-draft').fill('summarise this graph');
    await page.locator('#assistant-draft').press('Enter');
    await expect(page.locator('.assistant-turn--assistant')).toHaveCount(1);

    const shape = await page.evaluate(() => {
      const turn = document.querySelector('.assistant-turn--assistant');
      return {
        // Any element at all beyond the plain structure the renderer builds.
        fetchers: turn.querySelectorAll('img, iframe, object, embed, video, audio, source, image, link, script, style').length,
        anchors: turn.querySelectorAll('a').length,
        // The whole subtree, so a nested or namespaced element cannot hide.
        elementNames: [...turn.querySelectorAll('*')].map(node => node.tagName.toLowerCase()),
        // The text is present and readable — inert must not mean swallowed.
        showsMarkup: turn.textContent.includes('<img src=') && turn.textContent.includes('!['),
      };
    });

    expect(shape.fetchers).toBe(0);
    expect(shape.anchors).toBe(0);
    // The renderer's own vocabulary and nothing else. A new tag appearing here is a regression
    // worth reading even if it is harmless, which is why this is an exact set rather than a count.
    expect([...new Set(shape.elementNames)].sort()).toEqual(['div']);
    // Escaped, not stripped: the author still sees exactly what the model said.
    expect(shape.showsMarkup).toBe(true);
  });

  test('renders the same way when the text arrives through a notice turn', async ({ page }) => {
    // The failure path builds turns too, and it renders server-supplied prose.
    await signedIn(page);
    await page.route(MESSAGES, route => route.fulfill({
      status: 500,
      contentType: 'application/json',
      body: JSON.stringify({
        contract: 'ravenroot.error/1', code: 'INTERNAL_ERROR',
        message: `<img src="${BEACON}/via-error">`, error: `<img src="${BEACON}/via-error">`,
        correlationId: 'abc123',
      }),
    }));
    const escaped = [];
    page.on('request', request => { if (request.url().includes('/exfil')) escaped.push(request.url()); });
    await page.goto('/');
    await openAssistant(page);
    await withGraph(page);
    await page.locator('#assistant-draft').fill('anything');
    await page.locator('#assistant-draft').press('Enter');
    await expect(page.locator('.assistant-turn--notice')).toHaveCount(1);
    await page.waitForTimeout(600);

    expect(escaped).toEqual([]);
    expect(await page.locator('.assistant-turn--notice img').count()).toBe(0);
  });
});

// ── THE CONNECTION ────────────────────────────────────────────────────────────────────────
//
// A device flow with no entry control is complete on the server but unreachable from the interface.
// Nothing here simulates the UI path: the control and route are real, and only the deployment on the
// other end of `page.route` is a fixture.
//
// Every case stubs the STATUS, because which reason the panel is in is what decides whether the
// control appears at all, and that distinction is most easily widened in the generous
// direction.
const CONNECTION = '**/v1/assistant/connection';

const deploymentSaying = (page, body) => page.route(STATUS, route => route.fulfill({
  status: 200, contentType: 'application/json', body: JSON.stringify(body),
}));

const NOT_CONNECTED = {
  configured: true, allowlisted: true, signedIn: true, linkRequired: true, provider: 'Example Model',
};

const connect = page => page.locator('[data-action="connect-assistant"]');

test.describe('when the author’s own connection is what is missing', () => {
  test('says so, and offers the control that resolves it', async ({ page }) => {
    await deploymentSaying(page, NOT_CONNECTED);
    await page.goto('/');
    await openAssistant(page);

    const state = page.locator('#assistant-state');
    await expect(state).toHaveAttribute('data-state', 'inert');
    // NOT the Ravenroot session sentence. Returning it for this deployment would tell an author who
    // is already signed in to Ravenroot to sign in to Ravenroot.
    await expect(state).not.toContainText('Ravenroot session');
    await expect(connect(page)).toBeVisible();
    await expect(page.locator('#assistant-draft')).toBeDisabled();
  });

  test('shows the code and the address, and asks the deployment whether it has been used',
    async ({ page }) => {
      await deploymentSaying(page, NOT_CONNECTED);
      await page.route(CONNECTION, route => route.request().method() === 'POST'
        ? route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            userCode: 'WDJB-MJHT',
            verificationUri: 'https://provider.example/device',
            verificationUriComplete: null,
            interval: 1,
            expiresIn: 900,
          }),
        })
        : route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ state: 'waiting', reason: 'AUTHORIZATION_PENDING' }),
        }));
      await page.goto('/');
      await openAssistant(page);

      await connect(page).click();

      await expect(page.locator('#assistant-connection-code')).toHaveText('WDJB-MJHT');
      await expect(page.locator('#assistant-connection-uri'))
        .toHaveText('https://provider.example/device');
      await expect(page.locator('#assistant-connection-uri'))
        .toHaveAttribute('href', 'https://provider.example/device');
      await expect(page.locator('#assistant-connection-progress'))
        .toContainText('Waiting for you to finish');
    });

  // Each of these three outcomes ends the wait differently, and the panel must say which.
  for (const [reason, sentence] of [
    ['ACCESS_DENIED', 'declined on the provider'],
    ['EXPIRED_TOKEN', 'ran out before it was used'],
    ['SLOW_DOWN', 'asked for fewer checks'],
  ]) {
    test(`reports ${reason} in its own words`, async ({ page }) => {
      await deploymentSaying(page, NOT_CONNECTED);
      await page.route(CONNECTION, route => route.request().method() === 'POST'
        ? route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            userCode: 'WDJB-MJHT',
            verificationUri: 'https://provider.example/device',
            interval: 1,
            expiresIn: 900,
          }),
        })
        : route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ state: 'waiting', reason, retryAfter: 1 }),
        }));
      await page.goto('/');
      await openAssistant(page);

      await connect(page).click();

      await expect(page.locator('#assistant-connection-progress')).toContainText(sentence);
    });
  }

  // A click that leads nowhere must SAY it led nowhere. Writing a start refusal into the step region,
  // which is hidden until a code arrives, makes the refusal look exactly like a button that did
  // nothing.
  test('says so when the deployment refuses to start one', async ({ page }) => {
    await deploymentSaying(page, NOT_CONNECTED);
    await page.route(CONNECTION, route => route.fulfill({
      status: 409,
      contentType: 'application/json',
      body: JSON.stringify({
        contract: 'API-01', code: 'CONFLICT',
        message: 'this deployment cannot begin a connection', correlationId: 'abc123',
      }),
    }));
    await page.goto('/');
    await openAssistant(page);

    await connect(page).click();

    const progress = page.locator('#assistant-connection-progress');
    await expect(progress).toBeVisible();
    await expect(progress).toContainText('abc123');
    await expect(page.locator('#assistant-connection-step')).toBeHidden();
    // And the control is still there to press again, rather than left disabled by a failed attempt.
    await expect(connect(page)).toBeEnabled();
  });

  // A hostile address is readable and not clickable. The verification address arrives over the
  // wire, and a wire value written into `href` unchecked is how `javascript:` reaches a click.
  test('renders an address it will not follow as text without a link', async ({ page }) => {
    await deploymentSaying(page, NOT_CONNECTED);
    await page.route(CONNECTION, route => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        userCode: 'WDJB-MJHT',
        // Not a typo: this is the value under test.
        verificationUri: 'javascript:alert(1)',
        interval: 1,
        expiresIn: 900,
      }),
    }));
    await page.goto('/');
    await openAssistant(page);

    await connect(page).click();

    const uri = page.locator('#assistant-connection-uri');
    await expect(uri).toHaveText('javascript:alert(1)');
    expect(await uri.getAttribute('href')).toBeNull();
  });
});

// ── AND THE HALF THAT IS EASY TO GET WRONG: WHEN IT MUST NOT APPEAR ──────────────────────────────
//
// Three of the other four inert reasons are an operator's to fix and the fourth is a Ravenroot
// session. A Connect control on any of them invites the author to perform an act that cannot
// resolve what is actually wrong, which is exactly what distinguishing the reasons was for.
test.describe('the connection control in the states that are not about connecting', () => {
  for (const [why, body] of [
    ['no provider is configured', { configured: false, allowlisted: false, signedIn: false }],
    ['the host is not allowlisted', { configured: true, allowlisted: false, signedIn: false }],
    ['the Ravenroot session is not authenticated',
      { configured: true, allowlisted: true, signedIn: false }],
  ]) {
    test(`is not offered when ${why}`, async ({ page }) => {
      await deploymentSaying(page, body);
      await page.goto('/');
      await openAssistant(page);

      await expect(page.locator('#assistant-state')).toHaveAttribute('data-state', 'inert');
      await expect(connect(page)).toBeHidden();
    });
  }

  test('is not offered when the deployment has no assistant service at all', async ({ page }) => {
    // No status stub: the fixture server 404s the route, which is this repository's real state.
    await page.goto('/');
    await openAssistant(page);

    await expect(page.locator('#assistant-state')).toHaveAttribute('data-state', 'inert');
    await expect(connect(page)).toBeHidden();
  });

  test('is not offered when the panel is working', async ({ page }) => {
    await signedIn(page);
    await page.goto('/');
    await withGraph(page);
    await openAssistant(page);

    await expect(page.locator('#assistant-state')).toHaveAttribute('data-state', /ready|degraded/);
    await expect(connect(page)).toBeHidden();
  });

  // The operator-key path remains the default: a status that says nothing
  // about connections must behave exactly as it did before the field existed.
  test('is not offered to a deployment that says nothing about connections', async ({ page }) => {
    await deploymentSaying(page, { configured: true, allowlisted: true, signedIn: true });
    await page.goto('/');
    await openAssistant(page);

    await expect(page.locator('#assistant-state')).toHaveAttribute('data-state', /ready|degraded/);
    await expect(connect(page)).toBeHidden();
  });

  // Nothing is asked of the connection route in a state that does not offer the control. A panel
  // that polled anyway would be starting an exchange nobody asked for.
  test('sends nothing to the connection route unless the author asks', async ({ page }) => {
    const attempts = [];
    page.on('request', request => {
      if (request.url().includes('/v1/assistant/connection')) attempts.push(request.method());
    });
    await signedIn(page);
    await page.goto('/');
    await openAssistant(page);
    await page.waitForTimeout(500);

    expect(attempts).toEqual([]);
  });
});
