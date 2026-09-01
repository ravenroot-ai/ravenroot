import { describe, expect, it } from 'vitest';

// This UI-02 test imports ONLY the established graph-model exports, so every failure below is a
// behavioural one rather than a missing module.
import { createCommandHistory } from '../src/graph-commands.js';
import { createWorkflowDocument, serializeGraphML } from '../src/graph-document.js';
import { connectNodes, insertEdgeElement } from '../src/graph-editing.js';
import { parseGraphML } from '../src/graph-parsers.js';

function workflow() {
  // Start ──> dosomething ──> {end, error}, plus a middle 'review' node spliced right after
  // start to connect through. The tests below only address nodes by id, so dosomething/error simply
  // ride along as ordinary extra graph content.
  const graph = createWorkflowDocument();
  const middle = {
    ...graph.nodes[0], id: 'review', name: 'Review', kind: 'PASSTHROUGH', nodeType: 'flow',
    isStart: false, isEnd: false, properties: {}, propertyTypes: {},
  };
  graph.nodes.splice(1, 0, middle);
  graph.nodeMap.review = middle;
  return graph;
}

describe('topological constraints are enforced when an edge is authored', () => {
  it('allows a self-loop as an ordinary authored edge', () => {
    const graph = workflow();

    const loop = connectNodes(graph, 'review', 'review');
    expect(loop).toMatchObject({ source: 'review', target: 'review' });
    expect(graph.edges.filter(edge => edge.source === edge.target)).toEqual([loop]);
  });

  it('refuses an edge that would give the START node an incoming connection', () => {
    const graph = workflow();

    expect(connectNodes(graph, 'review', 'start')).toBeNull();
    expect(graph.edges.filter(edge => edge.target === 'start')).toHaveLength(0);
  });

  it('refuses an edge that would give the END node an outgoing connection', () => {
    const graph = workflow();

    expect(connectNodes(graph, 'end', 'review')).toBeNull();
    expect(graph.edges.filter(edge => edge.source === 'end')).toHaveLength(0);
  });

  it('refuses an edge to a node that is not in the document', () => {
    const graph = workflow();

    expect(connectNodes(graph, 'start', 'nowhere')).toBeNull();
    expect(connectNodes(graph, 'nowhere', 'end')).toBeNull();
  });

  it('refuses blank and duplicate ids while preserving an inserted whitespace-significant id', () => {
    const graph = workflow();

    expect(insertEdgeElement(graph, { id: '   ', source: 'start', target: 'review' })).toBeNull();
    expect(insertEdgeElement(graph, { id: ' spaced ', source: 'start', target: 'review' }))
      .toMatchObject({ id: ' spaced ' });
    expect(insertEdgeElement(graph, { id: 'edge-start-dosomething', source: 'start', target: 'review' })).toBeNull();
    expect(graph.edges).toHaveLength(4);
    expect(graph.edges.some(edge => edge.id === 'spaced')).toBe(false);
  });

  // Preservation: these were legal before and must stay legal. Ravenroot models parallel
  // branches deliberately — edges carry a `parallel` flag and the N8N2/N8N3 layouts draw several
  // edges between the same pair on distinct ports.
  it('still allows several edges between the same pair of nodes', () => {
    const graph = workflow();

    const first = connectNodes(graph, 'start', 'review');
    const second = connectNodes(graph, 'start', 'review');

    expect(first).not.toBeNull();
    expect(second).not.toBeNull();
    expect(second.id).not.toBe(first.id);
    expect(graph.edges.filter(edge => edge.source === 'start' && edge.target === 'review')).toHaveLength(2);
  });

  it('still connects two ordinary nodes and keeps the document consistent', () => {
    const graph = workflow();

    const edge = connectNodes(graph, 'review', 'end');

    expect(edge).toMatchObject({ source: 'review', target: 'end' });
    expect(graph.edges.at(-1)).toBe(edge);
  });
});

describe('an authored edge survives the GraphML round trip', () => {
  it('keeps its id, outcome and additional properties through serialize, parse and serialize', () => {
    const graph = workflow();
    const edge = connectNodes(graph, 'start', 'review');
    edge.outcome = 'approved';
    edge.command = 'process';
    edge.label = 'approved';
    edge.edgeName = 'Approval branch';
    edge.status = 7;
    edge.parallel = true;
    edge.trafficWeight = 0.25;
    edge.description = 'Taken when the reviewer approves';
    edge.properties.slaMinutes = '30';
    edge.propertyTypes.slaMinutes = 'int';

    const once = serializeGraphML(graph);
    const reparsed = parseGraphML(once);
    const twice = serializeGraphML(reparsed);

    const roundTripped = reparsed.edges.find(candidate => candidate.id === edge.id);
    expect(roundTripped).toMatchObject({
      id: edge.id,
      source: 'start',
      target: 'review',
      outcome: 'approved',
      command: 'process',
      edgeName: 'Approval branch',
      status: 7,
      parallel: true,
      trafficWeight: 0.25,
      description: 'Taken when the reviewer approves',
    });
    expect(roundTripped.properties.slaMinutes).toBe('30');
    // A second serialization of the parsed document is byte-identical: the round trip has settled
    // rather than drifting a little further on each pass.
    expect(twice).toBe(once);
  });

  it('keeps a self-loop id, custom properties and endpoints through undo, redo and GraphML', () => {
    const graph = workflow();
    const history = createCommandHistory();
    const loop = connectNodes(graph, 'review', 'review', history);
    loop.outcome = 'retry';
    loop.edgeName = 'Review again';
    loop.properties.maxAttempts = '3';
    loop.propertyTypes.maxAttempts = 'int';

    history.undo(graph);
    expect(graph.edges.some(edge => edge.id === loop.id)).toBe(false);
    history.redo(graph);
    expect(graph.edges.find(edge => edge.id === loop.id)).toMatchObject({
      source: 'review', target: 'review', properties: { maxAttempts: '3' },
    });

    const reparsed = parseGraphML(serializeGraphML(graph));
    expect(reparsed.edges.find(edge => edge.id === loop.id)).toMatchObject({
      id: loop.id,
      source: 'review',
      target: 'review',
      outcome: 'retry',
      edgeName: 'Review again',
      properties: { maxAttempts: '3' },
    });
  });
});
