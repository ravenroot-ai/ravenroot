// Turn a Playwright JSON report into the only comparable artifact a suite has: the
// sorted list of its test NAMES with each one's outcome.
//
// Counts are not comparable. "19 failed" in two runs can be two different sets of 19, and reading
// two equal counts as one result is how four measures of this suite were reconciled wrongly on
// 2026-08-26 and again on 2026-08-28. Names are comparable, so names are what this emits.
//
// Output, one line per test, sorted, tab-separated:
//   <outcome>\t<file>:<line>\t<full title path>
//
// The title path is deliberately included alongside file:line. Line numbers move whenever anything
// above a test is edited, so a set diffed by line alone reports churn as instability; the title is
// what identifies the same test across commits. Both are printed so a reader can jump to it and
// still recognise it after it moves. `docs/qa/end-to-end-and-accessibility.md` §9 already had to
// match a test "by title here rather than by line number" for exactly this reason.
//
// Usage: node scripts/e2e_report_names.mjs <playwright-json-report>
import { readFileSync } from 'node:fs';

const [reportPath] = process.argv.slice(2);
if (!reportPath) {
  process.stderr.write('usage: node scripts/e2e_report_names.mjs <playwright-json-report>\n');
  process.exit(2);
}

const report = JSON.parse(readFileSync(reportPath, 'utf8'));

// Playwright's own vocabulary is about expectation, not colour: a test annotated `fixme`/`fail`
// that fails is `expected`. Translating here keeps the rest of the pipeline reading in outcomes.
const OUTCOME = {
  expected: 'passed',
  unexpected: 'failed',
  flaky: 'flaky',
  skipped: 'skipped',
};

const lines = [];

const walk = (suite, titles) => {
  // The outermost suite of a file carries the file path as its title; it is not part of the test's
  // name, and including it would duplicate the `file:line` column.
  const nested = suite.title && suite.file !== suite.title ? [...titles, suite.title] : titles;
  for (const spec of suite.specs ?? []) {
    const file = spec.file ?? suite.file ?? '';
    const where = `${file}:${spec.line ?? 0}`;
    const name = [...nested, spec.title].join(' › ');
    for (const test of spec.tests ?? []) {
      lines.push(`${OUTCOME[test.status] ?? test.status ?? 'unknown'}\t${where}\t${name}`);
    }
    if (!(spec.tests ?? []).length) lines.push(`unknown\t${where}\t${name}`);
  }
  for (const child of suite.suites ?? []) walk(child, nested);
};

for (const suite of report.suites ?? []) walk(suite, []);

// Sorted, because a run's order is not a property of the suite: `fullyParallel: false, workers: 1`
// makes it stable today, and a diff that depends on that would break the day it is not.
lines.sort();
process.stdout.write(lines.length ? `${lines.join('\n')}\n` : '');
