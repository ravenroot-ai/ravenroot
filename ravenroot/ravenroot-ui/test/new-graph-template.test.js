import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

import {
  additionalProperties, createWorkflowDocument, serializeGraphML, validateWorkflow,
} from '../src/graph-document.js';
import { parseGraphML } from '../src/graph-parsers.js';

// The graph a user gets by pressing "New" is as much a first-contact artifact as the shipped
// example files -- it is what someone sees the moment they start authoring instead of opening one.
// It must be a valid, executable Ravenroot workflow, and its shape must not drift silently: the
// fixture below is a snapshot of the real output, regenerated and compared on every run rather than
// asserted by re-deriving the expected shape from the same code under test.
const FIXTURE = readFileSync(
  resolve(dirname(fileURLToPath(import.meta.url)), 'fixtures/new-graph-template.graphml'), 'utf8');

// additionalProperties() rather than raw .properties: a freshly created (never-parsed) document
// keeps canonical fields (kind, name, layout...) only as top-level fields and leaves .properties
// empty, while parseGraphML folds every <data> -- canonical or not -- into .properties too. Both
// are correct for their own origin; comparing raw .properties would only prove the two
// representations disagree about where canonical data lives, not that the graphs differ.
function normalizedModel(graph) {
  return {
    nodes: graph.nodes.map(node => ({
      id: node.id, kind: node.kind, behavior: node.behavior, name: node.name,
      custom: additionalProperties(node, 'node'),
    })),
    edges: graph.edges.map(edge => ({
      id: edge.id, source: edge.source, target: edge.target, outcome: edge.outcome,
      custom: additionalProperties(edge, 'edge'),
    })),
  };
}

describe('editor new-graph template', () => {
  it('is a valid, executable Ravenroot workflow with no catalog-absent behaviors', () => {
    const graph = createWorkflowDocument();
    expect(validateWorkflow(graph)).toEqual([]);
    // The blank template has no BEHAVIOR nodes at all, so there is nothing to name against the
    // catalog -- asserted explicitly so a future change to the template cannot add one silently
    // without this test noticing there is now something to check.
    expect(graph.nodes.filter(node => node.kind === 'BEHAVIOR')).toHaveLength(0);
  });

  it('round-trips through the UI parser back to an equivalent, still-valid document', () => {
    const graph = createWorkflowDocument();
    const serialized = serializeGraphML(graph);
    const reparsed = parseGraphML(serialized);
    expect(validateWorkflow(reparsed)).toEqual([]);
    expect(normalizedModel(reparsed)).toEqual(normalizedModel(graph));
  });

  it('matches the pinned snapshot shared with the core-side corpus check', () => {
    const generated = serializeGraphML(createWorkflowDocument());
    expect(normalizedModel(parseGraphML(generated))).toEqual(normalizedModel(parseGraphML(FIXTURE)));
  });
});
