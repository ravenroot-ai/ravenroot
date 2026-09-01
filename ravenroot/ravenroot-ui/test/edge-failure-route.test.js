import { describe, expect, it } from 'vitest';

import {
  DEFAULT_EDGE_OUTCOME,
  FAILURE_ROUTE_DECLARED,
  FAILURE_ROUTE_IMPLICIT,
  FAILURE_ROUTE_PROPERTY,
  FAILURE_ROUTE_TRUE,
  additionalProperties,
  classifyFailureRoutes,
  createWorkflowDocument,
  edgeDeclaresFailureRoute,
  edgeFailureRouteKind,
  serializeGraphML,
  setEdgeFailureRoute,
  validateWorkflow,
} from '../src/graph-document.js';
import { parseGraphML } from '../src/graph-parsers.js';

// A minimal document in a shape the engine accepts: the failure edge carries `failure.route` and no
// explicit outcome. GraphDefinition.validate() refuses that property beside any outcome OTHER than
// the default, so writing `outcome=continue` here would be accepted too — it is left out because it
// is what an author would actually write.
const DOCUMENT = `<?xml version="1.0" encoding="UTF-8"?>
<graphml xmlns="http://graphml.graphdrawing.org/xmlns">
  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
  <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
  <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
  <key id="failure.route" for="edge" attr.name="failure.route" attr.type="string"/>
  <graph id="g" edgedefault="directed">
    <node id="start"><data key="kind">START</data></node>
    <node id="py"><data key="kind">BEHAVIOR</data><data key="behavior">program</data></node>
    <node id="error-node"><data key="kind">ERROR</data></node>
    <node id="end"><data key="kind">END</data></node>
    <edge id="e1" source="start" target="py"><data key="outcome">continue</data></edge>
    <edge id="e2" source="py" target="end"><data key="outcome">continue</data></edge>
    <edge id="e3" source="py" target="error-node"><data key="failure.route">true</data></edge>
  </graph>
</graphml>`;

function edge(graph, id) {
  return graph.edges.find(candidate => candidate.id === id);
}

describe('failure.route survives the editor round trip', () => {
  it('reaches the model through the generic edge property bag', () => {
    const graph = parseGraphML(DOCUMENT);

    expect(edge(graph, 'e3').properties[FAILURE_ROUTE_PROPERTY]).toBe(FAILURE_ROUTE_TRUE);
    expect(edgeDeclaresFailureRoute(edge(graph, 'e3'))).toBe(true);
    expect(edgeDeclaresFailureRoute(edge(graph, 'e2'))).toBe(false);
  });

  it('is still declared after save, export and reimport', () => {
    const reopened = parseGraphML(serializeGraphML(parseGraphML(DOCUMENT)));

    expect(edgeDeclaresFailureRoute(edge(reopened, 'e3'))).toBe(true);
    // The pairing the engine checks: the property is only legal while the outcome is the default.
    // A round trip that silently promoted `continue` to an explicit authored outcome would still
    // read `continue` here, so this is asserted on the same edge the property is asserted on.
    expect(edge(reopened, 'e3').outcome).toBe(DEFAULT_EDGE_OUTCOME);
    expect(validateWorkflow(reopened)).toEqual([]);
  });

  it('reads the property the way the Java side does: stripped, and case-sensitive', () => {
    // FailureRouteEdgeProperty.declared() is `"true".equals(value.toString().strip())`. The parser
    // deliberately does not trim the generic bag, so the strip has to happen on the read --
    // otherwise a document the engine treats as a failure route would show an unticked checkbox.
    expect(edgeDeclaresFailureRoute({ properties: { [FAILURE_ROUTE_PROPERTY]: '\n  true\n  ' } }))
      .toBe(true);
    expect(edgeDeclaresFailureRoute({ properties: { [FAILURE_ROUTE_PROPERTY]: 'TRUE' } })).toBe(false);
    expect(edgeDeclaresFailureRoute({ properties: { [FAILURE_ROUTE_PROPERTY]: 'false' } })).toBe(false);
    expect(edgeDeclaresFailureRoute({ properties: {} })).toBe(false);
  });

  it('writes the declaration into a document that never had one', () => {
    const graph = createWorkflowDocument();
    const errorEdge = graph.edges.find(candidate => candidate.target === 'error');
    setEdgeFailureRoute(errorEdge, true);
    errorEdge.outcome = DEFAULT_EDGE_OUTCOME;

    const xml = serializeGraphML(graph);
    expect(xml).toContain(`attr.name="${FAILURE_ROUTE_PROPERTY}"`);
    expect(edgeDeclaresFailureRoute(edge(parseGraphML(xml), errorEdge.id))).toBe(true);
  });

  it('removes the <data> element from the document when the declaration is cleared', () => {
    // The clearing path is the one with no explicit write behind it: `failure.route` lives in the
    // generic bag, so deleting the entry is what makes serializeGraphML's prune pass drop the
    // element. A `failure.route=false` left in the file would be a property that declares nothing.
    const graph = parseGraphML(DOCUMENT);
    setEdgeFailureRoute(edge(graph, 'e3'), false);

    const xml = serializeGraphML(graph);
    const reopened = parseGraphML(xml);
    expect(edgeDeclaresFailureRoute(edge(reopened, 'e3'))).toBe(false);
    expect(Object.keys(edge(reopened, 'e3').properties)).not.toContain(FAILURE_ROUTE_PROPERTY);
    // It is STILL a failure route: the target is an Error node and the edge names no outcome.
    // Removing the declaration removed the declaration, not the behaviour.
    expect(edgeFailureRouteKind(edge(reopened, 'e3'), reopened)).toBe(FAILURE_ROUTE_IMPLICIT);
  });
});

describe('an edge that names no outcome into an Error node is a failure route', () => {
  // The default failure-route rule: "connecting a node to Error means that, unless I handle it
  // differently, an UNHANDLED error goes directly to that node." The precedence is what
  // these pin -- the default fills a silence and never overrides an explicit routing.
  const BARE = DOCUMENT.replace(
    '<edge id="e3" source="py" target="error-node"><data key="failure.route">true</data></edge>',
    '<edge id="e3" source="py" target="error-node"/>');

  it('is a failure route with nothing declared at all', () => {
    const graph = parseGraphML(BARE);

    expect(edgeDeclaresFailureRoute(edge(graph, 'e3'))).toBe(false);
    expect(edgeFailureRouteKind(edge(graph, 'e3'), graph)).toBe(FAILURE_ROUTE_IMPLICIT);
    expect(edge(graph, 'e3').edgeType).toBe('failure');
  });

  it('yields to an explicit outcome, which stays an ordinary outcome edge', () => {
    const routed = BARE.replace('<edge id="e3" source="py" target="error-node"/>',
      '<edge id="e3" source="py" target="error-node"><data key="outcome">failed</data></edge>');
    const graph = parseGraphML(routed);

    expect(edgeFailureRouteKind(edge(graph, 'e3'), graph)).toBeNull();
    expect(edge(graph, 'e3').edgeType).toBe('failed');
  });

  it('reports a declaration as declared even when the target would imply it anyway', () => {
    const graph = parseGraphML(DOCUMENT);

    expect(edgeFailureRouteKind(edge(graph, 'e3'), graph)).toBe(FAILURE_ROUTE_DECLARED);
  });

  it('does not reach an ordinary target, which is what the explicit control is still for', () => {
    const graph = parseGraphML(BARE);
    const ordinary = edge(graph, 'e2');

    expect(edgeFailureRouteKind(ordinary, graph)).toBeNull();
    setEdgeFailureRoute(ordinary, true);
    expect(edgeFailureRouteKind(ordinary, graph)).toBe(FAILURE_ROUTE_DECLARED);
  });

  it('follows the target when an edge is retargeted, in both directions', () => {
    // The reason the classification is redone on every render rather than once at parse: an
    // implicit route is contingent on the target's kind, so moving the endpoint changes what the
    // edge means, and a classification frozen at load would keep drawing the previous answer.
    const graph = parseGraphML(BARE);
    edge(graph, 'e3').target = 'end';
    classifyFailureRoutes(graph);
    expect(edge(graph, 'e3').edgeType).toBe('continue');
    expect(edge(graph, 'e3').label).toBe(DEFAULT_EDGE_OUTCOME);

    edge(graph, 'e3').target = 'error-node';
    classifyFailureRoutes(graph);
    expect(edge(graph, 'e3').edgeType).toBe('failure');
    expect(edge(graph, 'e3').label).toBe('failure');
  });

  it('follows the target node changing kind, without the edge being touched', () => {
    const graph = parseGraphML(BARE);
    graph.nodeMap['error-node'].kind = 'PASSTHROUGH';
    classifyFailureRoutes(graph);

    expect(edge(graph, 'e3').edgeType).toBe('continue');
  });
});

describe('the failure route is not offered twice', () => {
  it('is kept out of the generic Additional properties rows', () => {
    // Two controls writing one value is how a user sets the checkbox and then contradicts it from a
    // property row. The dedicated control owns the property; the rows must not see it.
    const graph = parseGraphML(DOCUMENT);
    const rows = additionalProperties(edge(graph, 'e3'), 'edge');

    expect(rows.map(row => row.name)).not.toContain(FAILURE_ROUTE_PROPERTY);
  });

  it('still lists the other edge properties an author wrote', () => {
    const graph = parseGraphML(DOCUMENT);
    const target = edge(graph, 'e3');
    target.properties = { ...target.properties, retries: '3' };

    expect(additionalProperties(target, 'edge').map(row => row.name)).toContain('retries');
  });
});

describe('an edge is a failure route or an outcome edge, never both', () => {
  it('refuses the combination in the editor rather than leaving it to the engine', () => {
    // Mirrors GraphDefinition.validate(). Reachable from an import or a hand-edited file; the
    // inspector's own control cannot produce it.
    const graph = parseGraphML(DOCUMENT);
    edge(graph, 'e3').outcome = 'failed';

    const violations = validateWorkflow(graph);
    expect(violations).toHaveLength(1);
    expect(violations[0]).toContain('e3');
    expect(violations[0]).toContain(FAILURE_ROUTE_PROPERTY);
    expect(violations[0]).toContain('never both');
  });

  it('accepts the default outcome alongside the declaration', () => {
    const graph = parseGraphML(DOCUMENT);
    edge(graph, 'e3').outcome = DEFAULT_EDGE_OUTCOME;

    expect(validateWorkflow(graph)).toEqual([]);
  });
});

describe('a failure route is distinguishable on the canvas', () => {
  it('classifies as its own edge type instead of an ordinary continue edge', () => {
    const graph = parseGraphML(DOCUMENT);

    expect(edge(graph, 'e3').edgeType).toBe('failure');
    // The edge it must not be confused with, and the one it would otherwise have looked like.
    expect(edge(graph, 'e2').edgeType).toBe('continue');
  });

  it('does not share its edge type with an outcome edge spelled failed or error', () => {
    // These are the two outcome words most likely to impersonate the mechanism. Both
    // must classify as ordinary outcome edges, and NEITHER as `failure`.
    const impostors = DOCUMENT
      .replace('<edge id="e2" source="py" target="end"><data key="outcome">continue</data></edge>',
        '<edge id="e2" source="py" target="end"><data key="outcome">failed</data></edge>'
        + '<edge id="e4" source="start" target="end"><data key="outcome">error</data></edge>');
    const graph = parseGraphML(impostors);

    expect(edge(graph, 'e2').edgeType).toBe('failed');
    expect(edge(graph, 'e4').edgeType).toBe('default');
    expect(edgeFailureRouteKind(edge(graph, 'e2'), graph)).toBeNull();
    expect(edgeFailureRouteKind(edge(graph, 'e4'), graph)).toBeNull();
  });

  it('draws both kinds of failure route the same, because they behave the same', () => {
    // Deliberate: the runtime does not distinguish a declared route from an implicit one, so a
    // second canvas mark would assert a difference the
    // execution does not have. The distinction is provenance, and provenance is stated in the
    // Inspector -- which is where an author acts on it -- through `failureRouteKind`.
    const declaredGraph = parseGraphML(DOCUMENT);
    const implicitGraph = parseGraphML(DOCUMENT.replace(
      '<edge id="e3" source="py" target="error-node"><data key="failure.route">true</data></edge>',
      '<edge id="e3" source="py" target="error-node"/>'));

    expect(edge(declaredGraph, 'e3').edgeType).toBe(edge(implicitGraph, 'e3').edgeType);
    expect(edge(declaredGraph, 'e3').failureRouteKind).toBe(FAILURE_ROUTE_DECLARED);
    expect(edge(implicitGraph, 'e3').failureRouteKind).toBe(FAILURE_ROUTE_IMPLICIT);
  });

  it('labels the edge for what it carries when the author supplied no label', () => {
    expect(edge(parseGraphML(DOCUMENT), 'e3').label).toBe('failure');
  });

  it('a legacy yEd label makes the edge an outcome edge, not a labelled failure route', () => {
    // Measured, and it is why `classifyFailureRoutes` sets the label unconditionally rather than
    // guarding an authored one: parseGraphML derives `outcome` from a yEd <y:EdgeLabel> when no
    // `outcome` key is present, so those words BECOME the outcome. The edge below declares
    // `failure.route` as well, which is the combination the engine refuses at load -- and
    // validateWorkflow says so here rather than leaving it to the engine.
    const labelled = DOCUMENT.replace(
      '<key id="failure.route"',
      '<key id="d22" for="edge" yfiles.type="edgegraphics"/><key id="failure.route"',
    ).replace(
      '<edge id="e3" source="py" target="error-node"><data key="failure.route">true</data></edge>',
      '<edge id="e3" source="py" target="error-node">'
        + '<data key="d22"><y:PolyLineEdge><y:EdgeLabel>ROTTURA</y:EdgeLabel></y:PolyLineEdge></data>'
        + '<data key="failure.route">true</data></edge>',
    ).replace(
      '<graphml xmlns="http://graphml.graphdrawing.org/xmlns">',
      '<graphml xmlns="http://graphml.graphdrawing.org/xmlns"'
        + ' xmlns:y="http://www.yworks.com/xml/graphml">',
    );
    const graph = parseGraphML(labelled);

    expect(edge(graph, 'e3').outcome).toBe('ROTTURA');
    expect(edge(graph, 'e3').label).toBe('ROTTURA');
    expect(edgeFailureRouteKind(edge(graph, 'e3'), graph)).toBeNull();
    expect(validateWorkflow(graph).some(violation => violation.includes(FAILURE_ROUTE_PROPERTY)))
      .toBe(true);
  });
});
