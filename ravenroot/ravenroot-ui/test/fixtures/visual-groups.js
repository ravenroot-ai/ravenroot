/** Public synthetic topology: 12 parallel branches, 24 boundary edges, reverse paths and loops. */
export function makeVisualGroupFixture({ nodeCount = 80, edgeCount = 180 } = {}) {
  const ids = Array.from({ length: nodeCount }, (_, index) => `node-${index}`);
  ids[nodeCount-2] = 'rr-visual:["summary","section-a"]';
  ids[nodeCount-1] = 'rr-visual:["edge","edge-0"]';
  const nodes = ids.map((id, index) => ({ id, name: `Step ${index}`, nodeType: 'flow',
    kind: 'PASSTHROUGH', behavior: '', properties: {}, propertyTypes: {},
    ox: 100 + (index % 10)*150, oy: 100 + Math.floor(index/10)*160,
    ow: 80, oh: 80, _positionIsCenter: true }));
  const edges = [];
  const add = (source, target, extra = {}) => {
    const index = edges.length;
    const failed = index % 5 === 0;
    edges.push({ id: `edge-${index}`, source: ids[source % nodeCount], target: ids[target % nodeCount],
      label: failed ? 'failed' : 'continue', outcome: failed ? 'failed' : 'continue',
      edgeType: failed ? 'failed' : 'continue', parallel: false,
      color: failed ? '#c82535' : '#086adb', properties: {}, ...extra });
  };
  for (let index = 1; index <= 24; index += 1) add(0, index, { parallel: index <= 12 });
  for (let index = 1; index <= 24; index += 1) add(index, 0, { status: index % 2 ? 'PING' : '' });
  for (let index = 1; index < nodeCount; index += 1) add(index-1, index);
  add(1, 1); add(25, 25); add(26, 8); add(8, 26);
  while (edges.length < edgeCount) add((edges.length*7)%nodeCount, (edges.length*11+3)%nodeCount,
    { command: edges.length % 2 ? 'PING' : 'CONTINUE' });
  const groups = [
    { id: 'section-a', name: 'Request processing', memberNodeIds: ids.slice(1, 25), anchorNodeId: ids[1], collapsed: false },
    { id: 'section-b', name: 'Result delivery', memberNodeIds: ids.slice(25, 49), anchorNodeId: ids[25], collapsed: false },
  ];
  return { graph: { format: 'graphml', nodes, edges,
    nodeMap: Object.fromEntries(nodes.map(node => [node.id, node])), graphProperties: {} }, groups };
}

export function visualGroupFixtureGraphML({ collapsed = false, includeGroups = true, ...options } = {}) {
  const { graph, groups } = makeVisualGroupFixture(options);
  const escape = value => String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;').replaceAll('"', '&quot;');
  return `<?xml version="1.0"?><graphml xmlns="http://graphml.graphdrawing.org/xmlns">
    <key id="name" for="node" attr.name="name" attr.type="string"/>
    <key id="kind" for="node" attr.name="kind" attr.type="string"/>
    <key id="x" for="node" attr.name="layoutX" attr.type="double"/>
    <key id="y" for="node" attr.name="layoutY" attr.type="double"/>
    <key id="width" for="node" attr.name="layoutWidth" attr.type="double"/>
    <key id="height" for="node" attr.name="layoutHeight" attr.type="double"/>
    <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
    <key id="parallel" for="edge" attr.name="parallel" attr.type="boolean"/>
    ${includeGroups ? '<key id="groups" for="graph" attr.name="ravenroot.ui.visualGroups" attr.type="string"/>' : ''}
    <graph id="synthetic-visual-groups" edgedefault="directed">
      ${includeGroups ? `<data key="groups">${escape(JSON.stringify({ version: 1, groups: groups.map(group => ({ ...group, collapsed })) }))}</data>` : ''}
      ${graph.nodes.map(node => `<node id="${escape(node.id)}"><data key="name">${escape(node.name)}</data><data key="kind">PASSTHROUGH</data><data key="x">${node.ox}</data><data key="y">${node.oy}</data><data key="width">80</data><data key="height">80</data></node>`).join('')}
      ${graph.edges.map(edge => `<edge id="${edge.id}" source="${escape(edge.source)}" target="${escape(edge.target)}"><data key="outcome">${edge.outcome}</data><data key="parallel">${edge.parallel}</data></edge>`).join('')}
    </graph></graphml>`;
}
