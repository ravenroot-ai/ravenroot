import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { parseGraphML } from '../src/graph-parsers.js';
import { createNode, serializeGraphML } from '../src/graph-document.js';
import { createCommandHistory } from '../src/graph-commands.js';
import { createVisualGroup, editVisualGroups, deleteElements, moveNodesTo } from '../src/graph-editing.js';
import { VISUAL_GROUPS_PROPERTY as KEY, readVisualGroups, reconcileVisualGroupState,
  graphWithVisualGroupPresentation, validateVisualGroups } from '../src/visual-groups.js';
import { createDocumentRecord, forkDocumentRecord } from '../src/workspace.js';
import { persistedDocument, validateWorkspaceSnapshot, workspaceScope, workspaceSnapshot } from '../src/workspace-persistence.js';

const metadata = (groups = [{ id: 'g', name: 'Section', memberNodeIds: ['a', 'b', 'c'], anchorNodeId: 'a', collapsed: true }]) => ({ version: 1, groups });
const xml = (data = JSON.stringify(metadata()), extra = '') => `<graphml xmlns="http://graphml.graphdrawing.org/xmlns"><key id="groups" for="graph" attr.name="${KEY}" attr.type="string"/><key id="other" for="graph" attr.name="owner" attr.type="string"/>${extra}<graph edgedefault="directed"><data key="groups">${data.replaceAll('&', '&amp;').replaceAll('<', '&lt;')}</data><data key="other">retained</data><node id="a"/><node id="b"/><node id="c"/><edge id="ab" source="a" target="b"/><edge id="bc" source="b" target="c"/></graph></graphml>`;

describe('portable visual groups', () => {
  it('treats an unused group key as absent and preserves it, while explicit empty data/default is invalid', () => {
    const declaredOnly = xml().replace(/<data key="groups">[^<]*<\/data>/, '');
    const graph = parseGraphML(declaredOnly);
    expect(readVisualGroups(graph).status).toBe('none');
    expect(serializeGraphML(graph)).toContain(`attr.name="${KEY}"`);
    expect(readVisualGroups(parseGraphML(xml(''))).status).toBe('invalid');
    const emptyDefault = declaredOnly.replace(`attr.name="${KEY}" attr.type="string"/>`,
      `attr.name="${KEY}" attr.type="string"><default/></key>`);
    expect(readVisualGroups(parseGraphML(emptyDefault)).status).toBe('invalid');
  });
  it('shares a valid scalar fixture with Java and never serializes presentation identities as executable elements', () => {
    const graph = parseGraphML(readFileSync(resolve('../ravenroot-core/src/test/resources/graphml-corpus/accepted/visual-groups.graphml'), 'utf8'));
    const semantics = value => ({ nodes: value.nodes.map(node => [node.id, node.kind, node.behavior]),
      edges: value.edges.map(edge => [edge.id, edge.source, edge.target, edge.outcome, edge.parallel]) });
    const before = semantics(graph);
    const exported = serializeGraphML(graphWithVisualGroupPresentation(graph, { 'group-work': { collapsed: false } }));
    expect(semantics(parseGraphML(exported))).toEqual(before);
    expect(readVisualGroups(parseGraphML(exported)).groups[0].collapsed).toBe(false);
    editVisualGroups(graph, []);
    expect(semantics(parseGraphML(serializeGraphML(graph)))).toEqual(before);
  });
  it('saves rename, replacement, presentation and removal repeatedly without resurrecting source values', () => {
    let graph = parseGraphML(xml());
    const history = createCommandHistory();
    editVisualGroups(graph, [{ ...metadata().groups[0], name: 'Renamed', memberNodeIds: ['b', 'c'], anchorNodeId: 'b' }], history);
    graph = parseGraphML(serializeGraphML(graphWithVisualGroupPresentation(graph, { g: { collapsed: false } })));
    expect(readVisualGroups(graph).groups[0]).toMatchObject({ name: 'Renamed', memberNodeIds: ['b', 'c'], collapsed: false });
    editVisualGroups(graph, [], history, 'Ungroup');
    for (let i = 0; i < 3; i++) {
      const saved = serializeGraphML(graph);
      expect((saved.match(/<data key="rr-graph-ravenroot-ui-visualgroups"/g) || []).length).toBe(1);
      graph = parseGraphML(saved);
      expect(readVisualGroups(graph).groups).toEqual([]);
      expect(graph.graphProperties.owner).toBe('retained');
    }
  });

  it('preserves future and malformed raw data until explicit removal', () => {
    for (const value of ['{"version":99,"groups":[]}', 'broken', JSON.stringify(metadata([{ ...metadata().groups[0], memberNodeIds: ['a', 'missing'] }]))]) {
      const graph = parseGraphML(xml(value));
      expect(readVisualGroups(graph).groups).toEqual([]);
      expect(readVisualGroups(graph).warning).toContain('complete graph');
      expect(parseGraphML(serializeGraphML(graph)).graphProperties[KEY]).toBe(value);
      editVisualGroups(graph, []);
      expect(readVisualGroups(parseGraphML(serializeGraphML(graph))).groups).toEqual([]);
      expect(readVisualGroups(parseGraphML(serializeGraphML(graph))).status).toBe('valid');
    }
  });

  it('keeps duplicate group data visible as invalid and permits explicit repair only', () => {
    const source = xml().replace('<node id="a"/>', '<data key="groups">{}</data><node id="a"/>');
    const graph = parseGraphML(source);
    expect(graph.nodes).toHaveLength(3);
    expect(readVisualGroups(graph).status).toBe('invalid');
    expect(serializeGraphML(graph)).toContain('<data key="groups">{}</data>');
    editVisualGroups(graph, []);
    expect(readVisualGroups(parseGraphML(serializeGraphML(graph))).status).toBe('valid');
    expect(() => parseGraphML(xml().replace('<node id="a"/>', '<data key="other">duplicate</data><node id="a"/>'))).toThrow('Duplicate');
  });

  it('repairs duplicate metadata whose raw value already equals the empty tombstone and undo restores the warning', () => {
    const source = xml(JSON.stringify(metadata([]))).replace('<node id="a"/>', `<data key="groups">${JSON.stringify(metadata([]))}</data><node id="a"/>`);
    const graph = parseGraphML(source); const history = createCommandHistory();
    expect(readVisualGroups(graph).status).toBe('invalid');
    editVisualGroups(graph, [], history);
    expect(readVisualGroups(graph).status).toBe('valid');
    expect(readVisualGroups(parseGraphML(serializeGraphML(graph))).status).toBe('valid');
    history.undo(graph); expect(readVisualGroups(graph).status).toBe('invalid');
    history.redo(graph); expect(readVisualGroups(graph).status).toBe('valid');
  });

  it('removes stale key defaults and handles duplicate graph group declarations safely', () => {
    const source = xml().replace('attr.name="ravenroot.ui.visualGroups" attr.type="string"/>', `attr.name="ravenroot.ui.visualGroups" attr.type="string"><default>${JSON.stringify(metadata())}</default></key>`);
    const graph = parseGraphML(source);
    editVisualGroups(graph, []);
    expect(serializeGraphML(graph)).not.toContain('<default>');
    const duplicate = parseGraphML(xml(undefined, `<key id="groups2" for="graph" attr.name="${KEY}" attr.type="string"/>`));
    expect(readVisualGroups(duplicate).status).toBe('invalid');
    editVisualGroups(duplicate, []);
    expect(readVisualGroups(parseGraphML(serializeGraphML(duplicate))).status).toBe('valid');
  });

  it('repairs an all-scope grouping key while retaining non-graph defaults and opaque data', () => {
    const source = xml().replace('<graphml xmlns=', '<graphml xmlns:z="urn:visual-fixture" xmlns=')
      .replace('id="groups" for="graph"', 'id="groups" for="all"')
      .replace('attr.name="ravenroot.ui.visualGroups" attr.type="string"/>',
        'attr.name="ravenroot.ui.visualGroups" attr.type="string"><default>opaque-default</default></key>')
      .replace('<node id="a"/>', '<node id="a"><data key="groups"><z:payload value="retained"/></data></node>')
      .replace('<edge id="ab" source="a" target="b"/>', '<edge id="ab" source="a" target="b"><data key="groups"><z:route value="kept"/></data></edge>');
    const graph = parseGraphML(source);
    expect(readVisualGroups(graph).status).toBe('invalid');
    editVisualGroups(graph, []);
    const saved = serializeGraphML(graph);
    const parsed = parseGraphML(saved);
    expect(readVisualGroups(parsed).status).toBe('valid');
    expect(saved).toContain('<z:payload value="retained"/>');
    expect(saved).toContain('<z:route value="kept"/>');
    const document = new DOMParser().parseFromString(saved, 'application/xml');
    for (const scope of ['node', 'edge']) {
      const key = [...document.getElementsByTagName('key')].find(key => key.getAttribute('for') === scope && key.getAttribute('attr.name') === KEY);
      expect(key.getElementsByTagName('default')[0].textContent).toBe('opaque-default');
    }
  });

  it('warns for an all-scope complex default without graph data and preserves it until explicit repair', () => {
    const source = xml().replace('<graphml xmlns=', '<graphml xmlns:z="urn:visual-fixture" xmlns=')
      .replace('id="groups" for="graph"', 'id="groups" for="all"')
      .replace(`attr.name="${KEY}" attr.type="string"/>`,
        `attr.name="${KEY}" attr.type="string"><default><z:payload value="opaque-default"/></default></key>`)
      .replace(/<data key="groups">[^<]*<\/data>/, '')
      .replace('<node id="a"/>', '<node id="a"><data key="groups"><z:payload value="retained"/></data></node>');
    const graph = parseGraphML(source);
    expect(readVisualGroups(graph)).toMatchObject({ status: 'invalid', groups: [] });
    expect(readVisualGroups(graph).warning).toBeTruthy();
    const untouched = serializeGraphML(graph);
    expect(untouched).toContain('<default><z:payload value="opaque-default"/></default>');
    expect(untouched).toContain('for="all"');
    expect(readVisualGroups(parseGraphML(untouched)).status).toBe('invalid');
    editVisualGroups(graph, []);
    const saved = serializeGraphML(graph);
    expect(readVisualGroups(parseGraphML(saved))).toMatchObject({ status: 'valid', groups: [] });
    expect(saved).toContain('<z:payload value="retained"/>');
    const document = new DOMParser().parseFromString(saved, 'application/xml');
    const keys = [...document.getElementsByTagName('key')].filter(key => key.getAttribute('attr.name') === KEY);
    expect(keys.filter(key => ['all', 'graph'].includes(key.getAttribute('for')))).toHaveLength(1);
    for (const scope of ['node', 'edge']) {
      const key = keys.find(key => key.getAttribute('for') === scope);
      expect(key.getElementsByTagName('default')[0].firstElementChild.getAttribute('value')).toBe('opaque-default');
    }
  });

  it('rejects overlap, missing anchors, empty names and duplicate group IDs atomically', () => {
    const graph = parseGraphML(xml());
    const original = graph.graphProperties[KEY];
    expect(() => createVisualGroup(graph, ['a', 'b'], 'Another', 'a')).toThrow('overlap');
    for (const patch of [{ anchorNodeId: 'z' }, { name: ' ' }, { memberNodeIds: ['a', 'a'] }]) {
      expect(() => validateVisualGroups(metadata([{ ...metadata().groups[0], ...patch }]), graph)).toThrow();
    }
    expect(graph.graphProperties[KEY]).toBe(original);
  });

  it('deletes members and restores group and edges in one history step, dissolving undersized groups', () => {
    const graph = parseGraphML(xml());
    const history = createCommandHistory();
    deleteElements(graph, ['a', 'b'], [], history);
    expect(readVisualGroups(graph).groups).toEqual([]);
    expect(graph.nodes.map(n => n.id)).toEqual(['c']);
    history.undo(graph);
    expect(readVisualGroups(graph).groups).toEqual(metadata().groups);
    expect(graph.edges.map(e => e.id)).toEqual(['ab', 'bc']);
    history.redo(graph);
    expect(graph.nodes.map(n => n.id)).toEqual(['c']);
  });

  it('moves members rigidly in one command and keeps presentation separate from semantic history', () => {
    const graph = parseGraphML(xml());
    graph.nodes.forEach((n, i) => Object.assign(n, createNode(n.id, n.id, 'PASSTHROUGH', { x: i * 100, y: 0 })));
    const history = createCommandHistory();
    moveNodesTo(graph, graph.nodes.map(n => ({ id: n.id, ox: n.ox + 20, oy: n.oy + 30 })), history, 'Move visual group');
    expect(graph.nodes.map(n => n.ox)).toEqual([20, 120, 220]);
    history.undo(graph);
    expect(graph.nodes.map(n => n.ox)).toEqual([0, 100, 200]);
    const state = reconcileVisualGroupState(metadata().groups, { g: { collapsed: false, anchorNodeId: 'missing', lastSelectedNodeIds: ['b', 'missing'] } });
    expect(state.g).toEqual({ collapsed: false, anchorNodeId: 'a', lastSelectedNodeIds: ['b'] });
    expect(readVisualGroups(graph).groups[0].collapsed).toBe(true);
  });

  it('restores independent same-name immutable document presentation without mutating pinned graphs', () => {
    const graph = parseGraphML(xml());
    const owner = createDocumentRecord({ id: 'one', graph, tenantId: 'tenant', mode: 'test', name: 'same.graphml' });
    owner.visualGroupState = { g: { collapsed: false, anchorNodeId: 'b', lastSelectedNodeIds: ['b'] } };
    owner.visualGroupPresentationDirty = true;
    owner.canvasState = { zoom: 1.4, pan: { x: 31, y: 29 }, positions: { b: { x: 20, y: 40 } }, focusNodeId: 'b', selectedIds: ['b'] };
    const before = JSON.stringify(graph);
    const fork = forkDocumentRecord(owner, { documentId: 'two' });
    fork.visualGroupState.g.collapsed = true;
    const scope = workspaceScope('https://runtime.example', 'tenant');
    const restored = validateWorkspaceSnapshot(workspaceSnapshot(scope, [owner, fork], 'one'), scope);
    expect(restored.documents[0].presentation.visualGroupState.g.collapsed).toBe(false);
    expect(restored.documents[1].presentation.visualGroupState.g.collapsed).toBe(true);
    expect(restored.documents[0].mode).toBe('test');
    expect(persistedDocument(owner).presentation.canvasState.zoom).toBe(1.4);
    expect(JSON.stringify(graph)).toBe(before);
  });
});
