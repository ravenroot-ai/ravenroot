import { describe, expect, it } from 'vitest';

import {
  VIEWER_CARD_GLYPH,
  VIEWER_NODE_ICONS,
  createViewerStylesheet,
  viewerCardImage,
  viewerCardLabel,
  viewerNodeLabel,
  viewerNodeSize,
  viewerNodeType,
  viewerProjectionElements,
} from '../src/viewer-presentation.js';

describe('shared viewer presentation', () => {
  it('uses the editor node vocabulary for an allowlisted snapshot without a visual override', () => {
    expect(['START', 'PASSTHROUGH', 'BEHAVIOR', 'END', 'ERROR'].map(kind =>
      viewerNodeType({ kind }))).toEqual(['start', 'passthrough', 'behavior', 'end', 'error']);
    expect(viewerNodeLabel({ id: 'worker', kind: 'BEHAVIOR' }))
      .toBe(`${VIEWER_NODE_ICONS.behavior}worker`);
    expect(viewerNodeLabel({ id: 'route', kind: 'PASSTHROUGH', bypassed: true }))
      .toBe(`${VIEWER_NODE_ICONS.passthrough}route · bypassed`);
    expect(viewerNodeType({ kind: 'BEHAVIOR', visualType: 'actor' })).toBe('actor');
  });

  it('computes the same fallback bounds and element data for native and embedded callers', () => {
    const node = { id: 'orders', kind: 'BEHAVIOR', label: 'Orders' };
    const [width, height] = viewerNodeSize(node);
    const [element] = viewerProjectionElements([node], []);
    expect(element.data).toMatchObject({
      id: 'orders', name: 'Orders', cardLabel: 'Orders', nodeType: 'behavior', nw: width, nh: height,
    });
    expect(viewerCardLabel({ label: 'Orders', bypassed: true })).toBe('Orders · bypassed');
  });

  it('defines N8N card geometry and taxi routing in the shared stylesheet', () => {
    const styles = createViewerStylesheet('dark', 'n8n');
    const node = styles.find(entry => entry.selector === 'node' && entry.style.label === 'data(cardLabel)');
    const edge = styles.findLast(entry => entry.selector === 'edge');
    expect(node.style).toMatchObject({ shape: 'roundrectangle', width: 80, height: 80 });
    expect(edge.style).toMatchObject({
      'curve-style': 'taxi', 'taxi-direction': 'auto',
      'source-endpoint': 'outside-to-line', 'target-endpoint': 'outside-to-line', width: 2.5,
    });
  });

  it('keeps Cyto dimensions and labels while N8N alone applies card presentation', () => {
    const cyto = createViewerStylesheet('dark', 'cyto');
    const cytoNode = cyto.find(entry => entry.selector === 'node');
    expect(cytoNode.style).toMatchObject({
      width: 'data(nw)', height: 'data(nh)', label: 'data(label)',
    });
    expect(cyto.some(entry => entry.style?.['background-image'])).toBe(false);

    const n8n = createViewerStylesheet('dark', 'n8n');
    expect(n8n.some(entry => entry.selector === 'node'
      && entry.style.label === 'data(cardLabel)')).toBe(true);
    expect(n8n.some(entry => entry.style?.['background-image'])).toBe(true);
  });

  it('preserves the public N8N glyph vocabulary and dedicated agent artwork', () => {
    expect(VIEWER_CARD_GLYPH).toMatchObject({
      consumer: '⧒', actor: '◎', system: '▤', trace: '▤', 'human-task': '👤',
    });
    expect(VIEWER_NODE_ICONS).toMatchObject({ workspace: '▣ ', trace: '▤ ', 'human-task': '👤 ' });
    const image = decodeURIComponent(viewerCardImage('agent', {
      nodeText: '#fff', nodeType: { agent: '#58a6ff' },
    }));
    expect(image).toContain("viewBox='0 0 400 400'");
    expect(image).toContain("stroke='#58a6ff'");
    expect(image).not.toContain('🧠');
  });

  it.each(['dark', 'light'])('defines editor and runtime styles for %s', theme => {
    const styles = createViewerStylesheet(theme);
    const selectors = styles.map(entry => entry.selector);
    expect(selectors).toEqual(expect.arrayContaining([
      'node[nodeType="actor"]', 'edge[edgeType="failure"]', 'node[runtimeState="active"]',
      'node[runtimeState="fallback"], node[?fallback]',
      'edge[?runtimeActive]', '.graph-cursor', '.trace-edge',
    ]));
    const fallback = styles.find(entry => entry.selector
      === 'node[runtimeState="fallback"], node[?fallback]');
    expect(fallback.style).toMatchObject({
      'border-color': expect.any(String), 'border-width': 4,
      'underlay-color': expect.any(String), 'underlay-opacity': 0.12,
    });
  });
});
