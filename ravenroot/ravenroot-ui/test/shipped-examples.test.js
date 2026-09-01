import { readdirSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

import { validateWorkflow } from '../src/graph-document.js';
import { parseGraphML } from '../src/graph-parsers.js';

// The examples this product ships are a new user's first contact with it. Every GraphML file
// here must parse, must validate as an executable Ravenroot workflow, and must never name a
// behavior the running server does not register -- the exact defect that made ravenroot-minimal.graphml
// (yEd-style name/start/end-boolean keys) and the project's uppercase-text sample both fail or
// silently no-op on submission. ShippedExampleCorpusTest (ravenroot-server) asserts the stronger,
// server-side half of the same claim: that submission is actually admitted for execution, which
// static parsing here cannot observe (missing required properties, e.g., are only rejected at
// /v1/executions, not at parse time -- see
// ravenroot-server/src/test/resources/shipped-examples/agent-cycle.graphml).
//
// Kept in sync by hand with StandardBehaviorFactories.all() (ravenroot-core). There is no import
// across the JVM/JS boundary to derive this list from; a mismatch here is exactly the kind of
// "apparent inconsistency between two sources" the repository contribution contract asks to be resolved rather than guessed past.
//
// Listed in that method's own order, so a diff of the two reads side by side. This set is a deny-list
// over the shipped examples, so drift becomes visible only when an example uses the affected name.
// It includes the core behaviors `json-parse` and `delay`, and excludes bundle-supplied `llm-prompt`
// and `agent`. An over-broad list silently ADMITS an example that names a node the server no longer
// registers, producing the "silent empty success" this suite exists to refuse.
const REGISTERED_BEHAVIORS = new Set([
  'log', 'delay', 'template', 'json-parse', 'cel-transform', 'cel-decision',
  'http-request', 'json-path', 'program',
]);

const here = dirname(fileURLToPath(import.meta.url));
const EXAMPLES_DIR = resolve(here, '../public/examples');
// Discovered, not hand-listed: a hardcoded array would repeat the defect described in
// ShippedExampleCorpusTest's Javadoc -- a check that knows only a fixed set of filenames silently
// omits any newly added sibling example.
const EXAMPLES = readdirSync(EXAMPLES_DIR)
  .filter(name => name.endsWith('.graphml'))
  .map(name => resolve(EXAMPLES_DIR, name));

describe('shipped GraphML examples', () => {
  it('discovers at least two shipped examples', () => {
    expect(EXAMPLES.length).toBeGreaterThanOrEqual(2);
  });

  for (const path of EXAMPLES) {
    describe(path.replace(here + '/', ''), () => {
      const source = readFileSync(path, 'utf8');

      it('parses and validates as an executable Ravenroot workflow', () => {
        const graph = parseGraphML(source);
        expect(validateWorkflow(graph)).toEqual([]);
      });

      it('names only behaviors the standard catalog registers', () => {
        const graph = parseGraphML(source);
        const behaviors = graph.nodes
          .filter(node => node.kind === 'BEHAVIOR')
          .map(node => node.behavior);
        for (const behavior of behaviors) {
          expect(REGISTERED_BEHAVIORS.has(behavior),
            `'${behavior}' is not in the standard behavior catalog: ${[...REGISTERED_BEHAVIORS]}`)
            .toBe(true);
        }
      });
    });
  }
});
