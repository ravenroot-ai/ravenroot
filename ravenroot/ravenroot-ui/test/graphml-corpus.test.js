import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it, vi } from 'vitest';

import { createNode, serializeGraphML } from '../src/graph-document.js';
import { parseGraphML, SYNTHESIZED_EDGE_ID_PREFIX } from '../src/graph-parsers.js';
import { nodeSemantics, xmlSemantics } from './xml-semantics.js';

// Deliberate cross-module coupling, not a bug: this suite and
// ravenroot-core/src/test/java/ai/ravenroot/core/graph/GraphMlCorpusTest.java share one fixture
// corpus (see its README.md) so the JS and Java GraphML implementations are checked against the same
// ambiguity contract. This only resolves when the sibling ravenroot-core module is present on disk,
// so this suite must run from a full repository checkout and never from a context that has copied
// only ravenroot-ui/.
const CORPUS = resolve('../ravenroot-core/src/test/resources/graphml-corpus');
const ACCEPTED = [
  'canonical-minimal.graphml',
  'scalar-types.graphml',
  'complex-extensions.graphml',
  'defaults-and-scopes.graphml',
  'topology.graphml',
  'optional-edge-ids.graphml',
];

function fixture(status, name) {
  return readFileSync(resolve(CORPUS, status, name), 'utf8');
}

describe('shared GraphML compatibility corpus', () => {
  for (const name of ACCEPTED) {
    it(`round-trips ${name} without changing XML semantics`, () => {
      const source = fixture('accepted', name);
      const parsed = parseGraphML(source);
      const serialized = serializeGraphML(parsed);
      const reparsed = parseGraphML(serialized);

      expect(xmlSemantics(serialized)).toBe(xmlSemantics(source));
      expect(normalizedModel(reparsed)).toEqual(normalizedModel(parsed));
    });
  }

  it('resolves same-name node and edge keys by scope and keeps defaults absent', () => {
    const source = fixture('accepted', 'defaults-and-scopes.graphml');
    const parsed = parseGraphML(source);

    expect(parsed.nodeMap.start.properties.owner).toBe('explicit-owner');
    expect(parsed.edges.find(edge => edge.id === 'explicit-edge').properties.owner)
      .toBe('explicit-edge-owner');
    expect(parsed.nodeMap.worker.kind).toBe('PASSTHROUGH');
    expect(serializeGraphML(parsed)).toContain('<node id="worker"/>');
  });

  it('preserves opaque node and edge extension subtrees while editing an unrelated property', () => {
    const source = fixture('accepted', 'complex-extensions.graphml');
    const parsed = parseGraphML(source);
    parsed.nodeMap.end.properties.reviewTicket = 'RR-42';
    parsed.nodeMap.end.propertyTypes.reviewTicket = 'string';

    const serialized = serializeGraphML(parsed);
    expect(opaqueDataSemantics(serialized, 'node-graphics'))
      .toBe(opaqueDataSemantics(source, 'node-graphics'));
    expect(opaqueDataSemantics(serialized, 'edge-graphics'))
      .toBe(opaqueDataSemantics(source, 'edge-graphics'));
    expect(parseGraphML(serialized).nodeMap.end.properties.reviewTicket).toBe('RR-42');
  });

  // GraphML requires `id` on <node> and leaves it optional on <edge>:
  // graphml-structure.xsd declares `<xs:attribute name="id" type="xs:NMTOKEN" use="required">`
  // inside node.type and `<xs:attribute name="id" type="xs:NMTOKEN" >` inside edge.type, and the
  // DTD spells the same distinction as #REQUIRED against #IMPLIED.
  it('accepts unnamed edges, keeps their handles distinct and never serializes them', () => {
    const source = fixture('accepted', 'optional-edge-ids.graphml');
    // The fixture declares the first synthesized candidate as an author id on purpose, so the
    // collision skip is exercised rather than assumed.
    expect(source).toContain(`id="${SYNTHESIZED_EDGE_ID_PREFIX}0"`);

    const parsed = parseGraphML(source);
    expect(parsed.edges).toHaveLength(3);
    // The author id occupies candidate 0, so the two unnamed edges take 1 and 2. Three distinct ids
    // also means no synthesized handle collided with the explicit id or with the other.
    expect(parsed.edges.map(edge => edge.id).sort()).toEqual([
      `${SYNTHESIZED_EDGE_ID_PREFIX}0`,
      `${SYNTHESIZED_EDGE_ID_PREFIX}1`,
      `${SYNTHESIZED_EDGE_ID_PREFIX}2`,
    ]);
    expect(parsed.edges.filter(edge => edge._syntheticId)).toHaveLength(2);

    // The handle is ours, not the author's: saving must not invent ids the document did not have.
    const serialized = serializeGraphML(parsed);
    expect(serialized).not.toContain(`${SYNTHESIZED_EDGE_ID_PREFIX}1`);
    expect(serialized).not.toContain(`${SYNTHESIZED_EDGE_ID_PREFIX}2`);
    expect(xmlSemantics(serialized)).toBe(xmlSemantics(source));
    expect(parseGraphML(serialized).edges.map(edge => edge.id))
      .toEqual(parsed.edges.map(edge => edge.id));
  });

  it('keeps an unnamed edge addressable through an edit and still omits its id on save', () => {
    const parsed = parseGraphML(fixture('accepted', 'optional-edge-ids.graphml'));
    const unnamed = parsed.edges.find(edge => edge._syntheticId && edge.target === 'worker');
    unnamed.properties = { ...unnamed.properties, reviewTicket: 'RR-115' };
    unnamed.propertyTypes = { ...unnamed.propertyTypes, reviewTicket: 'string' };

    const reparsed = parseGraphML(serializeGraphML(parsed));
    const edited = reparsed.edges.find(edge => edge.id === unnamed.id);
    expect(edited.properties.reviewTicket).toBe('RR-115');
    expect(edited._syntheticId).toBe(true);
    expect(edited.source).toBe('start');
    expect(edited.target).toBe('worker');
    expect(reparsed.edges).toHaveLength(3);
  });

  const rejected = {
    'ambiguous-key.graphml': 'Ambiguous GraphML property name',
    'duplicate-edge-id.graphml': "Duplicate GraphML edge id 'shared'",
    'complex-canonical-collision.graphml': 'collides with executable property',
    // assertGraphMLWithinLimits is the outer boundary and owns DTD rejection, so its message is
    // the observable one. Still rejected, still before DOMParser - only the classification moved.
    'doctype-entity.graphml': 'GraphML input rejected: DTD and entity declarations are not allowed',
    'dangling-edge.graphml': 'references an undeclared endpoint',
    'invalid-scalar.graphml': 'expects int',
    'late-key.graphml': 'key declarations must precede the graph',
    'nested-graph.graphml': 'Nested GraphML graphs are not supported',
    'orphan-data.graphml': 'must belong directly to a graph, node or edge',
    // Shared with GraphMlCorpusTest#rejectedCorpusFailsExplicitly, which asserts the core
    // parser refuses the same document for the same reason.
    'foreign-namespace-data-collision.graphml': 'GraphML extensions cannot redefine interpreted element names',
    // SEC-09 parity: parseGraphML carries the same reserved-`ravenroot.`-namespace guard the core
    // applies at ingest, so both parsers reject this fixture for the same reason.
    'reserved-format-version.graphml': "declares a property in Ravenroot's reserved namespace",
  };
  for (const [name, message] of Object.entries(rejected)) {
    it(`rejects ${name} explicitly`, () => {
      expect(() => parseGraphML(fixture('rejected', name))).toThrow(message);
    });
  }

  // `foreign-namespace-data-collision.graphml` is in the REJECTED corpus, so
  // parseGraphML refuses it before serializeGraphML ever sees it -- the loop above proves the
  // read-side guard, nothing more. serializeGraphML does not route through parseGraphML at all: it
  // re-parses `graph.sourceXml` with its own DOMParser call, a seam a `graph` object can reach
  // without ever having passed the read-side guard (e.g. one assembled directly rather than via
  // parseGraphML). This drives that seam directly, using the exact same fixture bytes core and the
  // read-side guard also refuse, so the write-side behavior is pinned by a document already established
  // to be adversarial rather than one invented just for this test.
  it('serializeGraphML matches <data> by qualified name even on a source parseGraphML never validated', () => {
    const source = fixture('rejected', 'foreign-namespace-data-collision.graphml');
    const node = createNode('start', 'Start', 'START');
    node.properties = { owner: 'routing-team' };
    node.propertyTypes = { owner: 'string' };
    const graph = { format: 'graphml', sourceXml: source, nodes: [node], edges: [], nodeMap: { start: node } };

    const serialized = serializeGraphML(graph);
    const document = new DOMParser().parseFromString(serialized, 'application/xml');
    const canonicalOwner = Array.from(document.getElementsByTagNameNS(
      'http://graphml.graphdrawing.org/xmlns', 'data')).find(el => el.getAttribute('key') === 'owner');
    const decoyOwner = Array.from(document.getElementsByTagNameNS(
      'http://www.yworks.com/xml/graphml', 'data')).find(el => el.getAttribute('key') === 'owner');

    // The edit must land on the real, canonical-namespace element...
    expect(canonicalOwner.textContent).toBe('routing-team');
    // ...and the foreign-namespace decoy, which the edit is not about at all, must be untouched.
    // Matching the decoy first (it precedes the canonical element in document order) overwrites IT
    // instead, leaving 'platform-team' stale on the
    // real property -- silent data loss on a plain edit.
    expect(decoyOwner.textContent).toBe('decoy-should-be-ignored');
  });

  // (SEC-09 parity). reserved-format-version.graphml declares the reserved key on NODE
  // content, which the loop above already covers via the shared `rejected` table. This test pins
  // the other two scopes the same fixture family exercises core-side, so the parity claim is about
  // the guard's shape and not just this one fixture:
  // - `for="edge"` is refused the same way `for="node"` is (same code path, same message).
  // - `for="graph"` is ACCEPTED on both sides today. Core's guard walks imported vertices and
  // edges only (GraphMlProfileReportTest pins RESERVED_NOT_REFUSED for a graph-scope key), and
  // the UI mirrors that exactly: simpleProperties(graphElement) is computed and discarded, so a
  // graph-scope reserved key never reaches rejectReservedProperties. This is the known compatibility
  // gap. Asserting acceptance here means that if this parser starts refusing
  // graph-scope reserved keys unilaterally, the test fails and says so.
  it('rejects a reserved-namespace property declared on an edge', () => {
    const doc = '<graphml xmlns="http://graphml.graphdrawing.org/xmlns">'
      + '<key id="e-version" for="edge" attr.name="ravenroot.format.version" attr.type="int"/>'
      + '<graph id="g" edgedefault="directed">'
      + '<node id="start"/><node id="end"/>'
      + '<edge id="e0" source="start" target="end"><data key="e-version">1</data></edge>'
      + '</graph></graphml>';

    expect(() => parseGraphML(doc)).toThrow("declares a property in Ravenroot's reserved namespace");
  });

  it('accepts a graph-scoped reserved property while matching the current core parity gap', () => {
    const doc = '<graphml xmlns="http://graphml.graphdrawing.org/xmlns">'
      + '<key id="g-version" for="graph" attr.name="ravenroot.format.version" attr.type="int"/>'
      + '<graph id="g" edgedefault="directed">'
      + '<data key="g-version">1</data>'
      + '<node id="start"/>'
      + '</graph></graphml>';

    const parsed = parseGraphML(doc);
    expect(parsed.nodeMap.start.properties).not.toHaveProperty('ravenroot.format.version');
  });

  it('rejects doctype-entity.graphml before invoking the XML parser', () => {
    const parseFromString = vi.spyOn(DOMParser.prototype, 'parseFromString');
    try {
      expect(() => parseGraphML(fixture('rejected', 'doctype-entity.graphml')))
        .toThrow('GraphML input rejected: DTD and entity declarations are not allowed');
      expect(parseFromString).not.toHaveBeenCalled();
    } finally {
      parseFromString.mockRestore();
    }
  });
});

function normalizedModel(graph) {
  return {
    nodes: graph.nodes.map(node => ({
      id: node.id,
      kind: node.kind,
      behavior: node.behavior,
      properties: node.properties,
      propertyTypes: node.propertyTypes,
    })),
    edges: graph.edges.map(edge => ({
      id: edge.id,
      source: edge.source,
      target: edge.target,
      outcome: edge.outcome,
      properties: edge.properties,
      propertyTypes: edge.propertyTypes,
    })),
  };
}

function opaqueDataSemantics(xml, key) {
  const document = new DOMParser().parseFromString(xml, 'application/xml');
  const data = Array.from(document.getElementsByTagNameNS(
    'http://graphml.graphdrawing.org/xmlns', 'data',
  )).find(element => element.getAttribute('key') === key);
  return nodeSemantics(data);
}
