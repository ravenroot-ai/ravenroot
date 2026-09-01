import { describe, expect, it } from 'vitest';

import { parseGraphML } from '../src/graph-parsers.js';
import {
  createNode,
  createWorkflowDocument,
  NODE_VISUAL_TYPES,
  serializeGraphML,
} from '../src/graph-document.js';

// An LLM prompt node set to visual type Agent reopened as Actor. The GraphML carried
// `classification=agent` correctly, so the value
// was never lost in writing — it was dropped in reading, by an `else if` chain in which
// `kind === 'BEHAVIOR'` short-circuits to 'actor' before any `classification` branch is reached.
//
// The regression document, cut down to the one node and the keys that matter. Kept verbatim rather than
// rebuilt through the editor: a test that authors its own input can only prove the parser agrees
// with the serializer, and here the serializer was already right. This is the actual bytes on disk.
const REGRESSION_DOCUMENT = `<?xml version="1.0" encoding="UTF-8"?>
<graphml xmlns="http://graphml.graphdrawing.org/xmlns">
  <key id="rr-node-name" for="node" attr.name="name" attr.type="string"/>
  <key id="rr-node-kind" for="node" attr.name="kind" attr.type="string"/>
  <key id="rr-node-behavior" for="node" attr.name="behavior" attr.type="string"/>
  <key id="rr-node-classification" for="node" attr.name="classification" attr.type="string"/>
  <key id="rr-node-provider" for="node" attr.name="provider" attr.type="string"/>
  <key id="rr-node-prompt" for="node" attr.name="prompt" attr.type="string"/>
  <key id="rr-node-model" for="node" attr.name="model" attr.type="string"/>
  <graph id="G" edgedefault="directed">
    <node id="llm-prompt-1">
      <data key="rr-node-provider">qwen-local</data>
      <data key="rr-node-prompt">reply with ok only</data>
      <data key="rr-node-model">qwen38</data>
      <data key="rr-node-name">LLM prompt</data>
      <data key="rr-node-kind">BEHAVIOR</data>
      <data key="rr-node-behavior">llm-prompt</data>
      <data key="rr-node-classification">agent</data>
    </node>
  </graph>
</graphml>`;

describe('a saved visual type survives being read back', () => {
  it('reads the regression document as an agent, not an actor', () => {
    const graph = parseGraphML(REGRESSION_DOCUMENT);

    expect(graph.nodeMap['llm-prompt-1'].nodeType).toBe('agent');
  });

  // The whole vocabulary, not just 'agent'. The BEHAVIOR branch swallowed every non-actor choice on
  // a behavior node — consumer, handler, terminal, system — and 'system' had no branch of its own at
  // all, so it was unreachable even in the right order. Driving the list from the canonical export
  // means a type added there is covered here without editing this test.
  const authorChosen = NODE_VISUAL_TYPES
    .map(entry => entry.type)
    .filter(type => !['start', 'end', 'error'].includes(type));

  it.each(authorChosen)('save → reload keeps a BEHAVIOR node typed as %s', visualType => {
    const graph = createWorkflowDocument();
    const node = createNode('probe', 'Probe', 'BEHAVIOR');
    node.behavior = 'llm-prompt';
    node.nodeType = visualType;
    graph.nodes.push(node);
    graph.nodeMap.probe = node;

    const reloaded = parseGraphML(serializeGraphML(graph));

    expect(reloaded.nodeMap.probe.nodeType).toBe(visualType);
  });

  it.each(authorChosen)('save → reload keeps a PASSTHROUGH node typed as %s', visualType => {
    const graph = createWorkflowDocument();
    const node = createNode('probe', 'Probe', 'PASSTHROUGH');
    node.nodeType = visualType;
    graph.nodes.push(node);
    graph.nodeMap.probe = node;

    const reloaded = parseGraphML(serializeGraphML(graph));

    expect(reloaded.nodeMap.probe.nodeType).toBe(visualType);
  });

  // START, END and ERROR are the exception, and deliberately so: `app.js` refuses to let an author
  // pick their visual type and stamps it from the kind. Reading must agree with writing, so a
  // document that carries a contradicting classification for one of them is corrected by the kind
  // rather than trusted. Otherwise reading and writing would disagree.
  it.each([['START', 'start'], ['END', 'end'], ['ERROR', 'error']])(
    'a %s node keeps its kind-owned visual type even against a contradicting classification',
    (kind, expected) => {
      const graph = createWorkflowDocument();
      const node = createNode('probe', 'Probe', kind);
      node.nodeType = 'agent';
      graph.nodes.push(node);
      graph.nodeMap.probe = node;

      const reloaded = parseGraphML(serializeGraphML(graph));

      expect(reloaded.nodeMap.probe.nodeType).toBe(expected);
    });

  // With nothing authored, the legacy path still resolves the visual type from the kind heuristic.
  it('still falls back to the kind heuristic when no classification is present', () => {
    const legacy = `<?xml version="1.0" encoding="UTF-8"?>
<graphml xmlns="http://graphml.graphdrawing.org/xmlns">
  <key id="rr-node-kind" for="node" attr.name="kind" attr.type="string"/>
  <graph id="G" edgedefault="directed">
    <node id="a"><data key="rr-node-kind">BEHAVIOR</data></node>
    <node id="b"><data key="rr-node-kind">PASSTHROUGH</data></node>
  </graph>
</graphml>`;

    const graph = parseGraphML(legacy);

    expect(graph.nodeMap.a.nodeType).toBe('actor');
    expect(graph.nodeMap.b.nodeType).toBe('flow');
  });
});
