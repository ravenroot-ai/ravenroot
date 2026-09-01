import { expect, test } from '@playwright/test';

// SEC-08 real-browser pins for the two facts test/graphml-namespace-encoding.test.js
// cannot establish on its own: that suite runs under vitest with `environment: 'jsdom'`
// (vitest.config.js), and jsdom is not the consumer this code ships to. The jsdom-derived suite and
// Playwright's Chromium disagree on 13 cases, including the two browser-specific facts pinned here.
// This file supplies the production-fidelity coverage required for that divergence:
//
// 1. The namespace evasion IS real in a browser: a GraphML namespace written with a single
// `&#x2f;` character reference resolves to the canonical GraphML namespace in Chromium's own
// DOMParser, so a document exploiting that spelling must still be rejected end to end, through
// the actual app, not just through the pre-DOM lexical guard in isolation.
// 2. An additional vector -- a whitespace-padded namespace
// URI -- is NOT an evasion in a browser. jsdom trims a namespace URI before binding it; real
// Chromium refuses the whole document as an invalid URI instead and never binds anything. If a
// future browser (or a future jsdom bump) starts trimming, this pin is what turns red, and
// `.trim()` in bindingNamespace (src/graph-parsers.js) stops being defence-in-depth and becomes
// the only thing standing between that spelling and a live evasion.

const CANONICAL = 'http://graphml.graphdrawing.org/xmlns';

function keyBomb(xmlnsLiteral, keys = 4_097) {
  return `<graphml xmlns="${xmlnsLiteral}">${'<key/>'.repeat(keys)}</graphml>`;
}

test('the &#x2f; character-reference evasion is real in Chromium and the app rejects it', async ({ page }) => {
  const evasion = keyBomb('http://graphml.graphdrawing.org&#x2f;xmlns');

  await page.goto('/');

  // Ground truth, established in THIS browser rather than assumed from the jsdom suite: the encoded
  // spelling resolves to the canonical namespace and every <key> is live in it.
  const domVerdict = await page.evaluate(xml => {
    const parsed = new DOMParser().parseFromString(xml, 'application/xml');
    return {
      error: Boolean(parsed.querySelector('parsererror')),
      namespace: parsed.documentElement?.namespaceURI ?? null,
      canonicalKeys: parsed.getElementsByTagNameNS(
        'http://graphml.graphdrawing.org/xmlns', 'key',
      ).length,
    };
  }, evasion);
  expect(domVerdict.error).toBe(false);
  expect(domVerdict.namespace).toBe(CANONICAL);
  expect(domVerdict.canonicalKeys).toBe(4_097);

  // Production path: the same document, fed through the real app the way a user would, in the same
  // browser. loadFileObj (src/app.js) reports a GraphInputRejection via `alert('Error: ' + message)`.
  // The app starts with its own default graph loaded, so "rejected" is shown by that graph staying
  // exactly as it was -- not by the canvas being empty.
  const elementsBefore = await page.evaluate(() => window.cy?.elements().length ?? 0);
  const dialogMessage = new Promise(resolve => {
    page.once('dialog', dialog => {
      resolve(dialog.message());
      dialog.accept();
    });
  });
  await page.locator('#file-inp').setInputFiles({
    name: 'evasion.graphml',
    mimeType: 'application/xml',
    buffer: Buffer.from(evasion),
  });
  await expect(dialogMessage).resolves.toContain('key count exceeds the configured limit');

  // Rejected before a graph was ever built from it: the 4,097-key bomb never replaced the existing
  // canvas, which is a stronger signal than an element count of zero would have been (the app never
  // starts empty, so zero would only prove the page failed to load at all).
  expect(await page.evaluate(() => window.cy?.elements().length ?? 0)).toBe(elementsBefore);
});

test('a whitespace-padded namespace URI is a parse error in Chromium, not a trim-and-bind', async ({ page }) => {
  await page.goto('/');

  const verdict = await page.evaluate(canonical => {
    const parsed = new DOMParser().parseFromString(
      `<graphml xmlns="${canonical} "><key/></graphml>`,
      'application/xml',
    );
    const parsererror = parsed.querySelector('parsererror');
    return {
      error: Boolean(parsererror),
      message: parsererror ? parsererror.textContent : null,
      namespace: parsererror ? null : parsed.documentElement.namespaceURI,
    };
  }, CANONICAL);

  // THE PIN. jsdom (test/graphml-namespace-encoding.test.js, 'trims the namespace the way jsdom
  // binds it') trims this same document's namespace and binds the canonical namespace. Chromium
  // does not: it refuses the document outright, before any namespace comparison -- ours or its own
  // -- ever runs. If this assertion goes red, a real evasion has opened and `.trim()` needs to be
  // treated as load-bearing rather than defence-in-depth.
  expect(verdict.error).toBe(true);
  expect(verdict.message).toContain('is not a valid URI');
  expect(verdict.namespace).toBeNull();
});
