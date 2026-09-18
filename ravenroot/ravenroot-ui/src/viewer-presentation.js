import { getRendererPalette } from './theme-palette.js';
import { COMMON_NODE_GLYPHS } from './catalog-node-icon.js';

export const VIEWER_NODE_ICONS = Object.freeze({
  start: '▶ ', end: '⏹ ', error: '⚠ ', terminal: '⊙ ', consumer: '⩓ ',
  handler: '↩ ', agent: '⬡ ', flow: '⚙ ', actor: '◉ ', system: '▪ ',
  behavior: '⚙ ', passthrough: '• ',
  workspace: `${COMMON_NODE_GLYPHS.workspace} `,
  trace: `${COMMON_NODE_GLYPHS.trace} `,
  'human-task': `${COMMON_NODE_GLYPHS['human-task']} `,
});

export function viewerNodeType(node) {
  return String(node?.visualType || node?.nodeType || node?.kind || 'actor')
    .toLowerCase();
}

export function viewerNodeSize(node) {
  const type = viewerNodeType(node);
  if (type === 'start' || type === 'end') return [84, 84];
  if (type === 'error') return [72, 72];
  const name = String(node?.label || node?.name || node?.id || '');
  return [Math.max(90, Math.min(230, name.length * 7.8 + 52)), 52];
}

export const VIEWER_CARD_GLYPH = Object.freeze({
  start: '▶', end: '■', error: '⚠', terminal: '⊙', consumer: '⧒', handler: '↩',
  flow: '⚙', actor: '◎', system: '▤', behavior: '⚙', passthrough: '↩',
  trace: COMMON_NODE_GLYPHS.trace, 'human-task': COMMON_NODE_GLYPHS['human-task'],
});

function agentCardSvg(palette) {
  return `<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 400 400' width='80' height='80'>
 <g fill='none' stroke='${palette.nodeType.agent}' stroke-width='8' stroke-linecap='round' stroke-linejoin='round'>
    <path d='M 200 45 C 170 45, 155 60, 140 75 C 115 65, 90 85, 95 115 C 70 120, 75 160, 90 170 C 70 185, 75 225, 95 230 C 80 250, 90 285, 115 290 C 110 320, 145 340, 170 330 C 185 345, 195 355, 200 355' />
    <path d='M 200 45 C 230 45, 245 60, 260 75 C 285 65, 310 85, 305 115 C 330 120, 325 160, 310 170 C 330 185, 325 225, 305 230 C 320 250, 310 285, 285 290 C 290 320, 255 340, 230 330 C 215 345, 205 355, 200 355' />
    <line x1='200' y1='65' x2='200' y2='335' />
    <path d='M 185 290 L 185 175 L 145 135 L 145 110' />
    <path d='M 170 260 L 170 215 L 125 185 L 125 165' />
    <path d='M 155 295 L 125 295 L 125 255 L 140 255' />
    <path d='M 215 290 L 215 175 L 255 135 L 255 110' />
    <path d='M 230 260 L 230 215 L 275 185 L 275 165' />
    <path d='M 245 295 L 275 295 L 275 255 L 260 255' />
    <circle cx='145' cy='110' r='9' /><circle cx='125' cy='165' r='9' />
    <circle cx='140' cy='255' r='9' /><circle cx='255' cy='110' r='9' />
    <circle cx='275' cy='165' r='9' /><circle cx='260' cy='255' r='9' />
  </g>
</svg>`;
}

export function viewerCardImage(nodeType, palette) {
  if (nodeType === 'agent') return `data:image/svg+xml,${encodeURIComponent(agentCardSvg(palette))}`;
  const glyph = VIEWER_CARD_GLYPH[nodeType] || '◎';
  const svg = `<svg xmlns='http://www.w3.org/2000/svg' width='80' height='80'>`
    + `<text x='40' y='40' text-anchor='middle' dominant-baseline='central' `
    + `font-size='32' fill='${palette.nodeText}' `
    + `font-family='system-ui,-apple-system,sans-serif'>${glyph}</text></svg>`;
  return `data:image/svg+xml,${encodeURIComponent(svg)}`;
}

/**
 * The single Cytoscape presentation contract used by the editor canvas and every read-only viewer.
 * Shell-only affordances are classes added by the editor; node/edge identity and runtime state live
 * here so an embedded graph cannot drift into a second visual language.
 */
export function createViewerStylesheet(themeOrPalette = 'dark', mode = 'cyto') {
  const palette = typeof themeOrPalette === 'string'
    ? getRendererPalette(themeOrPalette)
    : themeOrPalette;
  const node = palette.nodeType;
  const edge = palette.edgeType;
  const surface = palette.nodeSurfaceByType;
  return [
    { selector: 'node', style: {
      shape: 'roundrectangle', width: 'data(nw)', height: 'data(nh)',
      'background-color': palette.nodeSurface, 'border-width': 2,
      'border-color': palette.nodeBorder, label: 'data(label)', color: palette.nodeText,
      'font-size': '20px',
      'font-family': '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
      'text-valign': 'center', 'text-halign': 'center', 'text-wrap': 'wrap',
      'text-max-width': '210px', padding: '10px',
      'transition-property': 'border-color, background-color, opacity',
      'transition-duration': '150ms',
    } },
    { selector: 'node[nodeType="start"]', style: {
      shape: 'ellipse', 'background-color': surface.start, 'border-color': node.start,
      'border-width': 3, 'font-weight': 'bold', 'font-size': '13px',
    } },
    { selector: 'node[nodeType="end"]', style: {
      shape: 'ellipse', 'background-color': surface.end, 'border-color': node.end,
      'border-width': 3, 'font-weight': 'bold', 'font-size': '13px',
    } },
    { selector: 'node[nodeType="terminal"]', style: {
      shape: 'ellipse', 'background-color': surface.terminal, 'border-color': node.terminal,
      'border-width': 2.5,
    } },
    { selector: 'node[nodeType="error"]', style: {
      shape: 'diamond', 'background-color': surface.error, 'border-color': node.error,
      'border-width': 2.5,
    } },
    ...['handler', 'flow', 'agent', 'consumer', 'actor', 'system', 'workspace', 'trace', 'human-task']
      .map(type => ({
      selector: `node[nodeType="${type}"]`, style: {
        ...(type === 'system' ? { shape: 'rectangle' } : {}),
        'background-color': surface[type], 'border-color': node[type],
        'border-width': type === 'system' ? 1.5
          : (type === 'workspace' ? 3 : (type === 'human-task' ? 2.5 : 2)),
      },
    })),
    { selector: 'node[humanTaskPending > 0]', style: {
      'underlay-color': palette.focus, 'underlay-opacity': 0.22, 'underlay-padding': 9,
      'border-style': 'double', 'border-width': 4,
    } },
    { selector: 'node[humanTaskEscalated > 0]', style: {
      'underlay-color': node.error, 'underlay-opacity': 0.32, 'underlay-padding': 11,
      'border-style': 'double', 'border-width': 5,
    } },
    { selector: 'node.human-task-pulse[humanTaskPending > 0]', style: {
      'underlay-opacity': 0.45, 'underlay-padding': 15,
    } },
    { selector: 'node[nodeType="behavior"]', style: {
      'background-color': surface.flow, 'border-color': node.flow, 'border-width': 2,
    } },
    { selector: 'node[nodeType="passthrough"]', style: {
      'background-color': surface.handler, 'border-color': node.handler, 'border-width': 2,
    } },
    { selector: 'node[?bypassed], node[runtimeState="bypassed"]', style: {
      'border-style': 'dashed', 'border-color': node.system, 'border-width': 2.5,
    } },
    { selector: 'node[runtimeState="active"]', style: {
      'border-color': palette.focus, 'border-width': 5,
      'underlay-color': palette.focus, 'underlay-opacity': 0.2, 'underlay-padding': 8,
    } },
    { selector: 'node[runtimeState="completed"]', style: {
      'border-color': node.start, 'border-width': 4,
    } },
    { selector: 'node[runtimeState="fallback"], node[?fallback]', style: {
      'border-color': edge.validate, 'border-width': 4,
      'underlay-color': edge.validate, 'underlay-opacity': 0.12, 'underlay-padding': 7,
    } },
    { selector: 'node[runtimeState="failed"]', style: {
      'border-color': node.error, 'border-width': 5,
    } },
    { selector: 'node:active', style: { 'overlay-opacity': 0.1 } },
    { selector: 'edge', style: {
      width: 1.5, 'line-color': edge.default, 'target-arrow-shape': 'triangle',
      'target-arrow-color': edge.default, 'curve-style': 'bezier', label: 'data(label)',
      'font-size': '10px', color: palette.edgeLabel, 'text-background-opacity': 1,
      'text-background-color': palette.edgeLabelSurface, 'text-background-padding': '2px',
      'text-background-shape': 'roundrectangle', 'edge-text-rotation': 'autorotate',
      'transition-property': 'opacity, width', 'transition-duration': '150ms',
    } },
    { selector: 'edge[edgeType="failed"]', style: {
      'line-color': edge.failed, 'target-arrow-color': edge.failed, width: 2,
      'line-style': 'dashed', 'line-dash-pattern': [6, 3], color: edge.failed,
    } },
    { selector: 'edge[edgeType="failure"]', style: {
      'line-color': edge.failure, 'target-arrow-color': edge.failure,
      'target-arrow-shape': 'triangle-tee', width: 2.5, 'line-style': 'dashed',
      'line-dash-pattern': [2, 4], color: edge.failure,
    } },
    ...['completed', 'continue', 'continueP', 'validate', 'ping', 'outcome', 'undefined', 'callback']
      .map(type => ({ selector: `edge[edgeType="${type}"]`, style: {
        'line-color': edge[type], 'target-arrow-color': edge[type], color: edge[type],
        width: type === 'continueP' ? 4.5
          : (type === 'completed' || type === 'outcome' ? 2.5
            : (type === 'ping' || type === 'undefined' ? 1.5 : 2)),
        ...(['ping', 'undefined'].includes(type) ? { 'line-style': 'dashed' } : {}),
        ...(type === 'ping' ? { 'line-dash-pattern': [4, 4] } : {}),
      } })),
    { selector: 'edge[?runtimeActive]', style: {
      width: 'mapData(runtimeRecent, 0, 8, 2, 8)',
      'line-color': palette.focus, 'target-arrow-color': palette.focus,
      'underlay-color': palette.focus, 'underlay-opacity': 0.16, 'underlay-padding': 4,
    } },
    { selector: 'node:selected', style: {
      'border-color': palette.selection, 'border-width': 4,
    } },
    { selector: 'edge:selected', style: {
      'underlay-color': palette.selection, 'underlay-opacity': 0.38,
      'underlay-padding': 5, 'z-index': 10,
    } },
    { selector: '.connect-source', style: {
      'border-color': palette.focus, 'border-width': 5,
      'underlay-color': palette.focus, 'underlay-opacity': 0.18, 'underlay-padding': 10,
    } },
    { selector: '.graph-cursor', style: {
      'border-color': palette.focus, 'border-width': 4,
      'underlay-color': palette.focus, 'underlay-opacity': 0.22, 'underlay-padding': 8,
    } },
    { selector: '.connect-valid', style: {
      'border-color': node.start, 'border-width': 4,
      'underlay-color': node.start, 'underlay-opacity': 0.2, 'underlay-padding': 8,
    } },
    { selector: '.connect-invalid', style: {
      'border-color': node.end, 'border-width': 4,
      'underlay-color': node.end, 'underlay-opacity': 0.2, 'underlay-padding': 8,
    } },
    { selector: '.edge-reconnecting', style: {
      'line-style': 'dashed', 'line-color': palette.focus,
      'target-arrow-color': palette.focus, width: 3,
    } },
    { selector: '.dim', style: { opacity: 0.06 } },
    { selector: '.hi', style: { opacity: 1 } },
    { selector: '.trace-start', style: {
      'border-color': palette.nodeText, 'border-width': 4,
      'background-color': palette.traceStart,
    } },
    { selector: '.trace-node', style: {
      'border-color': palette.trace, 'border-width': 3,
    } },
    { selector: '.trace-edge', style: {
      'line-color': palette.trace, 'target-arrow-color': palette.trace, width: 3.5,
    } },
    { selector: 'edge:selected.dim', style: { opacity: 1 } },
    ...(mode === 'n8n' ? [
      { selector: 'node', style: {
        shape: 'roundrectangle', width: 80, height: 80,
        'border-width': 2.5,
        label: 'data(cardLabel)',
        'text-valign': 'bottom', 'text-margin-y': 10,
        'font-size': '20px', 'font-weight': '500', padding: '0px', 'text-wrap': 'none',
      } },
      ...Object.keys(VIEWER_CARD_GLYPH).map(type => ({
        selector: `node[nodeType="${type}"]`,
        style: { 'background-image': viewerCardImage(type, palette),
          'background-width': '100%', 'background-height': '100%',
          'background-fit': 'none', 'background-clip': 'none' },
      })),
      { selector: 'edge', style: {
        ...(mode === 'n8n' ? { 'curve-style': 'taxi', 'taxi-direction': 'auto',
          'taxi-turn': '50%', 'taxi-turn-min-distance': 20, 'taxi-radius': 28,
          'source-endpoint': 'outside-to-line', 'target-endpoint': 'outside-to-line', width: 2.5 } : {}),
      } },
    ] : []),
    ...(mode === 'elastic' ? [
      { selector: 'node', style: {
        shape: 'ellipse', width: 38, height: 38,
        'text-valign': 'bottom', 'text-margin-y': 26,
      } },
      { selector: 'edge', style: { 'curve-style': 'bezier', width: 1.5 } },
    ] : []),
  ];
}

export function viewerNodeLabel(node) {
  const type = viewerNodeType(node);
  const label = String(node?.label || node?.name || node?.id || '');
  const bypassed = Boolean(node?.bypassed);
  return `${VIEWER_NODE_ICONS[type] || '• '}${label}${bypassed ? ' · bypassed' : ''}`;
}

export function viewerCardLabel(node) {
  const label = String(node?.label || node?.name || node?.id || '');
  return `${label}${node?.bypassed ? ' · bypassed' : ''}`;
}

/** Closed mapping from the allowlisted wire projection into renderer data. */
export function viewerProjectionElements(nodes, edges) {
  return [
    ...nodes.map(node => ({
      // Kept here instead of each caller so static snapshots and deployment views compute the same
      // fallback bounds when a capture has no authored layout.
      data: {
        id: node.id,
        label: viewerNodeLabel(node),
        cardLabel: viewerCardLabel(node),
        name: node.label || node.name || node.id,
        nodeType: viewerNodeType(node),
        bypassed: Boolean(node.bypassed),
        runtimeState: 'idle', runtimeObserved: false,
        nw: node.layout?.width ?? node.width ?? viewerNodeSize(node)[0],
        nh: node.layout?.height ?? node.height ?? viewerNodeSize(node)[1],
      },
      ...(node.layout ? { position: { x: node.layout.x, y: node.layout.y } } : {}),
    })),
    ...edges.map((edge, index) => ({
      data: {
        id: edge.id || `viewer-edge-${index}`,
        source: edge.source, target: edge.target,
        label: edge.label || '', edgeType: edge.visualType || edge.edgeType || 'continue',
        runtimeActive: false, runtimeRecent: 0, runtimeCount: 0,
      },
    })),
  ];
}
