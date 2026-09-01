import { expect, test } from '@playwright/test';

import { logSummary, scanForViolations, summarizeViolations } from './accessibility-helpers.mjs';

// Accessibility baseline (QA-04c). Standard, tool and the report-only rule are all decided in
// `docs/qa/end-to-end-and-accessibility.md` ("Accessibility: WCAG 2.1 AA, report-only, explicitly
// secondary") — this file measures against that decision, it does not revisit it.
//
// ── WHY THESE SIX, AND WHY NONE OF THEM CAN FAIL THE BUILD ───────────────────────────────────────
//
// "Report-only" is not a note in the description, it is the shape of every assertion in every one of
// the six tests below. The public QA contract is unqualified — "It never fails a build. Report-only" —
// not "the four axe scans never fail a build". The two keyboard/focus PROBES must therefore report
// their findings as data: a redrawn focus ring or a newly unreachable tab stop must not fail
// `ui-e2e-test` on the kind of accessibility regression this report-only contract exists to expose
// without making it a gate. Grep this file for `violationRules`,
// `.length` or `.toBe(true)`/`.toBe(false)` driven by a measured accessibility property inside an
// `expect(...)` and you will not find one: every test below asserts only that its probe or scan RAN
// and returned well-formed data — the setup assertions that confirm the probe measured the right
// state (`toBeVisible`, `toBeFocused`) are the one exception, kept because a probe that silently
// measured the wrong state would produce a false number, which is worse than no number.
//
// The `keyboard:` and `focus:` tests are ordinary Playwright probes, not axe scans — they cover what
// axe's static DOM analysis cannot: whether Tab order is sane and whether a keyboard user can SEE
// where focus is. Automated tools miss both; these smoke specs complement rather than replace the
// axe coverage below them. Their findings are data, exactly like axe's:
// attached per-run, logged via `logSummary`, and folded into the baseline table in
// `docs/qa/end-to-end-and-accessibility.md` — a future gating decision reads them from there, same
// as the axe numbers.

test.describe('accessibility baseline (WCAG 2.1 AA, axe-core, report-only)', () => {
  // Measures 15 Tab stops from the document root: tag, id, computed bounding box and disabled state.
  // A `visible: true` predicate based on `display`/`visibility` alone is blind to exactly the failure
  // mode this probe exists to catch. `#file-inp` (stop 7 of
  // 15, `.visually-hidden`) passed that check: `display: block`, `visibility: visible`, and
  // `getClientRects().length > 0` are all true for a 1×1 box clipped to nothing, which is precisely
  // how the `.visually-hidden` technique works and precisely why it fooled a display/visibility-only
  // check. That element is independently one of the three `label` (critical) findings the axe scans
  // below report — recording box area here, not a boolean, is what lets a reader connect "reachable
  // by keyboard" with "1×1 and therefore invisible to a sighted keyboard user" without re-deriving it.
  test('keyboard: Tab from the document root — reachability and box size at each stop (report-only)', async ({ page }, testInfo) => {
    await page.goto('/');
    const visited = [];
    for (let i = 0; i < 15; i += 1) {
      await page.keyboard.press('Tab');
      // eslint-disable-next-line no-await-in-loop
      const info = await page.evaluate(() => {
        const el = document.activeElement;
        if (!el || el === document.body) return null;
        const style = getComputedStyle(el);
        const rect = el.getBoundingClientRect();
        return {
          tag: el.tagName,
          id: el.id || null,
          rect: { width: rect.width, height: rect.height, area: rect.width * rect.height },
          displayNone: style.display === 'none',
          visibilityHidden: style.visibility === 'hidden',
          disabled: el.disabled === true || el.getAttribute('aria-disabled') === 'true',
        };
      });
      if (info) visited.push(info);
    }
    const summary = {
      totalStops: visited.length,
      tinyBox: visited.filter(s => s.rect.area <= 1).length,
      displayNone: visited.filter(s => s.displayNone).length,
      visibilityHidden: visited.filter(s => s.visibilityHidden).length,
      disabled: visited.filter(s => s.disabled).length,
    };
    await testInfo.attach('keyboard-tab-order.json', {
      body: JSON.stringify({ summary, stops: visited }, null, 2),
      contentType: 'application/json',
    });
    logSummary('keyboard-tab-order', summary);
    // Report-only: the probe having collected at least one stop is the assertion, not any property
    // of what it found. `totalStops`, `tinyBox`, `displayNone`, `visibilityHidden` and `disabled` are
    // measurements for the baseline table, never gate conditions here.
    expect(visited.length).toBeGreaterThan(0);
  });

  // WCAG 2.4.7 (Focus Visible) does not mandate an outline specifically — it requires SOME visible
  // change when focus arrives. `#service-url` (`.command-field input`) sets `outline: none` and
  // signals focus only through a `border-color` change (`src/styles.css:216-217`), which is
  // legitimate but easy to miss with an outline-only check, so this compares the full computed style
  // snapshot before and after focus rather than assuming any one CSS property carries the signal.
  //
  // Report-only: asserting `changed.length > 0` per target would let a redrawn focus ring turn the
  // test red for reasons having nothing to do with correctness. Which properties changed for each of
  // the five targets is measurement data: attached, logged and folded into the baseline table, never
  // a gate.
  test('focus: keyboard-focused controls — visible-property change at each target (report-only)', async ({ page }, testInfo) => {
    await page.goto('/');
    const targets = ['#menu-file', '#btn-new', '#service-url', '#search-inp', '#btn-play'];
    const snapshot = el => {
      const style = getComputedStyle(el);
      return {
        outlineStyle: style.outlineStyle,
        outlineWidth: style.outlineWidth,
        outlineColor: style.outlineColor,
        boxShadow: style.boxShadow,
        borderColor: style.borderColor,
        backgroundColor: style.backgroundColor,
      };
    };
    const perTarget = [];
    for (const selector of targets) {
      const locator = page.locator(selector);
      // eslint-disable-next-line no-await-in-loop
      const blurred = await locator.evaluate(snapshot);
      // eslint-disable-next-line no-await-in-loop
      await locator.focus();
      // Setup assertion, kept: confirms the probe actually measured a focused element, not that the
      // page is accessible. If this fails, the measurement below would be about the wrong state.
      // eslint-disable-next-line no-await-in-loop
      await expect(locator).toBeFocused();
      // `#search-inp` signals focus through a `transition: border-color .15s` (`src/styles.css:804`).
      // Reading computed style in the same tick the pseudo-class flips catches the interpolation at
      // its start value, not its target — a settle wait is required for an honest comparison, not
      // an artifact of the assertion technique.
      // eslint-disable-next-line no-await-in-loop
      await page.waitForTimeout(250);
      // eslint-disable-next-line no-await-in-loop
      const focused = await locator.evaluate(snapshot);
      const changedProperties = Object.keys(blurred).filter(key => blurred[key] !== focused[key]);
      perTarget.push({ selector, changedProperties, blurred, focused });
    }
    const summary = {
      totalTargets: perTarget.length,
      targetsWithVisibleChange: perTarget.filter(t => t.changedProperties.length > 0).length,
    };
    await testInfo.attach('focus-indicator.json', {
      body: JSON.stringify({ summary, perTarget }, null, 2),
      contentType: 'application/json',
    });
    logSummary('focus-indicator', { ...summary, perTarget: perTarget.map(t => ({ selector: t.selector, changedProperties: t.changedProperties })) });
    // Report-only: the probe having measured all five targets is the assertion, not whether any of
    // them changed a visible property. `targetsWithVisibleChange` is a baseline number, not a gate.
    expect(perTarget.length).toBe(targets.length);
  });

  test('axe: initial application shell — WCAG 2.1 AA (report-only)', async ({ page }, testInfo) => {
    await page.goto('/');
    const results = await scanForViolations(page);
    const summary = summarizeViolations(results);
    await testInfo.attach('axe-initial-shell.json', {
      body: JSON.stringify({ ...summary, raw: results.violations }, null, 2),
      contentType: 'application/json',
    });
    logSummary('initial-shell', summary);
    // Report-only: the scan having produced a well-formed result is the assertion, not the count.
    expect(Array.isArray(results.violations)).toBe(true);
  });

  test('axe: application menu opened via keyboard — WCAG 2.1 AA (report-only)', async ({ page }, testInfo) => {
    await page.goto('/');
    await page.locator('#menu-file').focus();
    await page.keyboard.press('ArrowDown');
    await expect(page.locator('#application-menu')).toBeVisible();
    const results = await scanForViolations(page, { include: ['#application-menu'] });
    const summary = summarizeViolations(results);
    await testInfo.attach('axe-application-menu.json', {
      body: JSON.stringify({ ...summary, raw: results.violations }, null, 2),
      contentType: 'application/json',
    });
    logSummary('application-menu', summary);
    expect(Array.isArray(results.violations)).toBe(true);
  });

  // Scans the WHOLE page with the node editor open, rather than `include(['#node-editor'])`: that
  // scoped form was tried first and reproduces `axe-core`'s "No elements found for include in page
  // Context" here on every run (isolated outside Playwright Test too, with a plain
  // `browser.newContext()` — not a fixture artifact), even though the element itself is a completely
  // ordinary, singly-matched, on-screen, non-`aria-hidden` node
  // (`document.querySelectorAll('#node-editor')` returns exactly one). The two other scoped scans in
  // this file (`#application-menu`, `#topbar`) use `include()` successfully, so the failure is
  // specific to this dynamically-injected form's interaction with axe-core's context/visibility
  // resolution, not `include()` in general — a full-page scan sidesteps it and is arguably the more
  // honest measurement anyway, since WCAG applies to the rendered page as a whole. The subtree is
  // still isolated for the attached summary by filtering on the violation's DOM target.
  test('axe: node editor in the inspector — WCAG 2.1 AA (report-only)', async ({ page }, testInfo) => {
    await page.goto('/');
    await page.locator('#btn-new').click();
    await page.locator('#btn-modify').click();
    await page.locator('#btn-add-node').click();
    await expect(page.locator('#node-editor')).toBeVisible();
    const results = await scanForViolations(page);
    const summary = summarizeViolations(results);
    const editorTouching = results.violations.filter(v => v.nodes.some(n => n.target.some(t => t.includes('node-editor') || t.includes('editor-field'))));
    const editorSummary = summarizeViolations({ ...results, violations: editorTouching });
    await testInfo.attach('axe-node-editor.json', {
      body: JSON.stringify({ wholePage: summary, nodeEditorSubtree: editorSummary, raw: results.violations }, null, 2),
      contentType: 'application/json',
    });
    logSummary('node-editor (whole page, with editor open)', summary);
    logSummary('node-editor (subtree only)', editorSummary);
    expect(Array.isArray(results.violations)).toBe(true);
  });

  // The topbar hides `.layout-mirrors` (the dagre/elastic toolbar buttons) once it overflows —
  // `syncCommandBarDensity()` in `src/app.js`, `.density-hide-layout` in `src/styles.css`. This spec
  // MEASURES that state; it does not change the overflow behavior.
  //
  // A single derived `overflowed = scrollWidth > clientWidth` boolean measures
  // that erases itself. `syncCommandBarDensity()` hides `.layout-mirrors` (and other groups) UNTIL
  // `scrollWidth <= clientWidth`, so by the time this check runs, the density pass has already acted
  // and the boolean reads `false` — not because the topbar was never crowded, but because the
  // mitigation already fired. `false` here can only ever mean "no overflow, or overflow already
  // hidden by the app" — those are different facts a reader must not conflate. The full tuple below
  // — raw scrollWidth/clientWidth, whether `density-hide-layout` is applied, and the *computed*
  // display of `.layout-mirrors` — makes both cases distinguishable instead of collapsing them into
  // one flag.
  test('axe: color contrast, scoped — topbar in its default state (report-only)', async ({ page }, testInfo) => {
    await page.goto('/');
    const topbarState = await page.evaluate(() => {
      const topbar = document.getElementById('topbar');
      const mirrors = document.querySelector('.layout-mirrors');
      return {
        scrollWidth: topbar.scrollWidth,
        clientWidth: topbar.clientWidth,
        overflowedByWidth: topbar.scrollWidth > topbar.clientWidth,
        densityHideLayoutApplied: topbar.classList.contains('density-hide-layout'),
        layoutMirrorsDisplay: mirrors ? getComputedStyle(mirrors).display : null,
        // `.name` only, not the document object itself: `workspace.active` carries a live Cytoscape
        // instance (`document_.cy`), which is not the kind of thing `page.evaluate` should try to
        // serialize back across the CDP boundary.
        activeDocumentName: window.ravenroot?.activeDocument?.()?.name ?? null,
      };
    });
    const results = await scanForViolations(page, { include: ['#topbar'], tags: ['wcag2aa', 'wcag21aa'], disableRules: [] });
    const contrastOnly = results.violations.filter(v => v.id === 'color-contrast');
    const summary = summarizeViolations({ ...results, violations: contrastOnly });
    await testInfo.attach('axe-topbar-contrast.json', {
      body: JSON.stringify({ topbarState, ...summary, raw: contrastOnly }, null, 2),
      contentType: 'application/json',
    });
    logSummary('topbar-contrast', { topbarState, ...summary });
    expect(Array.isArray(results.violations)).toBe(true);
  });
});
