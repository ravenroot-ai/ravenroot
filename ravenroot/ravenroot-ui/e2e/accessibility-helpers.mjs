// Shared plumbing for the accessibility baseline (QA-04c).
//
// WCAG 2.1 AA is the target standard and `@axe-core/playwright` is the tool, both decided in
// `docs/qa/end-to-end-and-accessibility.md` ("Accessibility: WCAG 2.1 AA, report-only, explicitly
// secondary") and not open for re-litigation here. The one constraint every helper in this file
// exists to uphold: **a violation is reported, never asserted into a failure.** No function below
// throws or returns a boolean pass/fail — each returns data, and the specs that call them attach
// that data to the Playwright report and print a compact summary. A future gating decision reads
// those numbers; this file must never turn one into an exit code.

import AxeBuilder from '@axe-core/playwright';

// WCAG 2.1 AA per the decision above. 2.1 supersets 2.0, so the 2.0 tags are included for axe-core's
// own rule mapping rather than as an independent target.
export const WCAG_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];

/**
 * Runs an axe-core scan against `page` (or a subtree of it) and returns the raw axe results object
 * untouched. Callers decide what, if anything, to assert — this function never does.
 *
 * @param {import('@playwright/test').Page} page
 * @param {{ include?: string[], tags?: string[], disableRules?: string[] }} [options]
 */
export async function scanForViolations(page, { include, tags = WCAG_TAGS, disableRules = [] } = {}) {
  let builder = new AxeBuilder({ page }).withTags(tags);
  if (include) builder = builder.include(include);
  if (disableRules.length) builder = builder.disableRules(disableRules);
  return builder.analyze();
}

// Reduces a raw axe result set to the numbers the baseline actually needs: how many rule violations,
// how many affected DOM nodes, broken down by impact, plus a per-rule table for the detail a reader
// needs to act on any one of them without re-running the scan.
export function summarizeViolations(results) {
  const byImpact = { critical: 0, serious: 0, moderate: 0, minor: 0 };
  let totalNodes = 0;
  const rules = results.violations.map(violation => {
    const impact = violation.impact || 'minor';
    byImpact[impact] = (byImpact[impact] || 0) + violation.nodes.length;
    totalNodes += violation.nodes.length;
    return {
      id: violation.id,
      impact,
      help: violation.help,
      helpUrl: violation.helpUrl,
      nodes: violation.nodes.length,
      tags: violation.tags,
    };
  });
  return {
    url: results.url,
    violationRules: results.violations.length,
    violationNodes: totalNodes,
    byImpact,
    rules,
  };
}

// One line per scan, machine-parseable-enough to grep out of CI or local logs without opening the
// HTML report — the report-only baseline's audit trail.
export function logSummary(label, summary) {
  // eslint-disable-next-line no-console
  console.log(`[axe] ${label}: ${JSON.stringify(summary)}`);
}
