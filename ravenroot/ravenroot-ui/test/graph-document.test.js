import { describe, expect, it } from 'vitest';

import { parseGraphML } from '../src/graph-parsers.js';
import {
  JOIN_SEMANTICS_DECLARED,
  JOIN_SEMANTICS_PROPERTY,
  createEdge,
  createNode,
  createWorkflowDocument,
  declaredJoinKind,
  distinctPredecessorIds,
  effectiveJoinArrival,
  hasDeclaredJoinSemantics,
  isEachJoinPolicy,
  joinKindProperties,
  kindToNodeType,
  outcomeToEdgeType,
  planJoinSemanticsMigration,
  quorumWouldCollideWithLegacyStamp,
  serializeGraphML,
  validateWorkflow,
  wouldSaveStampEachJoinPolicy,
} from '../src/graph-document.js';

describe('editable GraphML documents', () => {
  // A drawing made from scratch has no join unless its author selects one. That is true only
  // because the document this editor creates says so: the graph-level marker is what versions the
  // undeclared case, and without it a node with two incoming edges is still inferred to be a
  // synchronisation point by the runtime -- which is how a node drawn looping back to itself became
  // its own second predecessor and waited for itself.
  it('stamps the explicit-join marker on a document it creates', () => {
    const xml = serializeGraphML(createWorkflowDocument());
    expect(xml).toContain(`attr.name="${JOIN_SEMANTICS_PROPERTY}"`);
    expect(xml).toContain(`for="graph"`);
    expect(xml).toContain(`>${JOIN_SEMANTICS_DECLARED}<`);
    const graphOpen = xml.indexOf('<graph');
    expect(xml.indexOf(JOIN_SEMANTICS_DECLARED, graphOpen))
      .toBeLessThan(xml.indexOf('<node', graphOpen));
  });

  // The other half, and the one that protects graphs already recorded: a document this editor merely
  // opened must not acquire the marker by being saved. Stamping on save would change what an existing
  // document means without its author asking for it. Migrating a legacy graph is a deliberate action,
  // not a side effect of pressing save.
  it('never stamps the marker onto a document it only opened', () => {
    const legacy = [
      '<?xml version="1.0" encoding="UTF-8"?>',
      '<graphml xmlns="http://graphml.graphdrawing.org/xmlns">',
      '<key id="kkind" for="node" attr.name="kind" attr.type="string"/>',
      '<graph id="legacy" edgedefault="directed">',
      '<node id="start"><data key="kkind">START</data></node>',
      '<node id="end"><data key="kkind">END</data></node>',
      '<edge id="e1" source="start" target="end"/>',
      '</graph></graphml>',
    ].join('');
    const opened = parseGraphML(legacy);
    expect(opened.graphProperties?.[JOIN_SEMANTICS_PROPERTY]).toBeUndefined();
    expect(serializeGraphML(opened)).not.toContain(JOIN_SEMANTICS_PROPERTY);
  });

  // A directly-created node (not parsed from GraphML) gets its visual classification from
  // kindToNodeType(). Before this kept the amber-diamond error style tied to the parser only (see
  // the parallel isError branch in graph-parsers.js), a node created with kind ERROR fell through
  // to 'actor' and only picked up the error style after a save-and-reload round trip.
  it('classifies a directly-created ERROR node as the error visual immediately', () => {
    expect(kindToNodeType('ERROR')).toBe('error');
    expect(createNode('err', 'Error', 'ERROR').nodeType).toBe('error');
  });

  // An inline 'continue'/'default' split in createEdge() never produces 'failed' or 'completed', so a
  // directly-created edge with either outcome rendered with the default gray style instead of the
  // renderer's red-dashed / green one, again only correcting itself after a save-and-reload round
  // trip through parseGraphML's richer classification. Same shape of bug as the node kind/nodeType
  // one, on edges instead of nodes.
  it('classifies a directly-created failed/completed edge with the renderer-colored edgeType immediately', () => {
    expect(outcomeToEdgeType('failed')).toBe('failed');
    expect(outcomeToEdgeType('completed')).toBe('completed');
    expect(createEdge('e1', 'a', 'b', 'failed').edgeType).toBe('failed');
    expect(createEdge('e2', 'a', 'b', 'completed').edgeType).toBe('completed');
  });

  it('classifies a directly-created canonical suffixed edge before any save and reload', () => {
    expect(createEdge('e1', 'a', 'b', 'APPROVED_OUTCOME')).toMatchObject({
      outcome: 'APPROVED_OUTCOME',
      label: 'APPROVED_OUTCOME',
      edgeType: 'outcome',
    });
  });
  it('creates a valid minimal workflow', () => {
    const graph = createWorkflowDocument();

    expect(validateWorkflow(graph)).toEqual([]);
    // The minimal graph is start -> dosomething -> {end, error}, not start -> end. dosomething
    // is a passthrough on purpose (documented decision): it must not require the user to choose
    // anything for a freshly created graph to validate.
    expect(parseGraphML(serializeGraphML(graph)).nodes.map(node => node.kind))
      .toEqual(['START', 'PASSTHROUGH', 'ERROR', 'END']);
  });

  // ERROR is bounded, not required. had made it part
  // of the minimum structure and these two tests both asserted a refusal; the obligation is gone, so
  // the first flipped and the second did not. The pair is kept side by side deliberately -- the two
  // ends of this rule have unrelated reasons, and reading them together is what stops the next change
  // from moving both because they look like one rule.
  it('accepts a graph with no ERROR node', () => {
    const graph = createWorkflowDocument();
    graph.nodes = graph.nodes.filter(node => node.kind !== 'ERROR');
    graph.edges = graph.edges.filter(edge => edge.target !== 'error');

    expect(validateWorkflow(graph)).toEqual([]);
  });

  // The surviving half, and not a leftover of the obligation: the JVM runner keys what reached a
  // terminal by terminal KIND, so a second ERROR node gives one field two concurrent writers. See
  // MAX_ERROR_NODE_COUNT in graph-document.js and GraphDefinition.MIN_ERROR_NODES on the Java side.
  it('rejects a graph with two ERROR nodes', () => {
    const graph = createWorkflowDocument();
    graph.nodes.push(createNode('error-2', 'Error 2', 'ERROR', { x: 300, y: 500 }));

    expect(validateWorkflow(graph)).toContain('The graph must contain at most one ERROR node');
  });

  // The default document still ships with an error terminal as a recommendation, even though the
  // validator does not require one. This keeps the "New" document separate from the minimum-valid
  // graph rule.
  it('still creates the default document with an ERROR node, now as a suggestion rather than a rule', () => {
    expect(createWorkflowDocument().nodes.filter(node => node.kind === 'ERROR')).toHaveLength(1);
  });

  it('round-trips canonical and additional properties while preserving complex extension data', () => {
    const source = `<?xml version="1.0"?><graphml xmlns="http://graphml.graphdrawing.org/xmlns" xmlns:y="http://www.yworks.com/xml/graphml">
      <key id="graphics" for="node" yfiles.type="nodegraphics"/>
      <key id="custom" for="node" attr.name="owner" attr.type="string"/>
      <graph id="g" edgedefault="directed">
        <node id="start"><data key="custom">team-a</data><data key="graphics"><y:ShapeNode><y:NodeLabel>Start</y:NodeLabel></y:ShapeNode></data></node>
        <node id="end"/>
        <edge id="e" source="start" target="end"/>
      </graph></graphml>`;
    const graph = parseGraphML(source);
    graph.nodes[0].kind = 'START';
    graph.nodes[0].name = 'Start';
    graph.nodes[1].kind = 'END';
    graph.nodes[1].name = 'End';
    graph.nodes[1].properties.reviewTicket = 'RR-42';
    graph.nodes[1].propertyTypes.reviewTicket = 'string';
    const serialized = serializeGraphML(graph);
    const reparsed = parseGraphML(serialized);

    expect(serialized).toContain('y:ShapeNode');
    expect(reparsed.nodeMap.start.properties.owner).toBe('team-a');
    expect(reparsed.nodeMap.end.properties.reviewTicket).toBe('RR-42');
    expect(reparsed.nodeMap.start.kind).toBe('START');
    expect(reparsed.edges[0].outcome).toBe('continue');
  });

  it('round-trips runtime nature and maxConcurrency together with their scalar types', () => {
    const source = `<?xml version="1.0"?><graphml xmlns="http://graphml.graphdrawing.org/xmlns">
      <key id="kind" for="node" attr.name="kind" attr.type="string"/>
      <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
      <key id="nature" for="node" attr.name="runtime.nature" attr.type="string"/>
      <key id="capacity" for="node" attr.name="runtime.maxConcurrency" attr.type="long"/>
      <graph id="g" edgedefault="directed">
        <node id="start"><data key="kind">START</data></node>
        <node id="program"><data key="kind">BEHAVIOR</data><data key="behavior">program</data>
          <data key="nature">TRAVERSAL</data><data key="capacity">1</data></node>
        <node id="end"><data key="kind">END</data></node>
        <edge id="a" source="start" target="program"/><edge id="b" source="program" target="end"/>
      </graph></graphml>`;
    const reparsed = parseGraphML(serializeGraphML(parseGraphML(source)));
    expect(reparsed.nodeMap.program.properties).toMatchObject({
      'runtime.nature': 'TRAVERSAL',
      'runtime.maxConcurrency': '1',
    });
    expect(reparsed.nodeMap.program.propertyTypes['runtime.nature']).toBe('string');
    expect(reparsed.nodeMap.program.propertyTypes['runtime.maxConcurrency']).toBe('long');
  });

  // Two <key> elements sharing one scope+attr.name is a malformed but
  // real document shape; before this, serializeGraphML resolved a graph-level write to whichever key
  // was declared LAST, found no existing <data> under THAT id (the pre-existing one was written
  // against the FIRST), and wrote a second <data> for the same logical property rather than reusing
  // the first one -- a graph that round-tripped through save would silently pick up a duplicate.
  it('does not duplicate a graph-level <data> when two <key> elements share one attr.name', () => {
    const source = [
      '<?xml version="1.0" encoding="UTF-8"?>',
      '<graphml xmlns="http://graphml.graphdrawing.org/xmlns">',
      '<key id="kjoin1" for="graph" attr.name="join.semantics" attr.type="string"/>',
      '<key id="kjoin2" for="graph" attr.name="join.semantics" attr.type="string"/>',
      '<key id="kkind" for="node" attr.name="kind" attr.type="string"/>',
      '<graph id="g" edgedefault="directed">',
      '<data key="kjoin1">declared</data>',
      '<node id="start"><data key="kkind">START</data></node>',
      '<node id="end"><data key="kkind">END</data></node>',
      '<edge id="e1" source="start" target="end"/>',
      '</graph></graphml>',
    ].join('');
    const graph = parseGraphML(source);
    expect(graph.graphProperties[JOIN_SEMANTICS_PROPERTY]).toBe('declared');

    const serialized = serializeGraphML(graph);
    expect(serialized.match(/<data key="kjoin1">declared<\/data>/g)).toHaveLength(1);
    expect(serialized).not.toContain('key="kjoin2"');
    expect(parseGraphML(serialized).graphProperties[JOIN_SEMANTICS_PROPERTY]).toBe('declared');
  });

  it('validates behavior names and edge references', () => {
    const graph = createWorkflowDocument();
    graph.nodes.push(createNode('worker', 'Worker', 'BEHAVIOR'));
    graph.edges.push(createEdge('bad', 'missing', 'worker'));

    expect(validateWorkflow(graph)).toEqual(expect.arrayContaining([
      'Behavior node worker needs a behavior name',
      'Edge bad has an unknown source',
    ]));
  });
});

// A node with several incoming edges is a synchronisation point only where the author declared one.
// The Inspector's Kind-of-arrival control writes and reads that vocabulary, and the "Migrate join
// semantics" action runs the client-side mirror of `JoinSemantics.migrate`. These tests cover both
// paths, including migration of legacy documents that have no declared semantics.
describe('join arrival vocabulary', () => {
  function fanIn(id, kind, sources) {
    const graph = { nodes: [], edges: [], graphProperties: {}, nodeMap: {} };
    graph.nodes.push(createNode('start', 'Start', 'START'));
    for (const source of sources) graph.nodes.push(createNode(source, source, 'PASSTHROUGH'));
    graph.nodes.push(createNode(id, id, kind));
    for (const source of sources) graph.edges.push(createEdge(`e-${source}`, source, id, 'continue'));
    graph.nodeMap = Object.fromEntries(graph.nodes.map(node => [node.id, node]));
    return graph;
  }

  it('counts distinct predecessor NODES, not incoming edges', () => {
    const graph = fanIn('j', 'PASSTHROUGH', ['a']);
    graph.edges.push(createEdge('e-a-2', 'a', 'j', 'rejected'));
    // Same source wired twice (an ordinary decision-node fan-out) is one branch, not two.
    expect(distinctPredecessorIds(graph, 'j')).toEqual(['a']);
  });

  it('a declared choice writes exactly one property; the no-join entry writes nothing', () => {
    expect(joinKindProperties('none', null)).toEqual({});
    expect(joinKindProperties('all', null)).toEqual({ joinPolicy: 'all' });
    expect(joinKindProperties('first', null)).toEqual({ joinPolicy: 'any' });
    expect(joinKindProperties('quorum', 2)).toEqual({ joinQuorum: '2' });
    // A quorum with no usable number writes nothing rather than a malformed property.
    expect(joinKindProperties('quorum', 'not-a-number')).toEqual({});
  });

  it('reads a declared kind back the same way it was written, and folds `each` into no-join', () => {
    expect(declaredJoinKind(createNode('j', 'j', 'PASSTHROUGH'))).toEqual({ kind: 'none', quorum: null });
    const all = createNode('j', 'j', 'PASSTHROUGH');
    all.properties.joinPolicy = 'all';
    expect(declaredJoinKind(all)).toEqual({ kind: 'all', quorum: null });
    const first = createNode('j', 'j', 'PASSTHROUGH');
    first.properties.joinPolicy = 'any';
    expect(declaredJoinKind(first)).toEqual({ kind: 'first', quorum: null });
    const quorum = createNode('j', 'j', 'PASSTHROUGH');
    quorum.properties.joinQuorum = '3';
    expect(declaredJoinKind(quorum)).toEqual({ kind: 'quorum', quorum: 3 });
    const each = createNode('j', 'j', 'PASSTHROUGH');
    each.properties.joinPolicy = 'each';
    expect(declaredJoinKind(each)).toEqual({ kind: 'none', quorum: null });
  });

  it('START is never applicable, and a single predecessor is not a fan-in', () => {
    const graph = fanIn('j', 'PASSTHROUGH', ['a']);
    expect(effectiveJoinArrival(graph, graph.nodeMap.start)).toMatchObject({ applicable: false });
    expect(effectiveJoinArrival(graph, graph.nodeMap.j)).toMatchObject({ applicable: false, branchCount: 1 });
  });

  it('an undeclared node under join.semantics=declared is no join; ERROR keeps an implicit quorum of one', () => {
    const ordinary = fanIn('j', 'PASSTHROUGH', ['a', 'b']);
    ordinary.graphProperties[JOIN_SEMANTICS_PROPERTY] = JOIN_SEMANTICS_DECLARED;
    expect(effectiveJoinArrival(ordinary, ordinary.nodeMap.j))
      .toMatchObject({ applicable: true, kind: 'none', source: 'undeclared' });

    const errorTerminal = fanIn('error', 'ERROR', ['a', 'b']);
    errorTerminal.graphProperties[JOIN_SEMANTICS_PROPERTY] = JOIN_SEMANTICS_DECLARED;
    expect(effectiveJoinArrival(errorTerminal, errorTerminal.nodeMap.error))
      .toMatchObject({ applicable: true, kind: 'quorum', quorum: 1, source: 'default-error' });
  });

  it('the same undeclared fan-in is an inferred wait-for-all without the marker', () => {
    const graph = fanIn('j', 'PASSTHROUGH', ['a', 'b']);
    expect(effectiveJoinArrival(graph, graph.nodeMap.j))
      .toMatchObject({ applicable: true, kind: 'all', source: 'legacy-inferred' });
  });

  it('a declared property is read back as the effective kind regardless of the marker', () => {
    const graph = fanIn('j', 'PASSTHROUGH', ['a', 'b']);
    graph.nodeMap.j.properties.joinPolicy = 'any';
    expect(effectiveJoinArrival(graph, graph.nodeMap.j))
      .toMatchObject({ applicable: true, kind: 'first', source: 'declared' });
  });

  it('planJoinSemanticsMigration is a no-op on a document that already declares the marker', () => {
    const graph = fanIn('j', 'PASSTHROUGH', ['a', 'b']);
    graph.graphProperties[JOIN_SEMANTICS_PROPERTY] = JOIN_SEMANTICS_DECLARED;
    expect(planJoinSemanticsMigration(graph)).toEqual({ alreadyDeclared: true, changes: [] });
  });

  // The exact rule JoinSemantics.migrate documents on the Java side: joinPolicy=all on an ordinary
  // inferred join, joinQuorum=1 on the error terminal, never `each`, and a node that already declares
  // something of its own is left untouched.
  it('materialises the currently-inferred policy on every undeclared fan-in, and skips declared ones', () => {
    const graph = fanIn('ordinary', 'PASSTHROUGH', ['a', 'b']);
    graph.nodes.push(createNode('error', 'error', 'ERROR'));
    graph.edges.push(createEdge('e-a-error', 'a', 'error', 'failed'));
    graph.edges.push(createEdge('e-b-error', 'b', 'error', 'failed'));
    const alreadyDeclared = createNode('declared-already', 'declared-already', 'PASSTHROUGH');
    alreadyDeclared.properties.joinPolicy = 'any';
    graph.nodes.push(alreadyDeclared);
    graph.edges.push(createEdge('e-a-declared', 'a', 'declared-already', 'continue'));
    graph.edges.push(createEdge('e-b-declared', 'b', 'declared-already', 'continue'));
    graph.nodeMap = Object.fromEntries(graph.nodes.map(node => [node.id, node]));

    const plan = planJoinSemanticsMigration(graph);

    expect(plan.alreadyDeclared).toBe(false);
    expect(plan.changes).toEqual(expect.arrayContaining([
      { nodeId: 'ordinary', property: 'joinPolicy', value: 'all' },
      { nodeId: 'error', property: 'joinQuorum', value: '1' },
    ]));
    expect(plan.changes.some(change => change.nodeId === 'declared-already')).toBe(false);
    expect(plan.changes.some(change => change.value === 'each')).toBe(false);
    expect(plan.changes).toHaveLength(2);
  });

  it('hasDeclaredJoinSemantics reads only the exact marker value, case-insensitively', () => {
    expect(hasDeclaredJoinSemantics({ graphProperties: {} })).toBe(false);
    expect(hasDeclaredJoinSemantics({ graphProperties: { [JOIN_SEMANTICS_PROPERTY]: 'DECLARED' } })).toBe(true);
    expect(hasDeclaredJoinSemantics({ graphProperties: { [JOIN_SEMANTICS_PROPERTY]: 'typo' } })).toBe(false);
  });

  // A document parsed without any canonical `kind` on some node (legacy
  // start/end/actor booleans -- graph-parsers.js's `_legacyKind`) is what serializeGraphML calls a
  // "legacy state machine" and stamps `joinPolicy=each` onto every multi-predecessor node AT SAVE
  // TIME, unconditionally -- see graph-document.js:759-784's own comment. `parseGraphML` never writes
  // that stamp back into `node.properties`, so a document that was just opened does not carry it yet.
  // `planJoinSemanticsMigration` and `effectiveJoinArrival` must not read that pre-stamp snapshot and
  // disagree with the document
  // serializeGraphML was about to write -- migrating proposed `joinPolicy=all` against a fan-in that
  // the very next save would still turn into `each`, silently turning "wait for the first
  // mutually-exclusive branch" into "wait for every branch, forever" on a state machine graph.
  function legacyStateMachineFanIn() {
    const xml = [
      '<?xml version="1.0" encoding="UTF-8"?>',
      '<graphml xmlns="http://graphml.graphdrawing.org/xmlns">',
      '<key id="dstart" for="node" attr.name="start" attr.type="boolean"/>',
      '<key id="dend" for="node" attr.name="end" attr.type="boolean"/>',
      '<key id="kkind" for="node" attr.name="kind" attr.type="string"/>',
      '<graph id="g" edgedefault="directed">',
      // No canonical `kind` data on `start`: the legacy boolean is the ONLY thing that says what it
      // is, which is exactly what makes graph-parsers.js flag it `_legacyKind` and, through that, the
      // whole document a "legacy state machine" for serializeGraphML's each-stamp.
      '<node id="start"><data key="dstart">true</data></node>',
      '<node id="a"><data key="kkind">PASSTHROUGH</data></node>',
      '<node id="b"><data key="kkind">PASSTHROUGH</data></node>',
      '<node id="j"><data key="kkind">PASSTHROUGH</data></node>',
      '<node id="end"><data key="dend">true</data></node>',
      '<edge id="e-start-a" source="start" target="a"/>',
      '<edge id="e-start-b" source="start" target="b"/>',
      '<edge id="e-a-j" source="a" target="j"/>',
      '<edge id="e-b-j" source="b" target="j"/>',
      '<edge id="e-j-end" source="j" target="end"/>',
      '</graph></graphml>',
    ].join('');
    return parseGraphML(xml);
  }

  it('parses a legacy state-machine fan-in without joinPolicy=each in its properties yet, but recognizes it will get it on save', () => {
    const graph = legacyStateMachineFanIn();
    expect(graph.nodeMap.start._legacyKind).toBe(true);
    expect(isEachJoinPolicy(graph.nodeMap.j)).toBe(false);
    expect(wouldSaveStampEachJoinPolicy(graph, graph.nodeMap.j)).toBe(true);
  });

  it('the required test: saving BEFORE and AFTER migrating a legacy state-machine document writes the same joinPolicy for its fan-in', () => {
    const graph = legacyStateMachineFanIn();

    const beforeXml = serializeGraphML(graph);
    const before = parseGraphML(beforeXml).nodeMap.j.properties.joinPolicy;
    expect(before).toBe('each');

    const plan = planJoinSemanticsMigration(graph);
    // The bug this test pins: a plan that proposes `joinPolicy=all` against a node save is about to
    // stamp `each` onto is planning against a document that will not exist the moment save runs.
    expect(plan.changes.some(change => change.nodeId === 'j')).toBe(false);

    graph.graphProperties[JOIN_SEMANTICS_PROPERTY] = JOIN_SEMANTICS_DECLARED;
    for (const change of plan.changes) graph.nodeMap[change.nodeId].properties[change.property] = change.value;

    const afterXml = serializeGraphML(graph);
    const after = parseGraphML(afterXml).nodeMap.j.properties.joinPolicy;
    expect(after).toBe('each');
    expect(after).toBe(before);
  });

  it('effectiveJoinArrival reports the same no-coordination kind for that fan-in before and after migration', () => {
    const graph = legacyStateMachineFanIn();
    const before = effectiveJoinArrival(graph, graph.nodeMap.j);
    expect(before).toMatchObject({ applicable: true, kind: 'none', quorum: null, source: 'each' });

    const plan = planJoinSemanticsMigration(graph);
    graph.graphProperties[JOIN_SEMANTICS_PROPERTY] = JOIN_SEMANTICS_DECLARED;
    for (const change of plan.changes) graph.nodeMap[change.nodeId].properties[change.property] = change.value;

    const after = effectiveJoinArrival(graph, graph.nodeMap.j);
    expect(after).toMatchObject({ applicable: true, kind: 'none', quorum: null, source: 'each' });
  });

  it('wouldSaveStampEachJoinPolicy is false for an ordinary (non-legacy) document, for START, and once a policy is already explicit', () => {
    const ordinary = fanIn('j', 'PASSTHROUGH', ['a', 'b']);
    expect(wouldSaveStampEachJoinPolicy(ordinary, ordinary.nodeMap.j)).toBe(false);

    const graph = legacyStateMachineFanIn();
    expect(wouldSaveStampEachJoinPolicy(graph, graph.nodeMap.start)).toBe(false); // START
    graph.nodeMap.j.properties.joinPolicy = 'all';
    expect(wouldSaveStampEachJoinPolicy(graph, graph.nodeMap.j)).toBe(false); // already explicit
  });

  // 'K of N' writes joinQuorum alone; on a legacy
  // state-machine fan-in that collides with the each-stamp regardless of what the node's OWN
  // properties currently say -- unlike `wouldSaveStampEachJoinPolicy`, an existing explicit
  // `joinPolicy` does not save this one, because writing joinQuorum alone is exactly what would
  // remove it (`properties` is a whole replace).
  it('quorumWouldCollideWithLegacyStamp is true for a legacy multi-predecessor fan-in regardless of its current joinPolicy, false for ordinary documents, START and single predecessors', () => {
    const graph = legacyStateMachineFanIn();
    expect(quorumWouldCollideWithLegacyStamp(graph, graph.nodeMap.j)).toBe(true);
    graph.nodeMap.j.properties.joinPolicy = 'each'; // already explicit -- still collides if overwritten
    expect(quorumWouldCollideWithLegacyStamp(graph, graph.nodeMap.j)).toBe(true);
    expect(quorumWouldCollideWithLegacyStamp(graph, graph.nodeMap.start)).toBe(false); // START

    const ordinary = fanIn('j', 'PASSTHROUGH', ['a', 'b']);
    expect(quorumWouldCollideWithLegacyStamp(ordinary, ordinary.nodeMap.j)).toBe(false);

    // Still the legacy document, but 'a' has only one predecessor ('start') -- not a fan-in, so no
    // collision regardless of the document's own flavor.
    expect(quorumWouldCollideWithLegacyStamp(graph, graph.nodeMap.a)).toBe(false);
  });

  // `declaredJoinKind` must not fold an unparseable declaration into the same
  // `{ kind: 'none' }` an honestly undeclared node reports -- indistinguishable from it, and from
  // there one submit away from being overwritten with nothing. These pin the new `recognized: false`
  // + `raw` escape hatch the Inspector control now reads (app.js's `joinFieldHtml`/`readJoinEditor`).
  it('reports an unrecognized joinPolicy spelling as unrecognized, not as "none"', () => {
    const node = createNode('j', 'j', 'PASSTHROUGH');
    node.properties.joinPolicy = 'ALL_OF';
    expect(declaredJoinKind(node)).toEqual({ kind: 'none', quorum: null, recognized: false, raw: 'ALL_OF' });
  });

  it('reports a non-integer joinQuorum as unrecognized, not as "none"', () => {
    const node = createNode('j', 'j', 'PASSTHROUGH');
    node.properties.joinQuorum = 'not-a-number';
    expect(declaredJoinKind(node)).toEqual({ kind: 'none', quorum: null, recognized: false, raw: 'not-a-number' });
  });

  it('every recognized declaration still reports the plain shape existing callers rely on (no `recognized` key)', () => {
    const each = createNode('j', 'j', 'PASSTHROUGH');
    each.properties.joinPolicy = 'each';
    expect(declaredJoinKind(each)).toEqual({ kind: 'none', quorum: null });
    expect(Object.hasOwn(declaredJoinKind(each), 'recognized')).toBe(false);
  });
});
