import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import { createNode, createWorkflowDocument, serializeGraphML, validateWorkflow } from '../src/graph-document.js';
import { connectNodes, insertNodeElement, reconnectEdge, updateEdgeFields } from '../src/graph-editing.js';
import { parseGraphML } from '../src/graph-parsers.js';

// UI-02 requires an exported fixture the server can re-execute. This builds one
// through exactly the editing entry points the UI uses — no hand-written XML — and pins the result
// as a golden file, so the fixture cannot drift away from what the editor actually produces.
//
// Regenerate deliberately with: UPDATE_FIXTURES=1 npm test
//
// The fixture is also accepted by the JVM side, which is the point of exporting one. The node
// and edge counts below (nodes=6 edges=6) were derived from this file's own topology. Re-verify
// against the Java CLI with:
// mvn -o -q -pl ravenroot-cli -am compile -DskipTests
// java -cp "ravenroot-cli/target/classes:$(cat cp.txt)" ai.ravenroot.cli.RavenrootCliMain \
// inspect ravenroot-ui/test/fixtures/edge-authoring.graphml
// -> expected: nodes=6 edges=6 start-nodes=1 end-nodes=1 error-nodes=1
const FIXTURE = resolve(dirname(fileURLToPath(import.meta.url)), 'fixtures/edge-authoring.graphml');

function authorWorkflow() {
  const graph = createWorkflowDocument();
  insertNodeElement(graph, createNode('review', 'Review', 'PASSTHROUGH', { x: 480, y: 340 }));
  insertNodeElement(graph, createNode('archive', 'Archive', 'PASSTHROUGH', { x: 480, y: 460 }));

  // The starting document is start ──> dosomething ──> {end, error}, not start ──> end.
  // Move dosomething's success edge (to end) onto Review, keeping its id, then fan out to End and
  // to Archive so the fixture exercises a branch as well as a chain. dosomething's other edge (to
  // Error, outcome `failed`) is left untouched, so the template's error branch survives into this
  // authored fixture too.
  reconnectEdge(graph, 'edge-dosomething-end', 'target', 'review');

  const approved = connectNodes(graph, 'review', 'end');
  updateEdgeFields(graph, approved.id, {
    outcome: 'approved',
    label: 'approved',
    edgeName: 'Approval branch',
    description: 'Taken when the reviewer approves',
    status: 200,
    properties: { slaMinutes: '30' },
    propertyTypes: { slaMinutes: 'int' },
  });

  const rejected = connectNodes(graph, 'review', 'archive');
  updateEdgeFields(graph, rejected.id, {
    outcome: 'rejected',
    label: 'rejected',
    edgeName: 'Rejection branch',
    parallel: true,
    trafficWeight: 0.25,
  });
  connectNodes(graph, 'archive', 'end');
  return graph;
}

describe('the editor exports a workflow the runtime can accept', () => {
  it('produces the committed fixture from the editing entry points alone', () => {
    const xml = serializeGraphML(authorWorkflow());

    if (process.env.UPDATE_FIXTURES) {
      mkdirSync(dirname(FIXTURE), { recursive: true });
      writeFileSync(FIXTURE, xml, 'utf8');
    }

    expect(existsSync(FIXTURE), `${FIXTURE} is missing; regenerate with UPDATE_FIXTURES=1`).toBe(true);
    expect(xml).toBe(readFileSync(FIXTURE, 'utf8'));
  });

  it('is a valid Ravenroot workflow with no violations', () => {
    const fixture = parseGraphML(readFileSync(FIXTURE, 'utf8'));

    expect(validateWorkflow(fixture)).toEqual([]);
    expect(fixture.nodes.map(node => node.id).sort())
      .toEqual(['archive', 'dosomething', 'end', 'error', 'review', 'start']);
    // Every edge resolves to a node that exists, which is the reference check the runtime repeats.
    const ids = new Set(fixture.nodes.map(node => node.id));
    for (const edge of fixture.edges) {
      expect(ids.has(edge.source), `${edge.id} source`).toBe(true);
      expect(ids.has(edge.target), `${edge.id} target`).toBe(true);
      expect(String(edge.outcome || '').trim()).not.toBe('');
    }
  });

  it('keeps the reconnected edge, its id and its branch properties through a reparse', () => {
    const fixture = parseGraphML(readFileSync(FIXTURE, 'utf8'));

    // The edge the fixture reconnected kept the id it was created with.
    expect(fixture.edges.find(edge => edge.id === 'edge-dosomething-end'))
      .toMatchObject({ source: 'dosomething', target: 'review' });
    // dosomething's other outgoing edge (to the ERROR terminal) was never touched.
    expect(fixture.edges.find(edge => edge.id === 'edge-dosomething-error'))
      .toMatchObject({ source: 'dosomething', target: 'error', outcome: 'failed' });
    expect(fixture.edges.find(edge => edge.outcome === 'approved')).toMatchObject({
      source: 'review', target: 'end', edgeName: 'Approval branch', status: 200,
    });
    expect(fixture.edges.find(edge => edge.outcome === 'approved').properties.slaMinutes).toBe('30');
    expect(fixture.edges.find(edge => edge.outcome === 'rejected')).toMatchObject({
      source: 'review', target: 'archive', parallel: true, trafficWeight: 0.25,
    });
  });

  it('is stable: serializing the reparsed fixture reproduces it byte for byte', () => {
    const original = readFileSync(FIXTURE, 'utf8');

    expect(serializeGraphML(parseGraphML(original))).toBe(original);
  });
});
