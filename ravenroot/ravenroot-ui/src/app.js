import cytoscape from 'cytoscape';
import cytoscapeDagre from 'cytoscape-dagre';
import cytoscapeElk from 'cytoscape-elk';
import cytoscapeEuler from 'cytoscape-euler';
import * as d3 from 'd3';
import {
  detectAndParse,
  GFY_MAX_WARN,
  GFY_SAMPLE,
  loadLocalGraphInput,
  loadUrlGraphInput,
  sampleLargeGraph,
} from './graph-parsers.js';
import {
  DEFAULT_EDGE_OUTCOME,
  NODE_KINDS,
  additionalProperties,
  classifyFailureRoutes,
  createEdge,
  createNode,
  createWorkflowDocument,
  declaredJoinKind,
  edgeDeclaresFailureRoute,
  edgeFailureRouteKind,
  effectiveJoinArrival,
  hasDeclaredJoinSemantics,
  JOIN_KIND_OPTIONS,
  JOIN_KIND_UNRECOGNIZED,
  JOIN_POLICY_PROPERTY,
  JOIN_QUORUM_PROPERTY,
  JOIN_TIMEOUT_PROPERTY,
  joinKindProperties,
  quorumWouldCollideWithLegacyStamp,
  kindOwnsNodeType,
  kindToNodeType,
  NODE_VISUAL_TYPES,
  outcomeToEdgeType,
  planJoinSemanticsMigration,
  serializeGraphML,
  setEdgeFailureRoute,
  validateWorkflow,
} from './graph-document.js';
import { catalogEmptyState } from './catalog-empty-state.js';
import { catalogNodeIcon, resolveDescriptorNodeType } from './catalog-node-icon.js';
import { createLayoutSessions } from './layout-session.js';
import { createRendererSessions } from './renderer-session.js';
import { renderNodeCatalogItems } from './node-catalog-view.js';
import {
  canvasInteractionState,
  isAdditiveSelection,
  modelPositionFromClient,
  nodeCanSourceEdge,
  nodeIsGrabbable,
  selectionAfterClick,
  stageTapAction,
  STAGE_TAP_ACTION,
} from './graph-interaction.js';
import { traceDownstreamIds } from './graph-trace.js';
import { adapterIdOf, catalogPropertyHasDeclaredDefault } from './adapter-binding.js';
// Now the WHOLE of this file's involvement with model providers: one sentence for the
// Inspector's unconfigured hint, and the predicate that decides which nodes get it. The panel, its
// transport and the three `model-provider-*` modules are gone — the AI node types leave the core for
// a plugin bundle, and the bundle that supplies a node type supplies its configuration surface too,
// so a panel here would be a second place to declare the same thing.
import { invokesModelProvider, PROVIDER_CONFIG_POINTER } from './generative-capability.js';
// The credential window and its transport. Same split, same reason: this file owns only the
// construction site and the one connection between the window and the node inspector's
// SECRET_REFERENCE control.
import { RavenrootCredentialClient } from './credential-client.js';
import { createCredentialsWindow } from './credential-panel.js';
// A construction site of the same shape as the credential window above, and for the
// same reason: the Deployments window reaches THE SAME Ravenroot service with THE SAME runtime
// client -- it is not a separate transport, unlike credentials, because `/v1/deployments` is already
// part of `RavenrootRuntimeClient`. The Deployments window owns registration and control.
import { createDeploymentsWindow } from './deployment-panel.js';
import {
  rendererEdgePath,
  rendererEdgeRouteToRendered,
  resolveCytoEdgeRouteUpdate,
  resolveRendererEdgeRoute,
  resolveRendererEdgeRoutes,
} from './renderer-edge-route.js';
import {
  applyViewerRoundedSegmentRoute,
  applyViewerUnbundledRoute,
} from './viewer-edge-style.js';
import { mountD3ElasticRenderer } from './viewer-elastic-renderer.js';
import {
  edgeFlowSnapshot,
  FLOW_PULSE_MS,
  bindMonitoringRuntimeState,
  createMonitoringRuntimeState,
  observeEdgeTraversal,
  resetMonitoringRuntimeState,
} from './monitoring-runtime-state.js';
import { isPropertyVisible, isPropertyRequiredNow } from './property-condition.js';
import {
  commonMultiSelectionProperties,
  MULTI_PROPERTY_STATE,
  planMultiPropertyUpdate,
  propertyStateLabel,
  sameSelectedIds,
} from './multi-selection-inspector.js';
import {
  BYPASS_TRUE,
  DEFAULT_BYPASS_PROPERTY,
  bypassPropertyName,
  bypassRoutingConsequence,
  declaredBypass,
  isNodeBypassed,
  nodeAcceptsBypass,
  untakenBypassOutcomes,
} from './node-bypass.js';
import {
  DEFAULT_MAX_CONCURRENCY_PROPERTY,
  DEFAULT_NATURE,
  DEFAULT_NATURE_PROPERTY,
  effectiveMaxConcurrency,
  effectiveNature,
  natureLabel,
  natureRiskText,
} from './node-nature.js';
import { resolveOutcomes, unreachableOutcome } from './node-outcomes.js';
import { RavenrootRuntimeClient, memoryTokenProvider } from './runtime-client.js';
import {
  captureSourceSessionToken,
  effectiveSourceCount,
  recoverSourceSessionState,
  sourceSessionIsActive,
  sourceSessionTokenIsCurrent,
} from './source-session.js';
import {
  acquireExecutionCommand,
  captureExecutionOutcomeToken,
  claimExecutionOutcomeFetch,
  claimExecutionOutcomeReport,
  completeExecutionOutcomeFetch,
  enforceExecutionOutcomeCapacity,
  executionCommandIsCurrent,
  executionOutcomeFetchSignal,
  preflightBoundExecutionCommand,
  reconcileExecution,
  releaseExecutionCommand,
  retireExecutionOutcomeClaim,
} from './execution-reconciliation.js';
import { publicExecutionDescription } from './execution-event-description.js';
import { runtimeActivityMessage, runtimeActivityOutput } from './runtime-activity-data.js';
import { executionOutcomeMessages } from './execution-outcome-description.js';
import { RavenrootAssistantClient } from './assistant-client.js';
import {
  applyAssistantGraphProposal,
  catalogProposalDigest,
  planAssistantGraphProposal,
  rejectAssistantGraphProposal,
} from './assistant-graph-proposal.js';
import {
  assistantCatalogSnapshot,
  graphSummary,
  runtimeEventProjection,
  validationFindings,
} from './assistant-projection.js';
import {
  ATTACHED,
  composeContext,
  contextChipDescription,
} from './assistant-context.js';
import {
  ASSISTANT,
  AUTHOR,
  NOTICE,
  appendTurn,
  connectionFailureText,
  deriveState,
  offersConnection,
  validateDraft,
} from './assistant-session.js';
import {
  DISCLOSURE_ROLE,
  admitTurn,
  describedById,
} from './assistant-disclosure.js';
import {
  PENDING_EXECUTION,
  createDocumentIncarnation,
  createDocumentRecord,
  createWorkspace,
  detachExecution,
  documentForRuntimeEvent,
  hasUnsavedWork,
} from './workspace.js';
import {
  captureDocumentCloseSnapshot,
  classifyDocumentCloseTargets,
  resolveDocumentCloseSnapshot,
} from './document-close-plan.js';
import {
  PANE_MIN_WIDTH,
  PANE_HEADER_HEIGHT,
  SPLITTER_KEY_STEP,
  STAGE_MIN_HEIGHT,
  resizeSplit,
  separatorPosition,
  separatorRange,
} from './panes.js';
import {
  WORKSPACE_LAYOUT_STORAGE_KEY,
  defaultWorkspaceLayout,
  equalAxisShares,
  planWorkspaceLayout,
  readableZoomForFonts,
  resizeAxisShares,
  serializeWorkspaceLayout,
  validateWorkspaceLayout,
} from './workspace-layout.js';
import {
  LAYOUT_STORAGE_KEY,
  PANELS,
  ZONES,
  defaultLayout,
  isPanelStackCompact,
  isZoneEmpty,
  movePanelToZone,
  movePanelToZoneAt,
  movePanelWithinZone,
  openPanelsInZone,
  panelDescriptor,
  panelMenuCommands,
  panelsInZone,
  setPanelClosed,
  setPanelSizes,
  setPanelShort,
  setDockMaximised,
  setZoneCollapsed,
  setZoneDimension,
  validateLayout,
  zoneLabel,
} from './panel-layout.js';
import {
  addConnectedNodeAt,
  addNodeAt,
  canDuplicateNode,
  canModifyGraph,
  connectNodes,
  deleteElements,
  duplicateNode,
  insertEdgeElement,
  insertNodeElement,
  migrateJoinSemantics,
  moveNodesTo,
  nextModifyState,
  reconnectEdge,
  uniqueElementId,
  updateEdgeFields,
  updateNodeFields,
  updateNodePropertiesBatch,
} from './graph-editing.js';
import {
  beginConnectGesture,
  beginPointerEdgeGesture,
  beginReconnectGesture,
  createEdgeGestureSession,
  describeEdge,
  describeEdgeGesture,
  edgeGestureCandidate,
  edgeGestureSessionOwns,
  finishPointerEdgeGesture,
  nearestEndpoint,
  updatePointerEdgeGesture,
  validateEdgeConnection,
  validateEdgeId,
} from './edge-gestures.js';
import {
  commandTargets,
  createCommandHistory,
  discardChangesMessage,
  shouldWarnBeforeUnload,
} from './graph-commands.js';
import {
  DEFAULT_RENDER_MODE,
  DEFAULT_VISUAL_STYLE,
  documentPresentationState,
  normalizeRenderMode,
  normalizeVisualStyle,
  renderModePresentation,
  renderGraphStatistics,
  syncGraphPositionsFromCy,
} from './graph-view-state.js';
import { createCommandRegistry } from './command-registry.js';
import { requestGraphLifecycle } from './graph-lifecycle.js';
import { createAppCommands, createNodeActionCatalog } from './app-commands.js';
import { uiText } from './ui-text.js';
import {
  nodePatchChanged,
  readInspectorAutosavePreference,
  writeInspectorAutosavePreference,
} from './inspector-autosave.js';
import { createVisualTooltip } from './visual-tooltip.js';
import { createContextualHelp } from './contextual-help.js';
import { bindNodeActionScaleControl } from './node-action-scale.js';
import { createThemePreferenceController, normalizeTheme } from './theme-preference.js';
import { getRendererPalette } from './theme-palette.js';
import { createCytoscapeReadOnlyRendererAdapter } from './viewer-renderer-adapter.js';
import {
  clampViewportCenter,
  minimapToWorld,
  normalizeBounds,
  projectMinimap,
} from './minimap-geometry.js';
import {
  defaultLanguageId,
  exampleSourceForLanguage,
  programLanguageOptionsHtml,
} from './program-language.js';

'use strict';

let applicationTheme = normalizeTheme(document.documentElement.dataset.theme) || 'dark';
let rendererPalette = getRendererPalette(applicationTheme);

const visualTooltip = createVisualTooltip({
  root: document,
  tooltip: document.getElementById('visual-tooltip'),
  scope: '#main, #contextual-help-popover',
  window,
});
const contextualHelp = createContextualHelp({
  root: document,
  popover: document.getElementById('contextual-help-popover'),
  window,
});

function nodeSize(n) {
  if (n.nodeType === 'start' || n.nodeType === 'end') return [84, 84];
  if (n.nodeType === 'error')                          return [72, 72];
  const w = Math.max(90, Math.min(230, n.name.length * 7.8 + 52));
  return [w, 52];
}

const NODE_ICONS = {
  start:    '▶ ', end:      '⏹ ', error:    '⚠ ',
  terminal: '⊙ ',
  consumer: '⩓ ', handler:  '↩ ',
  agent:    '⬡ ', flow:     '⚙ ',
  actor:    '◉ ', system:   '▪ '
};

/**
 * The name a node is drawn under, carrying the switched-off state as TEXT.
 *
 * The canvas is a `<canvas>`: none of its content reaches an accessibility tree, so "not colour
 * alone" here has to mean a mark a reader can see and read, not an ARIA attribute. This suffix is one
 * of the three carriers the bypass affordance uses (the other two are the dashed border and the
 * neutralised border colour in `createStylesheet`), and it is the only one that survives a screenshot
 * pasted into a bug report or a graph printed in greyscale.
 *
 * The ` · ` separator is not new punctuation: `runtimeCountLabel` already appends run state to a node
 * name exactly this way, so the canvas has one convention for "the name, plus what is true of it now"
 * rather than two.
 */
function bypassedNodeName(name, bypassed) {
  return bypassed ? `${name} · bypassed` : name;
}

function buildElements(gd) {
  // Reclassified on EVERY render, not once at parse, because whether an edge is a failure
  // route depends on its target node's kind: making a node an `ERROR` node, or dragging an
  // edge onto one, changes what its incoming edges mean. This is the one function every render
  // passes through, so it is the one place that cannot be bypassed by a new authoring path.
  classifyFailureRoutes(gd);
  const { nodes, edges } = gd;

  // Coordinate normalization (source GraphML can use all-negative Y values)
  let minX = Infinity, minY = Infinity;
  nodes.forEach(n => { minX = Math.min(minX, n.ox); minY = Math.min(minY, n.oy); });
  const PAD = 80;

  // One name for the whole render, not one per node: `/v1/catalog` publishes the same
  // platform-fixed `bypassProperty` on every descriptor (there is deliberately no per-type
  // `allowedBypassValues` either, for the same reason), so any entry answers for all of them — and a
  // node whose behavior the catalog does not know still has to get the derived name rather than a
  // hardcoded one, which is precisely the case this flag exists for.
  const bypassProperty = bypassPropertyName(null, nodeTypeCatalog);

  const cyNodes = nodes.map(n => {
    const [w, h] = nodeSize(n);
    const icon = NODE_ICONS[n.nodeType] || '• ';
    const center = n._positionIsCenter
      ? { x: n.ox, y: n.oy }
      : { x: n.ox - minX + PAD + n.ow / 2, y: n.oy - minY + PAD + n.oh / 2 };
    // Recomputed on EVERY render, like `classifyFailureRoutes` above and for the same reason:
    // the flag is an ordinary document property an author can change from the Inspector, from a
    // multi-selection edit or by loading a different document, and this is the one function every
    // render passes through. A `bypassed` computed once at parse would go stale on the first save.
    //
    // Gated on the node KIND, matching `NodeBypassValidator`: the key on a START/END/ERROR node is a
    // graph the runtime refuses to load, so drawing that node as switched off would announce a
    // behaviour it will never get to have.
    const bypassed = nodeAcceptsBypass(n.kind) && isNodeBypassed(n.properties, bypassProperty);
    return {
      data: {
        id: n.id, label: icon + bypassedNodeName(n.name, bypassed),
        name: n.name, nodeType: n.nodeType,
        bypassed,
        classname: n.classname,
        description: n.description || '',
        fillColor: n.fillColor,
        instances: n.instances,
        kind: n.kind || '', behavior: n.behavior || '',
        properties: n.properties || {}, propertyTypes: n.propertyTypes || {},
        runtimeState: n.runtimeState || 'idle',
        runtimeObserved: Boolean(n.runtimeObserved),
        lastEventType: n.lastEventType || '', lastOccurredAt: n.lastOccurredAt || '',
        processingDuration: n.processingDuration ?? null, fallback: Boolean(n.fallback),
        isStart: n.isStart, isEnd: n.isEnd,
        nw: w, nh: h,
        // store original position (center)
        px: center.x,
        py: center.y,
        // graphify extras (empty when loaded from GraphML)
        _graphify:    n._graphify    || false,
        gfSourceFile: n.gfSourceFile || '',
        gfSourceLoc:  n.gfSourceLoc  || '',
        gfCommunity:  n.gfCommunity  || '',
        gfRepo:       n.gfRepo       || '',
        gfLanguage:   n.gfLanguage   || '',
        gfKind:       n.gfKind       || '',
      },
      position: center
    };
  });

  const cyEdges = edges.map(e => ({
    data: {
      id: e.id, source: e.source, target: e.target,
      label: e.label, status: e.status,
      edgeType: e.edgeType, parallel: e.parallel,
      edgeName: e.edgeName,
      color: e.color,
      lineWidth: e.lineWidth,
      trafficWeight: e.trafficWeight,
      description: e.description || '',
      outcome: e.outcome || e.label || 'continue',
      // Reported alongside edgeType because the two answer different questions. `edgeType`
      // says how to DRAW it, and both kinds of failure route draw the same because they behave the
      // same; this says WHY it is one, which is what the Inspector states in words.
      failureRouteKind: e.failureRouteKind || '',
      properties: e.properties || {}, propertyTypes: e.propertyTypes || {},
      gfRelation:   e.gfRelation   || '',
      gfConfidence: e.gfConfidence || '',
    }
  }));

  return [...cyNodes, ...cyEdges];
}

// ═══════════════════════════════════════════════════════════════
// CYTOSCAPE STYLESHEET
// ═══════════════════════════════════════════════════════════════

function createStylesheet(palette = rendererPalette) {
  const node = palette.nodeType;
  const edge = palette.edgeType;
  const surface = palette.nodeSurfaceByType;
  return [
  // Default node
  { selector: 'node', style: {
    shape: 'roundrectangle', width: 'data(nw)', height: 'data(nh)',
    'background-color': palette.nodeSurface, 'border-width': 2, 'border-color': palette.nodeBorder,
    label: 'data(label)', color: palette.nodeText,
    'font-size': '20px',
    'font-family': '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
    'text-valign': 'center', 'text-halign': 'center',
    'text-wrap': 'wrap', 'text-max-width': '210px', padding: '10px',
    'transition-property': 'border-color, background-color, opacity',
    'transition-duration': '150ms',
  }},
  { selector: 'node[nodeType="start"]', style: {
    shape: 'ellipse', 'background-color': surface.start,
    'border-color': node.start, 'border-width': 3,
    'font-weight': 'bold', 'font-size': '13px',
  }},
  { selector: 'node[nodeType="end"]', style: {
    shape: 'ellipse', 'background-color': surface.end,
    'border-color': node.end, 'border-width': 3,
    'font-weight': 'bold', 'font-size': '13px',
  }},
  { selector: 'node[nodeType="error"]', style: {
    shape: 'diamond', 'background-color': surface.error,
    'border-color': node.error, 'border-width': 2.5,
  }},
  { selector: 'node[nodeType="handler"]', style: {
    'background-color': surface.handler,
    'border-color': node.handler, 'border-width': 2,
  }},
  { selector: 'node[nodeType="flow"]', style: {
    'background-color': surface.flow,
    'border-color': node.flow, 'border-width': 2,
  }},
  { selector: 'node[nodeType="agent"]', style: {
    'background-color': surface.agent,
    'border-color': node.agent, 'border-width': 2,
  }},
  { selector: 'node[nodeType="terminal"]', style: {
    shape: 'ellipse', 'background-color': surface.terminal,
    'border-color': node.terminal, 'border-width': 2.5,
  }},
  { selector: 'node[nodeType="consumer"]', style: {
    'background-color': surface.consumer,
    'border-color': node.consumer, 'border-width': 2.5,
  }},
  { selector: 'node[nodeType="actor"]', style: {
    'background-color': surface.actor,
    'border-color': node.actor, 'border-width': 2,
  }},
  { selector: 'node[nodeType="system"]', style: {
    shape: 'rectangle',
    'background-color': surface.system,
    'border-color': node.system, 'border-width': 1.5,
  }},
  // The node the author switched off. Last of the node-type rules on purpose — it has to beat
  // every `node[nodeType=…]` border above it, because "this does not run" outranks "this is an agent"
  // for a reader scanning the drawing, and the surface colour is left alone so the node's identity is
  // still readable underneath.
  //
  // THREE CARRIERS, NOT ONE, on the same reasoning `edge[edgeType="failure"]` above states: a dashed
  // border, a neutral border colour that is nobody's type colour, and the ` · bypassed` suffix
  // `bypassedNodeName` puts in the label. Colour alone would fail the rule that "a graph
  // with a bypassed node that looks normal is a trap") for anyone reading in greyscale, and would also
  // be ambiguous against `nodeType="system"`, which is already grey.
  //
  // Dashed is the language this canvas ALREADY uses for a route that is not the nominal one --
  // `failed`, `failure`, `ping`, `undefined` edges are all dashed -- so this borrows it for a node
  // instead of inventing a second vocabulary. The colour is `nodeType.system`, which is a real
  // palette token in both themes (light `#57606a` on white, dark `#8b949e` on `#21262d`) rather than
  // a new value picked here, so contrast in either theme is whatever `system` nodes already meet.
  { selector: 'node[?bypassed]', style: {
    'border-style': 'dashed', 'border-color': node.system, 'border-width': 2.5,
  }},
  // Grab cursor
  { selector: 'node:active', style: { 'overlay-opacity': 0.1 }},

  // ── Edges ────────────────────────────────
  { selector: 'edge', style: {
    width: 1.5, 'line-color': edge.default,
    'target-arrow-shape': 'triangle', 'target-arrow-color': edge.default,
    'curve-style': 'bezier',
    label: 'data(label)', 'font-size': '10px', color: palette.edgeLabel,
    'text-background-opacity': 1, 'text-background-color': palette.edgeLabelSurface,
    'text-background-padding': '2px', 'text-background-shape': 'roundrectangle',
    'edge-text-rotation': 'autorotate',
    'transition-property': 'opacity', 'transition-duration': '150ms',
  }},
  { selector: 'edge[edgeType="failed"]', style: {
    'line-color': edge.failed, 'target-arrow-color': edge.failed,
    width: 2, 'line-style': 'dashed', 'line-dash-pattern': [6,3], color: edge.failed,
  }},
  // This route and `edgeType="failed"` above must remain visually distinct.
  // Three carriers, not one: a hue that is nobody else's, a dot pattern no other edge type uses,
  // and a tee arrowhead. The distinction must not rest on colour alone
  // -- the same accessibility point buildLegend() already makes about the legend chips -- and the
  // one type it has to separate from is itself dashed, so the dash could not carry it alone either.
  { selector: 'edge[edgeType="failure"]', style: {
    'line-color': edge.failure, 'target-arrow-color': edge.failure,
    'target-arrow-shape': 'triangle-tee',
    width: 2.5, 'line-style': 'dashed', 'line-dash-pattern': [2,4], color: edge.failure,
  }},
  { selector: 'edge[edgeType="completed"]', style: {
    'line-color': edge.completed, 'target-arrow-color': edge.completed,
    width: 2.5, color: edge.completed,
  }},
  { selector: 'edge[edgeType="continue"]', style: {
    'line-color': edge.continue, 'target-arrow-color': edge.continue,
    width: 2, color: edge.continue,
  }},
  { selector: 'edge[edgeType="continueP"]', style: {
    'line-color': edge.continueP, 'target-arrow-color': edge.continueP,
    width: 4.5, color: edge.continueP,
  }},
  { selector: 'edge[edgeType="validate"]', style: {
    'line-color': edge.validate, 'target-arrow-color': edge.validate,
    width: 2, color: edge.validate,
  }},
  { selector: 'edge[edgeType="ping"]', style: {
    'line-color': edge.ping, 'target-arrow-color': edge.ping,
    width: 1.5, 'line-style': 'dashed', 'line-dash-pattern': [4,4], color: edge.ping,
  }},
  { selector: 'edge[edgeType="outcome"]', style: {
    'line-color': edge.outcome, 'target-arrow-color': edge.outcome,
    width: 2.5, color: edge.outcome,
  }},
  { selector: 'edge[edgeType="undefined"]', style: {
    'line-color': edge.undefined, 'target-arrow-color': edge.undefined,
    width: 1.5, 'line-style': 'dashed', color: edge.undefined,
  }},
  { selector: 'edge[edgeType="callback"]', style: {
    'line-color': edge.callback, 'target-arrow-color': edge.callback,
    width: 2, color: edge.callback,
  }},
  // Preserve the edge's own width, colour and dash semantics and project selection around them.
  // The padded underlay is deliberately not colour-only: its extra silhouette remains legible on
  // dense graphs, at different zoom levels and for `continueP`, whose normal line is already wide.
  { selector: 'edge:selected', style: {
    'underlay-color': palette.selection, 'underlay-opacity': 0.38, 'underlay-padding': 5,
    'z-index': 10,
  }},
  { selector: '.connect-source', style: {
    'border-color': palette.focus, 'border-width': 5,
    'underlay-color': palette.focus, 'underlay-opacity': 0.18, 'underlay-padding': 10,
  }},
  // The keyboard's position in the graph. It is deliberately not the same as selection: the cursor
  // says "you are here", selection says "this is what you are acting on", and an edge gesture needs
  // to move the first without disturbing the second.
  { selector: '.graph-cursor', style: {
    'border-color': palette.focus, 'border-width': 4,
    'underlay-color': palette.focus, 'underlay-opacity': 0.22, 'underlay-padding': 8,
  }},
  // Immediate feedback on whether the node under the pointer or cursor may take the edge.
  { selector: '.connect-valid', style: {
    'border-color': node.start, 'border-width': 4,
    'underlay-color': node.start, 'underlay-opacity': 0.2, 'underlay-padding': 8,
  }},
  { selector: '.connect-invalid', style: {
    'border-color': node.end, 'border-width': 4,
    'underlay-color': node.end, 'underlay-opacity': 0.2, 'underlay-padding': 8,
  }},
  { selector: '.edge-reconnecting', style: {
    'line-style': 'dashed', 'line-color': palette.focus, 'target-arrow-color': palette.focus, width: 3,
  }},

  // Highlight / dim
  { selector: '.dim',        style: { opacity: 0.06 }},
  { selector: '.hi',         style: { opacity: 1 }},
  // Downstream trace
  { selector: '.trace-start', style: {
    'border-color': palette.nodeText, 'border-width': 4,
    'background-color': palette.traceStart,
  }},
  { selector: '.trace-node', style: {
    'border-color': palette.trace, 'border-width': 3,
  }},
  { selector: '.trace-edge', style: {
    'line-color':          palette.trace,
    'target-arrow-color':  palette.trace,
    width: 3.5,
  }},
  // Hover/search/trace dimming must not make the selected edge disappear. Its native semantic
  // styling still wins; only the visibility of the selection affordance is restored.
  { selector: 'edge:selected.dim', style: { opacity: 1 }},
  ];
}

// ═══════════════════════════════════════════════════════════════
// STATE & INIT
// ═══════════════════════════════════════════════════════════════

// The HTML default of `#font-slider` (index.html) and the value `initCy` has always applied to a
// freshly opened document. Named so the two never drift apart silently (UI-12).
const DEFAULT_FONT_SIZE = 20;

// The workspace owns the open documents; the variables immediately below are the ACTIVE document's
// working view of its own record (UI-03). The record is the home of the state: everything here
// is copied out of it when a document is activated and written back into it when it is left, by
// `applyActiveDocument` and `captureActiveDocument`. Reading them anywhere else in this file
// therefore means "the document the user is working on", which is what every one of the ~300 call
// sites already meant when there could only ever be one.
const workspace = createWorkspace();
const layoutSessions = createLayoutSessions();
const rendererSessions = createRendererSessions();
const themePreference = createThemePreferenceController({
  onChange: (theme, detail) => applyApplicationTheme(theme, detail),
});
applicationTheme = themePreference.theme;
rendererPalette = getRendererPalette(applicationTheme);

let cy          = null;
let graphData   = null;
let activeDocumentIncarnation = null;
let renderMode  = DEFAULT_RENDER_MODE;
let layoutMode  = 'cyto';
let visualStyle = DEFAULT_VISUAL_STYLE;
let layoutBusy = false;
let filterActive = null;   // { elType, type } or null
let traceActive  = false;  // true while a downstream trace is pinned
// Label font size (UI-12): a working-view mirror of the ACTIVE document's own `fontSize`,
// exactly like `layoutMode` two lines up — captured and applied the same way, and defaulted the
// same way. See `DEFAULT_FONT_SIZE` and `workspace.js`'s `createDocumentRecord` for why 20.
let fontSize = DEFAULT_FONT_SIZE;
let graphName    = 'untitled.graphml';
let graphDisplayName = graphName;
let runtimeClient = null;
let runtimeDisconnect = null;
const runtimeTokenProvider = memoryTokenProvider();
const PROGRAM_TEST_PAYLOAD_DEFAULT = 'test payload';
const PROGRAM_BUILD_BATCH_LIMIT = 256;
const PROGRAM_BUILD_POLL_INTERVAL_MS = 100;
const PROGRAM_OUTPUT_DISPLAY_LIMIT = 8 * 1024;
const PROGRAM_WORKSPACE_PROPERTY_NAMES = new Set(['language', 'source', 'testPayload', 'artifactId']);
let hasRuntimeToken = false;
let confirmedServiceOrigin = '';
let activeExecutionId = null;
let activeSourceSession = null;
let activeGraphVersion = null;
let activeExecutionReconciliation = 'known';
let nodeTypeCatalog = [];
// Why the palette is empty, kept apart from the catalog itself: a failed request and a service
// that legitimately has nothing to offer are different states and are shown differently.
let nodeCatalogFailure = null;
let nodeCatalogLoaded = false;
// True from the moment a catalog request departs until it is answered or fails.
let nodeCatalogPending = false;
let finishedExecutions = new Set();

// ── AUTHORING ASSISTANT STATE (ADR 0025) ───────────────────────────────────────────────────
// `assistantAvailability` starts UNREACHABLE rather than unknown. A panel that assumed it was ready
// and discovered otherwise on the first send would already have told the user it was working, which
// is the exact "never pretending" prohibition — so the optimistic state is not the initial one.
let assistantClient = null;

// ── CREDENTIALS ───────────────────────────────────────────────────────────────────────────
// The window itself, and the ONE fact about it this file keeps: which references the author holds.
//
// `loaded` is not `credentials.length > 0`. An author with no connection and an author with no credentials are different
// states, and the SECRET_REFERENCE control must not answer them the same way: an empty list from a
// service that answered is "you have none to choose"; an empty list because nobody asked yet is "we
// do not know", and a value already on the node must survive it untouched.
let credentialsWindow = null;
let credentialReferences = { loaded: false, credentials: [] };

// ── DEPLOYMENTS ────────────────────────────────────────────────────────────────────
// The window itself. Unlike credentials this file keeps no derived state from it -- nothing else in
// the editor reads the deployment list today (see `deployment-panel.js`'s `publish` for the seam a
// future caller would use).
let deploymentsWindow = null;

/** The one reader. A function rather than the variable itself so the editor below has a single,
 * substitutable seam — which is also what lets it be rendered in a test without a service. */
function credentialReferenceChoices() {
  return credentialReferences;
}

let assistantAvailability = { reachable: false, configured: false, allowlisted: false, signedIn: false };
let assistantTranscript = [];
let assistantContext = null;
let assistantError = null;
let assistantBusy = false;
// Proposals are deliberately outside the transcript: clearing/reloading conversation text cannot
// replay an edit, and no proposal payload is retained as conversational provenance.
const assistantProposals = new Map();
let assistantProposalSequence = 0;
// The connection in progress, or null. One at a time and per tab: a second grant started
// while the first is outstanding would leave two codes on screen and one of them dead, so starting
// abandons whatever was pending rather than adding to it.
//
// `timer` is the only thing here that outlives a render, and it is held so it can be CLEARED. A
// poll loop that survives the panel closing, or the state moving on, keeps asking a question whose
// answer nothing will read, and does it on a schedule the provider set.
let assistantConnection = null;
// The bounded tail of runtime events the `events` context class attaches.
//
// THIS IS A VIEW OF THE ACTIVE DOCUMENT'S RECORD, exactly like `finishedExecutions` beside it: the
// record (`workspace.js`, `execution.events`) is authoritative, `captureActiveDocument` writes this
// back into it and `applyActiveDocument` restores it. It is held by reference and mutated in place,
// so an event appended to the record is visible here without a second copy to fall out of step.
//
// A workspace-scoped buffer filtered only at write time would let File → New show "Event stream:
// attached" while carrying the PREVIOUS graph's executionIds. Filtering does not make a persistent
// buffer follow the active document. Reading straight from `workspace.active` would instead violate
// this file's ownership invariant: module variables are the view, and only the two sync functions
// touch the record.
// Bounded because an unbounded buffer in a long editing session is a leak, and the assistant only
// ever needs the recent tail to reason about what just happened.
const RUNTIME_EVENT_TAIL = 50;
let recentRuntimeEvents = [];
let modifyEnabled = false;
let navigationEnabled = false;
let selectedCatalogBehavior = '';
let connectArmed = false;
let connectSourceId = null;
// The edge gesture in progress, shared by the pointer and the keyboard (UI-02), and the
// keyboard's position in the graph. Both are renderer-side state: nothing here is written to the
// document until the gesture is committed through the command model.
let edgeGestureSession = null;
let graphCursorId = null;
// Cytoscape emits `tap` after `tapend`, so a drag that just committed an edge would otherwise be
// followed by a tap that reopens or refuses one on the same element.
const suppressedEdgeTaps = new WeakSet();
let stageGestureMoved = false;
let stageGestureStarted = false;
// A background pane becomes active during native pointer bubbling, before Cytoscape translates that
// physical press into `tapstart`. Preserve the owning renderer's selection at capture time so the
// stage interaction machine is not accidentally fed selection state after active-document chrome
// has cleared it.
const stageSelectionAtPointerStart = new WeakMap();
// One undo stack per document (UI-01). It now lives on the document record, so undo cannot
// reach across two open workflows any more than it could across two loaded ones.
let editHistory = createCommandHistory();
let inspectorAutosave = readInspectorAutosavePreference();
let inspectorDraft = null;
let pendingInspectorTransition = null;
let inspectorEditSequence = 0;
let dragSnapshot = null;
let stableSelectionRevision = 0;
const stableSelectionTimers = new WeakMap();
// Cytoscape updates its own additive selection between `tapstart` and `tap`. A Ctrl-click decision
// made from the later set cannot distinguish "was selected, toggle off" from "was just added".
// Capture the user-visible set and modifier at pointer start, per renderer, then consume it only for
// the matching completed element tap. Releasing Ctrl after pointer-up cannot affect the next click.
const elementSelectionAtPointerStart = new WeakMap();
const selectionInspectorRefreshes = new WeakSet();
const canvasZoomBridges = new WeakMap();
const selectionOverlays = new WeakMap();
const nodeActionOverlays = new WeakMap();
const nodeActionOverlayRegistry = new Set();
const nodeActionGestureClaims = new Map();
let nodeActionGestureRouterInstalled = false;
let nextNodeActionSequenceId = 0;
const SELECTION_OUTLINE_GAP = 8;
const SELECTION_OUTLINE_CORNERS = ['nw', 'ne', 'se', 'sw'];
const NODE_ACTION_LEAVE_DELAY = 100;
let nodeActionGeometryState;
const nodeActionScaleControl = bindNodeActionScaleControl({
  input: document.getElementById('node-action-scale-slider'),
  output: document.getElementById('node-action-scale-val'),
  root: document.documentElement,
  onChange: geometry => {
    nodeActionGeometryState = geometry;
    nodeActionOverlayRegistry.forEach(overlay => scheduleNodeActionOverlay(overlay.instance));
    syncCommandBarDensity();
  },
});
const nodeActionScaleContainer = document.getElementById('node-action-scale-ctrl');
const nodeActionScaleSlider = document.getElementById('node-action-scale-slider');
nodeActionScaleContainer.title = uiText('controls.nodeActionScale.help');
nodeActionScaleSlider.setAttribute('aria-label', uiText('controls.nodeActionScale.label'));

function selectionOutlineRect(node) {
  if (!node?.visible()) return null;
  const bounds = node.renderedBoundingBox({
    includeNodes: true,
    includeEdges: false,
    includeLabels: false,
    includeOverlays: false,
    includeUnderlays: false,
  });
  const values = [bounds?.x1, bounds?.y1, bounds?.w, bounds?.h].map(Number);
  if (!values.every(Number.isFinite) || values[2] <= 0 || values[3] <= 0) return null;
  return {
    x: values[0] - SELECTION_OUTLINE_GAP,
    y: values[1] - SELECTION_OUTLINE_GAP,
    width: values[2] + SELECTION_OUTLINE_GAP * 2,
    height: values[3] + SELECTION_OUTLINE_GAP * 2,
  };
}

function createSelectionBox(nodeId) {
  const element = window.document.createElement('div');
  element.className = 'graph-selection-box';
  element.dataset.nodeId = nodeId;
  SELECTION_OUTLINE_CORNERS.forEach(corner => {
    const handle = window.document.createElement('span');
    handle.className = 'graph-selection-handle';
    handle.dataset.corner = corner;
    element.append(handle);
  });
  return element;
}

function syncSelectionOverlay(instance) {
  const overlay = selectionOverlays.get(instance);
  if (!overlay || instance.destroyed()) return;
  const retained = new Set();
  instance.nodes(':selected').forEach(node => {
    const rect = selectionOutlineRect(node);
    if (!rect) return;
    const nodeId = node.id();
    retained.add(nodeId);
    let element = overlay.boxes.get(nodeId);
    if (!element) {
      element = createSelectionBox(nodeId);
      overlay.boxes.set(nodeId, element);
      overlay.root.append(element);
    }
    element.style.transform = `translate3d(${rect.x}px, ${rect.y}px, 0)`;
    element.style.width = `${rect.width}px`;
    element.style.height = `${rect.height}px`;
  });
  overlay.boxes.forEach((element, nodeId) => {
    if (retained.has(nodeId)) return;
    element.remove();
    overlay.boxes.delete(nodeId);
  });
  overlay.root.hidden = overlay.boxes.size === 0;
}

function scheduleSelectionOverlay(instance) {
  const overlay = selectionOverlays.get(instance);
  if (!overlay || overlay.raf != null) return;
  overlay.raf = requestAnimationFrame(() => {
    overlay.raf = null;
    syncSelectionOverlay(instance);
  });
}

function installSelectionOverlay(owner, instance, container) {
  const installed = selectionOverlays.get(instance);
  const siblingRoots = [...container.children]
    .filter(child => child.classList?.contains('graph-selection-overlay'));
  if (installed?.root.parentElement === container
      && installed.root.dataset.documentId === owner.id) {
    siblingRoots.filter(root => root !== installed.root).forEach(root => root.remove());
    scheduleSelectionOverlay(instance);
    return installed;
  }
  if (installed) destroySelectionOverlay(instance);
  else siblingRoots.forEach(root => root.remove());

  const root = window.document.createElement('div');
  root.className = 'graph-selection-overlay';
  root.dataset.documentId = owner.id;
  root.dataset.ready = 'true';
  root.setAttribute('aria-hidden', 'true');
  root.hidden = true;
  container.append(root);

  const schedule = () => scheduleSelectionOverlay(instance);
  const overlay = { root, boxes: new Map(), raf: null, schedule };
  selectionOverlays.set(instance, overlay);
  instance.on('select unselect position style add remove', 'node', schedule);
  instance.on('pan zoom resize render', schedule);
  schedule();
  return overlay;
}

function destroySelectionOverlay(instance) {
  retireElementSelectionGesture(instance);
  const overlay = selectionOverlays.get(instance);
  if (!overlay) return;
  instance.off('select unselect position style add remove', 'node', overlay.schedule);
  instance.off('pan zoom resize render', overlay.schedule);
  if (overlay.raf != null) cancelAnimationFrame(overlay.raf);
  overlay.root.remove();
  overlay.boxes.clear();
  selectionOverlays.delete(instance);
}

function nodeActionLabel(node) {
  return node?.data('name') || node?.id() || 'node';
}

function nodeActionPointerType(event) {
  if (event.pointerType) return String(event.pointerType);
  return event.sourceCapabilities?.firesTouchEvents ? 'touch' : 'mouse';
}

function nodeActionClaimForEvent(event) {
  if (Number.isInteger(event.pointerId) && event.pointerId >= 0) {
    return nodeActionGestureClaims.get(event.pointerId) || null;
  }
  return nodeActionGestureClaims.size === 1 ? [...nodeActionGestureClaims.values()][0] : null;
}

function nodeActionGestureIsClaimed(event) {
  return Boolean(nodeActionClaimForEvent(event));
}

const NODE_ACTION_SEQUENCE_EVENTS = [
  'pointerdown', 'pointermove', 'pointerup', 'pointercancel',
  'click', 'dblclick', 'auxclick', 'contextmenu',
];

function syncNodeActionGestureRouter() {
  const visibleOverlay = [...nodeActionOverlayRegistry]
    .some(overlay => !overlay.bar.hidden || !overlay.menu.hidden || !overlay.bridge.hidden);
  const needed = visibleOverlay || nodeActionGestureClaims.size > 0;
  if (needed === nodeActionGestureRouterInstalled) return;
  nodeActionGestureRouterInstalled = needed;
  NODE_ACTION_SEQUENCE_EVENTS.forEach(type => window[needed ? 'addEventListener' : 'removeEventListener'](
    type, routeNodeActionGesture, true));
}

function replaceNodeActionClaim(claim, phase) {
  const next = Object.freeze({ ...claim, phase });
  nodeActionGestureClaims.set(claim.pointerId, next);
  return next;
}

function releaseNodeActionClaim(claim) {
  if (!claim || nodeActionGestureClaims.get(claim.pointerId)?.sequence !== claim.sequence) return;
  nodeActionGestureClaims.delete(claim.pointerId);
  try {
    if (claim.anchor.hasPointerCapture?.(claim.pointerId)) claim.anchor.releasePointerCapture(claim.pointerId);
  } catch { /* the renderer may already have retired the anchor */ }
  if (claim.overlay.retired
      && ![...nodeActionGestureClaims.values()].some(active => active.overlayId === claim.overlayId)) {
    claim.anchor.remove();
  }
  syncNodeActionGestureRouter();
}

function acquireNodeActionClaim(overlay, event) {
  if (!Number.isInteger(event.pointerId) || event.pointerId < 0) return null;
  const button = event.target.closest?.('[data-node-action]');
  const barRect = overlay.bar.getBoundingClientRect();
  const containerRect = overlay.container.getBoundingClientRect();
  const claim = Object.freeze({
    sequence: ++nextNodeActionSequenceId,
    pointerId: event.pointerId,
    pointerType: nodeActionPointerType(event),
    button: Number.isInteger(event.button) ? event.button : 0,
    overlayId: overlay.sequenceId,
    overlay,
    anchor: overlay.root,
    owner: overlay.owner,
    instance: overlay.instance,
    nodeId: overlay.nodeId,
    action: button?.dataset.nodeAction || null,
    selectedIds: overlay.instance.$(':selected').map(element => element.id()),
    menuX: barRect.left - containerRect.left,
    // Keep the textual menu touching the taller vertical toolbar. A visual gap here would briefly
    // hand pointer hit-testing back to the canvas while the user moves from More to the menu.
    menuY: barRect.bottom - containerRect.top,
    clientX: Number(event.clientX),
    clientY: Number(event.clientY),
    phase: 'pressed',
  });
  nodeActionGestureClaims.set(claim.pointerId, claim);
  try { overlay.root.setPointerCapture?.(claim.pointerId); } catch { /* capture is best effort */ }
  return claim;
}

function executeNodeActionClaim(claim) {
  if (!claim.action || claim.button !== 0 || claim.overlay.retired) return false;
  claim.overlay.selectionAtPointerDown = claim.selectedIds;
  if (claim.action === 'more') {
    const node = claim.instance.getElementById(claim.nodeId);
    openNodeActionMenu(claim.owner, claim.instance, node, {
      x: claim.menuX, y: claim.menuY,
    });
    return true;
  }
  const descriptor = nodeActionCatalog(claim.owner, claim.instance, claim.nodeId)
    .find(action => action.id === claim.action);
  hideNodeActionOverlay(claim.instance, { restoreFocus: true });
  const executed = Boolean(descriptor?.enabled && descriptor.run());
  claim.overlay.selectionAtPointerDown = null;
  return executed;
}

function dismissNodeActionOverlaysFromOutsidePointer(event) {
  const path = typeof event.composedPath === 'function' ? event.composedPath() : [];
  const owningRoot = path.find(element => element?.dataset?.nodeActionSequenceId);
  const owningOverlay = owningRoot && [...nodeActionOverlayRegistry]
    .find(overlay => overlay.sequenceId === Number(owningRoot.dataset.nodeActionSequenceId));
  if (owningOverlay) return;
  const owningPane = path.find(element => element?.classList?.contains('doc-pane'));
  const candidates = owningPane
    ? [...nodeActionOverlayRegistry].filter(overlay => overlay.owner.pane === owningPane)
    : [...nodeActionOverlayRegistry];
  candidates.forEach(overlay => hideNodeActionOverlay(overlay.owner.cy));
  const clickedControl = event.target?.closest?.(
    'button, input, select, textarea, a[href], [tabindex]:not([tabindex="-1"])');
  const clickedFocusable = Boolean(clickedControl && clickedControl !== owningPane
    && !clickedControl.matches('#cy-wrap'));
  if (owningPane && !clickedFocusable) {
    const finishFocusHandoff = () => {
      window.removeEventListener('click', finishFocusHandoff, true);
      window.removeEventListener('pointercancel', cancelFocusHandoff, true);
      queueMicrotask(() => {
        const focusLost = document.activeElement === document.body
          || document.activeElement?.closest?.('.graph-node-action-menu[hidden]');
        if (focusLost) owningPane.focus({ preventScroll: true });
      });
    };
    const cancelFocusHandoff = () => {
      window.removeEventListener('click', finishFocusHandoff, true);
      window.removeEventListener('pointercancel', cancelFocusHandoff, true);
    };
    window.addEventListener('click', finishFocusHandoff, true);
    window.addEventListener('pointercancel', cancelFocusHandoff, true);
  }
}

function routeNodeActionGesture(event) {
  let claim = nodeActionClaimForEvent(event);
  if (event.type === 'pointerdown') {
    if (claim?.phase === 'ambiguous-followup') {
      const path = typeof event.composedPath === 'function' ? event.composedPath() : [];
      const sameCanvas = path.includes(claim.overlay.container);
      const spatiallyAmbiguous = Math.abs(Number(event.clientX) - claim.clientX) <= 4
        && Math.abs(Number(event.clientY) - claim.clientY) <= 4;
      if (event.button === claim.button && sameCanvas && spatiallyAmbiguous) {
        claim = replaceNodeActionClaim(claim, 'followup-pressed');
      } else {
        releaseNodeActionClaim(claim);
        claim = null;
      }
    } else if (claim?.phase === 'awaiting-dblclick') {
      releaseNodeActionClaim(claim);
      dismissNodeActionOverlaysFromOutsidePointer(event);
      return;
    }
    if (!claim) {
      const path = typeof event.composedPath === 'function' ? event.composedPath() : [];
      const root = path.find(element => element?.dataset?.nodeActionSequenceId);
      const overlay = root && [...nodeActionOverlayRegistry]
        .find(candidate => candidate.sequenceId === Number(root.dataset.nodeActionSequenceId));
      if (!overlay) {
        dismissNodeActionOverlaysFromOutsidePointer(event);
        return;
      }
      claim = acquireNodeActionClaim(overlay, event);
    }
  }
  if (!claim && event.type === 'click') {
    const path = typeof event.composedPath === 'function' ? event.composedPath() : [];
    const root = path.find(element => element?.dataset?.nodeActionSequenceId);
    const overlay = root && [...nodeActionOverlayRegistry]
      .find(candidate => candidate.sequenceId === Number(root.dataset.nodeActionSequenceId));
    const button = event.target.closest?.('[data-node-action]');
    if (overlay && button) {
      const barRect = overlay.bar.getBoundingClientRect();
      const containerRect = overlay.container.getBoundingClientRect();
      executeNodeActionClaim({
        action: button.dataset.nodeAction, button: 0, overlay, owner: overlay.owner,
        instance: overlay.instance, nodeId: overlay.nodeId,
        selectedIds: overlay.instance.$(':selected').map(element => element.id()),
        menuX: barRect.left - containerRect.left,
        menuY: barRect.bottom - containerRect.top,
      });
      event.preventDefault();
      event.stopImmediatePropagation();
    } else {
      const owningPane = path.find(element => element?.classList?.contains('doc-pane'));
      const focusLost = document.activeElement === document.body
        || document.activeElement?.closest?.('.graph-node-action-menu[hidden]');
      if (owningPane && focusLost) owningPane.focus({ preventScroll: true });
    }
    return;
  }
  if (!claim) return;
  if (event.type === 'pointerup') {
    if (claim.phase === 'pressed') {
      const executed = executeNodeActionClaim(claim);
      if (!executed) releaseNodeActionClaim(claim);
      else if (claim.pointerType === 'mouse') replaceNodeActionClaim(claim, 'awaiting-click');
      else releaseNodeActionClaim(claim);
    } else if (claim.phase === 'followup-pressed') {
      replaceNodeActionClaim(claim, 'followup-released');
    }
  } else if (event.type === 'pointercancel') {
    releaseNodeActionClaim(claim);
  } else if (event.type === 'click') {
    if (claim.phase === 'awaiting-click') replaceNodeActionClaim(claim, 'ambiguous-followup');
    else if (claim.phase === 'followup-released') replaceNodeActionClaim(claim, 'awaiting-dblclick');
  } else if (event.type === 'dblclick') {
    releaseNodeActionClaim(claim);
  } else if (event.type === 'auxclick' || event.type === 'contextmenu') {
    releaseNodeActionClaim(claim);
  }
  event.preventDefault();
  event.stopImmediatePropagation();
}

function nodeActionCatalog(owner, instance, nodeId) {
  const node = instance?.getElementById(nodeId);
  const owned = Boolean(owner && workspace.find(owner.id) === owner && owner.cy === instance);
  const active = owner === workspace.active && instance === cy;
  const ownerGraph = active ? graphData : owner?.graph;
  const ownerLayoutMode = active ? layoutMode : owner?.layoutMode;
  const label = node && !node.empty() ? nodeActionLabel(node) : 'node';
  return createNodeActionCatalog({
    targetLabel: label,
    capabilities: {
      trace: Boolean(owned && node && !node.empty()),
      duplicate: Boolean(owned && modifyEnabled && canDuplicateNode(ownerGraph, nodeId, ownerLayoutMode)),
      delete: Boolean(owned && modifyEnabled && canModifyGraph(ownerGraph, ownerLayoutMode)),
    },
    handlers: {
      trace: () => {
        if (!owned || !node || node.empty()) return false;
        if (workspace.activeId !== owner.id) activateDocument(owner.id);
        if (workspace.active !== owner || cy !== instance) return false;
        traceDownstream(node);
        return true;
      },
      duplicate: () => duplicateNodeInDocument(owner, instance, nodeId),
      delete: () => deleteNodeFromActionOverlay(owner, instance, nodeId),
    },
  });
}

function syncNodeActionOverlay(instance) {
  const overlay = nodeActionOverlays.get(instance);
  if (!overlay || instance.destroyed()) return;
  if (overlay.bar.hidden) {
    if (!overlay.menu.hidden) positionNodeActionMenu(overlay, overlay.menuPosition);
    return;
  }
  const node = instance.getElementById(overlay.nodeId);
  if (!node || node.empty() || !node.visible() || (node.selected() && !overlay.allowSelected)
      || overlay.owner.layoutMode === 'elastic') {
    hideNodeActionOverlay(instance);
    return;
  }
  const bounds = node.renderedBoundingBox({
    includeNodes: true, includeEdges: false, includeLabels: false,
    includeOverlays: false, includeUnderlays: false,
  });
  // offsetWidth/offsetHeight are integers. Fractional user scales require the rendered rectangle,
  // otherwise a 25.3px bar leaves a 0.3px dead seam before its bridge on the flipped side.
  const barRect = overlay.bar.getBoundingClientRect();
  const width = barRect.width;
  const height = barRect.height;
  const gap = nodeActionGeometryState.bridgeGap;
  const inset = nodeActionGeometryState.edgeInset;
  const right = bounds.x2 + gap;
  const left = bounds.x1 - gap - width;
  const placeRight = right + width <= overlay.container.clientWidth - inset;
  const x = placeRight ? right : Math.max(inset, left);
  const y = Math.min(Math.max(inset, bounds.y1 + (bounds.h - height) / 2),
    Math.max(inset, overlay.container.clientHeight - height - inset));
  overlay.bar.style.transform = `translate3d(${x}px, ${y}px, 0)`;
  const bridgeX = placeRight ? bounds.x2 : x + width;
  overlay.bridge.style.transform = `translate3d(${bridgeX}px, ${y}px, 0)`;
  overlay.bridge.style.width = `${Math.max(0, placeRight ? x - bounds.x2 : bounds.x1 - (x + width))}px`;
  overlay.bridge.style.height = `${height}px`;
  if (!overlay.menu.hidden) positionNodeActionMenu(overlay, {
    x, y: y + height,
  });
}

function scheduleNodeActionOverlay(instance) {
  const overlay = nodeActionOverlays.get(instance);
  if (!overlay || overlay.raf != null) return;
  overlay.raf = requestAnimationFrame(() => {
    overlay.raf = null;
    syncNodeActionOverlay(instance);
  });
}

function cancelNodeActionLeave(instance) {
  const overlay = nodeActionOverlays.get(instance);
  if (!overlay || overlay.leaveTimer == null) return;
  clearTimeout(overlay.leaveTimer);
  overlay.leaveTimer = null;
}

function hideNodeActionOverlay(instance, { restoreFocus = false } = {}) {
  const overlay = nodeActionOverlays.get(instance);
  if (!overlay) return false;
  if (overlay.bar.hidden && overlay.menu.hidden) return false;
  cancelNodeActionLeave(instance);
  const ownedFocus = overlay.root.contains(document.activeElement);
  overlay.pointerWithin = false;
  overlay.allowSelected = false;
  overlay.nodeId = null;
  overlay.bar.hidden = true;
  overlay.bridge.hidden = true;
  overlay.menu.hidden = true;
  overlay.root.hidden = true;
  if (restoreFocus && ownedFocus) overlay.owner.pane?.focus({ preventScroll: true });
  syncNodeActionGestureRouter();
  return true;
}

function deferNodeActionLeave(instance) {
  const overlay = nodeActionOverlays.get(instance);
  if (!overlay || (overlay.bar.hidden && overlay.menu.hidden)
      || overlay.pointerWithin || overlay.root.contains(document.activeElement)) return;
  cancelNodeActionLeave(instance);
  overlay.leaveTimer = setTimeout(() => {
    overlay.leaveTimer = null;
    if (overlay.pointerWithin || overlay.root.contains(document.activeElement)) return;
    hideNodeActionOverlay(instance);
  }, NODE_ACTION_LEAVE_DELAY);
}

function nodeActionsHaveFinePointer() {
  return globalThis.matchMedia?.('(hover: hover) and (pointer: fine)').matches !== false;
}

function showNodeActionOverlay(owner, node, { pointer = true, allowSelected = false } = {}) {
  const instance = node?.cy();
  const overlay = instance && nodeActionOverlays.get(instance);
  if (!overlay || !owner || node.empty() || (node.selected() && !allowSelected) || owner.layoutMode === 'elastic'
      || (pointer && !nodeActionsHaveFinePointer())) return false;
  cancelNodeActionLeave(instance);
  if (!overlay.menu.hidden && overlay.nodeId !== node.id()) return true;
  const menuStaysOpen = !overlay.menu.hidden;
  overlay.nodeId = node.id();
  overlay.allowSelected = allowSelected;
  const actions = nodeActionCatalog(owner, instance, node.id());
  actions.forEach(action => {
    const button = overlay.bar.querySelector(`[data-node-action="${action.id}"]`);
    button.hidden = !action.enabled;
    button.setAttribute('aria-label', action.label);
    button.dataset.tooltip = action.label;
  });
  overlay.root.hidden = false;
  overlay.bar.hidden = false;
  overlay.bridge.hidden = false;
  if (!menuStaysOpen) overlay.menu.hidden = true;
  syncNodeActionGestureRouter();
  scheduleNodeActionOverlay(instance);
  return true;
}

function deleteNodeFromActionOverlay(owner, instance, nodeId, { skipDraftGuard = false } = {}) {
  if (!skipDraftGuard) {
    return runAfterInspectorDraft(() =>
      deleteNodeFromActionOverlay(owner, instance, nodeId, { skipDraftGuard: true }));
  }
  if (workspace.activeId !== owner.id) activateDocument(owner.id);
  if (workspace.active !== owner || cy !== instance || graphData !== owner.graph
      || !modifyEnabled || !canModifyGraph(graphData, layoutMode)) return false;
  const node = instance.getElementById(nodeId);
  if (!node || node.empty()) return false;
  const overlay = nodeActionOverlays.get(instance);
  const retainedSelection = overlay?.selectionAtPointerDown
    || instance.$(':selected').map(element => element.id());
  invalidateStableSelection(instance);
  const removed = deleteElements(graphData, [nodeId], [], editHistory);
  instance.remove(node);
  const retained = retainedSelection.filter(id => instance.getElementById(id).nonempty());
  retained.forEach(id => { instance.getElementById(id).select(); });
  queueMicrotask(() => {
    if (instance.destroyed() || workspace.find(owner.id) !== owner) return;
    retained.forEach(id => { instance.getElementById(id).select(); });
  });
  if (graphCursorId === nodeId) graphCursorId = null;
  clearHL();
  if (retained.length) showSelectionInfo();
  else closeInfo();
  updateStats();
  scheduleMinimap();
  updateHistoryUi();
  refreshCommands();
  addActivityMessage('editor',
    `Deleted ${removed.nodeIds.length} node(s) and ${removed.edgeIds.length} edge(s)`, 'completed');
  return true;
}

function invokeNodeAction(owner, instance, action, { skipDraftGuard = false } = {}) {
  const overlay = nodeActionOverlays.get(instance);
  const nodeId = overlay?.nodeId;
  const node = nodeId ? instance.getElementById(nodeId) : null;
  if (!overlay || !node || node.empty()) return false;
  if (!skipDraftGuard && workspace.activeId !== owner.id) {
    return runAfterInspectorDraft(() =>
      invokeNodeAction(owner, instance, action, { skipDraftGuard: true }));
  }
  if (workspace.activeId !== owner.id) activateDocument(owner.id);
  const descriptor = nodeActionCatalog(owner, instance, nodeId).find(item => item.id === action);
  hideNodeActionOverlay(instance, { restoreFocus: true });
  return Boolean(descriptor?.enabled && descriptor.run());
}

function positionNodeActionMenu(overlay, position = overlay?.menuPosition) {
  if (!overlay || overlay.menu.hidden) return;
  const inset = nodeActionGeometryState.edgeInset;
  const x = Number(position?.x);
  const y = Number(position?.y);
  overlay.menuPosition = {
    x: Number.isFinite(x) ? x : inset,
    y: Number.isFinite(y) ? y : inset,
  };
  const menuX = Math.min(Math.max(inset, overlay.menuPosition.x),
    Math.max(inset, overlay.container.clientWidth - overlay.menu.offsetWidth - inset));
  const menuY = Math.min(Math.max(inset, overlay.menuPosition.y),
    Math.max(inset, overlay.container.clientHeight - overlay.menu.offsetHeight - inset));
  overlay.menu.style.transform = `translate3d(${menuX}px, ${menuY}px, 0)`;
}

function openNodeActionMenu(owner, instance, node, { x, y, focus = true } = {}) {
  const overlay = nodeActionOverlays.get(instance);
  if (!overlay || !node || node.empty() || owner.layoutMode === 'elastic') return false;
  cancelNodeActionLeave(instance);
  overlay.nodeId = node.id();
  overlay.menu.replaceChildren(...nodeActionCatalog(owner, instance, node.id()).map(action => {
    const item = document.createElement('button');
    item.type = 'button';
    item.className = 'graph-node-action-menu-item';
    item.dataset.nodeAction = action.id;
    item.setAttribute('role', 'menuitem');
    item.disabled = !action.enabled;
    item.textContent = action.label;
    return item;
  }));
  overlay.menu.setAttribute('aria-label', `Actions for ${nodeActionLabel(node)}`);
  overlay.root.hidden = false;
  overlay.menu.hidden = false;
  positionNodeActionMenu(overlay, { x, y });
  if (focus) overlay.menu.querySelector(':not(:disabled)')?.focus({ preventScroll: true });
  syncNodeActionGestureRouter();
  return true;
}

function installNodeActionOverlay(owner, instance, container) {
  const existing = nodeActionOverlays.get(instance);
  if (existing?.root.parentElement === container) return existing;
  if (existing) destroyNodeActionOverlay(instance);
  const root = document.createElement('div');
  root.className = 'graph-node-actions-overlay';
  root.dataset.documentId = owner.id;
  const sequenceId = ++nextNodeActionSequenceId;
  root.dataset.nodeActionSequenceId = String(sequenceId);
  root.hidden = true;
  const bar = document.createElement('div');
  bar.className = 'graph-node-actions';
  bar.setAttribute('role', 'toolbar');
  bar.setAttribute('aria-label', 'Node actions');
  bar.setAttribute('aria-orientation', 'vertical');
  bar.hidden = true;
  const bridge = document.createElement('div');
  bridge.className = 'graph-node-actions-bridge';
  bridge.hidden = true;
  const menu = document.createElement('div');
  menu.className = 'graph-node-action-menu';
  menu.setAttribute('role', 'menu');
  menu.hidden = true;
  const actionButton = (action, glyph) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'graph-node-action';
    button.dataset.nodeAction = action;
    button.innerHTML = `<span aria-hidden="true">${glyph}</span>`;
    return button;
  };
  const traceButton = actionButton('trace', '⇢');
  const duplicateButton = actionButton('duplicate', '⧉');
  const deleteButton = actionButton('delete', '⌫');
  const moreButton = actionButton('more', '…');
  moreButton.setAttribute('aria-label', 'More node actions');
  moreButton.dataset.tooltip = 'More node actions';
  bar.append(traceButton, deleteButton, duplicateButton, moreButton);
  root.append(bridge, bar, menu);
  container.append(root);
  const schedule = () => scheduleNodeActionOverlay(instance);
  const hide = () => hideNodeActionOverlay(instance);
  const leaveContainer = () => deferNodeActionLeave(instance);
  const overlay = {
    owner, instance, container, root, bar, bridge, menu,
    traceButton, duplicateButton, deleteButton, moreButton,
    sequenceId,
    nodeId: null, raf: null, leaveTimer: null, pointerWithin: false, allowSelected: false,
    menuPosition: null,
    retired: false,
    selectionAtPointerDown: null,
    schedule, hide, leaveContainer,
  };
  nodeActionOverlays.set(instance, overlay);
  nodeActionOverlayRegistry.add(overlay);
  [bar, bridge, menu].forEach(element => {
    element.addEventListener('pointerenter', () => {
      overlay.pointerWithin = true;
      cancelNodeActionLeave(instance);
    });
    element.addEventListener('pointerleave', () => {
      overlay.pointerWithin = false;
      deferNodeActionLeave(instance);
    });
  });
  // Buttons keep native Enter/Space activation. Only stop the key from reaching the graph widget,
  // where Enter means "select the cursor node" and would hide this toolbar before its click fires.
  root.addEventListener('keydown', event => {
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      hideNodeActionOverlay(instance, { restoreFocus: true });
      return;
    }
    const inMenu = menu.contains(document.activeElement);
    const scope = inMenu ? menu : bar;
    const controls = [...scope.querySelectorAll('button:not([hidden]):not(:disabled)')]
      .filter(button => button.offsetParent !== null);
    const index = controls.indexOf(document.activeElement);
    if (inMenu && (event.key === 'Home' || event.key === 'End') && controls.length) {
      event.preventDefault();
      event.stopPropagation();
      controls[event.key === 'Home' ? 0 : controls.length - 1].focus();
      return;
    }
    if (!inMenu && (event.key === 'ArrowLeft' || event.key === 'ArrowRight')) {
      event.preventDefault();
      event.stopPropagation();
      return;
    }
    const delta = event.key === 'ArrowDown' ? 1 : event.key === 'ArrowUp' ? -1 : 0;
    if (delta && index >= 0) {
      event.preventDefault();
      event.stopPropagation();
      controls[(index + delta + controls.length) % controls.length]?.focus();
      return;
    }
    if (event.key === 'Enter' || event.key === ' ') event.stopPropagation();
  });
  root.addEventListener('focusout', () => queueMicrotask(() => {
    if (!root.contains(document.activeElement)) hideNodeActionOverlay(instance);
  }));
  container.addEventListener('pointerleave', leaveContainer);
  instance.on('pan zoom resize render position style', schedule);
  instance.on('select remove', 'node', hide);
  return overlay;
}

function destroyNodeActionOverlay(instance) {
  const overlay = nodeActionOverlays.get(instance);
  if (!overlay) return;
  instance.off('pan zoom resize render position style', overlay.schedule);
  instance.off('select remove', 'node', overlay.hide);
  overlay.container.removeEventListener('pointerleave', overlay.leaveContainer);
  if (overlay.raf != null) cancelAnimationFrame(overlay.raf);
  cancelNodeActionLeave(instance);
  overlay.retired = true;
  nodeActionOverlayRegistry.delete(overlay);
  overlay.root.hidden = true;
  if (![...nodeActionGestureClaims.values()].some(claim => claim.overlayId === overlay.sequenceId)) {
    overlay.root.remove();
  }
  nodeActionOverlays.delete(instance);
  syncNodeActionGestureRouter();
}

// ── Workspace plumbing (UI-03) ──────────────────────────────────────────────────────────────
//
// Two functions carry the whole mechanism. `captureActiveDocument` writes the working view back into
// the record being left; `applyActiveDocument` loads the record being entered. Every field that
// describes one document appears in both, and nowhere else — which is what makes it possible to
// check by reading that nothing leaks between documents.

let nextDocumentId = 0;
let nextUntitledNumber = 1;
const documentNameCounts = new Map();

function allocateDocumentDisplayName(name) {
  const count = (documentNameCounts.get(name) || 0) + 1;
  documentNameCounts.set(name, count);
  return count === 1 ? name : `${name} (${count})`;
}

function captureActiveDocument() {
  const document_ = workspace.active;
  if (!document_) return;
  document_.cy = cy;
  document_.graph = graphData;
  document_.incarnation = activeDocumentIncarnation;
  document_.history = editHistory;
  document_.name = graphName;
  document_.displayName = graphDisplayName;
  document_.renderMode = renderMode;
  document_.layoutMode = layoutMode;
  document_.visualStyle = visualStyle;
  document_.layoutBusy = layoutBusy;
  document_.filterActive = filterActive;
  document_.traceActive = traceActive;
  document_.n8nActive = n8nActive;
  document_.cursorId = graphCursorId;
  document_.fontSize = fontSize;
  document_.execution.executionId = activeExecutionId;
  document_.execution.graphVersion = activeGraphVersion;
  document_.execution.finished = finishedExecutions;
  document_.execution.events = recentRuntimeEvents;
  document_.execution.reconciliationState = activeExecutionReconciliation;
}

function cancelRetiredLayouts(cancelled = []) {
  cancelled.forEach(item => {
    layoutJobs.delete(item.generation);
    if (typeof item.nativeCancel === 'function') item.nativeCancel();
  });
}

function layoutRequestIsCurrent(token) {
  const owner = workspace.find(token?.documentId);
  return Boolean(owner && owner.cy === token.cy && owner.layoutMode === token.mode
    && layoutSessions.isCurrent(token));
}

function clearDynamicEdgeGeometry(owner) {
  if (!owner) return;
  if (owner.edgeGeometryRaf != null) cancelAnimationFrame(owner.edgeGeometryRaf);
  owner.edgeGeometryRaf = null;
  if (owner.cytoEdgeGeometryRaf != null) cancelAnimationFrame(owner.cytoEdgeGeometryRaf);
  owner.cytoEdgeGeometryRaf = null;
  owner.cytoEdgeRouteDirtyNodes?.clear();
}

function clearLayoutDeferredWork(owner) {
  if (!owner) return;
  if (owner.layoutDeferredRaf != null) cancelAnimationFrame(owner.layoutDeferredRaf);
  owner.layoutDeferredRaf = null;
  clearDynamicEdgeGeometry(owner);
  owner.layoutPendingRefit = false;
  owner.cy?.scratch('_rrRefitAfterLayout', false);
}

function invalidateDocumentLayouts(owner) {
  if (!owner) return;
  owner.pendingElasticLayoutToken = null;
  clearLayoutDeferredWork(owner);
  cancelRetiredLayouts(layoutSessions.invalidate(owner.id).cancelled);
  layoutJobs.forEach((job, generation) => {
    if (job.owner.id === owner.id) layoutJobs.delete(generation);
  });
  syncOwnedLayoutBusy(owner);
  owner.layoutSessionToken = null;
}

function applyActiveDocument() {
  cancelMinimapGesture();
  const document_ = workspace.active;
  cy = document_?.cy ?? null;
  graphData = document_?.graph ?? null;
  activeDocumentIncarnation = document_?.incarnation ?? null;
  editHistory = document_?.history ?? createCommandHistory();
  graphName = document_?.name ?? 'untitled.graphml';
  graphDisplayName = document_?.displayName ?? graphName;
  const presentation = documentPresentationState(document_);
  renderMode = presentation.renderMode;
  layoutMode = presentation.layoutMode;
  visualStyle = presentation.visualStyle;
  if (document_) Object.assign(document_, presentation);
  layoutBusy = document_?.layoutBusy ?? false;
  filterActive = document_?.filterActive ?? null;
  traceActive = document_?.traceActive ?? false;
  n8nActive = document_?.n8nActive ?? false;
  graphCursorId = document_?.cursorId ?? null;
  fontSize = document_?.fontSize ?? DEFAULT_FONT_SIZE;
  activeExecutionId = document_?.execution.executionId ?? null;
  activeSourceSession = document_?.sourceSession ?? null;
  activeGraphVersion = document_?.execution.graphVersion ?? null;
  finishedExecutions = document_?.execution.finished ?? new Set();
  recentRuntimeEvents = document_?.execution.events ?? [];
  activeExecutionReconciliation = document_?.execution.reconciliationState ?? 'known';
  // The inline-handler contract predates the workspace and still points at the visible graph.
  window.cy = cy;
  if (cy && document_) cy.batch(() => applyVisualStyle(visualStyle, cy, document_));
}

function syncSourceSessionChrome(owner = workspace.active) {
  const output = document.getElementById('source-session-status');
  if (!output) return;
  const session = owner?.sourceSession;
  if (!session?.state) {
    output.hidden = true;
    output.textContent = '';
    output.className = 'source-session-status';
    return;
  }
  const label = session.state[0] + session.state.slice(1).toLowerCase();
  const availability = session.observationUnavailable ? ' · status unavailable, retrying' : '';
  output.hidden = false;
  output.className = `source-session-status ${session.state.toLowerCase()}`;
  output.textContent = `${label} · ${session.sourceCount} local source${session.sourceCount === 1 ? '' : 's'}${availability}`;
  output.setAttribute('aria-label', `${label}. Process-local source session with ${session.sourceCount} source nodes.`
    + (session.observationUnavailable ? ' Status is unavailable; the editor is retrying.' : ''));
}

// The seam driven by both the tiling panes and the isolation tests.
// It is deliberately the same surface: the panes will not get a private path into the workspace, so
// what the tests exercise is what the UI will use. `captureActiveDocument` runs first because the
// active document's record is only up to date once its working view has been written back.
window.ravenroot = {
  workspace,
  openDocument,
  replaceActiveDocumentFromText,
  activateDocument,
  closeDocument,
  requestCloseDocument,
  requestCloseAllDocuments,
  documents: () => {
    captureActiveDocument();
    return workspace.documents;
  },
  activeDocument: () => {
    captureActiveDocument();
    return workspace.active;
  },
  setWorkspaceLayout: setWorkspaceLayoutMode,
  resetWorkspaceLayout,
  workspaceLayout: () => ({ ...workspaceLayout, plan: workspacePlan }),
  minimapSnapshot: () => minimapLastSnapshot ? JSON.parse(JSON.stringify(minimapLastSnapshot)) : null,
  applicationTheme: () => applicationTheme,
  setApplicationTheme: theme => themePreference.select(theme),
};

// ── Panes (UI-03) ───────────────────────────────────────────────────────────────────────────
//
// THE PANE IS BUILT BEFORE THE DOCUMENT, AND THE CANVAS IS CREATED ALREADY INSIDE IT.
//
// That is not a style preference, it is the one construction order that works. Moving an existing
// `.doc-canvas` to a different parent STOPS ITS CYTOSCAPE INSTANCE PAINTING, and `cy.resize()`,
// `cy.fit()` and `cy.forceRender()` do NOT recover it: the renderer binds to the container it was
// constructed against. So `documentContainer` asks for the pane first and appends the canvas into
// it, and nothing afterwards ever re-parents that canvas.
//
// Everything the layout does later is chosen to keep that promise. Panes are APPENDED to `#cy` and
// REMOVED from it; separators are inserted between them. Neither operation moves an existing pane,
// so neither can move a canvas. Showing and hiding is a class, never a detach.
//
// The width rule itself lives in `panes.js`, which has no DOM: this file renders the plan, it does
// not decide it.

// Mode is reload-persistent; track shares are intentionally session-only because document identity
// and order do not survive reload. Neither belongs to an individual document record.
let workspaceLayout = readStoredWorkspaceLayout();
let workspacePlan = null;
const workspaceSplitters = new Map();
let resizedGridTopology = null;
const resizedGridAxes = { column: false, row: false };

function readStoredWorkspaceLayout() {
  try {
    const raw = globalThis.localStorage?.getItem(WORKSPACE_LAYOUT_STORAGE_KEY);
    return raw ? validateWorkspaceLayout(JSON.parse(raw)) : defaultWorkspaceLayout();
  } catch {
    return defaultWorkspaceLayout();
  }
}

function persistWorkspaceLayout() {
  try {
    globalThis.localStorage?.setItem(WORKSPACE_LAYOUT_STORAGE_KEY, serializeWorkspaceLayout(workspaceLayout));
  } catch {
    // The current geometry remains usable when convenience persistence is unavailable.
  }
}

function setWorkspaceLayoutMode(mode) {
  workspaceLayout.mode = validateWorkspaceLayout({ mode }).mode;
  persistWorkspaceLayout();
  syncPaneLayout();
  refreshCommands();
}

function resetWorkspaceLayout() {
  workspaceLayout = defaultWorkspaceLayout();
  resizedGridTopology = null;
  resizedGridAxes.column = false;
  resizedGridAxes.row = false;
  try { globalThis.localStorage?.removeItem(WORKSPACE_LAYOUT_STORAGE_KEY); } catch { /* optional */ }
  syncPaneLayout();
  refreshCommands();
}

function workspaceLayoutIsDefault() {
  return workspaceLayout.mode === 'horizontal'
    && workspaceLayout.columnShares.every((value, index, values) => Math.abs(value - 1 / values.length) < 1e-9)
    && workspaceLayout.rowShares.every((value, index, values) => Math.abs(value - 1 / values.length) < 1e-9);
}
// One arrow press. Coarse enough to get somewhere, fine enough to aim.

// The active document's name and dirtiness live in the working view, not in the record, until they
// are written back. Reading the record for the active document would be the second answer to a
// question that must only have one.
function paneDisplayName(document_) {
  return workspace.activeId === document_.id ? graphDisplayName : document_.displayName;
}

function paneIsDirty(document_) {
  return workspace.activeId === document_.id
    ? Boolean(editHistory.state().dirty)
    : Boolean(document_.history?.isDirty());
}

function documentPane(document_) {
  if (document_.pane?.isConnected) return document_.pane;
  const host = window.document.getElementById('cy');
  const pane = window.document.createElement('div');
  pane.className = 'doc-pane';
  pane.dataset.documentId = document_.id;
  // Programmatically focusable always; in the TAB ORDER only when there is more than one pane to
  // choose between, so a single-document workspace keeps exactly the tab order it had before.
  pane.tabIndex = -1;

  const header = window.document.createElement('div');
  header.className = 'doc-pane-header';
  const name = window.document.createElement('span');
  name.className = 'doc-pane-name';
  header.append(name);
  // Spoken state, so the strip does not rely on the visual treatment to say which pane is which.
  const state = window.document.createElement('span');
  state.className = 'doc-pane-state visually-hidden';
  header.append(state);
  pane.append(header);
  const close = window.document.createElement('button');
  close.className = 'doc-pane-close';
  close.type = 'button';
  close.dataset.paneDocumentClose = document_.id;
  close.textContent = '×';
  // Out of the TAB ORDER, deliberately, the same way the pane above is until there is more than one
  // to choose between: a native <button> defaults to tabIndex 0, which places it between the pane and
  // the resize separator (pane, close, separator, pane instead of pane, separator, pane) and breaks
  // the measured Tab route. It remains a real, mouse- and
  // programmatically-focusable control -- `close.click()`/`close.focus()` both work -- just not a stop
  // Tab lands on by itself. Closing a background document from the keyboard still works: Tab into its
  // pane (which activates it, same as any other pane focus) and use the ordinary Close command.
  close.tabIndex = -1;
  // Do not activate a background document merely because its close control was used. The click
  // still bubbles to the shared document-close command route below.
  close.addEventListener('pointerdown', event => event.stopPropagation());
  header.append(close);

  // THE focus handler. It is the only way focus changes the active document, and it changes it by
  // calling `activateDocument` — the same seam the ownership tests drive. The strip above is
  // presentation and has no handler of its own: if it is ever made clickable that is a separate
  // decision, and it must still arrive here rather than take a private path into the workspace.
  pane.addEventListener('focusin', event => {
    if (event.target.closest?.('[data-pane-document-close]')) return;
    activateDocument(document_.id);
  });
  // A pointer on a background pane routes to that same handler by MOVING FOCUS rather than by
  // activating directly, so there is one path in and therefore one path to test. Skipped entirely
  // when the pane is already the active one, which is every interaction in a single-pane workspace.
  pane.addEventListener('pointerdown', () => {
    if (workspace.activeId === document_.id) return;
    pane.focus({ preventScroll: true });
  });

  host.append(pane);
  document_.pane = pane;
  return pane;
}

function documentContainer(document_) {
  if (document_.container?.isConnected) return document_.container;
  // The pane FIRST. The canvas is created already inside its destination and is never moved again.
  const pane = documentPane(document_);
  const element = window.document.createElement('div');
  element.className = 'doc-canvas';
  element.dataset.documentId = document_.id;
  pane.append(element);
  document_.container = element;
  // Seed the pane size bookkeeping from the canvas's first laid-out box, so that the first
  // resize the user performs is read as the change it is instead of as this document's first
  // measurement. See `paneSeedObserver` — which is a `const` declared BELOW this function, so this
  // function must never be reached during module evaluation. Everything above that declaration is
  // imports and assignments today; if that ever stops being true, this line becomes a boot-time
  // `ReferenceError` rather than anything subtler.
  paneSeedObserver.observe(element);
  return element;
}

// The strip carries the NAME, and the name is the identity. The focus indicator and the modified
// marker are ADDITIONAL signals layered on it, never replacements: a colour can say that two panes
// differ, never which is which, and says nothing at all to a user who cannot see it. Both extra
// signals are therefore spelled out in text as well as drawn.
function syncPaneHeaders() {
  const activeId = workspace.activeId;
  workspace.documents.forEach(document_ => {
    const pane = document_.pane;
    if (!pane) return;
    const header = pane.querySelector('.doc-pane-header');
    const active = document_.id === activeId;
    const name = paneDisplayName(document_);
    const dirty = paneIsDirty(document_);

    const label = header.querySelector('.doc-pane-name');
    label.textContent = name;
    // The name is ellipsised when the pane is tight, so the full one stays reachable.
    label.title = name;
    const close = header.querySelector('.doc-pane-close');
    close.title = `Close ${name}`;
    close.setAttribute('aria-label', `Close ${name}`);

    // The `*` an editor puts next to a modified file: a character, not a tint.
    let modified = header.querySelector('.doc-pane-modified');
    if (dirty && !modified) {
      modified = window.document.createElement('span');
      modified.className = 'doc-pane-modified';
      modified.setAttribute('aria-hidden', 'true');
      modified.textContent = '*';
      label.after(modified);
    } else if (!dirty && modified) {
      modified.remove();
    }

    header.querySelector('.doc-pane-state').textContent =
      [active ? 'active document' : '', dirty ? 'modified' : '', document_.layoutBusy ? 'layout in progress' : '']
        .filter(Boolean).join(', ');

    pane.classList.toggle('doc-pane--active', active);
    if (active) pane.setAttribute('aria-current', 'true');
    else pane.removeAttribute('aria-current');
    pane.setAttribute('aria-label', `${name}${dirty ? ', modified' : ''}`
      + `${document_.layoutBusy ? ', layout in progress' : ''}`);
  });
}

function documentEdgeGhost(document_) {
  const container = document_?.container;
  if (!container) return null;
  let ghost = container.querySelector(':scope > .edge-ghost');
  if (ghost) return ghost;
  ghost = window.document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  ghost.classList.add('edge-ghost');
  ghost.dataset.documentId = document_.id;
  ghost.setAttribute('aria-hidden', 'true');
  container.append(ghost);
  return ghost;
}

// Separators are rebuilt only when the PAIRING changes, never on every geometry update: recreating
// one would throw away the focus of the user who is resizing with the keyboard.
function workspaceSplitter(axis, boundary) {
  const key = `${axis}:${boundary}`;
  if (workspaceSplitters.has(key)) return workspaceSplitters.get(key);
  const separator = window.document.createElement('div');
  separator.className = `pane-separator workspace-splitter workspace-splitter--${axis === 'column' ? 'vertical' : 'horizontal'}`;
  separator.dataset.layoutSplitter = `workspace:${key}`;
  separator.dataset.splitterKind = 'document';
  separator.dataset.workspaceSplitter = key;
  separator.dataset.workspaceAxis = axis;
  separator.dataset.axis = axis;
  separator.dataset.boundary = String(boundary);
  separator.setAttribute('role', 'separator');
  separator.setAttribute('aria-orientation', axis === 'column' ? 'vertical' : 'horizontal');
  separator.tabIndex = 0;
  separator.addEventListener('keydown', event => onSeparatorKeydown(event, separator));
  separator.addEventListener('pointerdown', event => onSeparatorPointerDown(event, separator));
  window.document.getElementById('cy').append(separator);
  workspaceSplitters.set(key, separator);
  return separator;
}

function syncPaneSeparators(plan, visible) {
  const wanted = new Set();
  if (plan.visibility === 'all') {
    for (let index = 0; index < plan.columns - 1; index += 1) wanted.add(`column:${index}`);
    for (let index = 0; index < plan.rows - 1; index += 1) wanted.add(`row:${index}`);
  }
  workspaceSplitters.forEach((separator, key) => {
    if (wanted.has(key)) return;
    separator.hidden = true;
    separator.tabIndex = -1;
  });
  if (plan.visibility !== 'all') return;
  const hostRect = window.document.getElementById('cy').getBoundingClientRect();
  const configure = (axis, boundary, panes) => {
    const separator = workspaceSplitter(axis, boundary);
    panes.forEach(document_ => { document_.pane.id ||= `document-pane-${document_.id}`; });
    separator.hidden = false;
    separator.tabIndex = 0;
    separator.setAttribute('aria-controls', panes.map(document_ => document_.pane.id).join(' '));
    separator.setAttribute('aria-label', `${axis === 'column' ? 'Column' : 'Row'} ${boundary + 1} size`);
    if ((plan.rows === 1 && axis === 'column') || (plan.columns === 1 && axis === 'row')) {
      const nextPane = visible[boundary + 1]?.pane;
      if (nextPane && separator.nextSibling !== nextPane) {
        window.document.getElementById('cy').insertBefore(separator, nextPane);
      }
    }
    if (axis === 'column') {
      const before = visible.filter((_, index) => plan.cells[index].column <= boundary);
      const edge = Math.max(...before.map(item => item.pane.getBoundingClientRect().right));
      separator.style.left = `${edge - hostRect.left}px`;
      separator.style.top = '0';
      separator.style.height = `${hostRect.height}px`;
    } else {
      const before = visible.filter((_, index) => plan.cells[index].row <= boundary);
      const edge = Math.max(...before.map(item => item.pane.getBoundingClientRect().bottom));
      separator.style.top = `${edge - hostRect.top}px`;
      separator.style.left = '0';
      separator.style.width = `${hostRect.width}px`;
    }
  };
  for (let index = 0; index < plan.columns - 1; index += 1) configure('column', index, visible);
  for (let index = 0; index < plan.rows - 1; index += 1) configure('row', index, visible);
}

function measuredWorkspaceOverlays(host) {
  const hostRect = host.getBoundingClientRect();
  return ['minimap', 'zoom-ctrl'].map(id => window.document.getElementById(id)).filter(element => {
    if (!element || getComputedStyle(element).display === 'none') return false;
    const rect = element.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0;
  }).map(element => {
    const rect = element.getBoundingClientRect();
    return {
      left: rect.left - hostRect.left,
      top: rect.top - hostRect.top,
      right: rect.right - hostRect.left,
      bottom: rect.bottom - hostRect.top,
    };
  });
}

// The whole rendered plan. Structural: which panes are shown, which are in the tab order, where the
// separators are, what the strips say.
function syncPaneLayout() {
  const host = window.document.getElementById('cy');
  const wrap = window.document.getElementById('cy-wrap');
  if (!host || !wrap) return;

  const documents = workspace.documents;
  const activeId = workspace.activeId;
  const plan = planWorkspaceLayout({
    mode: workspaceLayout.mode,
    availableWidth: wrap.clientWidth,
    availableHeight: wrap.clientHeight,
    columnShares: workspaceLayout.columnShares,
    rowShares: workspaceLayout.rowShares,
    gridTopology: resizedGridTopology,
    preserveGridColumnShares: resizedGridAxes.column,
    preserveGridRowShares: resizedGridAxes.row,
    overlays: measuredWorkspaceOverlays(host),
    // Workspace feasibility is structural. Graph bounds change while asynchronous render layouts
    // run, but the graph already owns a pan/zoom viewport inside its pane. Letting a transient
    // bounding box decide whether the pane itself exists made the first command project one plan
    // and a later command project another, even though the requested mode never changed.
    documents: documents.map(document_ => ({ active: document_.id === activeId })),
  });
  workspacePlan = plan;
  if (plan.visibility === 'all') {
    if (workspaceLayout.mode === 'grid') {
      const topology = `${plan.columns}x${plan.rows}`;
      if (topology !== resizedGridTopology) {
        resizedGridAxes.column = false;
        resizedGridAxes.row = false;
      }
      resizedGridTopology = topology;
    }
    workspaceLayout.columnShares = plan.columnShares;
    workspaceLayout.rowShares = plan.rowShares;
  }

  const visible = plan.visibility === 'all'
    ? documents
    : documents.filter(document_ => document_.id === activeId);
  const visibleIds = new Set(visible.map(document_ => document_.id));
  if (edgeGestureSession && !visibleIds.has(edgeGestureSession.documentId)) {
    cancelEdgeGesture({ clearMessage: true });
  }

  // The strip earns its 24px only when there is another document to be told apart from. With one
  // document open there is nothing to disambiguate — `#graph-title` in the top bar already names it —
  // so the strip would be chrome that identifies nothing, paid for out of the canvas. Hiding it also
  // means a single-document workspace retains its established geometry, which
  // is what keeps UI-02's pointer contract measuring the same pixels it always measured.
  host.classList.toggle('panes-titled', documents.length > 1);

  documents.forEach(document_ => {
    const pane = document_.pane;
    if (!pane) return;
    const shown = visibleIds.has(document_.id);
    pane.classList.toggle('doc-pane--shown', shown);
    pane.classList.toggle('doc-pane--tight', shown && plan.columns > 1);
    pane.tabIndex = shown && visible.length > 1 ? 0 : -1;
    // The marker for "this is the active document's canvas" is kept because it is what the
    // lifecycle suite reads and because it still means exactly what it meant.
    document_.container?.classList.toggle('active-document', document_.id === activeId);
    if (shown) resumeDocumentRenderer(document_);
    else suspendDocumentRenderer(document_);
  });

  host.dataset.workspaceMode = workspaceLayout.mode;
  host.dataset.workspaceVisibility = plan.visibility;
  syncPaneHeaders();
  applyPaneGeometry();
}

// Geometry only: the grow factors, what the separators report, and the resize each renderer needs.
// Separated from `syncPaneLayout` so that dragging or keying a separator does not rebuild the DOM
// underneath the pointer or the focus that is driving it.
function applyPaneGeometry() {
  const host = window.document.getElementById('cy');
  if (!host || !workspacePlan) return;
  const shown = workspace.documents.filter(document_ => document_.pane?.classList.contains('doc-pane--shown'));
  host.style.gridTemplateColumns = workspacePlan.columnShares.map(value => `minmax(0, ${value}fr)`).join(' ');
  host.style.gridTemplateRows = workspacePlan.rowShares.map(value => `minmax(0, ${value}fr)`).join(' ');
  shown.forEach((document_, index) => {
    const cell = workspacePlan.cells[index] || { column: 0, row: 0 };
    document_.pane.dataset.workspaceColumn = String(cell.column + 1);
    document_.pane.dataset.workspaceRow = String(cell.row + 1);
    document_.pane.style.gridColumn = String(cell.column + 1);
    document_.pane.style.gridRow = String(cell.row + 1);
    document_.pane.style.removeProperty('flex-grow');
  });
  syncPaneSeparators(workspacePlan, shown);
  workspaceSplitters.forEach(separator => {
    if (separator.hidden) return;
    const axis = separator.dataset.axis;
    const boundary = Number(separator.dataset.boundary);
    const shares = axis === 'column' ? workspacePlan.columnShares : workspacePlan.rowShares;
    const minimums = axis === 'column' ? workspacePlan.columnMins : workspacePlan.rowMins;
    const cumulative = shares.slice(0, boundary + 1).reduce((sum, value) => sum + value, 0);
    const minBefore = minimums.slice(0, boundary + 1).reduce((sum, value) => sum + value, 0);
    const minAfter = minimums.slice(boundary + 1).reduce((sum, value) => sum + value, 0);
    const available = axis === 'column' ? host.clientWidth : host.clientHeight;
    separator.setAttribute('aria-valuenow', String(Math.round(cumulative * 100)));
    separator.setAttribute('aria-valuemin', String(Math.round(minBefore / available * 100)));
    separator.setAttribute('aria-valuemax', String(Math.round((available - minAfter) / available * 100)));
  });

  shown.forEach(syncPaneRenderer);
  shown.forEach(positionProgramReadinessOverlay);

  // The elastic overlay is a renderer too, and its pane can be resized like any other while it
  // runs. The SVG's CSS box follows the flex slot on its own; these ATTRIBUTES are what D3 sized
  // the drawing against, and they do not observe anything. Dimensions only — the simulation is not
  // recentred for a nudge, for the same reason `syncPaneRenderer` does not refit for one: the
  // geometry the user set is theirs.
  shown.forEach(owner => {
    const renderer = elasticRendererFor(owner);
    if (!renderer?.host.classList.contains('active')
        || !renderer.host.clientWidth || !renderer.host.clientHeight) return;
    renderer.svg.setAttribute('width', String(renderer.host.clientWidth));
    renderer.svg.setAttribute('height', String(renderer.host.clientHeight));
  });
  scheduleMinimap();
}

// The size each renderer was last told about, so a pane that did not change is never disturbed.
const paneRenderedSize = new Map();

// `syncPaneRenderer`'s "first measurement" branch below is sound only when the size it
// records IS the size the document's framing was computed against. At boot that premise does not
// hold. The pane is created and synced inside the SAME tick, while its box is still 0x0, so
// `syncPaneRenderer` returns on its own `!width || !height` guard and records nothing — and no
// further sync arrives, because nothing has resized yet. The first size the map ever sees is then
// the one the user's own resize produced; the change is invisible to it, the "first measurement"
// branch returns, and the graph is left framed for a box it no longer occupies. Clipped, and not
// briefly: until a SECOND resize arrives, because that one finally has a `last` to differ from.
//
// At 1800x900, `syncPaneLayout` can run
// three or four times before the document record exists (`shown` empty, nothing to record) and once
// more in the very tick that creates it, with `clientWidth`/`clientHeight` still 0; `finishOwnedLayout`
// does not run at all inside the first 700ms, so the layout's own completion is not a seam to hang
// this on either. Whether some later sync happened to land after the pane was laid out is what
// decided the outcome, making a deterministic defect appear intermittent.
//
// This observes the 0x0 -> laid-out TRANSITION rather than waiting a guessed number of frames for
// it, and unobserves on the first non-zero delivery: seeding is its whole job, it writes no DOM, so
// it cannot become a resize loop and it has nothing to do once the size is known.
const paneSeedObserver = new ResizeObserver(entries => {
  entries.forEach(entry => {
    const element = entry.target;
    if (!element.clientWidth || !element.clientHeight) return;
    paneSeedObserver.unobserve(element);
    const id = element.dataset.documentId;
    if (!id || paneRenderedSize.has(id)) return;
    paneRenderedSize.set(id, { width: element.clientWidth, height: element.clientHeight });
  });
});
// Above this proportion the pane changed because the WORKSPACE changed; below it, the user nudged it.
const PANE_REFIT_THRESHOLD = 0.2;
const PANE_REFIT_PADDING = 40;

function clampAutomaticFitZoom(document_) {
  const instance = document_?.cy;
  if (!instance) return;
  const nodeFont = Number(document_?.fontSize) || DEFAULT_FONT_SIZE;
  const edgeFont = Math.max(8, Math.round(nodeFont * 0.75));
  const minimumZoom = readableZoomForFonts(nodeFont, edgeFont);
  if (instance.zoom() >= minimumZoom) return;
  instance.zoom({
    level: minimumZoom,
    renderedPosition: { x: instance.width() / 2, y: instance.height() / 2 },
  });
}

// Cytoscape does not observe its container, so a pane that changed width has to tell its renderer —
// and `resize()` alone is not enough. `resize()` keeps the zoom, so a document that was framed for a
// full-width canvas spills out of a half-width pane and is simply clipped at the boundary.
function syncPaneRenderer(document_) {
  const cy = document_.cy;
  const container = document_.container;
  if (!cy || !container) return;
  const width = container.clientWidth;
  const height = container.clientHeight;
  // A hidden canvas measures zero — the active document's own canvas hides while the elastic
  // renderer occupies its pane — and handing an instance a 0×0 viewport is how a canvas ends up
  // permanently blank.
  if (!width || !height) return;

  const last = paneRenderedSize.get(document_.id);
  const refitPending = document_.layoutPendingRefit || Boolean(cy.scratch('_rrRefitAfterLayout'));
  const consumePendingRefit = refitPending && !cy.scratch('_rrLayoutRunning');
  if (last && last.width === width && last.height === height && !consumePendingRefit) return;
  paneRenderedSize.set(document_.id, { width, height });

  cy.resize();
  if (consumePendingRefit) {
    document_.layoutPendingRefit = false;
    cy.scratch('_rrRefitAfterLayout', false);
    cy.fit(undefined, PANE_REFIT_PADDING);
    clampAutomaticFitZoom(document_);
    return;
  }
  // First measurement: whatever framed this document is still current.
  if (!last) return;

  // A pane that changed size by a LOT changed because the workspace changed — a document opened or
  // closed, a split appeared or collapsed. The user never chose the old framing for this new width,
  // so the graph is refitted. A SMALL change is a window nudge or a separator drag: there the
  // framing is the user's own and only the centre is kept, because the per-document viewport is a
  // per-document property to preserve, not to recompute.
  if (Math.abs(width - last.width) / last.width > PANE_REFIT_THRESHOLD
      || Math.abs(height - last.height) / last.height > PANE_REFIT_THRESHOLD) {
    cy.fit(undefined, PANE_REFIT_PADDING);
    clampAutomaticFitZoom(document_);
    // AND AGAIN AFTER ANY LAYOUT STILL IN FLIGHT. An animated layout ends with a fit of its own,
    // computed from the dimensions it started with, so a pane that changes size DURING a layout gets
    // its refit silently overwritten and settles permanently clipped — not briefly, permanently.
    // An explicit layout can still finish while panes are being resized or documents are switched.
    if (cy.scratch('_rrLayoutRunning')) cy.scratch('_rrRefitAfterLayout', true);
    else cy.scratch('_rrRefitAfterLayout', false);
    return;
  }
  const pan = cy.pan();
  cy.pan({ x: pan.x + (width - last.width) / 2, y: pan.y + (height - last.height) / 2 });
}

function moveSeparator(separator, deltaPx) {
  if (!workspacePlan) return;
  const axis = separator.dataset.axis;
  const boundary = Number(separator.dataset.boundary);
  const host = window.document.getElementById('cy');
  const available = axis === 'column' ? workspacePlan.width : workspacePlan.height;
  const shares = axis === 'column' ? workspaceLayout.columnShares : workspaceLayout.rowShares;
  const minimums = axis === 'column' ? workspacePlan.columnMins : workspacePlan.rowMins;
  const sizes = shares.map(value => value * available);
  const next = resizeAxisShares({ shares, sizes, boundary, deltaPx, minimums });
  if (axis === 'column') {
    workspaceLayout.columnShares = next;
    workspacePlan.columnShares = next;
  } else {
    workspaceLayout.rowShares = next;
    workspacePlan.rowShares = next;
  }
  if (workspaceLayout.mode === 'grid') {
    resizedGridTopology = `${workspacePlan.columns}x${workspacePlan.rows}`;
    resizedGridAxes[axis] = true;
  }
  applyPaneGeometry();
}

// The ARIA window-splitter keyboard contract: arrows move the boundary, Home returns to the even
// split. This is the pane keyboard route in full, and it needs no chord — see `onSeparatorKeydown`'s
// counterpart decision recorded at the Tab handling in `syncPaneLayout`.
function onSeparatorKeydown(event, separator) {
  if (event.key === 'Home') {
    event.preventDefault();
    const axis = separator.dataset.axis;
    if (axis === 'column') {
      workspaceLayout.columnShares = equalAxisShares(workspacePlan.columns);
      workspacePlan.columnShares = workspaceLayout.columnShares;
    } else {
      workspaceLayout.rowShares = equalAxisShares(workspacePlan.rows);
      workspacePlan.rowShares = workspaceLayout.rowShares;
    }
    if (workspaceLayout.mode === 'grid') resizedGridAxes[axis] = false;
    applyPaneGeometry();
    return;
  }
  const axis = separator.dataset.axis;
  const direction = axis === 'column'
    ? (event.key === 'ArrowLeft' ? -1 : event.key === 'ArrowRight' ? 1 : 0)
    : (event.key === 'ArrowUp' ? -1 : event.key === 'ArrowDown' ? 1 : 0);
  if (!direction) return;
  event.preventDefault();
  // Stops here rather than bubbling to the graph widget, which owns the arrows for cursor movement.
  event.stopPropagation();
  moveSeparator(separator, direction * SPLITTER_KEY_STEP);
}

function onSeparatorPointerDown(event, separator) {
  event.preventDefault();
  separator.classList.add('pane-separator--active');
  separator.focus({ preventScroll: true });
  separator.setPointerCapture(event.pointerId);
  let lastPoint = separator.dataset.axis === 'column' ? event.clientX : event.clientY;
  const onMove = moveEvent => {
    const point = separator.dataset.axis === 'column' ? moveEvent.clientX : moveEvent.clientY;
    moveSeparator(separator, point - lastPoint);
    lastPoint = point;
  };
  const onRelease = () => {
    separator.classList.remove('pane-separator--active');
    separator.removeEventListener('pointermove', onMove);
    separator.removeEventListener('pointerup', onRelease);
    separator.removeEventListener('pointercancel', onRelease);
  };
  separator.addEventListener('pointermove', onMove);
  separator.addEventListener('pointerup', onRelease);
  separator.addEventListener('pointercancel', onRelease);
}

// Adds an empty record and makes it active, without rendering anything. Boot uses it so that a
// document exists before the first `initCy`, which now needs one to know where to draw.
function addDocumentRecord(name = defaultDocumentName(), displayName = allocateDocumentDisplayName(name)) {
  // Guarded like every other call site this function's sibling added: an unconditional call
  // here also fires at boot, before any document or gesture exists, and `cancelEdgeGesture` always
  // stamps `#cy-wrap`'s `data-edge-gesture-state` to `idle` regardless of whether there was anything
  // to cancel. That turned "no attribute at all" into "idle" from the very first paint, which
  // is observable state with no gesture behind it. Skipping a genuine no-op cancel changes nothing
  // for the case this call exists to protect -- an active gesture on another document, still live
  // when a new one is added -- while leaving a fresh boot untouched.
  if (edgeGestureSession) cancelEdgeGesture({ clearMessage: true });
  captureActiveDocument();
  if (workspace.documents.length && workspaceLayout.mode !== 'grid') {
    const axis = workspaceLayout.mode === 'horizontal' ? 'columnShares' : 'rowShares';
    const current = workspaceLayout[axis];
    workspaceLayout[axis] = current
      .map(value => value * current.length / (current.length + 1))
      .concat(1 / (current.length + 1));
  }
  const document_ = workspace.add(createDocumentRecord({
    id: `doc-${nextDocumentId += 1}`,
    name,
    displayName,
    history: createCommandHistory(),
  }));
  applyActiveDocument();
  documentContainer(document_);
  syncPaneLayout();
  return document_;
}

// Two panes both labelled `untitled.graphml` identify nothing, and identity is the whole purpose of
// the strip. Untitled documents are therefore numbered from the second one onwards; the first keeps
// the bare name the editor has always opened with, so nothing changes for a single document.
function defaultDocumentName() {
  if (nextUntitledNumber === 1) {
    nextUntitledNumber += 1;
    return 'untitled.graphml';
  }
  const name = `untitled-${nextUntitledNumber}.graphml`;
  nextUntitledNumber += 1;
  return name;
}

function initLoadedGraph(graph, currentStyle) {
  initCy(buildElements(graph), graph, {
    visualStyle: currentStyle,
  });
}

function openDocument({ name = defaultDocumentName(), graph = null } = {}) {
  const document_ = addDocumentRecord(name);
  if (graph) {
    graphName = name;
    graphDisplayName = document_.displayName;
    initLoadedGraph(graph, document_.visualStyle);
  } else {
    newWorkflow();
    // `newWorkflow` names what it creates `untitled.graphml`, because the New button shares it and
    // means exactly that. Opening a document ASKED for a name, so the name asked for is restored
    // over the one the shared helper assumed — otherwise the argument is silently dropped and every
    // new pane claims to be the same document.
    graphName = name;
    graphDisplayName = document_.displayName;
  }
  syncActiveDocumentChrome();
  scheduleProgramGraphReadiness(document_);
  return document_.id;
}

// Installs the semantic projection on both homes of the active view before a renderer observes it.
// This is intentionally position-neutral: renderer initialization still uses Cytoscape's preset
// layout, while an explicit Design command remains the only path that runs the Cyto layout engine.
function installActiveRenderModePresentation(owner, mode = DEFAULT_RENDER_MODE) {
  const presentation = renderModePresentation(mode);
  Object.assign(owner, presentation);
  renderMode = presentation.renderMode;
  layoutMode = presentation.layoutMode;
  visualStyle = presentation.visualStyle;
}

function completeReplaceActiveDocument(target, graph, name) {
  if (!target) return workspace.active ? false : openDocument({ name, graph });
  // The dirty prompt may resolve in a later task. Replace the record selected when the action was
  // requested, never whichever sibling happens to be active by then. A closed, rebound, or
  // programmatically backgrounded target makes the completion an atomic no-op.
  if (workspace.find(target.id) !== target || workspace.active !== target) return false;
  if (edgeGestureSession?.owner === target) cancelEdgeGesture({ clearMessage: true });
  retireProgramReadiness(target);
  // Retire callbacks before `initCy` destroys the old instance. ELK may not emit `layoutstart`
  // until a later task, so destroying first leaves a pre-start callback holding a dead owner.
  invalidateDocumentLayouts(target);
  destroyDocumentRenderer(target, 'replaced');
  detachExecution(target);
  target.incarnation = createDocumentIncarnation();
  activeDocumentIncarnation = target.incarnation;
  graphName = name;
  graphDisplayName = allocateDocumentDisplayName(name);
  target.name = graphName;
  target.displayName = graphDisplayName;
  // Replacement starts in the canonical Design view without moving the incoming persisted
  // coordinates. Install that projection on the owner before `initCy`: its internal paint calls
  // `setVisualStyle`, which must not observe the retired Monitoring layout and rewrite it to preset.
  installActiveRenderModePresentation(target, DEFAULT_RENDER_MODE);
  filterActive = null;
  traceActive = false;
  n8nActive = false;
  graphCursorId = null;
  fontSize = DEFAULT_FONT_SIZE;
  activeExecutionId = null;
  activeGraphVersion = null;
  activeExecutionReconciliation = 'known';
  finishedExecutions = new Set();
  // A new array, not `.length = 0`: the record survives a replace, so clearing in place would also
  // clear the tail this document had before it — and `captureActiveDocument` below writes this new
  // one through. Without it the replacing graph inherits the replaced graph's events.
  recentRuntimeEvents = [];
  editHistory = createCommandHistory();
  initLoadedGraph(graph, visualStyle);
  clearActivity();
  addActivityMessage('editor', `Loaded ${name}`, 'completed');
  captureActiveDocument();
  syncActiveDocumentChrome();
  scheduleProgramGraphReadiness(target);
  return target.id;
}

function requestReplaceActiveDocument(graph, name, origin = document.activeElement) {
  captureActiveDocument();
  const target = workspace.active;
  if (!target || !target.history?.isDirty()) return completeReplaceActiveDocument(target, graph, name);
  return openUnsavedDocumentDialog({
    documentId: target.id,
    origin,
    kind: 'replace',
    eligible: () => workspace.find(target.id) === target && workspace.active === target,
    complete: () => completeReplaceActiveDocument(target, graph, name),
  });
}

// The File command reads a file and hands its text here. Parsing is deliberately complete
// before a dirty prompt or record mutation, so a malformed replacement is a true no-op.
function replaceActiveDocumentFromText(text, name, origin = document.activeElement) {
  const graph = parsePreparedGraph(text, name);
  return requestReplaceActiveDocument(graph, name, origin);
}

function activateDocument(id) {
  if (!workspace.find(id) || workspace.activeId === id) return workspace.activeId;
  retireElementSelectionGesture(cy);
  invalidateStableSelection();
  cancelNodeMoveGesture();
  cancelEdgeGesture({ clearMessage: true });
  captureActiveDocument();
  workspace.activate(id);
  applyActiveDocument();
  syncPaneLayout();
  reconcileActiveRenderModeRenderer();
  syncActiveDocumentChrome();
  return workspace.activeId;
}

function teardownDocument(target) {
  if (dragSnapshot?.owner === target) cancelNodeMoveGesture();
  if (edgeGestureSession?.owner === target) cancelEdgeGesture({ clearMessage: true });
  retireProgramReadiness(target);
  // Closing only detaches this browser's observation. The process-local listener remains owned by
  // the server until its explicit Stop; aborting the poll prevents a closed document retaining a
  // request/controller without pretending that closing the tab is an undeploy command.
  target.sourceSession.pollController?.abort();
  target.sourceSession.pollController = null;
  // Renderer ownership is per document: close retires this target's callbacks and host without
  // touching any visible sibling, whether or not the target owns the shared chrome.
  destroyDocumentRenderer(target, 'closed');
  invalidateDocumentLayouts(target);
  // The run keeps going on the server; the client never owned it, so closing stops projecting it
  // rather than cancelling it.
  detachExecution(target);
  if (target.cy) releaseCanvasZoomBridge(target.cy);
  target.cy?.destroy();
  if (target.container) paneSeedObserver.unobserve(target.container);
  target.programReadiness?.overlay?.remove();
  target.pane?.remove();
  target.container = null;
  target.pane = null;
  paneRenderedSize.delete(target.id);
}

function removeClosedDocumentShares(targets) {
  if (workspaceLayout.mode !== 'grid') {
    const axis = workspaceLayout.mode === 'horizontal' ? 'columnShares' : 'rowShares';
    const closing = new Set(targets);
    const remaining = workspaceLayout[axis].filter((_, index) =>
      !closing.has(workspace.documents[index]));
    const total = remaining.reduce((sum, value) => sum + value, 0);
    workspaceLayout[axis] = total > 0 ? remaining.map(value => value / total) : [1];
  }
}

function projectWorkspaceAfterDocumentClose() {
  applyActiveDocument();
  syncPaneLayout();
  reconcileActiveRenderModeRenderer();
  syncActiveDocumentChrome();
}

function closeDocument(id) {
  const target = workspace.find(id);
  if (!target) return false;
  captureActiveDocument();
  removeClosedDocumentShares([target]);
  teardownDocument(target);
  workspace.close(id);
  projectWorkspaceAfterDocumentClose();
  return true;
}

function closeDocumentSnapshot(snapshot) {
  captureActiveDocument();
  const targets = resolveDocumentCloseSnapshot(workspace, snapshot);
  if (!targets.length) return false;
  removeClosedDocumentShares(targets);
  targets.forEach(teardownDocument);
  workspace.closeMany(targets.map(target => target.id));
  projectWorkspaceAfterDocumentClose();
  return true;
}

// Writes the binding into the owning record, and keeps the working view in step when that record is
// the one on screen. Every runtime binding goes through here so the record stays authoritative.
function setDocumentExecution(document_, executionId, graphVersion, reconciliationClient = null,
  processInstanceId = null) {
  if (!document_) return;
  retireExecutionOutcomeClaim(document_.execution, executionId, reconciliationClient);
  document_.execution.commandFlight?.controller?.abort();
  document_.execution.reconciliationController?.abort();
  document_.execution.generation += 1;
  document_.execution.commandFlight = null;
  document_.execution.reconciliationController = null;
  document_.execution.executionId = executionId;
  document_.execution.processInstanceId = processInstanceId;
  document_.execution.graphVersion = graphVersion;
  document_.execution.reconciliationState = 'known';
  document_.execution.reconciliationClient = reconciliationClient;
  document_.execution.monitoringFlow ||= createMonitoringRuntimeState();
  bindMonitoringRuntimeState(document_.execution.monitoringFlow, {
    executionId, processInstanceId, graphVersion,
  }, { pending: executionId === PENDING_EXECUTION });
  if (executionId && executionId !== PENDING_EXECUTION) {
    document_.execution.finished.delete(executionId);
  }
  if (document_ === workspace.active) {
    activeExecutionId = executionId;
    activeGraphVersion = graphVersion;
    activeExecutionReconciliation = 'known';
  }
  refreshCommands();
}

function setExecutionReconciliationState(owner, state) {
  owner.execution.reconciliationState = state;
  if (owner.id === workspace.activeId) activeExecutionReconciliation = state;
  refreshCommands();
}

// The auxiliary panels are single and follow the active document, so switching document has to
// repaint them. Everything here reads the working view that `applyActiveDocument` just loaded.
function syncActiveDocumentChrome() {
  // Closing the last document is permitted, and it leaves the workspace genuinely empty rather than
  // holding the closed document as a stale record (workspace rule 5). The chrome therefore needs an
  // Empty state: shared chrome must stop describing the document that was just closed.
  const hasDocument = Boolean(workspace.active);
  window.document.getElementById('graph-title').textContent = hasDocument ? graphDisplayName : 'No graph loaded';
  window.document.title = hasDocument ? `${graphDisplayName} — Ravenroot UI` : 'Ravenroot UI';
  // Play is shared chrome and has to describe the document in front of the user: with one button and
  // several documents, a run still in flight in one of them must not lock the others out.
  //
  // Read through the working view rather than through `workspace.active`: `applyActiveDocument` has
  // just loaded the record into these variables, and reading the record again here would be a second
  // answer to a question that must only have one.
  syncLayoutChrome(hasDocument ? layoutMode : null);
  syncFontChrome(hasDocument ? fontSize : DEFAULT_FONT_SIZE);
  window.document.getElementById('b-zoom').textContent = hasDocument && cy
    ? `${Math.round(cy.zoom() * 100)}%` : '—';
  updateHistoryUi();
  if (hasDocument) {
    updateStats();
    scheduleMinimap();
  } else {
    clearGraphChrome();
  }
  closeInfo();
  syncDocumentSwitcher();
  syncExecutionReconciliationChrome(hasDocument);
  syncSourceSessionChrome(workspace.active);
  syncProgramReadinessChrome(workspace.active);
  refreshCommands();
}

function syncExecutionReconciliationChrome(hasDocument) {
  const summary = document.getElementById('activity-summary');
  if (hasDocument && activeExecutionReconciliation === 'unknown' && activeExecutionId) {
    summary.dataset.executionReconciliation = 'unknown';
    summary.textContent = `Status unknown · execution ${shortId(activeExecutionId)} · Test/Run will check first`;
    summary.setAttribute('aria-label',
      `Execution ${activeExecutionId} status is unknown. Test and Run will check it before submitting.`);
    return;
  }
  if (summary.dataset.executionReconciliation) {
    delete summary.dataset.executionReconciliation;
    summary.removeAttribute('aria-label');
    summary.textContent = 'Waiting for events';
  }
}

// The layout buttons and the elastic force sliders are shared chrome, so like the inspector and the
// statistics they have to describe the active document. `layoutMode` is per document, so switching
// has to repaint them: without this the toolbar goes on claiming the layout of the document the user
// has just left — highlighting Elastic, and offering its force sliders, over a document laid out by
// something else entirely.
function syncLayoutChrome() {
  const elasticCtrl = document.getElementById('elastic-ctrl');
  if (elasticCtrl) elasticCtrl.classList.toggle('visible', renderMode === 'monitoring');
  refreshCommands();
  requestAnimationFrame(syncCommandBarDensity);
}

// The font slider is shared chrome too, and `fontSize` is per document for the same reason
// `layoutMode` is (UI-12): without this, switching documents left the slider and its readout
// claiming whatever size the document just left happened to show, instead of the size the document
// now in front of the user actually has. Each document's own Cytoscape instance already carries its
// own label sizes independently — `onFontSize` only ever restyled the instance it was given — so
// this repaints the CONTROL, not the canvas.
function syncFontChrome(px) {
  const slider = document.getElementById('font-slider');
  if (slider) slider.value = px;
  const readout = document.getElementById('font-val');
  if (readout) readout.textContent = px;
}

// The counterpart of the chrome that `initCy` paints when a graph arrives. Without it, closing the
// last document leaves the previous graph's numbers and thumbnail on screen next to an empty canvas.
function clearGraphChrome() {
  window.document.getElementById('b-nodes').textContent = '0';
  window.document.getElementById('b-edges').textContent = '0';
  renderGraphStatistics(window.document.getElementById('graph-stats'), 0, 0, {}, {});
  const context = mmCanvas.getContext('2d');
  context?.clearRect(0, 0, mmCanvas.width, mmCanvas.height);
  document.getElementById('minimap').hidden = true;
  minimapLastSnapshot = null;
  window.document.getElementById('empty').classList.remove('off');
}

let EDGE_TYPE_COLORS = rendererPalette.edgeType;
let NODE_TYPE_COLORS = rendererPalette.nodeType;

// Cytoscape 3.34 gates native wheel and two-finger zoom on `userPanningEnabled()` as well as on
// `userZoomingEnabled()`. Ravenroot deliberately reserves persistent panning for Navigation, so the
// renderer otherwise discards those zoom gestures in Selection and Editing. This owner-local bridge
// lends native panning only while Cytoscape handles the physical wheel/pinch event, then restores the
// exact flag it found. Single-touch and stage drags never enter the bridge.
function installCanvasZoomBridge(container) {
  const containerWindow = container.ownerDocument.defaultView || window;
  let targetCy = null;
  let temporaryPanning = false;
  let restorePanning = false;
  let pinchActive = false;
  let blockedPinch = false;
  let restoreTimer = null;
  let disposed = false;

  const prevent = event => {
    if (event.cancelable) event.preventDefault();
  };
  const liveTarget = () => targetCy && !targetCy.destroyed() ? targetCy : null;
  const restore = () => {
    if (restoreTimer !== null) {
      containerWindow.clearTimeout(restoreTimer);
      restoreTimer = null;
    }
    const target = liveTarget();
    if (target && temporaryPanning) target.userPanningEnabled(restorePanning);
    temporaryPanning = false;
    pinchActive = false;
    blockedPinch = false;
  };
  // Chromium may run a microtask checkpoint between listeners on the same event. Restoring in a
  // microtask can therefore hide the temporary flag from Cytoscape's later listener. A zero-delay
  // task runs after the complete native dispatch while still restoring before the next gesture.
  const restoreAfterDispatch = () => {
    if (restoreTimer !== null) containerWindow.clearTimeout(restoreTimer);
    restoreTimer = containerWindow.setTimeout(() => {
      restoreTimer = null;
      restore();
    }, 0);
  };
  const allowNativeZoom = event => {
    const target = liveTarget();
    if (!target || !target.userZoomingEnabled()) {
      prevent(event);
      return null;
    }
    if (!target.userPanningEnabled()) {
      restorePanning = false;
      temporaryPanning = true;
      target.userPanningEnabled(true);
    }
    return target;
  };
  const wheel = event => {
    if (!liveTarget()) return;
    if (!allowNativeZoom(event)) return;
    if (temporaryPanning) restoreAfterDispatch();
  };
  const touchstart = event => {
    if ((event.touches?.length || 0) < 2) return;
    if (!allowNativeZoom(event)) {
      blockedPinch = true;
      event.stopImmediatePropagation();
      return;
    }
    pinchActive = true;
  };
  const touchmove = event => {
    if (!pinchActive && !blockedPinch) return;
    if (blockedPinch || !liveTarget()?.userZoomingEnabled()) {
      prevent(event);
      event.stopImmediatePropagation();
    }
  };
  const touchfinish = event => {
    if (!pinchActive && !blockedPinch) return;
    if ((event.touches?.length || 0) >= 2) return;
    if (blockedPinch) {
      prevent(event);
      event.stopImmediatePropagation();
    }
    restoreAfterDispatch();
  };

  container.addEventListener('wheel', wheel, { capture: true, passive: false });
  container.addEventListener('touchstart', touchstart, { capture: true, passive: false });
  containerWindow.addEventListener('touchmove', touchmove, { capture: true, passive: false });
  containerWindow.addEventListener('touchend', touchfinish, { capture: true, passive: false });
  containerWindow.addEventListener('touchcancel', touchfinish, { capture: true, passive: false });
  containerWindow.addEventListener('blur', restore);

  return {
    bind(instance) {
      targetCy = instance;
      canvasZoomBridges.set(instance, this);
    },
    restore,
    destroy() {
      if (disposed) return;
      disposed = true;
      restore();
      container.removeEventListener('wheel', wheel, true);
      container.removeEventListener('touchstart', touchstart, true);
      containerWindow.removeEventListener('touchmove', touchmove, true);
      containerWindow.removeEventListener('touchend', touchfinish, true);
      containerWindow.removeEventListener('touchcancel', touchfinish, true);
      containerWindow.removeEventListener('blur', restore);
      if (targetCy) canvasZoomBridges.delete(targetCy);
      targetCy = null;
    },
  };
}

function releaseCanvasZoomBridge(targetCy) {
  canvasZoomBridges.get(targetCy)?.destroy();
}

function initCy(elements, gd, options = {}) {
  invalidateStableSelection();
  cancelNodeMoveGesture();
  const documentChanged = graphData !== gd;
  graphData = gd;
  if (documentChanged) {
    setModifyMode(false);
    editHistory.reset();
    updateHistoryUi();
    // A genuinely new document's font is its own, starting from its own default — not whatever the
    // slider happened to show for the document this one is replacing (UI-12). `rebuildGraph`
    // is the only same-`gd` caller, so ordinary edits never reach this reset.
    fontSize = DEFAULT_FONT_SIZE;
  }

  // Register layout extensions (safe re-registration)
  if (typeof cytoscapeDagre !== 'undefined') {
    try { cytoscape.use(cytoscapeDagre); } catch(e) { /* already registered */ }
  }
  if (typeof cytoscapeElk !== 'undefined') {
    try { cytoscape.use(cytoscapeElk); } catch(e) { /* already registered */ }
  }
  if (typeof cytoscapeEuler !== 'undefined') {
    try { cytoscape.use(cytoscapeEuler); } catch(e) { /* already registered */ }
  }

  if (cy) {
    releaseCanvasZoomBridge(cy);
    destroySelectionOverlay(cy);
    destroyNodeActionOverlay(cy);
    cy.destroy();
  }

  const canvasContainer = documentContainer(workspace.active);
  const zoomBridge = installCanvasZoomBridge(canvasContainer);
  try {
    cy = cytoscape({
    // The active document's own element, not the shared host: a second document is a second canvas.
    container: canvasContainer,
    elements,
    style: createStylesheet(),
    layout: { name: 'preset' },
    minZoom: 0.05, maxZoom: 5,
    wheelSensitivity: 0.25,
    boxSelectionEnabled: true,
    // Keep the renderer additive so programmatic selection and an already selected multi-node drag
    // retain the established Cytoscape contract. Pointer clicks are normalised below: plain click
    // replaces, Ctrl/Cmd-click adds, and repeated click remains selected.
    selectionType: 'additive',
    });
  } catch (error) {
    zoomBridge.destroy();
    throw error;
  }
  zoomBridge.bind(cy);

  // The original markup uses inline handlers. Keep the current contract while
  // the UI is modularized incrementally; it now names the active document's instance.
  window.cy = cy;
  if (workspace.active) workspace.active.cy = cy;
  const rendererOwner = workspace.active;
  if (rendererOwner) registerCytoscapeRenderer(rendererOwner, cy);

  // Whether a layout is in flight, tracked per instance so that a pane which changes size mid-layout
  // can fit again once the layout's own terminal fit has landed. Both handlers read `event.cy` rather
  // than the module-level `cy`: these fire for BACKGROUND documents too, and the working view points
  // at whichever document is active — the same shape that made the zoom badge throw (2b).
  cy.on('layoutstart', event => { event.cy.scratch('_rrLayoutRunning', true); });
  cy.on('layoutstop', event => { event.cy.scratch('_rrLayoutRunning', false); });
  cy.scratch('_rrLayoutRunning', false);

  // ── Events ──────────────────────────────
  cy.on('tapstart', e => {
    stageGestureMoved = false;
    stageGestureStarted = e.target === cy;
    if (e.target === cy) {
      elementSelectionAtPointerStart.delete(e.cy);
    } else {
      elementSelectionAtPointerStart.set(e.cy, {
        owner: rendererOwner,
        rendererToken: rendererFor(rendererOwner)?.token || null,
        elementId: e.target.id(),
        selectedIds: e.cy.$(':selected').map(element => element.id()),
        additive: isAdditiveSelection(e.originalEvent),
      });
    }
    const snapshot = stageSelectionAtPointerStart.get(e.cy);
    stageSelectionAtPointerStart.delete(e.cy);
    e.cy.scratch('_rrStageGestureHadSelection', snapshot?.hasSelection ?? e.cy.$(':selected').nonempty());
  });
  cy.on('tapdrag', e => {
    stageGestureMoved = true;
    retireElementSelectionGesture(e.cy);
  });
  cy.on('dragpan', e => {
    stageGestureMoved = true;
    retireElementSelectionGesture(e.cy);
  });
  cy.on('tap', 'node', e => {
    hideNodeActionOverlay(e.cy);
    if (consumeSuppressedEdgeTap(e.cy)) {
      retireElementSelectionGesture(e.cy);
      return;
    }
    if (!selectionRendererIsActive(rendererOwner, e.cy)) {
      retireElementSelectionGesture(e.cy);
      return;
    }
    if (modifyEnabled && connectArmed) {
      handleConnectTap(rendererOwner, e.target, e.originalEvent);
      return;
    }
    if (!scheduleStableSelection(rendererOwner, e.cy, e.target, e.originalEvent)) return;
    setGraphCursor(e.target.id());
    showSelectionInfo();
    if (!nodeActionsHaveFinePointer()) {
      const target = e.target;
      const targetCy = e.cy;
      queueMicrotask(() => {
        if (!targetCy.destroyed() && target.selected()) {
          showNodeActionOverlay(rendererOwner, target, { pointer: false, allowSelected: true });
        }
      });
    }
  });
  cy.on('cxttap', 'node', e => {
    retireElementSelectionGesture(e.cy);
    if (!selectionRendererIsActive(rendererOwner, e.cy)) return;
    e.originalEvent?.preventDefault?.();
    const rendered = e.renderedPosition || e.target.renderedPosition();
    openNodeActionMenu(rendererOwner, e.cy, e.target, {
      x: rendered.x + 8,
      y: rendered.y + 8,
    });
  });
  cy.on('tap', 'edge', e => {
    if (consumeSuppressedEdgeTap(e.cy)) {
      retireElementSelectionGesture(e.cy);
      return;
    }
    if (!scheduleStableSelection(rendererOwner, e.cy, e.target, e.originalEvent)) return;
    showSelectionInfo();
  });

  // ── Pointer edge gestures (UI-02) ────────────────────────
  // In Editing, dragging a node draws a new edge; dragging an edge near one of its ends moves that end.
  // Both open the same gesture the keyboard opens, so the rules and the wording cannot diverge.
  cy.on('tapstart', 'node', e => {
    if (!prepareEdgeGestureOwner(rendererOwner, e.cy)
        || !modifyEnabled || navigationEnabled || connectArmed || edgeGestureSession
        || !nodeCanSourceEdge(e.target.selected())) return;
    // A press records only a private candidate. Visual authoring starts after the pointer crosses
    // the accessible intent threshold, so a plain click remains selection and never flashes an
    // edge preview or leaves "Connecting…" behind in the live region or inspector.
    if (!startEdgeGesture(rendererOwner, beginConnectGesture(graphData, e.target.id()), {
      announce: false, deferVisuals: true,
    })) return;
    beginPointerGesture(e);
  });
  cy.on('tapstart', 'edge', e => {
    if (!prepareEdgeGestureOwner(rendererOwner, e.cy)
        || !modifyEnabled || connectArmed || edgeGestureSession) return;
    // An edge curve can cross a node. When the pointer is visibly inside that node, node authoring
    // wins over the lower-level canvas hit target so starting a loop never depends on z-order.
    const overlappingNode = nodeAtRenderedPosition(e.renderedPosition, e.cy);
    if (overlappingNode) {
      if (!nodeCanSourceEdge(e.cy.getElementById(overlappingNode).selected())) return;
      if (startEdgeGesture(rendererOwner, beginConnectGesture(graphData, overlappingNode), {
        announce: false, deferVisuals: true,
      })) {
        beginPointerGesture(e);
      }
      return;
    }
    const edge = e.target;
    const endpoint = nearestEndpoint(e.position, edge.source().position(), edge.target().position());
    if (!startEdgeGesture(rendererOwner, beginReconnectGesture(graphData, edge.id(), endpoint), {
      announce: false, deferVisuals: true,
    })) return;
    beginPointerGesture(e);
  });
  // Preview updates use the native bubbling PointerEvent below, rather than Cytoscape's synthetic
  // drag events: those stop at element boundaries and can omit the final source re-entry needed by
  // an intentional self-loop. One event stream also means one SVG update per pointer position.
  cy.on('tapend', e => {
    const session = edgeGestureSession;
    if (!session?.pointer || session.cy !== e.cy) return;
    if (e.originalEvent && Number.isFinite(Number(e.originalEvent.clientX))) {
      updatePointerEdgePreviewFromNativeEvent(e.originalEvent);
    }
    const result = finishPointerEdgeGesture(session.pointer);
    // A press-and-release is selection, not edge authoring. Do not suppress Cytoscape's following
    // tap: that tap is what performs the established plain-click selection.
    if (result.outcome === 'select') {
      cancelEdgeGesture({ clearMessage: true });
      return;
    }
    endPointerGesture();
    if (result.outcome === 'commit') commitEdgeGestureAt(result.candidateId);
    else cancelEdgeGesture({ clearMessage: true });
  });
  cy.on('tap', e => {
    if (e.target !== cy) return;
    const action = stageTapAction({
      editing: modifyEnabled,
      navigating: navigationEnabled,
      gestureStarted: stageGestureStarted,
      gestureMoved: stageGestureMoved,
      hasSelection: e.cy.scratch('_rrStageGestureHadSelection'),
    });
    e.cy.removeScratch('_rrStageGestureHadSelection');
    if (action === STAGE_TAP_ACTION.IGNORE) return;
    if (action === STAGE_TAP_ACTION.CREATE) {
      createNodeFromStage(e.position);
      return;
    }
    clearStageInteraction(e.cy);
  });
  cy.on('mouseover', 'node', e => {
    hoverHL(e.target);
    showNodeActionOverlay(rendererOwner, e.target);
  });
  cy.on('mouseout', 'node', e => {
    deferNodeActionLeave(e.cy);
    clearHL();
  });
  // The zoom badge is shared chrome, so like the inspector and the statistics it describes the
  // ACTIVE document. This handler used to close over the module-level `cy` instead of reading the
  // instance that emitted, which was invisible while only one document could ever be zoomed. With
  // panes a BACKGROUND document zooms too — it is refitted when its pane changes size — and the
  // handler would either write that document's zoom into the badge, or throw outright, because the
  // working view's `cy` is briefly null while a document is being opened.
  cy.on('zoom', event => {
    if (event.cy !== cy) return;
    document.getElementById('b-zoom').textContent = Math.round(event.cy.zoom() * 100) + '%';
  });
  // The selection badge is shared chrome too, so it describes the ACTIVE document by the same rule
  // as the zoom badge just above. These two handlers used to close over the module-level `cy` instead
  // of checking the instance that emitted — invisible while only one document could ever select or
  // unselect a node. A real pointer cannot reach the defect: a click on a background pane focuses and
  // activates it (`documentPane`'s `pointerdown` handler) BEFORE Cytoscape resolves the gesture into
  // `select`/`unselect`, so by the time either event fires the module-level `cy` already matches the
  // instance that emitted it. But nothing stops a background instance's OWN selection state from
  // changing programmatically, and the badge must not describe that document just because it happened
  // to change.
  cy.on('select', 'node', e => {
    hideNodeActionOverlay(e.cy);
    applyNodeGrabPolicy(e.cy);
    if (e.cy !== cy) return;
    document.getElementById('b-sel').textContent = selectionBadgeLabel(e.cy);
    scheduleSelectionInspectorRefresh(e.cy);
    refreshCommands();
  });
  cy.on('select', 'edge', e => {
    if (e.cy !== cy) return;
    document.getElementById('b-sel').textContent = selectionBadgeLabel(e.cy);
    scheduleSelectionInspectorRefresh(e.cy);
    refreshCommands();
  });
  cy.on('unselect', e => {
    applyNodeGrabPolicy(e.cy);
    if (e.cy !== cy) return;
    document.getElementById('b-sel').textContent = selectionBadgeLabel(e.cy);
    scheduleSelectionInspectorRefresh(e.cy);
    refreshCommands();
  });
  // A drag used to write straight into the document from the renderer. It now opens a gesture on
  // `grab` and closes it on the first `free` with one move-nodes command covering every node that
  // travelled, so a multi-node drag is a single undo step (UI-01).
  cy.on('grab', 'node', e => {
    if (dragSnapshot) return;
    if (workspace.active !== rendererOwner || e.cy !== rendererOwner.cy || cy !== e.cy
        || !nodeIsGrabbable(canvasInteractionState({ editing: modifyEnabled, navigating: navigationEnabled }),
          e.target.selected())) return;
    // A layout run moves nodes on screen without writing to the document, so before a gesture opens
    // the document is realigned with what the user is actually looking at. Otherwise undoing a drag
    // would restore pre-layout coordinates and the nodes would jump somewhere the user never saw.
    syncGraphPositions();
    const grabbed = e.target.selected() ? e.cy.nodes(':selected').union(e.target) : e.target;
    dragSnapshot = {
      owner: rendererOwner,
      cy: e.cy,
      graph: graphData,
      history: editHistory,
      nodes: grabbed.map(node => ({ id: node.id(), position: { ...node.position() } })),
    };
  });
  cy.on('free', 'node', e => {
    const snapshot = dragSnapshot;
    dragSnapshot = null;
    if (!snapshot?.nodes.length || snapshot.owner !== rendererOwner || snapshot.cy !== e.cy
        || snapshot.owner.cy !== snapshot.cy || snapshot.graph !== snapshot.owner.graph
        || snapshot.history !== snapshot.owner.history) return;
    const positions = snapshot.nodes
      .map(entry => snapshot.cy.getElementById(entry.id))
      .filter(element => element.nonempty())
      .map(element => ({ id: element.id(), ox: element.position('x'), oy: element.position('y') }));
    if (!moveNodesTo(snapshot.graph, positions, snapshot.history)) return;
    updateHistoryUi();
  });

  // Manual movement and animated layouts both change edge geometry after the mode was selected.
  // Keep custom curves derived from the current positions, coalescing Cytoscape's many position
  // notifications into one recalculation per animation frame.
  cy.on('position', 'node', event => {
    const owner = rendererOwner;
    if (!owner || event.cy !== owner.cy || !['n8n4', 'cyto'].includes(owner.visualStyle)) return;
    if (owner.layoutMode === 'hierarchical') {
      if (owner.cytoEdgeGeometryRaf != null) return;
      owner.cytoEdgeGeometryRaf = requestAnimationFrame(() => {
        owner.cytoEdgeGeometryRaf = null;
        if (owner.cy !== event.cy || owner.layoutMode !== 'hierarchical') return;
        applyHierarchicalEdgeRoutes(owner.cy);
      });
      return;
    }
    if (owner.visualStyle === 'cyto') {
      owner.cytoEdgeRouteDirtyNodes.add(event.target.id());
      if (owner.cytoEdgeGeometryRaf != null) return;
      owner.cytoEdgeGeometryRaf = requestAnimationFrame(() => {
        owner.cytoEdgeGeometryRaf = null;
        if (owner.cy !== event.cy || owner.visualStyle !== 'cyto') return;
        const dirtyNodeIds = [...owner.cytoEdgeRouteDirtyNodes];
        owner.cytoEdgeRouteDirtyNodes.clear();
        applyCytoEdgeRouteUpdate(owner, owner.cy, dirtyNodeIds);
      });
      return;
    }
    if (owner.edgeGeometryRaf != null) return;
    owner.edgeGeometryRaf = requestAnimationFrame(() => {
      owner.edgeGeometryRaf = null;
      if (owner.cy !== event.cy) return;
      if (owner.visualStyle === 'n8n4') applyN8n4EdgeCurves(owner.cy);
      else if (owner.visualStyle === 'cyto') applyCytoEdgeCurves(owner.cy);
    });
  });

  // This internal paint path is synchronous and position-neutral: loading, activation and rebuilding
  // never move coordinates. Explicit render-mode commands use setRenderMode and do run their owned
  // layout before the renderer-specific routing pass.
  setVisualStyle(options.visualStyle || visualStyle, { target: cy, owner: rendererOwner });
  updateStats();
  buildLegend();
  document.getElementById('empty').classList.add('off');
  setEditorAvailability();
  applyCanvasInteraction();
  scheduleMinimap();
  const minimapOwner = workspace.active;
  // Renderer events are semantic inputs, not a paint loop. Cytoscape may emit several `render`
  // notifications for one viewport mutation; listening to model/viewport changes directly keeps
  // overview work bounded to one rAF per browser frame.
  cy.on('pan zoom position add remove layoutstop', event => {
    if (event.cy === minimapOwner?.cy && minimapOwner === workspace.active) scheduleMinimap(minimapOwner);
  });
  // Loading is position-neutral, so label sizing is synchronous too. Capture both values explicitly:
  // a background instance must never resolve through the active document's module-level `cy`,
  // and a rebuild must keep this document's own font preference.
  const instance = cy;
  const instanceFontSize = fontSize;
  onFontSize(instanceFontSize, instance);
}

// ═══════════════════════════════════════════════════════════════
// LAYOUT
// ═══════════════════════════════════════════════════════════════

// ═══════════════════════════════════════════════════════════════
// N8N VISUAL MODE
// ═══════════════════════════════════════════════════════════════

const N8N_ICONS_CHAR = {
  start:    '▶', end:      '■', error:    '⚠',
  terminal: '⊙',
  consumer: '⧒', handler:  '↩',
  agent:    '🧠', flow:     '⚙',
  actor:    '◎', system:   '▤',
};
let N8N_BG = rendererPalette.nodeSurfaceByType;
let N8N_BORDER = rendererPalette.nodeType;

// Digital circuit-brain SVG — front view, two hemispheres, PCB traces
function agentBrainSvg() { return `<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 400 400' width='80' height='80'>
 <g fill='none' stroke='${rendererPalette.nodeType.agent}' stroke-width='8' stroke-linecap='round' stroke-linejoin='round'>

    <!-- Left outer profile (restored to its original smooth curves) -->
    <path d='M 200 45
             C 170 45, 155 60, 140 75
             C 115 65, 90 85, 95 115
             C 70 120, 75 160, 90 170
             C 70 185, 75 225, 95 230
             C 80 250, 90 285, 115 290
             C 110 320, 145 340, 170 330
             C 185 345, 195 355, 200 355' />

    <!-- Right outer profile (mirrored, with smooth curves) -->
    <path d='M 200 45
             C 230 45, 245 60, 260 75
             C 285 65, 310 85, 305 115
             C 330 120, 325 160, 310 170
             C 330 185, 325 225, 305 230
             C 320 250, 310 285, 285 290
             C 290 320, 255 340, 230 330
             C 215 345, 205 355, 200 355' />

    <!-- Central separator line -->
    <line x1='200' y1='65' x2='200' y2='335' />

    <!-- LEFT-HEMISPHERE CIRCUITS (segmented geometric lines) -->
    <!-- Upper circuit -->
    <path d='M 185 290 L 185 175 L 145 135 L 145 110' />
    <!-- Middle circuit -->
    <path d='M 170 260 L 170 215 L 125 185 L 125 165' />
    <!-- Lower circuit -->
    <path d='M 155 295 L 125 295 L 125 255 L 140 255' />

    <!-- RIGHT-HEMISPHERE CIRCUITS (segmented geometric lines) -->
    <!-- Upper circuit -->
    <path d='M 215 290 L 215 175 L 255 135 L 255 110' />
    <!-- Middle circuit -->
    <path d='M 230 260 L 230 215 L 275 185 L 275 165' />
    <!-- Lower circuit -->
    <path d='M 245 295 L 275 295 L 275 255 L 260 255' />

    <!-- TERMINAL CIRCLES (all with uniform radius R=9) -->
    <!-- Left -->
    <circle cx='145' cy='110' r='9' />
    <circle cx='125' cy='165' r='9' />
    <circle cx='140' cy='255' r='9' />

    <!-- Right -->
    <circle cx='255' cy='110' r='9' />
    <circle cx='275' cy='165' r='9' />
    <circle cx='260' cy='255' r='9' />

  </g>
</svg>`; }

function makeN8nSVG(char, nodeType) {
  if (nodeType === 'agent') {
    return 'data:image/svg+xml,' + encodeURIComponent(agentBrainSvg());
  }
  // Transparent-bg SVG with centered icon glyph — overlaid on background-color
  const s = `<svg xmlns='http://www.w3.org/2000/svg' width='80' height='80'>`
    + `<text x='40' y='40' text-anchor='middle' dominant-baseline='central' `
    + `font-size='32' fill='${rendererPalette.nodeText}' `
    + `font-family='system-ui,-apple-system,sans-serif'>${char}</text></svg>`;
  return 'data:image/svg+xml,' + encodeURIComponent(s);
}

let n8nActive = false;
function rendererFor(owner = workspace.active) {
  const renderer = owner?.renderer;
  return renderer && rendererSessions.isCurrent(renderer.token) ? renderer : null;
}

function elasticRendererFor(owner = workspace.active) {
  const renderer = rendererFor(owner);
  return renderer?.kind === 'elastic' ? renderer : null;
}

function createElasticHost(owner) {
  const host = document.createElement('div');
  host.className = 'doc-elastic-host';
  host.dataset.documentId = owner.id;
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.classList.add('d3-elastic');
  svg.dataset.documentId = owner.id;
  const tooltip = document.createElement('div');
  tooltip.className = 'd3-tooltip';
  host.append(svg, tooltip);
  owner.pane.append(host);
  return { host, svg, tooltip };
}

function disposeElasticRenderer(renderer, { restoreCanvas = true } = {}) {
  if (!renderer) return;
  renderer.elasticMount?.destroy();
  renderer.elasticMount = null;
  renderer.simulation = null;
  renderer.tooltip.style.display = 'none';
  renderer.svg.classList.remove('active');
  renderer.host.classList.remove('active', 'suspended');
  renderer.host.remove();
  if (!restoreCanvas) return;
  renderer.owner.container?.classList.remove('doc-canvas--elastic');
  const element = renderer.owner.container;
  if (!renderer.owner.pane?.classList.contains('doc-pane--shown')
      || !element?.clientWidth || !element.clientHeight) return;
  renderer.cy?.resize();
  renderer.cy?.forceRender();
  paneRenderedSize.set(renderer.owner.id, { width: element.clientWidth, height: element.clientHeight });
}

function registerElasticRenderer(owner, target, layoutToken) {
  const elements = createElasticHost(owner);
  const renderer = {
    kind: 'elastic', owner, cy: target, layoutToken, token: null, ...elements,
    restLengths: {}, baseWidths: {}, simulation: null,
    nodeLabelSelection: null, edgeLabelSelection: null, nodeSelection: null, edgeSelection: null,
    nodes: null, links: null, zoom: null, zoomGroup: null,
  };
  const registration = rendererSessions.register({ documentId: owner.id, renderer, kind: 'elastic' });
  if (registration.retired?.kind === 'elastic') disposeElasticRenderer(registration.retired.renderer);
  renderer.token = registration.token;
  owner.renderer = renderer;
  return renderer;
}

function registerCytoscapeRenderer(owner, target = owner?.cy) {
  if (!owner || !target) return null;
  const renderer = { kind: 'cytoscape', owner, cy: target, host: owner.container, token: null };
  const registration = rendererSessions.register({ documentId: owner.id, renderer, kind: 'cytoscape' });
  if (registration.retired?.kind === 'elastic') disposeElasticRenderer(registration.retired.renderer);
  renderer.token = registration.token;
  owner.renderer = renderer;
  installSelectionOverlay(owner, target, target.container());
  installNodeActionOverlay(owner, target, target.container());
  return renderer;
}

function destroyDocumentRenderer(owner, reason = 'destroyed') {
  if (edgeGestureSession?.owner === owner) cancelEdgeGesture({ clearMessage: true });
  const renderer = rendererFor(owner);
  if (!renderer) return;
  if (renderer.kind === 'cytoscape') destroySelectionOverlay(renderer.cy);
  if (renderer.kind === 'cytoscape') destroyNodeActionOverlay(renderer.cy);
  const transition = rendererSessions.destroy(renderer.token, reason);
  if (transition.action?.kind === 'elastic') disposeElasticRenderer(transition.action.renderer);
  owner.renderer = null;
}

// Elastic teardown is addressed by document, never by whichever document happens to own the shared
// chrome. Activation does not come through here; layout replacement, document replacement and
// close do, and the generation registry makes their late callbacks inert.
function stopElasticRendering(owner = workspace.active, reason = 'stopped') {
  const renderer = elasticRendererFor(owner);
  if (!renderer) return;
  destroyDocumentRenderer(owner, reason);
  registerCytoscapeRenderer(owner);
}

function updateElasticRendererTheme(renderer) {
  if (!renderer || renderer.kind !== 'elastic') return;
  renderer.nodes?.forEach(node => {
    const model = renderer.cy.getElementById(node.id);
    node.color = NODE_TYPE_COLORS[model.data('nodeType')]
      || model.data('fillColor') || rendererPalette.selection;
  });
  renderer.links?.forEach(link => {
    const edge = renderer.cy.getElementById(link.id);
    link.color = EDGE_TYPE_COLORS[edge.data('edgeType')]
      || edge.data('color') || rendererPalette.edgeType.default;
  });
  renderer.nodeSelection?.attr('fill', node => node.color)
    .attr('stroke', node => runtimeColor(node.runtimeState));
  renderer.nodeLabelSelection?.attr('fill', rendererPalette.nodeText);
  renderer.edgeLabelSelection?.attr('fill', rendererPalette.edgeLabel);

  const defs = d3.select(renderer.svg).select('defs');
  defs.selectAll('marker').remove();
  [...new Set((renderer.links || []).map(link => link.color))].forEach(color => {
    const id = `arr-${renderer.token.generation}-${color.replace('#','')}`;
    defs.append('marker')
      .attr('id', id).attr('viewBox','0 -5 10 10').attr('refX', 10).attr('refY', 0)
      .attr('markerWidth', 6).attr('markerHeight', 6).attr('markerUnits', 'userSpaceOnUse').attr('orient','auto')
      .append('path').attr('d','M0,-5L10,0L0,5').attr('fill', color);
  });
  renderer.edgeSelection?.attr('stroke', link => link.color)
    .attr('marker-end', link => `url(#arr-${renderer.token.generation}-${link.color.replace('#','')})`);
}

// Theme is paint-only. It deliberately avoids every layout entry point and never reads or writes
// GraphML/document state, positions, viewport or edit history. Cytoscape stylesheet replacement and
// D3 attribute updates preserve the existing renderer and its geometry in place.
function applyApplicationTheme(theme) {
  applicationTheme = normalizeTheme(theme) || 'dark';
  rendererPalette = getRendererPalette(applicationTheme);
  EDGE_TYPE_COLORS = rendererPalette.edgeType;
  NODE_TYPE_COLORS = rendererPalette.nodeType;
  N8N_BG = rendererPalette.nodeSurfaceByType;
  N8N_BORDER = rendererPalette.nodeType;

  workspace.documents.forEach(owner => {
    const target = owner.cy;
    if (!target || target.destroyed()) return;
    target.batch(() => {
      target.style().fromJson(createStylesheet()).update();
      if (isN8nFamilyLayout(owner.visualStyle)) applyN8nNodeStyle(target, owner);
      else target.nodes().forEach(applyRuntimeVisual);
    });
    updateElasticRendererTheme(rendererFor(owner));
    scheduleSelectionOverlay(target);
    scheduleMinimap(owner);
  });
  buildLegend();
  refreshCommands();
}

function suspendDocumentRenderer(owner) {
  const renderer = rendererFor(owner);
  if (!renderer) return;
  const transition = rendererSessions.suspend(renderer.token);
  if (!transition.changed) return;
  if (renderer.kind !== 'elastic') return;
  renderer.simulation?.stop();
  renderer.host.classList.add('suspended');
}

function resumeDocumentRenderer(owner) {
  const renderer = rendererFor(owner);
  if (renderer) {
    const transition = rendererSessions.resume(renderer.token);
    if (transition.changed && renderer.kind === 'elastic') {
      renderer.host.classList.remove('suspended');
      if (renderer.host.clientWidth && renderer.host.clientHeight) {
        renderer.svg.setAttribute('width', String(renderer.host.clientWidth));
        renderer.svg.setAttribute('height', String(renderer.host.clientHeight));
      }
      rehydrateD3RuntimeEdges(owner);
      renderer.simulation?.alpha(0.18).restart();
    }
  }
  resumePendingElasticLayout(owner);
}

function startD3Elastic(owner = workspace.active, target = cy, token = owner?.layoutSessionToken) {
  if (!target || !owner?.pane?.classList.contains('doc-pane--shown') || !layoutRequestIsCurrent(token)) return;
  destroyDocumentRenderer(owner, 'restarted');
  const renderer = registerElasticRenderer(owner, target, token);

  // ---- collect data from Cytoscape elements ----
  const nodeRange = metricExtent(target.nodes(), n => Number(n.data('instances')));
  const fontPx = owner.fontSize || DEFAULT_FONT_SIZE;

  const idIndex = {};
  const d3nodes = target.nodes().map((n, i) => {
    const inst = Number(n.data('instances'));
    const size = scaleMetric(Number(n.data('instances')), nodeRange, 12, 44, 18);
    const color = NODE_TYPE_COLORS[n.data('nodeType')] || n.data('fillColor') || rendererPalette.selection;
    const runtimeState = n.data('runtimeState') || 'idle';
    const obj = {
      id: n.id(), label: n.data('name') || n.id(),
      r: size / 2, color,
      x: n.position().x, y: n.position().y,
      instances: Number.isFinite(inst) ? inst : null,
      runtimeState,
      runtimeObserved: Boolean(n.data('runtimeObserved')),
      lastEventType: n.data('lastEventType') || null,
      lastOccurredAt: n.data('lastOccurredAt') || null,
      processingDuration: n.data('processingDuration') ?? null,
      fallback: Boolean(n.data('fallback')),
      stroke: runtimeColor(runtimeState),
      strokeWidth: runtimeState === 'idle' ? 1.5 : 4,
    };
    idIndex[n.id()] = obj;
    return obj;
  });
  renderer.nodes = d3nodes;

  const d3links = target.edges().map(e => {
    const configured = e.data('trafficWeight');
    const traffic = configured == null || configured === '' ? Number.NaN : Number(configured);
    const color = EDGE_TYPE_COLORS[e.data('edgeType')] || e.data('color') || rendererPalette.edgeType.default;
    // GraphML trafficWeight is authored configuration: it may shape the stable force rest length,
    // but never the observed stroke/pulse. Runtime EDGE_TRAVERSED state is painted separately below.
    const restLen = Number.isFinite(traffic)
      ? Math.max(50, 180 - Math.min(120, Math.sqrt(Math.max(0, traffic)) * 18))
      : 130;
    return {
      id: e.id(), source: e.source().id(), target: e.target().id(),
      baseWidth: 1.8, restLen, color,
      label: e.data('label') || '',
      configuredWeight: Number.isFinite(traffic) ? traffic : null,
      flow: edgeFlowSnapshot(owner.execution.monitoringFlow, e.id()),
    };
  });
  renderer.links = d3links;

  // ---- SVG setup: this document owns one overlay inside its own pane ----
  // Elastic replaces only its owner's canvas and never hides `#cy`, the shared parent of every
  // pane. The host is born in its final pane and remains there for its whole generation, just like
  // the Cytoscape canvas beside it; no renderer is reparented during activation.
  const pane  = owner.pane;
  const hostEl = renderer.host;
  if (!pane || !hostEl) return;
  owner.container?.classList.add('doc-canvas--elastic');
  hostEl.classList.add('active');
  // Measured AFTER the swap, so the numbers are the slot the overlay actually occupies.
  const W     = hostEl.clientWidth  || 800;
  const H     = hostEl.clientHeight || 600;
  const svgEl = renderer.svg;
  svgEl.classList.add('active');

  const initAttr = parseInt(document.getElementById('attr-slider')?.value || '30', 10) / 100;
  const initRep  = parseInt(document.getElementById('rep-slider')?.value  || '320', 10);
  const designViewport = { k: target.zoom(), x: target.pan().x, y: target.pan().y };
  const elasticMount = mountD3ElasticRenderer({
    svg: svgEl,
    tooltip: renderer.tooltip,
    nodes: d3nodes,
    links: d3links,
    width: W,
    height: H,
    palette: rendererPalette,
    markerKey: renderer.token.generation,
    fontSize: fontPx,
    attraction: initAttr,
    repulsion: initRep,
    initialTransform: designViewport,
    isLive: () => layoutRequestIsCurrent(token) && rendererSessions.isLive(renderer.token),
    onViewportChange: () => {
      if (owner === workspace.active) scheduleMinimap(owner);
    },
  });
  renderer.elasticMount = elasticMount;
  Object.assign(renderer, elasticMount);
}

function isN8nFamilyLayout(name = visualStyle) {
  return name === 'n8n' || name === 'n8n2' || name === 'n8n3' || name === 'n8n4'
    || name === 'cyto';
}

function metricExtent(collection, getter) {
  let min = Infinity;
  let max = -Infinity;
  collection.forEach(item => {
    const value = getter(item);
    if (!Number.isFinite(value)) return;
    min = Math.min(min, value);
    max = Math.max(max, value);
  });
  return {
    min: min === Infinity ? null : min,
    max: max === -Infinity ? null : max,
  };
}

function scaleMetric(value, range, minOut, maxOut, fallback) {
  if (!Number.isFinite(value) || range.min === null || range.max === null) return fallback;
  if (range.max <= range.min) return (minOut + maxOut) / 2;
  const norm = (Math.sqrt(Math.max(0, value)) - Math.sqrt(Math.max(0, range.min))) /
               (Math.sqrt(Math.max(0, range.max)) - Math.sqrt(Math.max(0, range.min)) || 1);
  return minOut + Math.max(0, Math.min(1, norm)) * (maxOut - minOut);
}

function applyElasticVisualStyle() {
  if (!cy) return;

  const nodeRange = metricExtent(cy.nodes(), node => Number(node.data('instances')));
  const edgeRange = metricExtent(cy.edges(), edge => Number(edge.data('trafficWeight')));
  const fontPx = parseInt(document.getElementById('font-slider')?.value || '20', 10);

  cy.nodes().forEach(node => {
    const size = scaleMetric(Number(node.data('instances')), nodeRange, 14, 46, 18);
    const nodeColor = NODE_TYPE_COLORS[node.data('nodeType')] || node.data('fillColor') || rendererPalette.selection;
    node.style({
      shape: 'ellipse',
      width: size,
      height: size,
      'background-image': 'none',
      'background-color': nodeColor,
      // This style writes INLINE styles, which beat the stylesheet, so the switched-off ring's
      // WIDTH and COLOUR are restated here — the border is normally the canvas colour (a separator,
      // not a signal), so a neutral ring reads as clearly here as elsewhere. `border-style` is
      // deliberately NOT written: the dash belongs to `createStylesheet`'s `node[?bypassed]` rule, and
      // writing it inline is what made it go stale on the autosave path (see `refreshBypassBorder`).
      'border-width': node.data('bypassed') ? 2.5 : 1.5,
      'border-color': node.data('bypassed') ? rendererPalette.nodeType.system : rendererPalette.canvas,
      'border-opacity': 0.9,
      label: bypassedNodeName(node.data('name'), node.data('bypassed')),
      color: rendererPalette.nodeText,
      'font-size': Math.max(10, Math.min(fontPx, 14)) + 'px',
      'font-weight': '500',
      'text-valign': 'bottom',
      'text-halign': 'center',
      'text-margin-y': Math.round(size / 2 + 10),
      'text-wrap': 'none',
      'text-max-width': '240px',
      padding: '0px',
    });
  });

  cy.edges().forEach(edge => {
    const width = scaleMetric(Number(edge.data('trafficWeight')), edgeRange, 1.2, 8, 1.6);
    const color = EDGE_TYPE_COLORS[edge.data('edgeType')] || edge.data('color') || rendererPalette.edgeType.default;
    edge.style({
      width,
      'line-color': color,
      'target-arrow-color': color,
      'target-arrow-shape': 'triangle',
      'arrow-scale': Math.max(0.7, Math.min(1.6, width / 2.2)),
      'curve-style': 'bezier',
      'source-endpoint': 'outside-to-node',
      'target-endpoint': 'outside-to-node',
      label: 'data(label)',
      'font-size': Math.max(8, Math.min(11, Math.round(fontPx * 0.55))) + 'px',
      color,
      'text-background-opacity': 0.92,
      'text-background-color': rendererPalette.edgeLabelSurface,
      'text-background-padding': '2px',
      'text-background-shape': 'roundrectangle',
      'edge-text-rotation': 'none',
      'text-rotation': 'none',
      'text-halign': 'center',
      'text-valign': 'top',
      'text-margin-y': -(Math.round(width / 2) + 9),
      'text-margin-x': 0,
    });
  });
}

function applyN8nNodeStyle(target = cy, owner = workspace.active) {
  if (!target) return;
  const fontPx = `${owner?.fontSize || DEFAULT_FONT_SIZE}px`;
  target.nodes().forEach(n => {
    const t  = n.data('nodeType');
    const ic = N8N_ICONS_CHAR[t] || '◎';
    const bg = N8N_BG[t]         || rendererPalette.nodeSurface;
    const bd = N8N_BORDER[t]     || rendererPalette.nodeBorder;
    n.style({
      shape:                  'roundrectangle',
      width:                   80,
      height:                  80,
      'background-color':      bg,
      'border-width':          2.5,
      // This family includes the DEFAULT `cyto` style, so this is the border most authors
      // actually see. The neutral ring is restated here because the per-type `bd` written inline
      // would otherwise beat the stylesheet; the per-type icon tile is untouched, so the node stays
      // identifiable. `border-style` is deliberately absent — see `refreshBypassBorder`.
      'border-color':          n.data('bypassed') ? rendererPalette.nodeType.system : bd,
      'border-opacity':        1,
      'background-image':      makeN8nSVG(ic, t),
      'background-width':     '100%',
      'background-height':    '100%',
      'background-fit':       'none',
      'background-clip':      'none',
      label:                   runtimeNodeLabel(n),
      'font-size':             fontPx,
      'font-weight':          '500',
      color:                  rendererPalette.nodeText,
      'text-valign':          'bottom',
      'text-halign':          'center',
      'text-margin-y':         10,
      'text-background-opacity': 0,
      padding:                '0px',
      'text-wrap':            'none',
    });
    applyRuntimeVisual(n);
  });
}

// ── Snap all nodes to a regular grid ─────────────────────────────────────
function snapToGrid(step) {
  cy.nodes().forEach(n => {
    const p = n.position();
    n.position({
      x: Math.round(p.x / step) * step,
      y: Math.round(p.y / step) * step,
    });
  });
}

// ── S-curve bezier N8N-style ──────────────────────────────────────────────
// CP1 = (sx+hoff, sy+srcOff) → exits rightward from source at its Y level
// CP2 = (tx-hoff, ty+tgtOff) → arrives rightward into target at its Y level
// Both are converted to Cytoscape (weight, distance) coords relative to S→T.
function applyN8nEdgeCurves(target = cy) {
  if (!target) return;

  // ── Collect and sort edges per node so slots are stable top→bottom ───────
  const outMap = {}, inMap = {};
  target.edges().forEach(e => {
    if (e.source().id() === e.target().id()) return;
    const s = e.source().id(), t = e.target().id();
    (outMap[s] = outMap[s] || []).push(e);
    (inMap[t]  = inMap[t]  || []).push(e);
  });
  Object.values(outMap).forEach(arr =>
    arr.sort((a, b) => a.target().position().y - b.target().position().y));
  Object.values(inMap).forEach(arr =>
    arr.sort((a, b) => a.source().position().y - b.source().position().y));

  const SPREAD   = 22;   // max vertical spread half-range (px) at each node
  const HOFF_MIN = 90;   // minimum horizontal handle length

  function slotOff(arr, edge, maxSpread) {
    const n = arr.length;
    if (n <= 1) return 0;
    const i   = arr.indexOf(edge);
    const half = Math.min(maxSpread, (n - 1) * 11);
    return -half + (2 * half / (n - 1)) * i;
  }

  target.edges().forEach(edge => {
    const src = edge.source(), tgt = edge.target();
    if (src.id() === tgt.id()) {
      edge.style({ 'curve-style': 'loop', width: 2.5 });
      return;
    }
    const sp = src.position(), tp = tgt.position();
    const dx = tp.x - sp.x,  dy = tp.y - sp.y;
    const L2 = dx * dx + dy * dy;
    if (L2 < 1) return;
    const L = Math.sqrt(L2);

    const srcOff = slotOff(outMap[src.id()] || [edge], edge, SPREAD);
    const tgtOff = slotOff(inMap[tgt.id()]  || [edge], edge, SPREAD);

    // Wider handles for back-edges and tall vertical spans
    const hoff = Math.max(HOFF_MIN, Math.abs(dx) * 0.42 + Math.abs(dy) * 0.12);

    // Absolute bezier control-point positions
    const cp1x = sp.x + hoff,  cp1y = sp.y + srcOff;
    const cp2x = tp.x - hoff,  cp2y = tp.y + tgtOff;

    // Convert to Cytoscape (weight, distance) relative to the S→T baseline
    // w = dot(P−S, T−S) / |T−S|²
    // d = cross(P−S, T−S) / |T−S| (positive = left of S→T direction)
    function toWD(cpx, cpy) {
      const ax = cpx - sp.x, ay = cpy - sp.y;
      return {
        w: (ax * dx + ay * dy) / L2,
        d: (ax * (-dy) + ay * dx) / L,
      };
    }
    const r1 = toWD(cp1x, cp1y);
    const r2 = toWD(cp2x, cp2y);

    edge.style({
      'curve-style':             'unbundled-bezier',
      'control-point-weights':   [r1.w, r2.w],
      'control-point-distances': [r1.d, r2.d],
      'source-endpoint':         'outside-to-line',
      'target-endpoint':         'outside-to-line',
      width:                      2.5,
    });
  });
}

function rendererRouteNode(node, owner = null) {
  const position = node.position();
  const label = String(node.data('name') || node.data('label') || '');
  const labelFont = Math.max(10, Number(owner?.fontSize || DEFAULT_FONT_SIZE));
  return {
    id: node.id(), x: position.x, y: position.y, width: node.width(), height: node.height(),
    labelBounds: label ? {
      x: position.x, y: position.y + node.height() / 2 + labelFont,
      width: Math.min(260, label.length * labelFont * 0.56), height: labelFont * 1.35,
    } : null,
  };
}

function rendererRouteInputs(target, prospective = null, owner = null) {
  const nodes = target.nodes().map(node => rendererRouteNode(node, owner));
  if (prospective?.node) nodes.push(prospective.node);
  const edges = target.edges().map(edge => ({
    id: edge.id(), source: edge.source().id(), target: edge.target().id(),
    label: edge.data('label') || edge.data('name') || '',
  }));
  if (prospective?.edge) {
    const index = edges.findIndex(edge => edge.id === prospective.edge.id);
    if (index >= 0) edges.splice(index, 1, prospective.edge);
    else edges.push(prospective.edge);
  }
  return { nodes, edges };
}

function rendererRouteSet(target, mode, prospective = null, owner = null) {
  return resolveRendererEdgeRoutes({ mode, ...rendererRouteInputs(target, prospective, owner) });
}

function applyCustomLoop(edge, { cytoMode = false } = {}) {
  const source = edge.source();
  // Cytoscape's 40px default control-point step is smaller than Ravenroot's card nodes, so most of
  // a self-loop is painted underneath the card. Cyto loops use a model-space clearance derived
  // from the actual node size: the loop remains unmistakably outside both compact and wide cards,
  // and scales naturally with zoom because the value is not a rendered-pixel measurement.
  const loopClearance = cytoMode
    ? Math.max(88, Math.round(Math.max(source.outerWidth(), source.outerHeight()) * 0.85))
    : 40;
  edge.style({
    'curve-style': cytoMode ? 'bezier' : 'unbundled-bezier',
    'loop-direction': cytoMode ? '0deg' : '-45deg',
    'loop-sweep': cytoMode ? '-80deg' : '70deg',
    'control-point-step-size': loopClearance,
    'source-endpoint': 'outside-to-node',
    'target-endpoint': 'outside-to-node',
    'source-distance-from-node': cytoMode ? 8 : 0,
    'target-distance-from-node': cytoMode ? 8 : 0,
    'text-rotation': cytoMode ? 'none' : 'autorotate',
    'text-margin-y': cytoMode ? -10 : 0,
    'line-cap': 'round',
    width: cytoMode ? 2 : 2.5,
  });
}

function applyN8n2EdgeCurves(target = cy) {
  if (!target) return;
  const routes = rendererRouteSet(target, 'n8n2');
  target.edges().forEach(edge => {
    if (edge.source().id() === edge.target().id()) return applyCustomLoop(edge);
    const route = routes.get(edge.id());
    if (!route) return;
    edge.style({
      'curve-style': route.family,
      'taxi-direction': route.direction,
      'taxi-turn': `${route.turn}px`,
      'taxi-turn-min-distance': 24,
      'taxi-radius': route.radius,
      'radius-type': 'arc-radius',
      'source-endpoint': route.sourceEndpoint,
      'target-endpoint': route.targetEndpoint,
      width: 2.5,
    });
  });
}

function applyN8n3EdgeCurves(target = cy) {
  if (!target) return;
  const routes = rendererRouteSet(target, 'n8n3');
  target.edges().forEach(edge => {
    if (edge.source().id() === edge.target().id()) return applyCustomLoop(edge);
    const route = routes.get(edge.id());
    if (route) applyViewerRoundedSegmentRoute(edge, route);
  });
}

// Explicit Hierarchical arrangement keeps ELK's left-to-right rank grammar after the plugin has
// positioned nodes. The route resolver fixes every source on the east side and every target on the
// west side, then allocates sibling lanes and ports by stable edge id; this dedicated pass must not
// fall through to the default Design curves, which would erase that grammar.
function applyHierarchicalEdgeRoutes(target = cy) {
  if (!target) return;
  const routes = rendererRouteSet(target, 'hierarchical');
  target.edges().forEach(edge => {
    if (edge.source().id() === edge.target().id()) return applyCustomLoop(edge, { cytoMode: true });
    const route = routes.get(edge.id());
    if (route) applyViewerRoundedSegmentRoute(edge, route);
  });
}

function scheduleHierarchicalEdgeRoutes(owner, target, token, complete = null) {
  if (!layoutRequestIsCurrent(token)) return;
  owner.layoutDeferredRaf = requestAnimationFrame(() => {
    owner.layoutDeferredRaf = null;
    if (layoutRequestIsCurrent(token)) applyHierarchicalEdgeRoutes(target);
    complete?.();
  });
}

function scheduleN8n3EdgeCurves(owner, target, token, complete = null) {
  if (!layoutRequestIsCurrent(token)) return;
  owner.layoutDeferredRaf = requestAnimationFrame(() => {
    owner.layoutDeferredRaf = null;
    if (layoutRequestIsCurrent(token)) applyN8n3EdgeCurves(target);
    complete?.();
  });
}

// N8N4 uses the shared sibling allocation for every edge, then chooses a rounded N8N3-style detour
// or the current n8n canvas's long S curve from each route's model-space port gap.
function applyN8n4EdgeCurves(target = cy) {
  if (!target) return;
  const routes = rendererRouteSet(target, 'n8n4');
  target.edges().forEach(edge => {
    if (edge.source().id() === edge.target().id()) return applyCustomLoop(edge);
    const route = routes.get(edge.id());
    if (!route) return;
    if (route.family === 'unbundled-bezier') applyViewerUnbundledRoute(edge, route);
    else applyViewerRoundedSegmentRoute(edge, route);
  });
}

// Cyto remains a deliberately light reference mode. ELK places nodes, while one explicit
// unbundled descriptor now gives Cytoscape and the authoring preview identical ports and controls.
function applyCytoEdgeCurves(target = cy, owner = workspace.documents.find(document_ => document_.cy === target)) {
  if (!target) return;
  const routes = rendererRouteSet(target, 'cyto', null, owner);
  if (owner) owner.cytoEdgeRouteCache = routes;
  target.edges().forEach(edge => {
    if (edge.source().id() === edge.target().id()) return applyCustomLoop(edge, { cytoMode: true });
    const route = routes.get(edge.id());
    if (route) applyViewerUnbundledRoute(edge, route, { lineCap: 'round' });
  });
}

function applyCytoEdgeRouteUpdate(owner, target, dirtyNodeIds) {
  if (!owner || owner.cy !== target || owner.visualStyle !== 'cyto') return;
  const update = resolveCytoEdgeRouteUpdate({
    ...rendererRouteInputs(target, null, owner),
    previousRoutes: owner.cytoEdgeRouteCache,
    dirtyNodeIds,
  });
  owner.cytoEdgeRouteCache = update.routes;
  target.batch(() => update.routedEdgeIds.forEach(id => {
    const edge = target.getElementById(id);
    const route = update.routes.get(id);
    if (edge.nonempty() && route) applyViewerUnbundledRoute(edge, route, { lineCap: 'round' });
  }));
}

function scheduleN8n4EdgeCurves(owner, target, token, complete = null) {
  if (!layoutRequestIsCurrent(token)) return;
  owner.layoutDeferredRaf = requestAnimationFrame(() => {
    owner.layoutDeferredRaf = null;
    if (layoutRequestIsCurrent(token)) applyN8n4EdgeCurves(target);
    complete?.();
  });
}

function scheduleCytoEdgeCurves(owner, target, token, complete = null) {
  if (!layoutRequestIsCurrent(token)) return;
  owner.layoutDeferredRaf = requestAnimationFrame(() => {
    owner.layoutDeferredRaf = null;
    if (layoutRequestIsCurrent(token)) applyCytoEdgeCurves(target, owner);
    complete?.();
  });
}

function scheduleN8n2EdgeCurves(owner, target, token, complete = null) {
  if (!layoutRequestIsCurrent(token)) return;
  owner.layoutDeferredRaf = requestAnimationFrame(() => {
    owner.layoutDeferredRaf = null;
    if (layoutRequestIsCurrent(token)) applyN8n2EdgeCurves(target);
    complete?.();
  });
}

function restoreDefaultStyle(target = cy, owner = workspace.active) {
  if (!target) return;
  if (owner) owner.n8nActive = false;
  if (owner === workspace.active) n8nActive = false;
  target.nodes().removeStyle();
  target.edges().removeStyle();
}

function applyVisualStyle(name, target = cy, owner = workspace.active) {
  if (!target || !owner) return;
  name = normalizeVisualStyle(name);
  if (owner === workspace.active) visualStyle = name;
  owner.visualStyle = name;
  restoreDefaultStyle(target, owner);
  if (isN8nFamilyLayout(name)) {
    owner.n8nActive = true;
    if (owner === workspace.active) n8nActive = true;
    applyN8nNodeStyle(target, owner);
    if (name === 'n8n4') applyN8n4EdgeCurves(target);
    else if (name === 'cyto') applyCytoEdgeCurves(target, owner);
    else if (name === 'n8n2') applyN8n2EdgeCurves(target);
    else if (name === 'n8n3') applyN8n3EdgeCurves(target);
    else target.edges().style({
      'curve-style': name === 'n8n' ? 'taxi' : 'round-taxi',
      'taxi-direction': 'auto',
      'taxi-turn': '50%',
      'taxi-turn-min-distance': 20,
      'taxi-radius': name === 'n8n' ? 28 : 36,
      'source-endpoint': name === 'n8n' ? 'outside-to-line' : 'outside-to-node',
      'target-endpoint': name === 'n8n' ? 'outside-to-line' : 'outside-to-node',
      width: 2.5,
    });
  } else {
    target.nodes().forEach(applyRuntimeVisual);
  }
  // Restoring the base stylesheet also restores its 10px edge-label default. Reapply the
  // document-owned label size in the same batch so a style switch and a newly inserted edge use
  // the same typography before the next paint, and rebuild/undo cannot visibly change it later.
  onFontSize(owner.fontSize || DEFAULT_FONT_SIZE, target, false);
  refreshCommands();
  scheduleMinimap(owner);
}

function setVisualStyle(name, options = {}) {
  const owner = options.owner || workspace.active;
  const target = options.target || cy;
  if (!owner || !target) return;
  // Leaving Elastic restores the already-owned Cytoscape view. It does not run a layout or import
  // positions from the force simulation.
  if (owner.layoutMode === 'elastic') {
    stopElasticRendering(owner, 'visual-style-changed');
    owner.layoutMode = 'preset';
    if (owner === workspace.active) layoutMode = 'preset';
    syncPaneLayout();
    syncLayoutChrome('preset');
  }
  target.batch(() => applyVisualStyle(name, target, owner));
}

function applyN8nBaseEdgeStyle(target = cy, mode = 'n8n') {
  if (!target) return;
  target.edges().style({
    'curve-style': mode === 'n8n' ? 'taxi' : 'round-taxi',
    'taxi-direction': 'auto',
    'taxi-turn': '50%',
    'taxi-turn-min-distance': 20,
    'taxi-radius': mode === 'n8n' ? 28 : 36,
    'source-endpoint': mode === 'n8n' ? 'outside-to-line' : 'outside-to-node',
    'target-endpoint': mode === 'n8n' ? 'outside-to-line' : 'outside-to-node',
    width: 2.5,
  });
}

// The only dispatcher for editor-created edges. Layout setup and edge authoring both call the
// helpers that own each renderer's geometry, rather than maintaining a second approximation for
// newly inserted elements. This is synchronous so the add batch has one visible final style.
function applyActiveEdgeVisualContract(target = cy, mode = visualStyle) {
  if (!target) return;
  const owner = workspace.documents.find(document_ => document_.cy === target) || workspace.active;
  const routeMode = owner?.layoutMode === 'hierarchical' ? 'hierarchical' : mode;
  const sample = target.edges().last();
  const route = resolveRendererEdgeRoute({
    mode: routeMode,
    source: sample?.nonempty() ? rendererRouteNode(sample.source()) : null,
    target: sample?.nonempty() ? rendererRouteNode(sample.target()) : null,
  });
  if (route.family === 'taxi' && mode === 'n8n') applyN8nBaseEdgeStyle(target, mode);
  else if (routeMode === 'hierarchical') applyHierarchicalEdgeRoutes(target);
  else if (route.family === 'round-taxi') applyN8n2EdgeCurves(target);
  else if (mode === 'n8n3') applyN8n3EdgeCurves(target);
  // N8N4 is deliberately hybrid per edge. Never choose a renderer-wide fallback from the last
  // inserted edge: doing so would restyle existing forward edges when a backward edge is added.
  else if (mode === 'n8n4') applyN8n4EdgeCurves(target);
  else if (mode === 'cyto') applyCytoEdgeCurves(target, owner);
  else applyEdgeCurveStyle(mode === 'elk' ? 'elk' : 'bezier', target);
}

// Standard and Ortho still use the base Cytoscape curve contract. The renderer dispatcher above
// also reaches this path for a newly authored edge, so it must be present independently of the
// named N8N/Cyto helpers; otherwise the edge is inserted but the terminal keyboard announcement is
// skipped by the resulting ReferenceError.
function applyEdgeCurveStyle(mode, target = cy) {
  if (!target) return;
  if (mode === 'elk') {
    target.edges().style({
      'curve-style': 'taxi',
      'taxi-direction': 'auto',
      'taxi-turn': '50%',
      'taxi-turn-min-distance': 20,
      'taxi-radius': 28,
      'source-endpoint': 'outside-to-line',
      'target-endpoint': 'outside-to-line',
    });
    return;
  }
  target.edges().style({ 'curve-style': 'bezier', 'taxi-radius': 0 });
}

// These modes are complete rendering choices, not position-neutral paint overlays. They share the
// deterministic ELK positioning pass and apply their renderer-specific edge routing after layout.
const ELK_LAYOUT_MODES = new Set(['elk', 'hierarchical', 'n8n', 'n8n2', 'n8n3', 'n8n4', 'cyto']);
// Dagre and CoSE animate asynchronously too. They therefore own the same interaction transaction
// as ELK-backed modes even though only ELK modes need the per-document serialisation slot. Keeping
// the two concerns separate prevents an ELK -> native queue hand-off from briefly publishing idle
// while the replacement layout is already registered and about to start.
const FINITE_ASYNC_LAYOUT_MODES = new Set(['dagre', 'cose', ...ELK_LAYOUT_MODES]);
const layoutJobs = new Map();

const DESIGN_ARRANGEMENTS = Object.freeze({
  hierarchical: Object.freeze({ layout: 'hierarchical' }),
  flow: Object.freeze({ layout: 'dagre' }),
  organic: Object.freeze({ layout: 'cose' }),
  keep: Object.freeze({ preservePositions: true }),
});

function renderModeLabel(mode) {
  const semanticMode = normalizeRenderMode(mode);
  return commandRegistry.get(`layout.${semanticMode}`)?.label || 'Graph';
}

function syncOwnedLayoutBusy(owner) {
  if (!owner) return;
  const busy = [...layoutJobs.values()].some(job => job.owner === owner
    && FINITE_ASYNC_LAYOUT_MODES.has(job.token.mode));
  if (owner.layoutBusy === busy) return;
  owner.layoutBusy = busy;
  if (workspace.active === owner) layoutBusy = busy;
  owner.pane?.classList.toggle('doc-pane--layout-busy', busy);
  if (busy) owner.pane?.setAttribute('aria-busy', 'true');
  else owner.pane?.removeAttribute('aria-busy');
  syncPaneHeaders();
  if (workspace.active === owner) {
    announceGraph(busy
      ? `${renderModeLabel(owner.layoutMode)} layout in progress. Graph editing is available when it finishes.`
      : `${renderModeLabel(owner.layoutMode)} layout complete.`);
    refreshCommands();
  }
}

function completeOwnedLayout(job) {
  const { owner, token } = job;
  if (job.fitAfterLayout && layoutRequestIsCurrent(token)) {
    if (!owner.container?.clientWidth || !owner.container.clientHeight) owner.layoutPendingRefit = true;
    else {
      job.target.resize();
      job.target.fit(undefined, 65);
      clampAutomaticFitZoom(owner);
    }
  }
  if (job.recordPositions && layoutRequestIsCurrent(token)
      && owner.graph?.format !== 'graphify' && owner.cy === job.target) {
    const positions = job.target.nodes().map(node => ({
      id: node.id(), ox: node.position('x'), oy: node.position('y'),
    }));
    if (moveNodesTo(owner.graph, positions, owner.history, job.commandLabel)) {
      if (workspace.active === owner) updateHistoryUi();
      else {
        syncPaneHeaders();
        syncDocumentSwitcher();
      }
    }
  }
  layoutJobs.delete(token.generation);
  const released = token.kind === 'elk' ? layoutSessions.complete(token).start : null;
  syncOwnedLayoutBusy(owner);
  if (released) runOwnedLayout(released);
}

function finishOwnedLayout(token) {
  const job = layoutJobs.get(token.generation);
  if (!job) return;
  const { owner, target } = job;
  const publish = layoutRequestIsCurrent(token);
  if (publish) {
    if (target.scratch('_rrRefitAfterLayout') && !job.fitAfterLayout) {
      if (!owner.container?.clientWidth || !owner.container.clientHeight) owner.layoutPendingRefit = true;
      else {
        target.scratch('_rrRefitAfterLayout', false);
        target.resize();
        target.fit(undefined, PANE_REFIT_PADDING);
        clampAutomaticFitZoom(owner);
      }
    }
    const deferredRouting = token.mode === 'hierarchical' ? scheduleHierarchicalEdgeRoutes
      : owner.visualStyle === 'n8n2' ? scheduleN8n2EdgeCurves
      : owner.visualStyle === 'n8n3' ? scheduleN8n3EdgeCurves
        : owner.visualStyle === 'n8n4' ? scheduleN8n4EdgeCurves
          : owner.visualStyle === 'cyto' ? scheduleCytoEdgeCurves : null;
    // Positioning and renderer-specific routing are one operation. A pointer edit must not land
    // between `layoutstop` and the final route callback and then be restyled by stale work.
    if (deferredRouting) {
      deferredRouting(owner, target, token, () => completeOwnedLayout(job));
      return;
    }
  }
  completeOwnedLayout(job);
}

function elkOptions(mode, { fit = true, animate = true } = {}) {
  const hierarchical = mode === 'hierarchical';
  return {
    name: 'elk', animate, animationDuration: animate ? 600 : 0,
    fit, padding: hierarchical || mode === 'elk' ? 70 : 90,
    elk: {
      algorithm: 'layered',
      'elk.direction': 'RIGHT',
      'elk.edgeRouting': 'ORTHOGONAL',
      'elk.layered.nodePlacement.strategy': 'BRANDES_KOEPF',
      'elk.layered.crossingMinimization.strategy': 'LAYER_SWEEP',
      'elk.layered.unnecessaryBendpoints': 'true',
      'elk.layered.compaction.postCompaction.strategy': 'EDGE_LENGTH',
      'elk.spacing.nodeNode': hierarchical ? '75' : mode === 'elk' ? '70' : '80',
      'elk.layered.spacing.nodeNodeBetweenLayers': hierarchical ? '130' : mode === 'elk' ? '120' : '160',
      'elk.layered.spacing.edgeNodeBetweenLayers': '30',
      'elk.padding': hierarchical || mode === 'elk'
        ? '[top=50,left=50,bottom=50,right=50]'
        : '[top=90,left=100,bottom=100,right=100]',
    },
  };
}

function prepareOwnedLayout(job) {
  const { owner, target, token } = job;
  if (token.mode !== 'elastic') applyVisualStyle(owner.visualStyle || 'standard', target, owner);
}

// Ends a layout job that will never emit `layoutstop`, and releases the ELK serialisation slot if
// this token was holding it.
//
// `layoutSessions.request` claims the slot the moment an ELK-kind mode is requested, but only a
// NATIVE layout can give it back, because `finishOwnedLayout` is reached from a `layoutstop`
// listener. Several paths through `runOwnedLayout` finish the work without ever constructing a
// native layout — a preserved-position request most of all, which just assigns coordinates and
// fits. Those paths used to `layoutJobs.delete` and return, leaving `elkRunning` claimed by a
// layout that had already finished. Every later request for that document then queued behind it
// forever, `start:false`, and silently never ran. Releasing here is what makes the claim
// symmetric: whoever ends the job, ends the slot.
function settleOwnedLayout(token) {
  const job = layoutJobs.get(token.generation);
  if (job) completeOwnedLayout(job);
}

function runOwnedLayout(token) {
  const job = layoutJobs.get(token.generation);
  if (!job || !layoutRequestIsCurrent(token)) {
    // A token can still own the ELK slot here: `layoutSessions.complete` hands the slot to the
    // request it releases, and this stricter ownership check can reject it a moment later — the
    // document may have been rebound to another `cy` or another mode in between. Settling rather
    // than returning keeps the slot from being stranded by the very handshake meant to pass it on.
    if (job) settleOwnedLayout(token);
    return;
  }
  const { owner, target, preservePositions, keepPositions, fitAfterLayout } = job;

  if (keepPositions) {
    // A running native layout can move nodes between the command click and cancellation, while an
    // ELK run must settle internally before its queued replacement can start. Restore the exact
    // click-time snapshot, but retain the current mode and edge style: Keep is a viewport action,
    // not a new layout or renderer publication.
    // CoSE's stop() leaves one already-queued frame which performs its final refresh. Publish Keep
    // in the following frame: the retired refresh runs first, then this restoration, all before the
    // browser paints. This also keeps the fit singular rather than visibly snapping twice.
    owner.layoutDeferredRaf = requestAnimationFrame(() => {
      owner.layoutDeferredRaf = null;
      if (!layoutRequestIsCurrent(token)) return settleOwnedLayout(token);
      target.batch(() => job.retainedPositions.forEach(position => {
        const node = target.getElementById(position.id);
        if (node.nonempty()) node.position({ x: position.x, y: position.y });
      }));
      target.fit(60);
      clampAutomaticFitZoom(owner);
      settleOwnedLayout(token);
    });
    return;
  }

  prepareOwnedLayout(job);

  if (token.mode === 'elastic') {
    owner.n8nActive = true;
    if (owner === workspace.active) n8nActive = true;
    if (!owner.pane?.classList.contains('doc-pane--shown')) {
      owner.pendingElasticLayoutToken = token;
      return;
    }
    owner.pendingElasticLayoutToken = null;
    startD3Elastic(owner, target, token);
    return;
  }
  if (preservePositions) {
    if (owner.visualStyle === 'n8n2') scheduleN8n2EdgeCurves(owner, target, token);
    if (owner.visualStyle === 'n8n3') scheduleN8n3EdgeCurves(owner, target, token);
    if (owner.visualStyle === 'n8n4') scheduleN8n4EdgeCurves(owner, target, token);
    if (owner.visualStyle === 'cyto') scheduleCytoEdgeCurves(owner, target, token);
    if (token.mode === 'preset') target.nodes().forEach(
      node => node.position({ x: node.data('px'), y: node.data('py') }));
    target.fit(60);
    clampAutomaticFitZoom(owner);
    settleOwnedLayout(token);
    return;
  }

  const animate = globalThis.matchMedia?.('(prefers-reduced-motion: reduce)').matches !== true;
  let nativeLayout;
  if (token.mode === 'dagre') nativeLayout = target.layout({
    name: 'dagre', rankDir: 'LR', rankSep: 110, nodeSep: 55, edgeSep: 20,
    animate, animationDuration: animate ? 450 : 0, animationEasing: 'ease-in-out',
    fit: !fitAfterLayout, padding: 60,
  });
  else if (token.mode === 'cose') nativeLayout = target.layout({
    name: 'cose', animate, animationDuration: animate ? 800 : 0, fit: !fitAfterLayout, padding: 60,
    nodeOverlap: 24, idealEdgeLength: 140, nodeRepulsion: () => 10000, gravity: 1.2,
  });
  else if (ELK_LAYOUT_MODES.has(token.mode)) nativeLayout = target.layout(elkOptions(token.mode, {
    fit: !fitAfterLayout,
    animate,
  }));
  else if (token.mode === 'preset') {
    target.nodes().forEach(node => node.position({ x: node.data('px'), y: node.data('py') }));
    target.fit(60);
    settleOwnedLayout(token);
    return;
  }
  if (!nativeLayout) {
    settleOwnedLayout(token);
    return;
  }
  job.nativeLayout = nativeLayout;
  nativeLayout.one('layoutstop', () => finishOwnedLayout(token));
  if (token.kind === 'elk') {
    // `cytoscape-elk` defers its own start and its `stop()` is a no-op. Give same-turn close,
    // replace, and newer requests a real pre-start cancellation point instead of destroying an
    // instance underneath plugin work that has already escaped onto its task queue.
    queueMicrotask(() => {
      if (!layoutRequestIsCurrent(token)) {
        settleOwnedLayout(token);
        return;
      }
      nativeLayout.run();
    });
  } else nativeLayout.run();
}

function resumePendingElasticLayout(owner) {
  const token = owner?.pendingElasticLayoutToken;
  if (!token) return;
  // Resume is deferred by one microtask so a same-turn close, replace, or newer layout gets the
  // same cancellation point as a pre-start ELK request. The immutable token is checked again in
  // the callback; focus alone cannot revive work whose owner or Cytoscape binding was retired.
  queueMicrotask(() => {
    if (owner.pendingElasticLayoutToken !== token) return;
    const job = layoutJobs.get(token.generation);
    const stillCurrent = token.mode === 'elastic'
      && owner.layoutMode === 'elastic'
      && owner.cy === token.cy
      && job?.owner === owner
      && job.target === owner.cy
      && job.token === token
      && layoutRequestIsCurrent(token);
    if (!stillCurrent) {
      owner.pendingElasticLayoutToken = null;
      return;
    }
    // A rapid show-hide transition may run this callback after the pane is hidden again. The token
    // remains current, so leave it pending for the next genuine show instead of dropping the work.
    if (!owner.pane?.classList.contains('doc-pane--shown')) return;
    owner.pendingElasticLayoutToken = null;
    runOwnedLayout(token);
  });
}

function setLayout(name, options = {}) {
  const owner = workspace.active;
  const target = cy;
  // Native `stop()` may synchronously publish a final frame, so Keep must capture the canvas before
  // the session request invokes cancellation callbacks for the layout it replaces.
  const retainedPositions = options.keepPositions && target ? target.nodes().map(node => ({
    id: node.id(), x: node.position('x'), y: node.position('y'),
  })) : [];
  // A render-mode request owns the canvas until it settles. Retire any edit gesture against the
  // pre-layout coordinates before the first style/position callback is allowed to run.
  if (dragSnapshot?.owner === owner) cancelNodeMoveGesture();
  if (edgeGestureSession?.owner === owner) cancelEdgeGesture({ clearMessage: true });
  // Retire only stale dynamic route publications from the previous mode. The layout completion RAF
  // remains independently owned so rapid requests can still cancel/settle their session correctly.
  clearDynamicEdgeGeometry(owner);
  layoutMode = name;
  if (owner) {
    owner.layoutMode = name;
    owner.pendingElasticLayoutToken = null;
    // Snapshot the active document's live visual inputs with the explicit layout request.
    owner.fontSize = fontSize;
    owner.n8nActive = n8nActive;
  }
  syncLayoutChrome(name);
  updateModifyAvailability();
  if (!owner || !target) return;

  // Elastic is stopped while its captured owner is still the active working view. Standard layouts
  // are never restarted by activation; a visible background owner may simply finish its own token.
  stopElasticRendering(owner, 'layout-changed');
  syncPaneLayout();

  let job;
  const kind = ELK_LAYOUT_MODES.has(name) ? 'elk' : 'native';
  const request = layoutSessions.request({
    documentId: owner.id,
    cy: target,
    mode: name,
    kind,
    nativeCancel: kind === 'native' ? () => {
      job?.nativeLayout?.stop();
      // Dagre/CoSE animate node positions separately from the layout controller. Stopping only the
      // controller can leave those animations publishing retired frames after Keep restores its
      // click-time snapshot.
      job?.target?.nodes().stop(true, false);
    } : null,
  });
  cancelRetiredLayouts(request.cancelled);
  job = {
    owner,
    target,
    token: request.token,
    preservePositions: Boolean(options.preservePositions),
    keepPositions: Boolean(options.keepPositions),
    retainedPositions,
    recordPositions: Boolean(options.recordPositions),
    fitAfterLayout: Boolean(options.fitAfterLayout),
    commandLabel: options.commandLabel || null,
    nativeLayout: null,
  };
  owner.layoutSessionToken = request.token;
  layoutJobs.set(request.token.generation, job);
  syncOwnedLayoutBusy(owner);
  if (request.start) runOwnedLayout(request.token);
}

function setRenderMode(name, { skipDraftGuard = false } = {}) {
  const owner = workspace.active;
  const target = cy;
  if (!owner || !target) return;
  const semanticMode = normalizeRenderMode(name);
  if (!skipDraftGuard && semanticMode !== renderMode) {
    return runAfterInspectorDraft(() => setRenderMode(name, { skipDraftGuard: true }));
  }
  renderMode = semanticMode;
  owner.renderMode = semanticMode;
  // Product choices project onto existing internal engines. Design owns a deterministic full
  // relayout plus the established Cyto routing; Monitoring owns the continuous D3 lifecycle.
  const style = 'cyto';
  const layout = semanticMode === 'design' ? 'cyto' : 'elastic';
  target.batch(() => applyVisualStyle(style, target, owner));
  setLayout(layout);
}

function arrangeDesign(name, { skipDraftGuard = false } = {}) {
  const arrangement = DESIGN_ARRANGEMENTS[name];
  const owner = workspace.active;
  if (!arrangement || renderMode !== 'design' || !owner || !cy) return false;
  if (!skipDraftGuard) {
    return runAfterInspectorDraft(() => arrangeDesign(name, { skipDraftGuard: true }));
  }
  setLayout(name === 'keep' ? owner.layoutMode || layoutMode || 'preset' : arrangement.layout, {
    preservePositions: arrangement.preservePositions,
    keepPositions: name === 'keep',
    recordPositions: !arrangement.preservePositions,
    fitAfterLayout: !arrangement.preservePositions,
    commandLabel: commandRegistry.get(`layout.arrange.${name}`)?.label || 'Arrange graph',
  });
  return true;
}

// Activation normally resumes the renderer already owned by the document. A legacy split record,
// however, may name Elastic while still owning only its old Cytoscape renderer (or the inverse).
// Normalizing the fields without reconciling that handle makes the radio truthful about state but
// false about what is actually painted. This runs only after the target pane is visible, and only
// when the semantic mode and renderer kind disagree; ordinary document switches do no work.
function reconcileActiveRenderModeRenderer() {
  const owner = workspace.active;
  if (!owner?.cy) return;
  const kind = rendererFor(owner)?.kind;
  if (renderMode === 'monitoring' && kind !== 'elastic') setLayout('elastic');
  else if (renderMode === 'design' && kind === 'elastic') setLayout('cyto');
}

function fitGraph() {
  if (edgeGestureSession || (cy && !cy.userZoomingEnabled())) return;
  const renderer = elasticRendererFor(workspace.active);
  if (layoutMode === 'elastic' && renderer?.nodes?.length && renderer.zoom) {
    const svgEl = renderer.svg;
    if (!svgEl) return;
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    renderer.nodes.forEach(d => {
      if (!Number.isFinite(d.x) || !Number.isFinite(d.y)) return;
      minX = Math.min(minX, d.x - d.r); minY = Math.min(minY, d.y - d.r - 20);
      maxX = Math.max(maxX, d.x + d.r); maxY = Math.max(maxY, d.y + d.r + 24);
    });
    if (minX === Infinity) return;
    const W   = svgEl.clientWidth  || 800;
    const H   = svgEl.clientHeight || 600;
    const pad = 60;
    const bw  = maxX - minX + pad * 2;
    const bh  = maxY - minY + pad * 2;
    const sc  = Math.min(W / bw, H / bh, 4);
    const tx  = (W - sc * (minX + maxX)) / 2;
    const ty  = (H - sc * (minY + maxY)) / 2;
    const selection = d3.select(svgEl);
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      selection.call(renderer.zoom.transform, d3.zoomIdentity.translate(tx, ty).scale(sc));
    } else {
      selection.transition().duration(400)
        .call(renderer.zoom.transform, d3.zoomIdentity.translate(tx, ty).scale(sc));
    }
    return;
  }
  if (cy) createCytoscapeReadOnlyRendererAdapter(cy).fit(60);
}
function zoomBy(f) {
  if (edgeGestureSession || (cy && !cy.userZoomingEnabled())) return;
  const renderer = elasticRendererFor(workspace.active);
  if (layoutMode === 'elastic' && renderer?.zoom) {
    d3.select(renderer.svg).call(renderer.zoom.scaleBy, f, [renderer.host.clientWidth / 2, renderer.host.clientHeight / 2]);
    return;
  }
  if (cy) createCytoscapeReadOnlyRendererAdapter(cy).zoomBy(f);
}

// `target` names the instance whose labels are being sized, and `writeChrome` says whether this call
// also owns the shared readout. Both used to be implicit in the module-level `cy`, which means "the
// ACTIVE document" — and not every caller is talking about the active document. Initial paint and
// background renderer work therefore pass the owned instance rather than assuming (UI-11).
//
// The slider in the shared chrome (index.html, `data-input-action="font-size"`) omits both arguments
// and so keeps meaning "size the active document and update its readout", which is what a global
// control means. A renderer-owned caller passes its instance explicitly.
//
function onFontSize(val, target = cy, writeChrome = target === cy) {
  const px = parseInt(val);
  if (writeChrome) {
    document.getElementById('font-val').textContent = px;
    const renderer = elasticRendererFor(workspace.active);
    renderer?.nodeLabelSelection?.attr('font-size', Math.max(10, Math.min(px, 18)) + 'px');
    renderer?.edgeLabelSelection?.attr('font-size', Math.max(8, Math.min(13, Math.round(px * 0.55))) + 'px');
    // `writeChrome` true means the active document's own size is taking effect, so the working view
    // is updated to match. Without this assignment, the value would live only in the DOM element,
    // leaving `captureActiveDocument` with no per-document value to save (UI-12).
    fontSize = px;
    // Style commands may run in the same task, before any capture boundary. Keep the authoritative
    // active record coherent with its working-view mirror immediately; background calls pass
    // `writeChrome = false` and therefore cannot restyle or rewrite an inactive document.
    if (workspace.active) workspace.active.fontSize = px;
  }
  if (!target) return;
  // Update label font-size on all nodes without touching geometry or layout
  target.nodes().style({ 'font-size': px + 'px' });
  // Also scale edge labels proportionally (70% of node label size)
  target.edges().style({ 'font-size': Math.max(8, Math.round(px * 0.75)) + 'px' });
}

function onElasticRepulsion(val) {
  const v = parseInt(val);
  document.getElementById('rep-val').textContent = v;
  const renderer = elasticRendererFor(workspace.active);
  if (!renderer?.simulation) return;
  renderer.simulation.force('charge', d3.forceManyBody().strength(-v));
  renderer.simulation.alpha(0.5).restart();
}

function onElasticAttraction(val) {
  const v = parseInt(val);
  const strength = v / 100;
  document.getElementById('attr-val').textContent = strength.toFixed(2);
  const renderer = elasticRendererFor(workspace.active);
  if (!renderer?.simulation) return;
  renderer.simulation.force('link').strength(strength);
  renderer.simulation.alpha(0.5).restart();
}

// ═══════════════════════════════════════════════════════════════
// HIGHLIGHT
// ═══════════════════════════════════════════════════════════════

function hoverHL(node) {
  if (filterActive) return;   // don't override filter mode
  if (traceActive)  return;   // don't override pinned trace
  const nb = node.closedNeighborhood();
  cy.elements().addClass('dim');
  nb.removeClass('dim').addClass('hi');
}

function clearHL() {
  if (filterActive) return;   // don't clear in filter mode
  if (traceActive)  return;   // don't wipe a pinned trace on mouseout
  cy && cy.elements().removeClass('dim hi');
}

function forceHL(eles) {
  cy.elements().addClass('dim');
  eles.removeClass('dim').addClass('hi');
}

function clearFilter() {
  filterActive = null;
  cy && cy.elements().removeClass('dim hi');
  document.querySelectorAll('.li').forEach(el => {
    el.classList.remove('filt');
    const kind = el.dataset.legendKind;
    const label = kind === 'node'
      ? NODE_TYPES.find(type => type.type === el.dataset.legendType)?.label
      : EDGE_TYPES.find(type => type.type === el.dataset.legendType)?.label;
    if (!label) return;
    const accessibleName = `Filter ${kind} type: ${label}`;
    el.setAttribute('aria-label', accessibleName);
    el.dataset.tooltip = accessibleName;
  });
}

// ═══════════════════════════════════════════════════════════════
// DOWNSTREAM PATH TRACE (right-click)
// ═══════════════════════════════════════════════════════════════

function traceDownstream(startNode) {
  if (!cy) return;
  clearTrace();
  clearHL();

  // Build a plain adjacency snapshot of the graph and delegate the actual BFS to
  // traceDownstreamIds (graph-trace.js), which is unit-tested independently of Cytoscape.
  // See graph-trace.js for why 'error' does NOT stop the trace.
  const adjacency = new Map();
  cy.nodes().forEach(node => {
    const outEdges = [];
    node.outgoers('edge').forEach(edge => {
      outEdges.push({ edgeId: edge.id(), targetId: edge.target().id() });
    });
    adjacency.set(node.id(), { nodeType: node.data('nodeType'), outEdges });
  });

  const { nodeIds, edgeIds } = traceDownstreamIds(adjacency, startNode.id());
  const visitedNodes = cy.nodes().filter(n => nodeIds.has(n.id()));
  const visitedEdges = cy.edges().filter(e => edgeIds.has(e.id()));

  traceActive = true;

  // Dim everything, highlight only the traced downstream subgraph
  cy.elements().addClass('dim');
  visitedNodes.removeClass('dim').addClass('hi trace-node');
  visitedEdges.removeClass('dim').addClass('hi trace-edge');
  startNode.addClass('trace-start');

  document.getElementById('b-sel').textContent =
    `↓ Trace from «${startNode.data('name')}» — ${visitedNodes.length} nodes, ${visitedEdges.length} edges  (click canvas or press Esc to exit)`;
}

function clearTrace() {
  traceActive = false;
  cy && cy.elements().removeClass('trace-node trace-edge trace-start dim hi');
  document.getElementById('b-sel').textContent = '—';
}

// ═══════════════════════════════════════════════════════════════
// INFO PANEL
// ═══════════════════════════════════════════════════════════════

function scheduleSelectionInspectorRefresh(targetCy) {
  if (!targetCy || selectionInspectorRefreshes.has(targetCy)) return;
  selectionInspectorRefreshes.add(targetCy);
  queueMicrotask(() => {
    selectionInspectorRefreshes.delete(targetCy);
    if (targetCy === cy && !targetCy.destroyed()) showSelectionInfo();
  });
}

function showSelectionInfo({ skipDraftGuard = false } = {}) {
  if (!cy || cy.destroyed()) return;
  const nodes = cy.nodes(':selected');
  const edges = cy.edges(':selected');
  const desiredIds = [...nodes.map(node => node.id()), ...edges.map(edge => edge.id())];
  if (!skipDraftGuard && modifyEnabled && inspectorDraft?.form.isConnected
      && desiredIds.length === 1 && desiredIds[0] === inspectorDraft.nodeId) return;
  if (!skipDraftGuard && guardInspectorSelectionChange(desiredIds)) return;
  if (nodes.length > 1 && edges.empty()) {
    showMultiNodeInfo(nodes.map(node => node.id()));
  } else if (nodes.length === 1 && edges.empty()) {
    showNodeInfo(nodes.first());
  } else if (edges.length === 1 && nodes.empty()) {
    showEdgeInfo(edges.first());
  } else if (nodes.nonempty() || edges.nonempty()) {
    revealInspector();
    document.getElementById('info-title').textContent = `${nodes.length + edges.length} elements selected`;
    document.getElementById('info-body').innerHTML =
      '<div class="info-empty">Select nodes only to inspect common properties as a batch.</div>';
  } else {
    // Ctrl-click can now remove the final selected element. The renderer selection is already
    // empty, so clear the inspector in the same tap task without cancelling the guarded selection
    // repair that still has to outlive Cytoscape's post-tap bookkeeping.
    resetInfoContents();
    clearHL();
  }
}

function showMultiNodeInfo(nodeIds) {
  contextualHelp.dismiss();
  revealInspector();
  const nodes = nodeIds.map(id => graphData?.nodeMap?.[id]).filter(Boolean);
  if (nodes.length < 2) return;
  const editing = modifyEnabled && canModifyGraph(graphData, layoutMode);
  const properties = graphData.format === 'graphml'
    ? commonMultiSelectionProperties(nodes, nodeTypeCatalog) : [];
  document.getElementById('info-title').textContent = `${nodes.length} nodes selected`;
  document.getElementById('info-body').innerHTML = multiNodeInspectorHtml(nodes, properties, editing);
  if (editing && properties.length) bindMultiNodeEditor(nodes, properties);
}

function multiNodeInspectorHtml(nodes, properties, editing) {
  const count = nodes.length;
  const fields = properties.map((property, index) =>
    multiNodePropertyHtml(property, index, editing)).join('');
  const empty = properties.length ? '' : `<div class="info-empty" data-multi-selection-empty>
    No catalog property is compatible, visible, and editable across every selected node.
    Custom and unknown behaviors are not batch edited without a trusted schema.
  </div>`;
  const body = fields
    ? `<div class="editor-grid multi-selection-properties">${fields}</div>` : empty;
  const actions = editing && fields ? `<div class="editor-actions">
    <button class="btn primary" type="submit" data-apply-multi-properties>Apply to ${count} nodes</button>
  </div>` : '';
  const content = `<div class="multi-selection-summary">
    <div class="editor-label-row"><strong data-multi-selection-count>${count} nodes selected</strong>
      ${contextualHelpButtonHtml('Batch editing', 'Only catalog properties with the same schema and current visibility on every selected node are shown. Other node data remains unchanged.')}</div>
  </div>${body}${actions}`;
  return editing
    ? `<form id="multi-node-editor" class="editor-form" data-multi-node-inspector
        data-mode="edit" data-selection-count="${count}" role="region"
        aria-label="${count} selected nodes inspector" data-tooltip-exempt="persistent-form-labels">
        ${content}</form>`
    : `<div class="editor-form" data-multi-node-inspector data-mode="view"
        data-selection-count="${count}" role="region"
        aria-label="${count} selected nodes inspector">${content}</div>`;
}

function multiNodePropertyHtml(property, index, editing) {
  const controlId = `multi-property-${index}`;
  const state = property.state.kind;
  const secret = property.type === 'SECRET_REFERENCE';
  const label = escapeHtml(property.displayName || property.name);
  const stateText = propertyStateLabel(property);
  const wrapper = `class="editor-field full multi-selection-property"`
    + ` data-multi-property="${escapeAttribute(property.name)}"`
    + ` data-property-state="${state}" data-property-secret="${secret}"`;
  if (!editing) {
    const displayed = state === MULTI_PROPERTY_STATE.SAME && !secret
      ? property.state.value : stateText;
    return `<div ${wrapper}><label>${label}</label>
      <output class="multi-selection-value" data-multi-property-value>${escapeHtml(displayed)}</output>
      <small id="${controlId}" data-multi-property-state>${escapeHtml(stateText)}</small></div>`;
  }

  const control = multiNodePropertyControl(property, controlId);
  const description = secret
    ? 'Enter an opaque secret reference. Existing references are redacted and never copied into this form.'
    : (property.description || 'Changing this value updates every selected node.');
  return `<div ${wrapper}><div class="editor-label-row"><label for="${controlId}">${label}</label>
      ${contextualHelpButtonHtml(property.displayName || property.name, description)}</div>${control}
    <small id="${controlId}-state" data-multi-property-state>${escapeHtml(stateText)}</small>
    </div>`;
}

function multiNodePropertyControl(property, controlId) {
  const common = `id="${controlId}" data-multi-property-input="${escapeAttribute(property.name)}"`
    + ` data-catalog-type="${escapeAttribute(property.type)}" data-batch-touched="false"`
    + ` aria-describedby="${controlId}-state"`;
  const state = property.state.kind;
  const current = state === MULTI_PROPERTY_STATE.SAME ? property.state.value : '';
  const choices = property.allowedValues?.length
    ? property.allowedValues
    : (property.type === 'BOOLEAN' ? ['false', 'true'] : null);
  if (choices) {
    const unchanged = state === MULTI_PROPERTY_STATE.SAME ? ''
      : '<option value="" data-batch-action="unchanged" selected>Leave unchanged</option>';
    const options = choices.map(value => `<option value="${escapeAttribute(value)}"
      ${state === MULTI_PROPERTY_STATE.SAME && value === current ? 'selected' : ''}>${escapeHtml(value)}</option>`)
      .join('');
    return `<select ${common}>${unchanged}${options}<option value="" data-batch-action="clear">Clear from all</option></select>`;
  }

  const placeholder = property.type === 'SECRET_REFERENCE'
    ? 'Enter a replacement reference'
    : state === MULTI_PROPERTY_STATE.MIXED ? 'Mixed values — type to replace'
      : state === MULTI_PROPERTY_STATE.ABSENT ? 'Not declared — type to add' : '';
  if (property.type === 'TEXT' || property.type === 'CEL_EXPRESSION') {
    return `<textarea ${common} placeholder="${escapeAttribute(placeholder)}">${escapeHtml(current)}</textarea>`;
  }
  const inputType = property.type === 'INTEGER' || property.type === 'DECIMAL' ? 'number' : 'text';
  const step = property.type === 'DECIMAL' ? ' step="any"' : '';
  const secretOptions = property.type === 'SECRET_REFERENCE' ? ' autocomplete="off" spellcheck="false"' : '';
  return `<input ${common} type="${inputType}"${step}${secretOptions}
    value="${escapeAttribute(current)}" placeholder="${escapeAttribute(placeholder)}">`;
}

function bindMultiNodeEditor(nodes, properties) {
  const form = document.getElementById('multi-node-editor');
  if (!form) return;
  const markTouched = event => {
    const control = event.target.closest('[data-multi-property-input]');
    if (!control) return;
    const unchanged = control.tagName === 'SELECT'
      && control.selectedOptions[0]?.dataset.batchAction === 'unchanged';
    control.dataset.batchTouched = String(!unchanged);
  };
  form.addEventListener('input', markTouched);
  form.addEventListener('change', markTouched);
  form.addEventListener('submit', event => {
    event.preventDefault();
    if (layoutBusy) return showFormError(form, 'Layout in progress');
    if (!modifyEnabled || !canModifyGraph(graphData, layoutMode)) {
      return showFormError(form, 'Modify mode is OFF');
    }
    const changes = [...form.querySelectorAll('[data-multi-property-input]')].map(control => {
      const action = control.tagName === 'SELECT'
        ? control.selectedOptions[0]?.dataset.batchAction : '';
      return {
        name: control.dataset.multiPropertyInput,
        touched: control.dataset.batchTouched === 'true' && action !== 'unchanged',
        value: action === 'clear' ? '' : control.value,
      };
    });
    const currentNodes = nodes.map(node => graphData.nodeMap?.[node.id]).filter(Boolean);
    if (currentNodes.length !== nodes.length) {
      return showFormError(form, 'A selected node is no longer part of the document');
    }
    const plan = planMultiPropertyUpdate(currentNodes, properties, changes, nodeTypeCatalog);
    if (plan.errors.length) return showFormError(form, plan.errors.join('. '));
    if (!plan.entries.length) return showFormError(form, 'Change at least one common property first');
    if (!globalThis.confirm(`Apply changes to ${nodes.length} selected nodes?`)) return;
    try {
      updateNodePropertiesBatch(graphData, plan.entries, editHistory);
    } catch (error) {
      return showFormError(form, error.message || 'The selected nodes could not be updated');
    }
    const ids = nodes.map(node => node.id);
    rebuildGraph();
    cy.batch(() => ids.forEach(id => cy.getElementById(id).select()));
    updateHistoryUi();
    showMultiNodeInfo(ids);
    announceGraph(`Applied common property changes to ${ids.length} selected nodes. One undo restores them.`);
  });
}

function showReadOnlyProgramReadiness(model, state) {
  document.getElementById('info-title').textContent = `Program readiness · ${model.id}`;
  document.getElementById('info-body').innerHTML = `<div class="program-workspace"
      data-node-id="${escapeAttribute(model.id)}">
    <div class="editor-section-title"><span>Server readiness result</span></div>
    <div class="program-status"></div>
    <ol class="program-timeline" aria-label="Program build phases"></ol>
    <output class="program-output" hidden></output>
  </div>`;
  renderProgramPanelState(
    document.querySelector(`.program-workspace[data-node-id="${CSS.escape(model.id)}"]`),
    state,
  );
}

function showNodeInfo(node) {
  // Selecting or authoring reveals the Inspector: a selection that silently does nothing
  // because a panel is closed is worse than a panel reappearing.
  revealInspector();
  const model = graphData?.nodeMap?.[node.id()];
  if (!model) return;
  document.getElementById('info-title').textContent = model.name || model.id;
  if (graphData.format === 'graphify') {
    showReadOnlyElement(model, 'Graphify node');
    return;
  }
  if (!modifyEnabled) {
    const readiness = model.behavior === 'program'
      ? model.programReadinessState
        || (workspace.active ? programReadiness(workspace.active).phases.get(model.id) : null)
      : null;
    if (readiness && (readiness.phase === 'FAILED' || readiness.phase === 'RETIRED')) {
      showReadOnlyProgramReadiness(model, readiness);
      return;
    }
    showReadOnlyElement(model, 'Workflow node');
    return;
  }
  renderNodeForm(model, false);
}

function edgeEndpointLabel(edge) {
  const endpoint = node => node.data('name') || node.id();
  return `${endpoint(edge.source())} → ${endpoint(edge.target())}`;
}

function selectionBadgeLabel(instance) {
  const nodes = instance.nodes(':selected');
  const edges = instance.edges(':selected');
  if (nodes.length > 1 && edges.empty()) return `${nodes.length} nodes`;
  if (nodes.length === 1 && edges.empty()) return nodes.first().data('name');
  if (edges.length === 1 && nodes.empty()) return edgeEndpointLabel(edges.first());
  const count = nodes.length + edges.length;
  return count ? `${count} elements` : '—';
}

function showEdgeInfo(edge) {
  // Selecting or authoring reveals the Inspector: a selection that silently does nothing
  // because a panel is closed is worse than a panel reappearing.
  revealInspector();
  const model = graphData?.edges?.find(candidate => candidate.id === edge.id());
  if (!model) return;
  // The outcome of a failure route is `continue` by construction, so titling the inspector
  // with it would announce the opposite of what the edge does. The author's own name still wins.
  const descriptor = model.edgeName
    || (edgeFailureRouteKind(model, graphData) ? 'failure' : model.outcome);
  document.getElementById('info-title').textContent =
    `${edgeEndpointLabel(edge)}${descriptor ? ` · ${descriptor}` : ''}`;
  if (graphData.format === 'graphify') {
    showReadOnlyElement(model, 'Graphify relation');
    return;
  }
  if (!modifyEnabled) {
    showReadOnlyElement(model, 'Workflow edge');
    return;
  }
  renderEdgeForm(model, false);
}

function resetInfoContents() {
  contextualHelp.dismiss();
  document.getElementById('info-title').textContent = 'Inspector';
  document.getElementById('info-body').innerHTML =
    '<div class="info-empty">Select a node or edge, or create a new one.</div>';
}

function closeInfo() {
  if (guardInspectorSelectionChange([])) return false;
  resetInfoContents();
  invalidateStableSelection();
  cy?.elements().unselect();
  clearHL();
  return true;
}

function readNodeEditorPatch(form, model) {
  const values = new FormData(form);
  const id = String(values.get('id') || '').trim();
  const custom = readPropertyEditor(form);
  const catalog = readCatalogPropertyEditor(form);
  const nature = readNatureEditor(form);
  // The bypass flag is read HERE and not at the submit site, and that placement is the
  // whole point rather than tidiness. This function has three callers: the submit handler, the
  // baseline capture in `bindNodeInspectorDraft`, and `inspectNodeDraft`, which is what the autosave
  // diffs to decide whether anything changed. A read that lived only in the submit path would leave
  // the flag out of the baseline AND out of the comparison, so `nodePatchChanged` would report "not
  // changed" for a node whose only edit was switching it off — and the autosave would drop it in
  // silence. That is the worst way this feature can fail: the author sees a ticked box, believes the
  // node is off, and it runs. the `runtime.maxConcurrency` is read here for the same reason and
  // must stay here for it: every platform-owned property this form writes has to pass through this
  // one function, or it is invisible to the autosave.
  const bypass = readBypassEditor(form);
  const runtimeConcurrency = readMaxConcurrencyEditor(form);
  const join = readJoinEditor(form, model, graphData);
  const kind = String(values.get('kind'));
  return {
    name: String(values.get('name') || id).trim() || id,
    kind,
    behavior: kind === 'BEHAVIOR' ? String(values.get('behavior') || '').trim() : '',
    nodeType: kindOwnsNodeType(kind)
      ? kindToNodeType(kind) : String(values.get('nodeType') || kindToNodeType(kind)),
    isStart: kind === 'START',
    isEnd: kind === 'END',
    isActor: kind === 'BEHAVIOR',
    classname: String(values.get('classname') || '').trim(),
    description: String(values.get('description') || '').trim(),
    // `properties` is a whole replace, never a merge with the model's previous value (the rule,
    // stated on `catalogPropertyFieldsHtml`). That is what makes an absent contribution an ERASURE:
    // `nature`'s "inherit default", `bypass`'s unticked box and `runtimeConcurrency`'s inherited
    // default all return `{}`, and that is exactly how a previously-declared value gets dropped back
    // to absent, with no separate unset path. For it is also how switching Kind away from
    // BEHAVIOR removes a key the runtime would refuse.
    //
    // Every platform-owned contributor has to appear in BOTH maps below. Dropping one from
    // `propertyTypes` while keeping it in `properties` does not fail loudly -- it writes the value
    // under a default type, which is the kind of divergence that only surfaces on a GraphML round
    // trip, so the two lists are kept in the same order to make an omission visible by eye.
    properties: {
      ...custom.properties, ...catalog.properties, ...nature.properties,
      ...bypass.properties, ...runtimeConcurrency.properties, ...join.properties,
    },
    propertyTypes: {
      ...custom.propertyTypes, ...catalog.propertyTypes, ...nature.propertyTypes,
      ...bypass.propertyTypes, ...runtimeConcurrency.propertyTypes, ...join.propertyTypes,
    },
  };
}

function inspectNodeDraft(draft = inspectorDraft) {
  const model = draft && graphData?.nodeMap?.[draft.nodeId];
  if (!draft || !model || draft.documentId !== workspace.activeId || !draft.form.isConnected) {
    return { valid: false, changed: false, patch: null, model: null };
  }
  if (!draft.form.checkValidity()) return { valid: false, changed: draft.dirty, patch: null, model };
  try {
    const patch = readNodeEditorPatch(draft.form, model);
    return {
      valid: true,
      changed: draft.baseline ? nodePatchChanged(draft.baseline, patch) : draft.dirty,
      patch,
      model,
    };
  } catch {
    return { valid: false, changed: draft.dirty, patch: null, model };
  }
}

/**
 * Re-applies the one bypass carrier an INLINE style owns after an autosave changes a
 * node's data without re-running a visual-style pass.
 *
 * The dash needs no help: it lives in `createStylesheet`'s `node[?bypassed]` rule, and a Cytoscape
 * selector re-evaluates the moment the data changes. That is exactly why nothing writes
 * `border-style` inline any more — an inline write wins over the stylesheet and then goes stale here,
 * on the ordinary editing path, which is the measured defect this function exists to close: switching
 * a node off through autosave updated its label and left its border drawn as an executing node's.
 *
 * The COLOUR does need help, and only in the n8n family — which includes the default `cyto` style, so
 * it is the border most authors actually see. Those styles write a per-type border colour inline that
 * would otherwise beat the stylesheet.
 *
 * A node the runtime is painting is left alone: `applyRuntimeVisual` owns its border while a run is
 * in flight, and what the run is doing right now outranks what the document says it will do next time.
 */
function refreshBypassBorder(node) {
  if (!isN8nFamilyLayout()) return;
  if ((node.data('runtimeState') || 'idle') !== 'idle') return;
  const ordinary = N8N_BORDER[node.data('nodeType')] || rendererPalette.nodeBorder;
  node.style('border-color', node.data('bypassed') ? rendererPalette.nodeType.system : ordinary);
}

function syncAutosavedNodeRenderer(nodeId) {
  const element = cy?.getElementById(nodeId);
  if (!element?.nonempty()) return;
  const rendered = buildElements(graphData).find(candidate =>
    candidate.data?.id === nodeId && !Object.hasOwn(candidate.data, 'source'));
  if (rendered) {
    element.data(rendered.data);
    refreshBypassBorder(element);
  }
}

function commitNodeDraft(draft = inspectorDraft, { coalesceKey = null } = {}) {
  if (!draft) return false;
  clearTimeout(draft.timer);
  draft.timer = null;
  const assessment = inspectNodeDraft(draft);
  draft.dirty = assessment.changed;
  if (!assessment.valid) return false;
  if (!assessment.changed) return true;
  const command = updateNodeFields(graphData, draft.nodeId, assessment.patch, editHistory, { coalesceKey });
  if (!command) return false;
  draft.baseline = structuredClone(assessment.patch);
  draft.dirty = false;
  syncAutosavedNodeRenderer(draft.nodeId);
  updateHistoryUi();
  scheduleProgramGraphReadiness(workspace.active);
  return true;
}

function scheduleNodeDraftCommit(draft, immediate = false) {
  if (!draft || draft !== inspectorDraft || !inspectorAutosave) return;
  clearTimeout(draft.timer);
  const commit = () => commitNodeDraft(draft, { coalesceKey: draft.focusKey });
  if (immediate) commit();
  else draft.timer = setTimeout(commit, 180);
}

function bindNodeInspectorDraft(form, model, creating) {
  if (creating) return;
  clearTimeout(inspectorDraft?.timer);
  const draft = {
    form,
    nodeId: model.id,
    documentId: workspace.activeId,
    dirty: false,
    baseline: null,
    timer: null,
    focusKey: null,
    focusControl: null,
    lastFocusControl: null,
  };
  if (form.checkValidity()) {
    try { draft.baseline = readNodeEditorPatch(form, model); } catch { /* invalid stays untouched */ }
  }
  inspectorDraft = draft;
  form.addEventListener('focusin', event => {
    if (!event.target.matches('input:not([readonly]), textarea')) return;
    if (draft.focusControl !== event.target) {
      draft.focusControl = event.target;
      draft.lastFocusControl = event.target;
      draft.focusKey = `node:${model.id}:edit:${++inspectorEditSequence}`;
    }
  });
  form.addEventListener('focusout', event => {
    if (event.target !== draft.focusControl) return;
    if (inspectorAutosave) commitNodeDraft(draft, { coalesceKey: draft.focusKey });
    draft.focusControl = null;
    draft.focusKey = null;
  });
  form.addEventListener('input', () => {
    draft.dirty = true;
    draft.dirty = inspectNodeDraft(draft).changed;
    scheduleNodeDraftCommit(draft, false);
  });
  form.addEventListener('change', () => {
    draft.dirty = true;
    draft.dirty = inspectNodeDraft(draft).changed;
    scheduleNodeDraftCommit(draft, true);
  });
}

function retireInspectorDraft(form = null) {
  if (!inspectorDraft || (form && inspectorDraft.form !== form)) return;
  clearTimeout(inspectorDraft.timer);
  inspectorDraft = null;
}

function restoreDraftSelection(draft) {
  const node = cy?.getElementById(draft.nodeId);
  if (!node?.nonempty()) return;
  applyStableSelection(cy, [draft.nodeId]);
}

function guardInspectorSelectionChange(desiredIds) {
  const draft = inspectorDraft;
  if (!draft || !draft.form.isConnected || pendingInspectorTransition) {
    if (pendingInspectorTransition) restoreDraftSelection(pendingInspectorTransition.draft);
    return Boolean(pendingInspectorTransition);
  }
  if (desiredIds.length === 1 && desiredIds[0] === draft.nodeId) return false;
  clearTimeout(draft.timer);
  draft.timer = null;
  const assessment = inspectNodeDraft(draft);
  if (inspectorAutosave && assessment.valid) {
    if (assessment.changed && !commitNodeDraft(draft, { coalesceKey: draft.focusKey })) {
      openInspectorUnsavedDialog(draft, desiredIds, false);
      return true;
    }
    retireInspectorDraft(draft.form);
    return false;
  }
  if (!assessment.changed) {
    retireInspectorDraft(draft.form);
    return false;
  }
  openInspectorUnsavedDialog(draft, desiredIds, assessment.valid);
  return true;
}

function openInspectorUnsavedDialog(draft, desiredIds, valid) {
  pendingInspectorTransition = {
    draft,
    desiredIds: [...desiredIds],
    origin: document.activeElement,
    complete: null,
  };
  restoreDraftSelection(draft);
  const dialog = document.getElementById('inspector-unsaved-dialog');
  document.getElementById('inspector-unsaved-title').textContent = uiText('inspector.unsaved.title');
  document.getElementById('inspector-unsaved-description').textContent = uiText(valid
    ? 'inspector.unsaved.description' : 'inspector.unsaved.invalidDescription');
  for (const action of ['save', 'discard', 'cancel']) {
    dialog.querySelector(`[data-inspector-unsaved-action="${action}"]`).textContent =
      uiText(`inspector.unsaved.${action}`);
  }
  if (!dialog.open) dialog.showModal();
  dialog.querySelector('[data-inspector-unsaved-action="cancel"]')?.focus();
}

function completeInspectorTransition(action) {
  const pending = pendingInspectorTransition;
  if (!pending) return false;
  const dialog = document.getElementById('inspector-unsaved-dialog');
  if (action === 'save' && !commitNodeDraft(pending.draft, { coalesceKey: pending.draft.focusKey })) {
    document.getElementById('inspector-unsaved-description').textContent =
      uiText('inspector.unsaved.invalidDescription');
    return false;
  }
  if (action === 'cancel') {
    pendingInspectorTransition = null;
    dialog.close();
    restoreDraftSelection(pending.draft);
    const focusTarget = pending.draft.lastFocusControl?.isConnected
      ? pending.draft.lastFocusControl : pending.origin;
    focusTarget?.focus?.({ preventScroll: true });
    return true;
  }
  pendingInspectorTransition = null;
  dialog.close();
  retireInspectorDraft(pending.draft.form);
  if (pending.complete) return Boolean(pending.complete());
  applyStableSelection(cy, pending.desiredIds);
  queueMicrotask(() => showSelectionInfo({ skipDraftGuard: true }));
  return true;
}

// Commands that mutate the graph or replace the Inspector enter here before doing either. The
// dialog therefore owns a deferred intention, not a rollback of work that already happened.
function runAfterInspectorDraft(action, { deferredAction = action, deferredResult = true } = {}) {
  if (pendingInspectorTransition) return deferredResult;
  const draft = inspectorDraft;
  if (!draft?.form.isConnected) return Boolean(action());
  clearTimeout(draft.timer);
  draft.timer = null;
  const assessment = inspectNodeDraft(draft);
  if (inspectorAutosave && assessment.valid) {
    if (assessment.changed && !commitNodeDraft(draft, { coalesceKey: draft.focusKey })) {
      openInspectorUnsavedDialog(draft, [draft.nodeId], false);
      pendingInspectorTransition.complete = deferredAction;
      return deferredResult;
    }
    retireInspectorDraft(draft.form);
    return Boolean(action());
  }
  if (!assessment.changed) {
    retireInspectorDraft(draft.form);
    return Boolean(action());
  }
  openInspectorUnsavedDialog(draft, [draft.nodeId], assessment.valid);
  pendingInspectorTransition.complete = deferredAction;
  return deferredResult;
}

function renderNodeForm(model, creating) {
  contextualHelp.dismiss();
  const descriptor = catalogDescriptor(model.behavior);
  const catalogEditorDescriptor = programCatalogEditorDescriptor(descriptor);
  const catalogFieldOwner = { documentId: workspace.activeId, nodeId: model.id };
  const catalogNames = new Set((descriptor?.properties || []).map(property => property.name));
  // `runtime.nature` (or whatever `descriptor.natureProperty` names) is platform-owned, never a
  // behavior property (see NodeRuntimeNatureProperty's javadoc) — it has its own dedicated control
  // below and must never also surface as a free-form "additional property" row, which would give the
  // author two controls writing the same key and no way to know which one wins.
  // `execution.bypass` (or whatever `descriptor.bypassProperty` names) is platform-owned on
  // exactly the same terms — `NodeBypassProperty.validateShape` refuses any descriptor that declares
  // the key, so it can never be a behavior property, and it has its own dedicated control below.
  // Excluded here for the same reason nature is: two controls writing one key, and no way for the
  // author to know which one wins — with the key that decides whether the node runs at all.
  // the `runtime.maxConcurrency` is the third member of the same family and is excluded on the
  // same terms. THIS SET IS A UNION AND HAS TO STAY ONE: it is named `platformExclusions` rather
  // than after any single feature because dropping one entry does not fail anywhere -- the key
  // simply reappears as a free-form "Additional properties" row beside its own dedicated control,
  // which is precisely the two-authorities-over-one-key defect all three features exist to prevent.
  const platformExclusions = new Set([
    DEFAULT_NATURE_PROPERTY, descriptor?.natureProperty,
    DEFAULT_MAX_CONCURRENCY_PROPERTY, descriptor?.maxConcurrencyProperty,
    bypassPropertyName(descriptor, nodeTypeCatalog),
  ].filter(Boolean));
  const extras = additionalProperties(model, 'node')
    .filter(property => !catalogNames.has(property.name) && !platformExclusions.has(property.name));
  const visualTypes = NODE_TYPES.map(type =>
    `<option value="${type.type}" ${type.type === model.nodeType ? 'selected' : ''}>${escapeHtml(type.label)}</option>`)
    .join('');
  const kindOptions = NODE_KINDS.map(kind =>
    `<option value="${kind}" ${kind === model.kind ? 'selected' : ''}>${kind}</option>`).join('');
  const catalogOptions = nodeTypeCatalog.map(type =>
    `<option value="${escapeAttribute(type.behavior)}" ${type.behavior === model.behavior ? 'selected' : ''}>${escapeHtml(type.category)} · ${escapeHtml(type.displayName)}</option>`).join('');
  const catalogDescription = descriptor?.description
    || 'Unknown behavior names are valid: the runtime reports fallback and executes pass-through.';
  document.getElementById('info-body').innerHTML = `
    <form id="node-editor" class="editor-form" data-tooltip-exempt="persistent-form-labels">
      <div class="editor-grid">
        <div class="editor-field full"><label>ID</label><input name="id" value="${escapeAttribute(model.id)}" ${creating ? '' : 'readonly'} required></div>
        <div class="editor-field full"><label>Name</label><input name="name" value="${escapeAttribute(model.name)}" required></div>
        <div class="editor-field"><label>Kind</label><select name="kind">${kindOptions}</select></div>
        <div class="editor-field"><label>Visual type</label><select name="nodeType">${visualTypes}</select></div>
        <div class="editor-field full"><div class="editor-label-row"><label>Catalog type</label>
          ${contextualHelpButtonHtml('Catalog type', catalogDescription)}</div>
          <select name="catalogBehavior"><option value="">Custom / unknown behavior</option>${catalogOptions}</select></div>
        <div class="editor-field full behavior-field"><label>Behavior</label><input name="behavior" value="${escapeAttribute(model.behavior || '')}" placeholder="registered-name or future behavior"></div>
        <div class="editor-field full"><label>Class name</label><input name="classname" value="${escapeAttribute(model.classname || '')}" placeholder="optional metadata"></div>
        <div class="editor-field full"><label>Description</label><textarea name="description">${escapeHtml(model.description || '')}</textarea></div>
      </div>
      <div id="node-bypass-section">${bypassFieldHtml(descriptor, model, graphData)}</div>
      <div id="node-nature-section">${natureFieldHtml(descriptor, model)}</div>
      <div id="node-max-concurrency-section">${maxConcurrencyFieldHtml(descriptor, model)}</div>
      <div id="node-join-section">${joinFieldHtml(graphData, model)}</div>
      <div id="catalog-properties">${catalogPropertyFieldsHtml(
        catalogEditorDescriptor, model.properties || {}, catalogFieldOwner)}</div>
      <div id="program-workspace">${programWorkspaceContentHtml(descriptor, model)}</div>
      ${propertyEditorHtml('node-properties', extras)}
      <div class="editor-actions">
        ${creating ? '' : '<button class="btn danger" type="button" id="delete-node">Delete</button>'}
        <button class="btn primary" type="submit">${creating ? 'Add node' : 'Save node'}</button>
      </div>
    </form>`;
  const form = document.getElementById('node-editor');
  form.elements.catalogBehavior.addEventListener('change', event => {
    const selected = catalogDescriptor(event.target.value);
    if (!selected) return;
    form.elements.kind.value = 'BEHAVIOR';
    form.elements.behavior.value = selected.behavior;
    if (Array.from(form.elements.nodeType.options).some(option => option.value === selected.visualType)) {
      form.elements.nodeType.value = selected.visualType;
    }
    contextualHelp.dismiss();
    const catalogHelp = form.querySelector('[data-contextual-help-title="Catalog type"]');
    if (catalogHelp) catalogHelp.dataset.contextualHelp = selected.description;
    // A behavior switch changes which natures the node may declare, so the control has to be
    // rebuilt against the NEW descriptor's allowlist, not left showing the old one's options (which
    // would let a save write a value the newly-selected behavior never permitted).
    document.getElementById('node-nature-section').innerHTML = natureFieldHtml(selected, model);
    document.getElementById('node-max-concurrency-section').innerHTML = maxConcurrencyFieldHtml(selected, model);
    bindNatureField();
    bindMaxConcurrencyField();
    // Picking a catalog type forces `kind` to BEHAVIOR two lines above, which is exactly the
    // condition that decides whether the bypass control may exist at all. Rebuilt for the same reason
    // the nature control is, and against the CURRENT form state rather than the loaded model.
    renderBypassSection(form, model);
    document.getElementById('catalog-properties').innerHTML = catalogPropertyFieldsHtml(
      programCatalogEditorDescriptor(selected), {}, catalogFieldOwner);
    document.getElementById('program-workspace').innerHTML = programWorkspaceContentHtml(selected, model);
    bindProgramWorkspace(form, model);
  });
  // `NodeBypassValidator` refuses the key on every non-BEHAVIOR node, `false` included, so
  // changing Kind changes whether the control may be offered. Re-rendered rather than left stale: a
  // checkbox surviving a switch to END would let a save write a graph the runtime refuses to load.
  form.elements.kind.addEventListener('change', () => renderBypassSection(form, model));
  bindBypassField();
  bindNatureField();
  bindMaxConcurrencyField();
  bindJoinField();
  // Any catalog property can be the sibling a condition names, so this listens on the
  // container rather than on specific properties the descriptor happens to declare as
  // condition-drivers today — nothing here is specific to "mode" or to any one behavior. Delegated
  // rather than bound per-control because `#catalog-properties` itself gets replaced by this same
  // handler, which would otherwise have to re-bind itself on every change.
  document.getElementById('catalog-properties')?.addEventListener('change', event => {
    if (!catalogEditorDescriptor || !event.target.closest('[data-catalog-property]')) return;
    refreshConditionalCatalogProperties(catalogEditorDescriptor, catalogFieldOwner);
  });
  form.addEventListener('submit', event => {
    event.preventDefault();
    if (layoutBusy) return showFormError(form, 'Layout in progress');
    if (!modifyEnabled || !canModifyGraph(graphData, layoutMode)) return showFormError(form, 'Modify mode is OFF');
    const values = new FormData(form);
    const id = String(values.get('id') || '').trim();
    if (creating && graphData.nodeMap[id]) return showFormError(form, `Node ID ${id} already exists`);
    const patch = readNodeEditorPatch(form, model);
    // Creating applies the values to a detached node and inserts it as one command; editing patches
    // the document node. Either way the mutation is a command, never a write from the form.
    if (creating) {
      const created = createNode(id, patch.name, patch.kind);
      Object.assign(created, patch);
      insertNodeElement(graphData, created, editHistory);
    } else {
      const draft = inspectorDraft?.form === form ? inspectorDraft : null;
      if (draft) {
        const assessment = inspectNodeDraft(draft);
        if (!assessment.valid) return showFormError(form, uiText('inspector.unsaved.invalidDescription'));
        if (assessment.changed && !commitNodeDraft(draft, { coalesceKey: draft.focusKey })) {
          return showFormError(form, 'This node is no longer part of the document');
        }
      } else if (nodePatchChanged(model, patch)
          && !updateNodeFields(graphData, model.id, patch, editHistory)) {
        return showFormError(form, 'This node is no longer part of the document');
      }
    }
    retireInspectorDraft(form);
    rebuildGraph();
    updateHistoryUi();
    showNodeInfo(cy.getElementById(id));
    scheduleProgramGraphReadiness(workspace.active);
  });
  document.getElementById('delete-node')?.addEventListener('click', () => {
    if (!modifyEnabled || !canModifyGraph(graphData, layoutMode)) return;
    runAfterInspectorDraft(() => {
      deleteElements(graphData, [model.id], [], editHistory);
      rebuildGraph();
      updateHistoryUi();
      closeInfo();
      return true;
    });
  });
  bindProgramWorkspace(form, model);
  bindNodeInspectorDraft(form, model, creating);
}

function catalogDescriptor(behavior) {
  return nodeTypeCatalog.find(type => type.behavior === behavior) || null;
}

function programCatalogEditorDescriptor(descriptor) {
  if (descriptor?.behavior !== 'program') return descriptor;
  return {
    ...descriptor,
    properties: (descriptor.properties || [])
      .filter(property => !PROGRAM_WORKSPACE_PROPERTY_NAMES.has(property.name)),
  };
}

/**
 * The option list of a SECRET_REFERENCE control, for `stringValue` as the
 * currently declared reference. Written once and used twice — by the editor below, and by
 * `refreshSecretReferenceChoices` when a newly stored credential has to appear in a control that is
 * already on screen. Two copies of this reasoning would be two chances for the two paths to disagree
 * about what a preserved-but-unknown reference looks like.
 */
function secretReferenceOptionsHtml(stringValue) {
  const held = credentialReferenceChoices();
  const credentials = held?.credentials || [];
  const known = credentials.some(entry => String(entry.reference) === stringValue);
  // First and `value=""`, which is what makes it HTML's placeholder label option: a
  // native-`required` select reports itself missing while it is selected, so the fail-closed
  // guarantee holds here for the same reason and by the same mechanism as it does for a closed
  // choice.
  const notSelected = `<option value="" ${stringValue === '' ? 'selected' : ''}>Not selected</option>`;
  // Carries the RAW stored reference as its own option's value, selected, exactly as
  // `mismatchedOption` does for a closed choice: left untouched the control round-trips it verbatim,
  // so saving a form cannot silently drop a reference this build did not recognise — an imported
  // graph, another author's credential, or simply a list nobody has fetched yet. The two cases are
  // LABELLED APART because they are different facts: one is "not yours", the other is "not known
  // yet", and telling an author the first when the second is true would invite them to delete a
  // perfectly good declaration.
  const foreign = stringValue !== '' && !known
    ? `<option value="${escapeAttribute(stringValue)}" selected>${held?.loaded
      ? `Kept as declared — not one of your credentials: ${escapeHtml(stringValue)}`
      : `Kept as declared — connect to your service to choose: ${escapeHtml(stringValue)}`}</option>`
    : '';
  const options = credentials.map(entry =>
    `<option value="${escapeAttribute(entry.reference)}" ${String(entry.reference) === stringValue ? 'selected' : ''}>${escapeHtml(entry.label || entry.reference)}</option>`).join('');
  return `${notSelected}${foreign}${options}`;
}

/**
 * Re-offers the current credential list in every SECRET_REFERENCE control already on screen, and
 * touches nothing else.
 *
 * NOT a re-render of the inspector. Rebuilding the node editor when a listing lands would discard
 * whatever the author had half-typed in a sibling field. Each control's own `value` is read back and
 * passed through the same option builder, so a selection survives and an unknown reference stays
 * preserved.
 * The multi-selection inspector renders SECRET_REFERENCE as an input rather than a select and is
 * therefore untouched by this selector, deliberately: its redaction behaviour is its own.
 */
function refreshSecretReferenceChoices() {
  document.querySelectorAll('select[data-catalog-type="SECRET_REFERENCE"]').forEach(select => {
    select.innerHTML = secretReferenceOptionsHtml(String(select.value));
  });
}

function catalogPropertyFieldsHtml(descriptor, values, owner) {
  if (!descriptor?.properties?.length) return '';
  // Code-point tokens and a separator that cannot occur inside one encoded component keep the
  // document/node/property tuple reversible and collision-free without exposing a document name.
  const idPart = raw => {
    const points = Array.from(String(raw ?? ''), character => character.codePointAt(0).toString(16));
    return points.length ? points.join('-') : 'empty';
  };
  const fieldIdsFor = propertyName => {
    const identity = [owner?.documentId, owner?.nodeId, propertyName].map(idPart).join('--');
    const base = `catalog-property-${identity}`;
    return { control: `${base}-control`, hint: `${base}-hint`, state: `${base}-state` };
  };
  const describedByAttribute = (...ids) => {
    const describedBy = [...new Set(ids.flat().filter(Boolean))].join(' ');
    return describedBy ? ` aria-describedby="${escapeAttribute(describedBy)}"` : '';
  };
  // Every sibling's CURRENTLY DISPLAYED value, resolved with the exact same fallback each
  // field's own control uses below — so a condition reads the same value the user actually sees in
  // the referenced sibling, never a stale or differently-defaulted one. Computed once, up front,
  // because a condition can name any other property in this same list, not only ones earlier in it.
  const resolvedValues = Object.fromEntries(
    descriptor.properties.map(property => [property.name, values[property.name] ?? property.defaultValue ?? '']));
  const fields = descriptor.properties.map(property => {
    const value = resolvedValues[property.name];
    const title = property.displayName || property.name;
    const fieldIds = fieldIdsFor(property.name);
    // `adapterBinding` (always paired with `required` — see
    // NodePropertyDescriptor#adapterBinding) names a property whose EMPTY value does not make the
    // graph invalid, it makes the node UNCONFIGURED: the server admits it and the node refuses only
    // once execution actually reaches it. The editor must therefore never emit the native `required`
    // attribute for these properties — doing so blocks the browser's own submit before the form's JS
    // handler ever runs, which is exactly the defect reports. Read only from the catalog
    // descriptor: no behavior name is special-cased here, so any node package that declares an
    // adapter binding gets the same treatment (SEC-09 keeps the catalog the single source of truth).
    //
    // "EMPTY" here must be decided the same way the server decides it, via `adapterIdOf`
    // (ported to `./adapter-binding.js`, itself a port of
    // `NodePropertyDescriptor#adapterIdOf`/`BehaviorPropertySchema#namesNoAdapter`). Do not spell
    // this with `.trim()`, `.replace(/\s/g, '')` or any other ad hoc check — see adapter-binding.js
    // for the enumerated set of code points that makes those spellings disagree with the server.
    const adapterBound = Boolean(property.adapterBinding);
    // Visibility and conditional required-ness, read from `visibleWhen`/`requiredWhen` via the
    // same generic evaluator the HTTP layer's own contract is built from — nothing here names a
    // property or a behavior, so this is identical for any node package that declares a condition.
    const visible = isPropertyVisible(property, resolvedValues);
    // `requiredNow` is the SCHEMA-level answer from the contract alone — used for the `*`
    // marker, which has always reflected "the author should fill this in" independently of whether
    // the browser enforces it natively (adapterBinding already establishes that split; see
    // the marker comment below). `nativeRequired` is the NARROWER, attribute-level answer that
    // actually reaches the DOM: never native-`required` while hidden (defensively, on top of
    // `isPropertyRequiredNow` already being unable to hold without `visibleWhen` also holding for a
    // load-time-validated descriptor — belt and suspenders against a descriptor this Inspector
    // should never actually receive, the same fail-closed instinct `conditionHolds` applies to an
    // unrecognised contract), and never native-`required` for an adapter binding, unchanged from
    // existing behavior.
    const requiredNow = isPropertyRequiredNow(property, resolvedValues);
    const nativeRequired = requiredNow && visible && !adapterBound;
    const unconfigured = adapterBound && adapterIdOf(value) === '';
    // A closed-choice property whose descriptor declares NO default has three states, not two
    // — each allowed value, plus "the author has not declared this" — and a `<select>` built only
    // from `allowedValues` can represent two of them. HTML then picks the first option as the
    // selected one, so the control reads back the first allowed value with nobody having chosen it,
    // and submit (which whole-replaces `properties`, see the submit handler) writes that value.
    // Opening a node to look at it and pressing Save became a declaration the author never made.
    //
    // The choice made here is the explicit empty option rather than a different control shape when
    // there is no default, for three reasons, in order of weight:
    // 1. This editor ALREADY has a control for exactly this idea, three sections up:
    // `natureFieldHtml` renders `<option value="">Inherit default (…)</option>` first, and
    // `readNatureEditor` drops the empty value so the property goes back to absent. Two
    // controls in one panel that mean "not declared" must not look like two different things.
    // 2. The absence path on the read side already exists and is untouched:
    // `readCatalogPropertyEditor` drops `value === ''`. Only the RENDER could not reach it.
    // 3. A second control shape would need a second read path, a second keyboard and
    // screen-reader contract, and a second round trip through
    // `readCurrentCatalogPropertyValues`/`refreshConditionalCatalogProperties`, which push
    // values through the DOM. The option's NAME carries the distinction honestly,
    // not by the widget type — and the name is available either way.
    //
    // The option is named "Not declared", not "None", "Any" or an empty label. "Empty" is a value;
    // "not declared" is the absence of one, and for a property like `recovery.repeatable` the two
    // sit on opposite sides of a fail-closed boundary. Nothing here names that property or any
    // other: the trigger is the SHAPE (closed choice, no default), which is not incidental —
    // `NodeTypeDescriptorValidator.requiredWhenHasNoDefault` REFUSES a default on any
    // conditionally-required property, so every such property is born in this shape by rule.
    //
    // Deliberately not extended to a closed choice that HAS a default: there the descriptor itself
    // nominates the value the control shows, so the pre-selection is a prefill the author can see
    // and change, not a value invented by option ordering. Without a default the descriptor
    // nominates nothing, and the control was showing one anyway.
    //
    // Placed FIRST and carrying `value=""`, so for a native-`required` select it is the HTML
    // "placeholder label option": the browser then reports the control as missing while it is
    // selected, which is precisely the intent of a `requiredWhen` that holds — the author is
    // stopped and asked to decide, instead of a value being decided for them.
    // "has a default" must be decided the same way `NodeTypeDescriptorValidator` decides it —
    // via Java's `String#isBlank()`, which is `strip().isEmpty()` — not via `=== ''`. A defaultValue
    // of only whitespace (e.g. a single space) is non-empty under `=== ''` but blank under
    // `isBlank()`, so the two sides would disagree on this descriptor shape: the
    // editor would render "has a default" (no `Not declared` option, so a non-matching value falls to
    // whichever option the browser elects first) while the validator's own `defaultValueIsAdmissible`
    // rule now refuses that same shape when the default is non-blank and outside `allowedValues` —
    // but a purely-whitespace default is blank, so it is a "not declared" descriptor the validator
    // does not reject, and the editor must read it the same way. `catalogPropertyHasDeclaredDefault`
    // (imported from adapter-binding.js and built on `adapterIdOf`'s verified Java-whitespace strip)
    // is the single answer to this question shared by every call site that needs it — this one,
    // `showAddCatalogNodeForm` below, AND `graph-editing.js`'s canvas insertion path, which is why it
    // lives in an importable module rather than as a private function of this file. A shared formula
    // not actually imported by every caller is a third copy waiting to diverge.
    const undeclarable = Boolean(property.allowedValues?.length) && !catalogPropertyHasDeclaredDefault(property);
    // Whether the DOCUMENT actually stores something for this property, independent of the
    // fallback `resolvedValues` applies below by `??`-ing in `property.defaultValue`/`''`. A declared
    // `defaultValue` resolves `value` to non-`''` content even though the document
    // declares nothing at all -- so "the document declares nothing" has to be read from `values`
    // itself, not inferred from `value === ''`, or that shape would misclassify as a real declaration
    // below.
    //
    // `!= null` (not `??`) does make an explicit stored `''` count as PRESENT rather than absent --
    // but that distinction does NOT survive contact with either end of this property's life, and
    // nothing below relies on it being preserved. `readCatalogPropertyEditor`'s own
    // `if (value === '') return;` drops an explicitly-stored `''` at save exactly like an absent one,
    // unconditionally; the render below (`undeclaredOption` /
    // `mismatchedOption`, and the BOOLEAN branch's `recognized`) treats an empty STRING value the
    // same as absent regardless of what `present` says, precisely so a stored `''` shows and behaves
    // as "Not declared" end to end, not as a mismatch nobody can act on. What REMAINS true, and is
    // the only thing `present` is actually used for, is the shape this variable exists to guard:
    // telling a document that declares nothing at all apart from a document that declares a non-empty
    // value the allowed values do not recognise -- `mismatchedOption` needs exactly that second case.
    const present = values[property.name] != null;
    // The sentence follows the control. It used to end "never paste a secret" because the
    // control was a text box that would have taken one; the control now cannot, so the hint says
    // where the choices come from and what the document actually stores instead.
    const secretHint = property.type === 'SECRET_REFERENCE'
      ? ' The list holds the credentials you have stored. The value itself is entered in the'
        + ' Credentials window, on the Run menu; only the reference is written to the graph.'
      : '';
    // The `*` marker survives regardless: `adapterBinding` implies `required`, so the author should
    // still be prompted to fill the property in. What changes is only whether the browser blocks
    // saving over it, and — while it is blank — a distinct hint that replaces the native :invalid
    // state so "not configured yet" cannot be mistaken for "required and missing".
    const fieldClass = unconfigured ? 'editor-field full catalog-property catalog-property--unconfigured' : 'editor-field full catalog-property';
    // Scoped to the properties each sentence is about: every other property keeps its exact
    // pre-existing description text (no inserted punctuation), so properties outside this state are
    // unchanged. `appendSentence` is the same joining rule used inline.
    const baseText = (property.description || '') + secretHint;
    const appendSentence = (text, sentence) =>
      text.trim().replace(/[.!?]?$/, text.trim() ? '. ' : '') + sentence;
    let helpText = baseText;
    let stateText = '';
    if (unconfigured) {
      stateText = 'Not configured yet — this node will refuse when execution reaches it, not when the graph is saved.';
      // For a node that invokes a MODEL provider, the UI also states where the thing it is
      // waiting for is declared. Without this the sentence above tells an author their node will
      // refuse and leaves them with an unexplained blank — which they resolve, if at all, after a
      // failed run. This editor has no Model providers panel, so the sentence names
      // the plugin bundle that supplies the node type; see `PROVIDER_CONFIG_POINTER` for why it is
      // rewritten rather than dropped.
      //
      // Gated on the catalog's declared capabilities, never on the behavior name and never on
      // `adapterBinding` alone: that flag is a plain boolean meaning "names a deployment-configured
      // adapter", so an AMQP or Telegram node package carries it too, and telling its author to go
      // and configure a model provider would be a confident instruction to the wrong place. See
      // `invokesModelProvider`, which reads the same capability set the runtime reads.
      if (invokesModelProvider(descriptor)) stateText = appendSentence(stateText, PROVIDER_CONFIG_POINTER);
    }
    // Stated unconditionally for the shape, not only while the value happens to be undeclared.
    // The hint is rendered once and is not re-rendered on a plain value change (only
    // `refreshConditionalCatalogProperties` re-renders, and only when a CONDITION changed), so a
    // sentence phrased as "this is currently undeclared" would go stale in the DOM the moment the
    // author picked a value. Phrased as what the option MEANS, it stays true in every state. It says
    // nothing about what any particular behavior does with the absence — that belongs to the
    // property's own `description`, which the catalog owns.
    if (undeclarable) {
      helpText = appendSentence(helpText,
        'Not declared is a state of its own: it saves no value for this property, which is not the same as choosing one.');
    }
    const hintText = helpText.trim();
    const describedBy = describedByAttribute(
      stateText ? fieldIds.state : null,
      hintText ? fieldIds.hint : null,
    );
    const accessibility = ` id="${fieldIds.control}"${describedBy}`;
    let control;
    if (property.allowedValues?.length) {
      const declared = property.allowedValues.some(option => String(option) === String(value));
      // `!declared` covers two different documents. Either the property is genuinely absent (nothing in
      // `values`, `value` is only the resolved fallback) and "Not declared" is the true, pre-existing
      // behavior; or the document HAS a stored value and it simply does not match any
      // `allowedValues` entry byte-for-byte -- padded with whitespace (the parser deliberately
      // preserves it) or just not one of the declared strings. If the second case falls through to
      // the first, no option is
      // selected, the browser showed the FIRST allowedValues entry (or, when `undeclarable`, the
      // control collapsed the real value down to the empty "Not declared" option, which submit then
      // DROPS entirely) -- silently, on open, with no author action. Both paths lose an authored
      // declaration, with `undeclarable` determining which path is taken.
      //
      // The mismatch option carries the RAW, unmodified stored value as its selected `value`
      // attribute. Left untouched, the control round-trips it exactly -- a `<select>`
      // reports the value of whichever option is selected, and this one holds the original string
      // verbatim; changed, the author's explicit pick is written instead. No before/after comparison
      // is needed: the native `<select>` value IS the comparison, the same generalization the
      // `readJoinEditor` reached (compare what submit would declare against what the document already
      // declares, return the raw declaration untouched if they agree) -- reached here at the DOM layer
      // instead of the read layer, because a single scalar property fits entirely inside one option's
      // `value` attribute, unlike a join's three-property declaration.
      // `present` alone is not enough to tell "the document declares a mismatching
      // value" apart from "the document declares nothing" -- `refreshConditionalCatalogProperties`
      // re-renders this same container from `readCurrentCatalogPropertyValues`, which reads
      // every control's CURRENT DOM value and therefore always returns a string, present or not. A
      // property the document never declared at all is `present === true` after any such re-render,
      // same as one the author actually filled in. The extra guard is the stored value ITSELF, not
      // just whether it exists: an empty string is what `readCurrentCatalogPropertyValues` reports
      // for a control the document never declared (the "Not declared" option's own `value=""`), so
      // `String(value) === ''` reads as "not declared" here before and after the container is
      // rerendered, regardless of what `present` says after that rerender.
      const undeclaredOption = undeclarable
        ? `<option value="" ${!declared && (!present || String(value) === '') ? 'selected' : ''}>Not declared</option>` : '';
      const mismatchedOption = !declared && present && String(value) !== ''
        ? `<option value="${escapeAttribute(value)}" selected>Current value not among the declared alternatives: ${escapeHtml(value)}</option>` : '';
      // a non-empty, non-conforming value deliberately leaves the fail-closed
      // guarantee behind for THIS control. Once `mismatchedOption` is selected, the control's own
      // value IS that raw string -- not `''`, not the first (placeholder label) option -- so the
      // browser's `valueMissing` check no longer fires and a native `required` on this `<select>`
      // stops blocking save. That is not an accident of option ordering: it is the deliberate
      // required behavior. The author's already-declared, merely non-conforming value must be
      // SAVABLE, not just visible -- the whole point of carrying it as a real
      // option's `value` is that leaving it untouched round-trips it, and a control the browser
      // refuses to submit cannot round-trip anything. The guarantee this deliberately narrows is
      // "do not invent a value the author never chose"; it was never "do not let an already-present
      // value be kept". That narrower, still-fully-intact guarantee lives in `undeclaredOption` above:
      // a GENUINELY absent value (nothing declared, or a declared empty string -- see `present`'s own
      // comment) still renders "Not declared" FIRST with `value=""` and selected, so it is still the
      // HTML placeholder label option and `required` still stops the save until the author decides.
      control = `<select data-catalog-property="${escapeAttribute(property.name)}" data-catalog-type="${property.type}"${accessibility} ${nativeRequired ? 'required' : ''}>${undeclaredOption}${mismatchedOption}${property.allowedValues.map(option =>
        `<option value="${escapeAttribute(option)}" ${String(option) === String(value) ? 'selected' : ''}>${escapeHtml(option)}</option>`).join('')}</select>`;
    } else if (property.type === 'SECRET_REFERENCE') {
      // CHOOSE, NEVER TYPE.
      //
      // This property used to fall through to the final `else` and render a plain text input. That
      // control asks an author to reproduce, by hand, an opaque identifier they have to have found
      // somewhere else — and it accepts every string, so the failure mode is a node that looks
      // configured, saves cleanly, and refuses at execution with a reference nothing resolves. It is
      // also a control that invites an actual leak: a person who does not
      // have the reference to hand pastes the SECRET into the box, because the box takes it.
      //
      // A `<select>` over the author's OWN credentials closes both. The option TEXT is the label the
      // author chose in the Credentials window; the option VALUE is the reference, which is what the
      // document stores — so the reference never has to be read, remembered or retyped, and the
      // control cannot produce one that does not exist.
      //
      // THERE IS NO FREE-TEXT FALLBACK, in any state. That is the point of the criterion, not an
      // omission: a control that degrades to an input when the list is empty degrades exactly when
      // an author is most likely to reach for the secret instead. What the two degraded states do
      // instead is PRESERVE, never invent — see the two options below.
      control = `<select data-catalog-property="${escapeAttribute(property.name)}" data-catalog-type="${property.type}"${accessibility} ${nativeRequired ? 'required' : ''}>${secretReferenceOptionsHtml(String(value))}</select>`;
    } else if (property.type === 'TEXT' || property.type === 'CEL_EXPRESSION') {
      control = `<textarea data-catalog-property="${escapeAttribute(property.name)}" data-catalog-type="${property.type}"${accessibility} ${nativeRequired ? 'required' : ''}>${escapeHtml(value)}</textarea>`;
    } else if (property.type === 'BOOLEAN') {
      // Same defect as the closed-choice branch above, muter -- `String(value) !== 'true'` is
      // true for ANY value that is not the exact string "true", so a stored value that merely FAILED
      // to parse as a boolean (padded with whitespace, or not "true"/"false" at all) used to select
      // `false` explicitly and WRITE `false` back on the very next save with no message anywhere. Same
      // reasoning as above: a third option carries the raw stored value and stays selected
      // until the author actually picks true or false. `recognized` (below) keeps the existing,
      // unaffected behaviour for a genuinely undeclared boolean -- the shape the existing test
      // suite exercised -- where `false` remains the pre-selected default.
      // same `present`-after-rerender caveat as the closed-choice branch above --
      // `stringValue === ''` is folded into `recognized` for the same reason `undeclaredOption` folds
      // it in above: after `refreshConditionalCatalogProperties` re-renders from
      // `readCurrentCatalogPropertyValues`, a BOOLEAN property the document never declared is
      // `present === true` too, and without this it would misreport as "Current value not
      // recognized: " with nothing after the colon -- the exact inverse of what this label is for.
      // There is no `required`/`valueMissing` stake on this branch (a BOOLEAN `<select>` here never
      // carries `required`), so this is purely the honesty fix, not a fail-closed one.
      const stringValue = String(value);
      const recognized = !present || stringValue === '' || stringValue === 'true' || stringValue === 'false';
      const unrecognizedOption = recognized ? ''
        : `<option value="${escapeAttribute(value)}" selected>Current value not recognized: ${escapeHtml(value)}</option>`;
      control = `<select data-catalog-property="${escapeAttribute(property.name)}" data-catalog-type="BOOLEAN"${accessibility}>${unrecognizedOption}<option value="false" ${recognized && stringValue !== 'true' ? 'selected' : ''}>false</option><option value="true" ${stringValue === 'true' ? 'selected' : ''}>true</option></select>`;
    } else {
      const inputType = property.type === 'INTEGER' || property.type === 'DECIMAL' ? 'number' : 'text';
      const step = property.type === 'DECIMAL' ? ' step="any"' : '';
      control = `<input data-catalog-property="${escapeAttribute(property.name)}" data-catalog-type="${property.type}" type="${inputType}"${step} value="${escapeAttribute(value)}"${accessibility} ${nativeRequired ? 'required' : ''}>`;
    }
    // `hidden`, never omitted from the render. `readCatalogPropertyEditor` collects every
    // `[data-catalog-property]` control that EXISTS in the form regardless of `hidden` — submit
    // whole-replaces the node's `properties` from exactly that collection, so a property this
    // function did not render at all would have its previously-saved value silently dropped on the
    // next save. `hidden` keeps the control (and its current value) in the DOM and out of layout, the
    // accessibility tree and Tab order, and out of native constraint validation — see
    // `.catalog-property[hidden]` in styles.css for why the CSS side of this needs its own rule
    // rather than relying on the attribute alone.
    const hint = hintText
      ? `<small id="${fieldIds.hint}" class="catalog-property-hint visually-hidden">${escapeHtml(hintText)}</small>` : '';
    const state = stateText
      ? `<small id="${fieldIds.state}" class="catalog-property-state">${escapeHtml(stateText)}</small>` : '';
    return `<div class="${fieldClass}" ${visible ? '' : 'hidden'}>
      <div class="editor-label-row"><label for="${fieldIds.control}">${escapeHtml(title)}${requiredNow
        ? ' <span aria-hidden="true">*</span>' : ''}</label>
        ${contextualHelpButtonHtml(title, helpText)}</div>${control}${hint}${state}</div>`;
  }).join('');
  return `<div class="editor-section-title"><span>${escapeHtml(descriptor.displayName)} properties</span></div><div class="editor-grid">${fields}</div>`;
}

/**
 * Every catalog-property control's CURRENT raw DOM value, including blank ones — unlike
 * `readCatalogPropertyEditor` below, which is submit-time and deliberately drops blanks, condition
 * evaluation needs to tell "blank" apart from "absent" (`PRESENT`/`BLANK` read exactly that
 * distinction), so this reads every control unfiltered.
 */
function readCurrentCatalogPropertyValues(container) {
  const values = {};
  container?.querySelectorAll('[data-catalog-property]').forEach(field => {
    values[field.dataset.catalogProperty] = field.value;
  });
  return values;
}

/**
 * One row per property: whether it is currently visible, and whether it is native-`required` given
 * that it is. `requiredNow` here deliberately mirrors `catalogPropertyFieldsHtml`'s `nativeRequired`
 * (visible-gated, adapter-binding-gated) rather than the plain schema-level `isPropertyRequiredNow`
 * the `*` marker uses: this is compared, in `refreshConditionalCatalogProperties`, against a "before"
 * snapshot read from the DOM's own `required` attribute — the two sides of that comparison have to
 * mean the same thing, or an adapter-bound property (whose marker is schema-required but whose
 * attribute never is) would look like it "changed" on every unrelated edit and re-render for no
 * reason.
 */
function conditionalPropertyStates(descriptor, valuesByName) {
  return (descriptor?.properties || []).map(property => ({
    name: property.name,
    displayName: property.displayName,
    visible: isPropertyVisible(property, valuesByName),
    requiredNow: isPropertyRequiredNow(property, valuesByName) && isPropertyVisible(property, valuesByName)
      && !property.adapterBinding,
  }));
}

// One sentence per property whose visibility or required-ness actually changed between two
// `conditionalPropertyStates` snapshots — visibility changes take priority over a simultaneous
// required-ness change on the same property (a field that just appeared already states whether it is
// required in the same breath; announcing both separately would say the same field twice).
function describeConditionalChanges(before, after) {
  const parts = [];
  for (let i = 0; i < after.length; i += 1) {
    const was = before[i];
    const is = after[i];
    if (!was || was.name !== is.name) continue; // defensive: same descriptor, so this should not happen
    if (was.visible !== is.visible) {
      parts.push(`${is.displayName} is now ${is.visible ? (is.requiredNow ? 'visible and required' : 'visible') : 'hidden'}.`);
    } else if (is.visible && was.requiredNow !== is.requiredNow) {
      parts.push(`${is.displayName} is now ${is.requiredNow ? 'required' : 'optional'}.`);
    }
  }
  return parts.join(' ');
}

/**
 * Re-evaluates every property's `visibleWhen`/`requiredWhen` against the form's own CURRENT
 * values and re-renders `#catalog-properties` only if something actually changed — a keystroke in a
 * property nothing conditions on must not re-render or move focus at all. When something did change:
 *
 * - focus returns to the control that triggered the change, looked up by `data-catalog-property`
 * name after the re-render (its old DOM node was destroyed by the `innerHTML` replace, so a stale
 * element reference cannot be reused) — predictable over "helpful": the user acted on that control
 * and did not ask to be moved anywhere else, and for a screen-reader user an unrequested focus jump
 * reads as disorientation, not assistance.
 * - the change is announced through the app's one existing status channel (`announceGraph`/
 * `#graph-live`, `aria-live="polite"`), not a new region: a field appearing because the user chose
 * a mode is a status change, not an error, so it must not interrupt (`aria-live="assertive"` would);
 * and a second live region would just be two channels racing to describe one piece of UI.
 */
function refreshConditionalCatalogProperties(descriptor, owner = {}) {
  const container = document.getElementById('catalog-properties');
  if (!container) return;
  const activeProperty = document.activeElement?.dataset?.catalogProperty;
  // "Before" is read from what is ACTUALLY RENDERED right now — the `hidden`/`required` attributes
  // this same function (or the initial render) set last time — not recomputed from values, because by
  // the time a `change` event fires the DOM already holds the NEW value: there is no "old value" left
  // to recompute an old state from. Reading the DOM's own current attributes is what makes this
  // comparison correct rather than comparing a state against itself.
  const before = (descriptor?.properties || []).map(property => {
    const control = container.querySelector(`[data-catalog-property="${escapeAttribute(property.name)}"]`);
    const wrapper = control?.closest('.catalog-property');
    return {
      name: property.name,
      displayName: property.displayName,
      visible: wrapper ? !wrapper.hidden : true,
      requiredNow: control ? control.required : false,
    };
  });
  const currentValues = readCurrentCatalogPropertyValues(container);
  const after = conditionalPropertyStates(descriptor, currentValues);
  const changed = before.some((state, index) =>
    state.visible !== after[index].visible || state.requiredNow !== after[index].requiredNow);
  if (!changed) return;
  contextualHelp.dismiss();
  container.innerHTML = catalogPropertyFieldsHtml(descriptor, currentValues, owner);
  if (activeProperty) {
    container.querySelector(`[data-catalog-property="${escapeAttribute(activeProperty)}"]`)?.focus();
  }
  const message = describeConditionalChanges(before, after);
  if (message) announceGraph(message);
}

function readCatalogPropertyEditor(form) {
  const properties = {};
  const propertyTypes = {};
  form.querySelectorAll('[data-catalog-property]').forEach(field => {
    const name = field.dataset.catalogProperty;
    const value = field.value;
    if (value === '') return;
    properties[name] = value;
    propertyTypes[name] = catalogTypeToGraphMl(field.dataset.catalogType);
  });
  return { properties, propertyTypes };
}

function catalogTypeToGraphMl(type) {
  if (type === 'BOOLEAN') return 'boolean';
  if (type === 'INTEGER') return 'long';
  if (type === 'DECIMAL') return 'double';
  return 'string';
}

// ═══════════════════════════════════════════════════════════════
// EXECUTION BYPASS
// ═══════════════════════════════════════════════════════════════
//
// The switch that takes one node out of execution while the traversal continues past it. Same shape
// as the runtime-nature control below — a platform-owned node property whose name is DERIVED from
// `/v1/catalog` (`bypassProperty`; see `nodeTypeJson` in RavenrootServer.java and `./node-bypass.js`,
// which this rendering wraps) rather than hardcoded here.
//
// Two rules this control obeys that are not obvious from the flag itself:
//
// 1. It is offered on BEHAVIOR nodes only, INCLUDING behaviors the catalog does not know. That second
// half is where it parts company with the nature control, which requires a catalogued behavior:
// the motivating case is a node the deployment cannot provision, so refusing the switch on an
// uncatalogued behavior would refuse it exactly where it is needed. `NodeBypassValidator` agrees —
// it refuses the key by node KIND, never by catalog membership.
// 2. It states the routing consequence next to itself, not only in the documentation. A switched-off
// node always emits the default outcome, so its named branches stop being taken. That consequence
// must be legible while the author is switching the node off rather than afterwards.
// `untakenBypassOutcomes` names the actual branches of THIS node, because "some branches may not
// fire" would leave the author to go and count edges to find out which.

function bypassFieldHtml(descriptor, model, graph) {
  const propertyName = bypassPropertyName(descriptor, nodeTypeCatalog);
  const declared = declaredBypass(model.properties, propertyName);
  const help = 'The node does not run its behaviour. The traversal continues past it with the payload it received, unchanged. Everything downstream executes normally.';
  const title = `<div class="editor-section-title"><span>Execution</span>
    ${contextualHelpButtonHtml('Execution bypass', help)}</div>`;
  if (!nodeAcceptsBypass(model.kind)) {
    // The control must not merely be disabled here: `NodeBypassValidator` refuses the KEY on a
    // non-behavior node, `false` included, so an offered checkbox would make switching it back off
    // the very thing that breaks the graph. Nothing is rendered at all — unless the document already
    // carries the key, which is a graph the runtime will refuse to load and which saving this node
    // silently repairs. Saying so is the whole point: a silent repair of an invalid document is
    // indistinguishable, to the author, from the document having been fine.
    if (!declared.declared) return '';
    return `${title}
      <div class="node-bypass-fixed">
        <div class="bypass-refused" role="status">
          <strong>This node carries ${escapeHtml(propertyName)}.</strong> The runtime refuses that
          property on a ${escapeHtml(String(model.kind || 'non-behavior'))} node — even set to
          <code>false</code> — because there is no behaviour to skip, and will not load this graph.
          Saving this node removes the property.
        </div>
      </div>`;
  }
  const untaken = untakenBypassOutcomes(graph?.edges, model.id);
  const consequence = bypassRoutingConsequence(untaken);
  const stateText = declared.state === 'on'
    ? 'Switched off. This node does not execute.'
    : 'Executing normally.';
  const unreadable = declared.state === 'unreadable'
    ? `<div class="bypass-refused" role="status">
        <strong>The document declares ${escapeHtml(propertyName)} = ${escapeHtml(declared.raw)}.</strong>
        Only <code>true</code> and <code>false</code> are legal, so the runtime refuses to load this
        graph rather than guess. The box below shows what saving this node will write.
      </div>`
    : '';
  // The stable behavior explanation now lives behind the adjacent contextual-help button. The
  // dynamic `bypass-consequence` remains named in `aria-describedby` and is
  // `role="status"`/`aria-live="polite"`, so a screen-reader user hears the branch consequence the
  // moment the box is ticked, not only if they happen to tab past it.
  return `${title}
    <div class="editor-field full node-bypass">
      <label class="bypass-toggle" for="node-bypass-flag">
        <input type="checkbox" id="node-bypass-flag" name="executionBypass"
          data-bypass-property="${escapeAttribute(propertyName)}"
          aria-describedby="bypass-consequence"
          ${declared.state === 'on' ? 'checked' : ''}>
        <span>Bypass this node</span>
        <span class="bypass-state" data-bypass-state>${escapeHtml(stateText)}</span>
      </label>
      ${unreadable}
      <div id="bypass-consequence" class="bypass-consequence" role="status" aria-live="polite"
        data-bypass-consequence="${escapeAttribute(consequence)}"
        ${consequence && declared.state === 'on' ? '' : 'hidden'}>${escapeHtml(consequence)}</div>
    </div>`;
}

/** Re-renders `#node-bypass-section` against the CURRENT form state — the Kind select and the
 * catalog-type select both change whether the control may exist — and re-binds it. Reads `kind` off
 * the form rather than off `model`, because the author may have changed it without saving yet. */
function renderBypassSection(form, model) {
  contextualHelp.dismiss();
  const section = document.getElementById('node-bypass-section');
  if (!section) return;
  const behavior = String(form.elements.behavior?.value || model.behavior || '');
  const kind = String(form.elements.kind?.value || model.kind || '');
  section.innerHTML = bypassFieldHtml(catalogDescriptor(behavior), { ...model, kind }, graphData);
  bindBypassField();
}

/** Attaches the live-update listener for whichever `#node-bypass-flag` is currently in the DOM —
 * called after every render of `#node-bypass-section`, same convention as `bindNatureField`. */
function bindBypassField() {
  document.getElementById('node-bypass-flag')?.addEventListener('change', event => {
    const box = document.getElementById('bypass-consequence');
    const state = event.target.closest('.node-bypass')?.querySelector('[data-bypass-state]');
    if (state) {
      state.textContent = event.target.checked
        ? 'Switched off. This node does not execute.'
        : 'Executing normally.';
    }
    if (box) box.hidden = !(event.target.checked && box.dataset.bypassConsequence);
  });
}

/** Submit-time read of the bypass control. An unchecked box — and a form where the control was never
 * rendered, because the node is not a BEHAVIOR — returns no property at all: `properties` is a whole
 * replace (see the submit handler's own comment), so that is exactly how a previously-declared flag
 * gets dropped back to absent, and how a key illegal on this node kind gets removed rather than
 * carried forward into a graph the runtime would refuse.
 *
 * `'string'` as the GraphML type, matching `readNatureEditor` and every other property this editor
 * writes. `NodeBypassProperty.parse` accepts the string and the typed boolean alike precisely so the
 * flag does not depend on which exporter produced the document. */
function readBypassEditor(form) {
  const box = form.elements.namedItem('executionBypass');
  if (!box || !box.checked) return { properties: {}, propertyTypes: {} };
  const propertyName = box.dataset.bypassProperty || DEFAULT_BYPASS_PROPERTY;
  return { properties: { [propertyName]: BYPASS_TRUE }, propertyTypes: { [propertyName]: 'string' } };
}

// ═══════════════════════════════════════════════════════════════
// RUNTIME NATURE (ADR 0024 §2)
// ═══════════════════════════════════════════════════════════════
//
// There is deliberately no per-node inspection route: the effective nature — declared, or the
// descriptor's default — is computed client-side from the three fields `/v1/catalog` already ships
// (`defaultNature`, `allowedNatures`, `natureProperty`; see `nodeTypeJson` in RavenrootServer.java and
// `./node-nature.js`, which this rendering wraps). `allowedNatures` is already the fail-closed
// EFFECTIVE allowlist, so this control offering exactly those options — never the full
// `NodeRuntimeNature` vocabulary — is what keeps this from ever offering an escalation the catalog
// withheld: there is no server round trip to duplicate, because the allowlist IS the catalog payload.
//
// `NodeRuntimeNatureValidator` refuses a declaration on any node that is not a catalogued BEHAVIOR (a
// non-behavior kind, or a behavior name absent from the catalog) — see its javadoc. Those nodes get a
// fixed, uneditable readout rather than a control with nowhere legal to send its value: a reachable
// dropdown that the server would refuse on every option is worse than no dropdown, per this team's
// "offer only what the catalog permits" rule.

function natureFieldHtml(descriptor, model) {
  const help = 'Runtime nature controls this node\'s lifecycle — when a runtime instance exists and how many may be authoritative. It does not control concurrency or replica count.';
  const title = `<div class="editor-section-title"><span>Runtime nature</span>
    ${contextualHelpButtonHtml('Runtime nature', help)}</div>`;
  const declarable = model.kind === 'BEHAVIOR' && Boolean(descriptor);
  if (!declarable) {
    return `${title}
      <div class="node-nature-fixed">
        <span class="nature-value">${escapeHtml(natureLabel(DEFAULT_NATURE))}</span>
        <span class="nature-state">Fixed default · not configurable for this node.</span>
      </div>`;
  }
  const natureProperty = descriptor.natureProperty || DEFAULT_NATURE_PROPERTY;
  const declaredRaw = (model.properties || {})[natureProperty];
  const resolved = effectiveNature(descriptor, declaredRaw);
  const defaultIdentifier = descriptor.defaultNature || DEFAULT_NATURE;
  const allowed = Array.isArray(descriptor.allowedNatures) && descriptor.allowedNatures.length
    ? descriptor.allowedNatures : [defaultIdentifier];
  const options = allowed.map(identifier =>
    `<option value="${escapeAttribute(identifier)}" ${resolved.declared && resolved.value === identifier ? 'selected' : ''}>${escapeHtml(natureLabel(identifier))}</option>`).join('');
  const riskText = natureRiskText(resolved.value);
  const stateText = resolved.declared
    ? 'Declared on this node.'
    : `Inherited default (${escapeHtml(natureLabel(defaultIdentifier))}).`;
  // Stable lifecycle guidance is disclosed by the adjacent contextual-help button. The dynamic
  // deploy-refusal warning (`nature-risk`) remains named in `aria-describedby`, so the control and
  // the consequence that currently applies stay programmatically associated.
  return `${title}
    <div class="editor-field full node-nature">
      <label for="node-nature-select">Runtime nature <span class="nature-state" data-nature-state>${stateText}</span></label>
      <select id="node-nature-select" name="runtimeNature" data-nature-property="${escapeAttribute(natureProperty)}"
        data-default-nature="${escapeAttribute(defaultIdentifier)}"
        aria-describedby="nature-risk">
        <option value="" ${resolved.declared ? '' : 'selected'}>Inherit default (${escapeHtml(natureLabel(defaultIdentifier))})</option>
        ${options}
      </select>
      <div id="nature-risk" class="nature-risk" role="status" aria-live="polite"${riskText ? '' : ' hidden'}>${escapeHtml(riskText)}</div>
    </div>`;
}

/** Attaches the live-update listener for whichever `#node-nature-select` is currently in the DOM —
 * called once after every render of `#node-nature-section`, including the catalog-behavior switch. */
function bindNatureField() {
  document.getElementById('node-nature-select')?.addEventListener('change', event => updateNatureFieldState(event.target));
}

/**
 * Updates the declared/inherited badge and the AUTHORITY/KEYED deploy-refusal warning in place, on
 * every change — never a full re-render of `#node-nature-section`, so focus never leaves the select.
 * `nature-risk` is `role="status"`/`aria-live="polite"`, so a screen-reader user hears the warning
 * appear or clear the moment the value it concerns changes, not only when they happen to tab past it.
 */
function updateNatureFieldState(select) {
  const declared = select.value !== '';
  const value = declared ? select.value : select.dataset.defaultNature;
  const stateEl = select.closest('.node-nature')?.querySelector('[data-nature-state]');
  if (stateEl) {
    stateEl.textContent = declared ? 'Declared on this node.'
      : `Inherited default (${natureLabel(select.dataset.defaultNature)}).`;
  }
  const riskEl = document.getElementById('nature-risk');
  if (riskEl) {
    const text = natureRiskText(value);
    riskEl.textContent = text;
    riskEl.hidden = !text;
  }
}

/** Submit-time read of the nature control. An empty value ("Inherit default") returns no property at
 * all — `properties` is a whole replace (see the submit handler's own comment), so returning `{}` here
 * is exactly how a previously-declared nature gets dropped back to absent. */
function readNatureEditor(form) {
  const select = form.elements.namedItem('runtimeNature');
  if (!select || select.value === '') return { properties: {}, propertyTypes: {} };
  const natureProperty = select.dataset.natureProperty || DEFAULT_NATURE_PROPERTY;
  return { properties: { [natureProperty]: select.value }, propertyTypes: { [natureProperty]: 'string' } };
}

// Independent of runtime nature: this controls admission into one logical node within one traversal.
function maxConcurrencyFieldHtml(descriptor, model) {
  const declarable = model.kind === 'BEHAVIOR' && Boolean(descriptor);
  const resolved = declarable ? effectiveMaxConcurrency(descriptor,
    (model.properties || {})[descriptor.maxConcurrencyProperty || DEFAULT_MAX_CONCURRENCY_PROPERTY]) : null;
  if (!resolved) {
    return `<div class="editor-section-title"><span>Runtime concurrency</span>
        ${contextualHelpButtonHtml('Runtime concurrency', 'This catalog type does not authorize a graph-selectable runtime limit. Runtime concurrency is independent of runtime nature.')}</div>
      <div class="node-max-concurrency-fixed">Not configurable for this catalog type.</div>`;
  }
  const property = descriptor.maxConcurrencyProperty || DEFAULT_MAX_CONCURRENCY_PROPERTY;
  const raw = (model.properties || {})[property];
  const inputValue = raw == null ? '' : String(raw);
  const state = resolved.declared
    ? (resolved.valid ? `Declared (${resolved.value}).` : 'Declared value is invalid and will be refused.')
    : `Inherited default (${resolved.value}).`;
  const help = `Positive, per node and per traversal. The trusted catalog ceiling is ${resolved.ceiling}; stricter plugin or profile limits still apply.`;
  return `<div class="editor-section-title"><span>Runtime concurrency</span>
      ${contextualHelpButtonHtml('Runtime concurrency', help)}</div>
    <div class="editor-field full node-max-concurrency">
      <label for="node-max-concurrency">Maximum concurrent arrivals
        <span class="nature-state" data-max-concurrency-state>${escapeHtml(state)}</span></label>
      <input id="node-max-concurrency" name="runtimeMaxConcurrency" type="number" inputmode="numeric"
        min="1" max="${resolved.ceiling}" value="${escapeAttribute(inputValue)}"
        placeholder="Inherit default (${resolved.value})"
        data-max-concurrency-property="${escapeAttribute(property)}"
        data-default-max-concurrency="${resolved.value}" data-max-concurrency-ceiling="${resolved.ceiling}">
    </div>`;
}

function bindMaxConcurrencyField() {
  document.getElementById('node-max-concurrency')?.addEventListener('input', event => {
    const input = event.target;
    const state = input.closest('.node-max-concurrency')?.querySelector('[data-max-concurrency-state]');
    if (!state) return;
    state.textContent = input.value === ''
      ? `Inherited default (${input.dataset.defaultMaxConcurrency}).`
      : `Declared (${input.value}).`;
  });
}

function readMaxConcurrencyEditor(form) {
  const input = form.elements.namedItem('runtimeMaxConcurrency');
  if (!input || input.value === '') return { properties: {}, propertyTypes: {} };
  const property = input.dataset.maxConcurrencyProperty || DEFAULT_MAX_CONCURRENCY_PROPERTY;
  return { properties: { [property]: input.value }, propertyTypes: { [property]: 'long' } };
}

// ═══════════════════════════════════════════════════════════════
// KIND OF ARRIVAL
// ═══════════════════════════════════════════════════════════════
//
// A node with several incoming edges is a synchronisation point only where the author declared one.
// This dedicated control writes `joinPolicy`/`joinQuorum`/`joinTimeout`, so an author can declare a
// join without leaving the editor. It has the
// same shape as `natureFieldHtml` above: a node property with its own well-known vocabulary, not
// something the generic catalog-descriptor or free-form "Additional properties" machinery owns.

function describeEffectiveJoin(state) {
  if (!state.applicable) return '';
  if (state.kind === 'none') return 'Effective now: no join — each arrival runs independently.';
  if (state.kind === 'all') return `Effective now: waits for all ${state.branchCount} branches.`;
  if (state.kind === 'quorum' && state.quorum === 1) {
    return 'Effective now: first arrival wins, the rest are discarded.';
  }
  if (state.kind === 'quorum') return `Effective now: waits for ${state.quorum} of ${state.branchCount} branches.`;
  return '';
}

function joinFieldHtml(graph, model) {
  const state = effectiveJoinArrival(graph, model);
  const help = 'No join writes nothing to the document. A declared choice writes exactly one property. A join needs at least two distinct incoming edges; START arrivals are always external.';
  const title = `<div class="editor-section-title"><span>Kind of arrival</span>
    ${contextualHelpButtonHtml('Kind of arrival', help)}</div>`;
  if (!state.applicable) {
    const reason = model.kind === 'START'
      ? 'Not a join · START receives externally.'
      : `Not a join · ${state.branchCount} incoming edge${state.branchCount === 1 ? '' : 's'}; 2 required.`;
    return `${title}
      <div class="node-join-fixed"><span class="join-state">${escapeHtml(reason)}</span></div>`;
  }
  const declared = declaredJoinKind(model);
  // `declared.recognized === false` means the document declares something this
  // control's four-option vocabulary cannot represent -- an unmatched `joinPolicy` spelling, or a
  // `joinQuorum` that is not a positive integer. None of the four options is selected for it; a
  // fifth, unrepresented-value option is added instead (below) and IS selected, so the author sees
  // that something is declared and unrecognized rather than seeing "No join" for a document that
  // does not actually say that. See `readJoinEditor` for the submit-time half of this: leaving this
  // option selected preserves the raw value instead of materialising 'none' over it.
  const unrecognized = declared.recognized === false;
  // The conservative rule keeps the legacy each-stamp active when join.semantics is declared.
  // 'K of N' writes joinQuorum alone, never joinPolicy
  // (joinKindProperties) -- the one Kind-of-arrival choice that does not block serializeGraphML's
  // own each-stamp on a legacy state-machine fan-in, and the combination the engine then refuses to
  // load (JoinConfigurationException: "joinPolicy is each, so joinQuorum and joinTimeout cannot be
  // set"). Disabled here rather than left offerable and caught only at submit, so the author sees
  // why before choosing it, not after. `readJoinEditor` enforces the same refusal at the data layer
  // in case a stale DOM ever reaches submit with it selected regardless.
  const quorumCollides = quorumWouldCollideWithLegacyStamp(graph, model);
  const options = JOIN_KIND_OPTIONS.map(option =>
    `<option value="${option.value}" ${!unrecognized && option.value === declared.kind ? 'selected' : ''}`
    + `${option.value === 'quorum' && quorumCollides ? ' disabled' : ''}>${escapeHtml(option.label)}</option>`).join('')
    + (unrecognized
      ? `<option value="${JOIN_KIND_UNRECOGNIZED}" selected>Current value not recognized: ${escapeHtml(String(declared.raw))}</option>`
      : '');
  const quorumValue = declared.kind === 'quorum' && declared.quorum ? declared.quorum : Math.min(2, state.branchCount);
  const timeoutRaw = (model.properties || {})[JOIN_TIMEOUT_PROPERTY] || '';
  const quorumCollisionNote = quorumCollides
    ? `<small class="join-quorum-collision-note">"K of N" is unavailable on this legacy state-machine
        document: it would declare joinQuorum while save keeps stamping joinPolicy=each on this
        fan-in, a combination the engine refuses to load. Migrate this document to declared join
        semantics first (Edit menu), or choose "Wait for all branches" instead.</small>`
    : '';
  // The END terminal is precisely the node an author would never think to open, and
  // an undeclared fan-in into it returns a DIFFERENT result payload shape on different runs (measured
  // twice independently, 120 traversals each -- see JoinSemantics' class Javadoc).
  // This is the only place that consequence can be surfaced, so it is shown unconditionally whenever
  // it currently applies, not only when the author already suspects something is wrong.
  //
  // `each` -- explicit, or the
  // legacy-save-stamped one `wouldSaveStampEachJoinPolicy` reports under `source: 'each'` (see
  // effectiveJoinArrival) -- genuinely needs this warning: JoinSpec#defaultQuorum's javadoc records
  // the measured runtime behaviour directly -- WITHOUT a declared join, an END fan-in's two branches
  // both land, 200 runs out of 200; WITH `each`, exactly one lands, and which one varies -- and the
  // general shape is non-deterministic, as documented by that same Javadoc. But
  // `state.kind === 'none'` is not the same set as "source is 'undeclared' or
  // 'each'": `declaredJoinKind` ALSO resolves to 'none' for a REAL declaration this control's
  // four-option vocabulary cannot name -- an unrecognized `joinPolicy` spelling, or a bare
  // `joinTimeout` with no policy/quorum. Those declarations must be preserved rather than erased.
  // Probed against the core:
  // a bare `joinTimeout` is accepted and deterministic (quorum defaults to N of N); an unrecognized
  // `joinPolicy` is refused at load. Neither "varies between runs" nor "choose Wait for all branches"
  // is true of either, so `state.kind === 'none'` said so anyway. Gated on the two SOURCES directly --
  // `undeclared` and `each` -- which is exactly the set the javadoc's measurement covers, and excludes
  // `declared` (a real declaration exists, recognized or not) the same way it excludes `default-error`
  // and `legacy-inferred`'s deterministic `all`.
  const endWarning = model.kind === 'END' && state.applicable
    && (state.source === 'undeclared' || state.source === 'each')
    ? `<div class="join-end-warning" role="status">
        <strong>This END node has no declared join.</strong> With two or more branches converging here
        and nothing declared, the recorded result varies between runs — sometimes the merged list of
        every branch's payload, sometimes one branch's payload alone. Choose “Wait for all branches”
        for a consistent merged result.
      </div>`
    : '';
  return `${title}
    <div class="editor-field full node-join">
      <label for="node-join-kind">Kind of arrival <span class="join-state" data-join-state>${escapeHtml(describeEffectiveJoin(state))}</span></label>
      <select id="node-join-kind" name="joinKind" data-branch-count="${state.branchCount}">${options}</select>
      <div class="join-quorum-field" ${declared.kind === 'quorum' && !unrecognized ? '' : 'hidden'}>
        <label for="node-join-quorum">Branches required</label>
        <input id="node-join-quorum" name="joinQuorum" type="number" min="1" max="${state.branchCount}" value="${quorumValue}">
      </div>
      <div class="join-timeout-field" ${declared.kind === 'none' || unrecognized ? 'hidden' : ''}>
        <label for="node-join-timeout">Timeout (ISO-8601 duration, optional)</label>
        <input id="node-join-timeout" name="joinTimeout" placeholder="PT30S" value="${escapeAttribute(timeoutRaw)}">
      </div>
      ${quorumCollisionNote}
      ${endWarning}
    </div>`;
}

/** Attaches the live-update listener for whichever `#node-join-kind` is currently in the DOM --
 * called once after every render of `#node-join-section`, same convention as `bindNatureField`. */
function bindJoinField() {
  const select = document.getElementById('node-join-kind');
  if (!select) return;
  select.addEventListener('change', () => {
    const quorumField = document.querySelector('.join-quorum-field');
    const timeoutField = document.querySelector('.join-timeout-field');
    if (quorumField) quorumField.hidden = select.value !== 'quorum';
    if (timeoutField) timeoutField.hidden = select.value === 'none';
  });
}

/** The three join-only properties `node` currently carries, verbatim -- raw values, no
 * interpretation, no folding. Used to round-trip a declaration this control does not represent
 * instead of erasing it. */
function rawJoinDeclaration(node) {
  const properties = {};
  const propertyTypes = {};
  for (const name of [JOIN_POLICY_PROPERTY, JOIN_QUORUM_PROPERTY, JOIN_TIMEOUT_PROPERTY]) {
    const value = (node?.properties || {})[name];
    if (value == null || String(value).trim() === '') continue;
    properties[name] = value;
    propertyTypes[name] = (node?.propertyTypes || {})[name] || 'string';
  }
  return { properties, propertyTypes };
}

/** Submit-time read of the join control. 'none' returns no property at all -- `properties` is a whole
 * replace (see the submit handler's own comment), so this is exactly how a previously-declared join
 * gets dropped back to absent, and exactly how "the no-join entry writes nothing" holds.
 *
 * A bare `joinTimeout` with no policy/quorum and an emptied `joinQuorum` next to a `joinTimeout` both
 * read as kind 'none' by `declaredJoinKind`, same as a genuinely undeclared node, and both used to
 * disappear into `joinKindProperties('none', ...)`'s `{}` on any unrelated edit). Rather than add a
 * fifth special case, this compares what submitting the form right now would declare -- kind, quorum
 * text, timeout text -- against what `declaredJoinKind`/the raw properties already say. Unchanged in
 * all three respects: the raw declaration is returned untouched, whatever shape it is (empty,
 * unrecognized, `each`, a bare timeout, an unparseable quorum -- `rawJoinDeclaration` does not care
 * which). Only a REAL difference reaches `joinKindProperties`, which is the only thing that has ever
 * needed to materialise a document change. Subsumes, as the same one check: no `<select>` rendered at
 * all (`joinFieldHtml`'s `!state.applicable` branch -- START, or fewer than two distinct predecessors
 * -- `select` is null, `shownKind` cannot equal it, always "changed"... except the guard below returns
 * before that comparison ever runs, for the same reason a control that renders nothing has nothing to
 * compare against); `JOIN_KIND_UNRECOGNIZED` left selected; an explicit legacy `each` folded to 'none'
 * for display; a bare `joinTimeout`; an emptied `joinQuorum` beside a `joinTimeout`.
 */
function readJoinEditor(form, node, graph) {
  const raw = rawJoinDeclaration(node);
  const select = form.elements.namedItem('joinKind');
  if (!select) return raw;

  const declared = declaredJoinKind(node);
  const shownKind = declared.recognized === false ? JOIN_KIND_UNRECOGNIZED : declared.kind;
  const declaredQuorumText = declared.kind === 'quorum' && declared.quorum != null ? String(declared.quorum) : '';
  const declaredTimeoutRaw = (node?.properties || {})[JOIN_TIMEOUT_PROPERTY];
  const declaredTimeoutText = declaredTimeoutRaw != null ? String(declaredTimeoutRaw).trim() : '';

  const kind = select.value;
  const quorumInput = form.elements.namedItem('joinQuorum');
  const quorumText = quorumInput ? String(quorumInput.value ?? '').trim() : '';
  const timeoutInput = form.elements.namedItem('joinTimeout');
  const timeout = timeoutInput ? String(timeoutInput.value || '').trim() : '';

  const unchanged = kind === shownKind
    && (kind !== 'quorum' || quorumText === declaredQuorumText)
    && timeout === declaredTimeoutText;
  if (unchanged) return raw;

  // See `quorumWouldCollideWithLegacyStamp` and `joinFieldHtml`'s matching disabled option. This
  // control refuses to WRITE the one combination it can produce -- joinQuorum with no
  // joinPolicy -- that collides with serializeGraphML's legacy each-stamp on the very same save. The
  // option is already disabled in the UI; this is the same refusal enforced at the data layer, so a
  // stale DOM reaching here regardless still cannot produce the colliding document.
  if (kind === 'quorum' && quorumWouldCollideWithLegacyStamp(graph, node)) return raw;

  const properties = joinKindProperties(kind, quorumInput ? quorumInput.value : null);
  const propertyTypes = Object.fromEntries(Object.keys(properties).map(name => [name, 'string']));
  // Timeout only makes sense where a join is selected AND that join actually
  // materialised a property above: 'quorum' with no usable number writes nothing for the policy
  // (joinKindProperties), and writing a bare joinTimeout on top of that would leave the
  // document with a deadline and no coordinator to hold it for -- a shape JoinSpec rejects outright,
  // silently produced by this control otherwise. A node with no join has no coordinator either, and
  // JoinSpec rejects joinTimeout on an `each` node outright too.
  if (kind !== 'none' && timeout && Object.keys(properties).length > 0) {
    properties[JOIN_TIMEOUT_PROPERTY] = timeout;
    propertyTypes[JOIN_TIMEOUT_PROPERTY] = 'string';
  }
  return { properties, propertyTypes };
}

function programNodes(graph) {
  return (graph?.nodes || []).filter(node => node.kind === 'BEHAVIOR' && node.behavior === 'program');
}

function programReadiness(owner) {
  if (!owner.programReadiness) {
    owner.programReadiness = {
      phases: new Map(), inFlight: null, pendingGeneration: 0,
      generation: 0, signature: null, client: null, settledGeneration: 0,
      activeBuildId: null, activeRevision: 0,
      overlay: null, overlayPreviousInert: false,
    };
  }
  return owner.programReadiness;
}

function retireProgramReadiness(owner) {
  const state = owner?.programReadiness;
  if (!state) return;
  state.generation += 1;
  state.signature = null;
  state.client = null;
  state.pendingGeneration = 0;
  state.settledGeneration = 0;
  state.activeBuildId = null;
  state.activeRevision = 0;
  state.phases.clear();
  hideProgramReadinessOverlay(owner);
}

function programGraph(owner) {
  return owner === workspace.active ? graphData : owner?.graph;
}

function programResultDetail(result) {
  const identity = result.artifactId ? ` · Artifact ${result.artifactId}` : '';
  const reused = result.phase === 'READY' ? ` · ${result.reused ? 'server reused' : 'server built'}` : '';
  const diagnostic = result.diagnostic ? ` · ${result.diagnostic}` : '';
  return `${result.phase || 'Readiness request failed'}${identity}${reused}${diagnostic}`;
}

function programPhase(owner, nodeId, result) {
  const state = programReadiness(owner);
  const previous = state.phases.get(nodeId);
  const phase = result.phase || '';
  const detail = result.detail || programResultDetail({ ...result, phase });
  const history = [...(previous?.history || [])];
  const revision = Number(result.revision) || 0;
  if (phase && (!history.length || history.at(-1).phase !== phase
      || history.at(-1).revision !== revision || history.at(-1).detail !== detail)) {
    history.push({ phase, revision, detail, updatedAt: result.updatedAt || '' });
  }
  const snapshot = { ...result, phase, detail, output: result.smokeOutput, history };
  state.phases.set(nodeId, snapshot);
  const model = programGraph(owner)?.nodeMap?.[nodeId];
  if (model) {
    model.programPhase = phase;
    model.programReadinessState = snapshot;
  }
  const node = owner.cy?.getElementById(nodeId);
  if (node?.length) {
    node.data('programPhase', phase);
    node.removeStyle('border-color border-width underlay-color underlay-opacity underlay-padding label');
    const runtimeState = phase === 'READY' ? 'completed'
      : phase === 'FAILED' || phase === 'RETIRED' || result.transportError ? 'failed' : 'idle';
    node.data('runtimeState', runtimeState);
    applyRuntimeVisual(node);
    updateD3RuntimeNode(owner, nodeId, 0, runtimeState);
  }
  if (owner === workspace.active) {
    const panel = document.querySelector(`.program-workspace[data-node-id="${CSS.escape(nodeId)}"]`);
    if (panel) renderProgramPanelState(panel, snapshot);
    syncProgramReadinessChrome(owner);
  }
  syncProgramReadinessOverlay(owner);
}

function renderProgramPanelState(panel, state = {}) {
  if (!panel) return;
  const status = panel.querySelector('.program-status');
  const timeline = panel.querySelector('.program-timeline');
  if (status && state.detail) status.textContent = state.detail;
  if (timeline) {
    timeline.innerHTML = (state.history || []).map((entry, index, entries) => {
      const terminalFailure = entry.phase === 'FAILED' || entry.phase === 'RETIRED';
      const marker = terminalFailure ? 'failed' : index === entries.length - 1 && entry.phase !== 'READY'
        ? 'current' : 'complete';
      return `<li data-program-phase="${escapeAttribute(entry.phase)}" data-state="${marker}">`
        + `${escapeHtml(entry.phase)}${entry.revision ? ` · revision ${entry.revision}` : ''} · ${marker}</li>`;
    }).join('');
  }
  const output = panel.querySelector('.program-output');
  if (output) {
    const rawOutput = state.output === undefined || state.output === null
      ? '' : JSON.stringify(state.output);
    const boundedOutput = rawOutput.length > PROGRAM_OUTPUT_DISPLAY_LIMIT
      ? `${rawOutput.slice(0, PROGRAM_OUTPUT_DISPLAY_LIMIT)}… (output truncated)` : rawOutput;
    output.hidden = !rawOutput;
    output.textContent = rawOutput ? `Smoke output: ${boundedOutput}` : '';
  }
}

function ensureProgramReadinessChrome() {
  let button = document.getElementById('btn-program-approve');
  if (button) return;
  const controls = document.querySelector('.runtime-controls');
  if (!controls) return;
  button = document.createElement('button');
  button.className = 'btn';
  button.id = 'btn-program-approve';
  button.type = 'button';
  button.hidden = true;
  button.textContent = 'Approve program batch';
  button.addEventListener('click', () => {
    if (workspace.active) void approveProgramGraph(workspace.active);
  });
  const summary = document.createElement('span');
  summary.id = 'program-readiness-summary';
  summary.setAttribute('role', 'status');
  summary.setAttribute('aria-live', 'polite');
  summary.hidden = true;
  controls.append(button, summary);
}

function syncProgramReadinessChrome(owner = workspace.active) {
  ensureProgramReadinessChrome();
  const button = document.getElementById('btn-program-approve');
  const summary = document.getElementById('program-readiness-summary');
  if (!button || !summary) return;
  const nodes = programNodes(programGraph(owner));
  if (!owner || !nodes.length) {
    button.hidden = true;
    summary.hidden = true;
    return;
  }
  const phases = programReadiness(owner).phases;
  const values = nodes.map(node => phases.get(node.id));
  const ready = values.filter(value => value?.phase === 'READY').length;
  const approvals = values.filter(value => value?.phase === 'APPROVAL_REQUIRED').length;
  const failed = values.filter(value => value?.phase === 'FAILED' || value?.phase === 'RETIRED'
    || value?.transportError).length;
  const checking = nodes.length - ready - approvals - failed;
  summary.hidden = false;
  summary.textContent = `Programs ${ready}/${nodes.length} ready`
    + `${approvals ? ` · ${approvals} awaiting approval` : ''}`
    + `${failed ? ` · ${failed} failed` : ''}`
    + `${checking ? ` · ${checking} checking` : ''}`;
  button.hidden = approvals === 0;
  button.disabled = Boolean(programReadiness(owner).inFlight);
  button.setAttribute('aria-disabled', String(button.disabled));
}

function positionProgramReadinessOverlay(owner) {
  const overlay = programReadiness(owner).overlay;
  const wrap = document.getElementById('cy-wrap');
  if (!overlay || overlay.hidden || !owner.pane || !wrap) return;
  const pane = owner.pane.getBoundingClientRect();
  const host = wrap.getBoundingClientRect();
  overlay.style.left = `${Math.max(0, pane.left - host.left)}px`;
  overlay.style.top = `${Math.max(0, pane.top - host.top)}px`;
  overlay.style.width = `${Math.max(0, pane.width)}px`;
  overlay.style.height = `${Math.max(0, pane.height)}px`;
}

function ensureProgramReadinessOverlay(owner) {
  const state = programReadiness(owner);
  if (state.overlay?.isConnected) return state.overlay;
  const overlay = document.createElement('div');
  overlay.className = 'program-readiness-overlay';
  overlay.dataset.documentId = owner.id;
  overlay.setAttribute('role', 'status');
  overlay.setAttribute('aria-live', 'polite');
  overlay.setAttribute('aria-atomic', 'true');
  overlay.hidden = true;
  document.getElementById('cy-wrap')?.append(overlay);
  state.overlay = overlay;
  return overlay;
}

function syncProgramReadinessOverlay(owner) {
  const state = programReadiness(owner);
  const overlay = state.overlay;
  if (!overlay || overlay.hidden) return;
  const nodes = programNodes(programGraph(owner));
  const rows = nodes.map(node => {
    const phase = state.phases.get(node.id)?.phase || 'Awaiting server snapshot';
    return `<li><span>${escapeHtml(node.id)}</span><strong>${escapeHtml(phase)}</strong></li>`;
  }).join('');
  const settled = nodes.filter(node => Boolean(state.phases.get(node.id)?.phase)).length;
  overlay.innerHTML = `<div class="program-readiness-card"><div class="program-readiness-spinner" aria-hidden="true"></div>`
    + `<h3>Preparing program artifacts</h3><p>${settled}/${nodes.length} server phases received`
    + `${state.activeBuildId ? ` · build ${escapeHtml(state.activeBuildId)} · revision ${state.activeRevision}` : ''}</p>`
    + `<ul>${rows}</ul></div>`;
  positionProgramReadinessOverlay(owner);
}

function showProgramReadinessOverlay(owner) {
  const state = programReadiness(owner);
  const overlay = ensureProgramReadinessOverlay(owner);
  if (!overlay) return;
  state.overlayPreviousInert = Boolean(owner.pane?.inert);
  if (owner.pane) owner.pane.inert = true;
  overlay.hidden = false;
  overlay.setAttribute('aria-busy', 'true');
  syncProgramReadinessOverlay(owner);
}

function hideProgramReadinessOverlay(owner) {
  const state = programReadiness(owner);
  if (state.overlay) {
    state.overlay.hidden = true;
    state.overlay.removeAttribute('aria-busy');
  }
  if (owner.pane) owner.pane.inert = state.overlayPreviousInert;
}

function bindProgramArtifact(owner, node, artifactId) {
  node.properties ||= {};
  node.propertyTypes ||= {};
  node.properties.artifactId = artifactId;
  node.propertyTypes.artifactId = 'string';
  const rendered = owner.cy?.getElementById(node.id);
  if (rendered?.length) {
    rendered.data('properties', node.properties);
    rendered.data('propertyTypes', node.propertyTypes);
  }
}

function programBuildSubmission(node) {
  return {
    nodeId: String(node.id),
    language: String(node.properties?.language ?? ''),
    source: String(node.properties?.source ?? ''),
    testPayload: String(node.properties?.testPayload ?? PROGRAM_TEST_PAYLOAD_DEFAULT),
  };
}

function programBuildPlan(owner) {
  const nodes = programNodes(programGraph(owner));
  nodes.forEach(node => {
    node.properties ||= {};
    node.propertyTypes ||= {};
    if (node.properties.testPayload == null) {
      node.properties.testPayload = PROGRAM_TEST_PAYLOAD_DEFAULT;
      node.propertyTypes.testPayload = 'string';
    }
  });
  const programs = nodes.map(programBuildSubmission);
  return { nodes, programs, signature: JSON.stringify(programs) };
}

function resetProgramGeneration(owner, state, plan) {
  state.phases.clear();
  state.activeBuildId = null;
  state.activeRevision = 0;
  state.settledGeneration = 0;
  plan.nodes.forEach(model => {
    delete model.programPhase;
    delete model.programReadinessState;
    const node = owner.cy?.getElementById(model.id);
    if (!node?.length) return;
    node.removeData('programPhase');
    node.data('runtimeState', 'idle');
    applyRuntimeVisual(node);
    updateD3RuntimeNode(owner, model.id, 0, 'idle');
  });
}

function programGeneration(owner, state, plan) {
  if (state.signature !== plan.signature || state.client !== runtimeClient) {
    state.signature = plan.signature;
    state.client = runtimeClient;
    state.generation += 1;
    resetProgramGeneration(owner, state, plan);
  }
  return state.generation;
}

function applyProgramServerResult(owner, node, result) {
  if (!result || result.nodeId !== node.id) {
    programPhase(owner, node.id, {
      phase: '', ready: false, reused: false, transportError: true,
      diagnostic: `Build snapshot omitted a valid result for ${node.id}`,
    });
    return;
  }
  const previous = programReadiness(owner).phases.get(node.id);
  if (Number(result.revision) < Number(previous?.revision || 0)) return;
  if (result.artifactId) bindProgramArtifact(owner, node, result.artifactId);
  if (result.ready && !result.artifactId) {
    programPhase(owner, node.id, {
      ...result, phase: '', ready: false, transportError: true,
      diagnostic: `READY snapshot omitted artifact identity for ${node.id}`,
    });
    return;
  }
  programPhase(owner, node.id, result);
  if ((result.phase === 'FAILED' || result.phase === 'RETIRED') && owner === workspace.active
      && (previous?.phase !== result.phase || previous?.diagnostic !== result.diagnostic)) {
    addActivityMessage(`program build · ${node.id}`,
      `${result.phase}: ${result.diagnostic || 'the server supplied no diagnostic'}`, 'failed');
  }
}

function applyProgramBuildSnapshot(owner, plan, generation, snapshot) {
  const state = programReadiness(owner);
  if (state.generation !== generation || state.signature !== plan.signature) return false;
  if (state.activeBuildId && state.activeBuildId !== snapshot.buildId) {
    throw new Error(`Program build changed identity from ${state.activeBuildId} to ${snapshot.buildId}`);
  }
  if (snapshot.revision < state.activeRevision) return true;
  state.activeBuildId = snapshot.buildId;
  state.activeRevision = snapshot.revision;
  const byNode = new Map(snapshot.programs.map(result => [result.nodeId, result]));
  plan.nodes.forEach(node => applyProgramServerResult(owner, node, byNode.get(node.id)));
  syncProgramReadinessChrome(owner);
  syncProgramReadinessOverlay(owner);
  return true;
}

function programBuildPaused(snapshot) {
  return !snapshot.terminal && snapshot.programs.length > 0
    && snapshot.programs.every(program => program.terminal || program.phase === 'APPROVAL_REQUIRED');
}

function waitForProgramBuildPoll() {
  return new Promise(resolve => setTimeout(resolve, PROGRAM_BUILD_POLL_INTERVAL_MS));
}

async function observeProgramBuildSnapshots(client, initial, { current = () => true, onSnapshot } = {}) {
  let snapshot = initial;
  while (true) {
    if (!current()) return null;
    onSnapshot?.(snapshot);
    if (snapshot.terminal || programBuildPaused(snapshot)) return snapshot;
    await waitForProgramBuildPoll();
    if (!current()) return null;
    snapshot = await client.programArtifactBuild(snapshot.buildId);
  }
}

function selectFirstProgramFailure(owner, { skipDraftGuard = false } = {}) {
  if (owner !== workspace.active) return;
  const state = programReadiness(owner);
  const failed = programNodes(programGraph(owner)).find(node => {
    const phase = state.phases.get(node.id)?.phase;
    return phase === 'FAILED' || phase === 'RETIRED';
  });
  const rendered = failed && owner.cy?.getElementById(failed.id);
  if (!rendered?.length) return;
  if (!skipDraftGuard) {
    return runAfterInspectorDraft(() => selectFirstProgramFailure(owner, { skipDraftGuard: true }));
  }
  owner.cy.elements().unselect();
  rendered.select();
  showNodeInfo(rendered);
}

async function approveProgramGraph(owner) {
  const state = programReadiness(owner);
  if (state.inFlight) return state.inFlight.promise;
  const plan = programBuildPlan(owner);
  const generation = programGeneration(owner, state, plan);
  const waiting = programNodes(programGraph(owner)).filter(node =>
    state.phases.get(node.id)?.phase === 'APPROVAL_REQUIRED');
  const artifactIds = [...new Set(waiting.map(node => state.phases.get(node.id)?.artifactId).filter(Boolean))];
  if (!artifactIds.length || !state.activeBuildId) return false;
  const buildId = state.activeBuildId;
  state.settledGeneration = 0;
  return startProgramReadinessFlight(owner, plan, generation, {
    automatic: true,
    activity: 'program batch approval',
    start: async client => {
      await client.approveProgramArtifactBatch(
        artifactIds, 'Graph-level approval in Ravenroot UI',
      );
      return client.programArtifactBuild(buildId);
    },
  });
}

function startProgramReadinessFlight(owner, plan, generation, {
  automatic = false, activity = 'program batch build', start,
} = {}) {
  const state = programReadiness(owner);
  if (automatic) showProgramReadinessOverlay(owner);
  const client = runtimeClient;
  const isCurrent = () => workspace.find(owner.id) === owner && runtimeClient === client
    && state.generation === generation && state.signature === plan.signature;
  const promise = (async () => {
    const initial = await start(client);
    const settled = await observeProgramBuildSnapshots(client, initial, {
      current: isCurrent,
      onSnapshot: snapshot => applyProgramBuildSnapshot(owner, plan, generation, snapshot),
    });
    if (!settled || !isCurrent()) return false;
    state.settledGeneration = generation;
    return settled.terminal && settled.programs.every(program => program.ready && program.phase === 'READY');
  })().catch(error => {
    if (!isCurrent()) return false;
    plan.nodes.forEach(node => programPhase(owner, node.id, {
      phase: '', ready: false, reused: false, transportError: true,
      diagnostic: error.message, detail: `Readiness request failed · ${error.message}`,
    }));
    state.settledGeneration = generation;
    addActivityMessage(activity, error.message, 'failed');
    return false;
  }).finally(() => {
    if (state.inFlight?.generation === generation) state.inFlight = null;
    const newerGenerationPending = state.pendingGeneration && state.pendingGeneration !== generation;
    if (!newerGenerationPending) {
      state.pendingGeneration = 0;
      hideProgramReadinessOverlay(owner);
    }
    syncProgramReadinessChrome(owner);
    refreshCommands();
    selectFirstProgramFailure(owner);
  });
  state.pendingGeneration = 0;
  state.inFlight = { generation, promise };
  syncProgramReadinessChrome(owner);
  return promise;
}

async function ensureProgramGraphReady(owner, { automatic = false } = {}) {
  if (!owner || !runtimeClient) return false;
  const state = programReadiness(owner);
  const plan = programBuildPlan(owner);
  if (!plan.nodes.length) {
    syncProgramReadinessChrome(owner);
    return true;
  }
  const generation = programGeneration(owner, state, plan);
  if (state.inFlight) {
    if (state.inFlight.generation === generation) return state.inFlight.promise;
    state.pendingGeneration = generation;
    return state.inFlight.promise.then(() => ensureProgramGraphReady(owner, { automatic }));
  }
  if (state.settledGeneration === generation) {
    return plan.nodes.every(node => state.phases.get(node.id)?.phase === 'READY');
  }
  if (plan.programs.length > PROGRAM_BUILD_BATCH_LIMIT) {
    const message = `Program graph has ${plan.programs.length} nodes; one server build accepts at most ${PROGRAM_BUILD_BATCH_LIMIT}`;
    plan.nodes.forEach(node => programPhase(owner, node.id, {
      phase: '', ready: false, reused: false, transportError: true,
      diagnostic: message, detail: `Readiness request failed · ${message}`,
    }));
    state.settledGeneration = generation;
    syncProgramReadinessChrome(owner);
    return false;
  }
  return startProgramReadinessFlight(owner, plan, generation, {
    automatic,
    start: client => client.buildProgramArtifacts(plan.programs),
  });
}

function scheduleProgramGraphReadiness(owner) {
  if (!owner || !runtimeClient || !programNodes(programGraph(owner)).length) return;
  void ensureProgramGraphReady(owner, { automatic: true });
}

// `source` and `language` are stored as ordinary properties of THIS node, right next to
// `artifactId` -- not fetched from the server on reopen. That is a deliberate choice, not the
// default we'd have picked if either alternative were free:
//
// 1. A route that returns a created artifact's source was the first design considered and was
// rejected on evidence, not preference. ADR 0005 ("Generated program artifacts") states it in
// so many words: "source is accepted on creation but is never returned by list or lifecycle
// responses." `ArtifactRegistry#admitForExecution`'s javadoc makes the same point from the
// execution side: even `find(id)` -- already narrower than a hypothetical "get source" -- is
// documented as "a stale snapshot, not an admission", precisely because handing source out
// informally is the shape of bug this registry exists to close. Adding a GET-source endpoint
// would reopen, for READING, the exact surface those two documents describe closing for
// execution: an approved, immutable artifact's executable payload made reachable by an
// identifier alone, with no maker-checker separation on the read.
// 2. The runtime catalog now declares `language`, `source` and `testPayload` as the canonical program
// binding. This workspace renders those declared fields once, with the source-specific controls
// they need, while `programCatalogEditorDescriptor` prevents the generic editor duplicating them.
// 3. `language` is a
// real node property instead of client-only `<select>` state that a reload throws away.
//
// The corollary: on a legacy document, or on an artifact created by another
// author's document, THIS field is genuinely empty and the source is NOT retrievable from anywhere
// -- the network can no more supply it than this file could invent it. `bindProgramWorkspace`'s
// "artifact not available" branch has to say so plainly, not fall back to a starter that looks like
// an answer.
//
// The literal newline this function puts right after the Source textarea's opening
// tag, before the interpolated content, is not formatting -- it compensates for a real HTML
// parsing rule (WHATWG: a textarea's first token, if it is a U+000A LINE FEED, is dropped by the
// parser). Without it, a `storedSource` that itself starts with a newline -- which every reopened
// program node with a leading blank line has -- silently lost that one character on every render,
// before any JS ran at all: not a bug in this file's own logic, but in the literal HTML string it
// builds. Prepending one unconditional newline compensates exactly, because the parser eats
// exactly one leading LINE FEED and no more: whether `storedSource` starts with '\n' or not, the
// content that ends up in the DOM is `storedSource`, byte for byte. Programmatic `.value =`
// assignments elsewhere in this file (the starter fill, the language-switch replacement) are not
// subject to this rule at all -- it only fires while the browser parses literal HTML text, which
// is only this one render, never a later JS assignment.
function programWorkspaceContentHtml(descriptor, model) {
  if (descriptor?.behavior !== 'program') return '';
  const artifactId = model?.properties?.artifactId || '';
  const storedSource = model?.properties?.source || '';
  const testPayload = model?.properties?.testPayload ?? PROGRAM_TEST_PAYLOAD_DEFAULT;
  const artifactHelp = 'Source and language are saved in this node\'s own properties, next to Artifact ID — the server accepts source on creation but never returns it afterward (ADR 0005), so this document is the only place that can show it back to you. The local control API must be explicitly enabled.\n\nArtifacts are stored by the connected service. This document keeps source, language and test payload, and reuses a matching ACTIVE artifact after restart.';
  return `<div class="program-workspace" data-node-id="${escapeAttribute(model?.id || '')}"
      data-artifact-id="${escapeAttribute(artifactId)}">
    <div class="editor-section-title"><span>Governed artifact</span>
      ${contextualHelpButtonHtml('Governed artifact', artifactHelp)}</div>
    <div class="editor-field"><div class="editor-label-row"><label>Language</label>
      ${contextualHelpButtonHtml('Program language', 'Languages come from the connected runtime adapter. Changing language offers its starter example and never replaces non-empty source without confirmation.')}</div>
      <select class="program-language" data-catalog-property="language" data-catalog-type="STRING" required disabled></select>
      <small class="program-language-status">Loading the languages this runtime supports…</small>
    </div>
    <div class="editor-field full"><div class="editor-label-row"><label>Source</label>
      ${contextualHelpButtonHtml('Program source', 'Receives executionId, nodeId, payload and attributes and must return serializable data. Saved with this node on Save. Changing the language offers to replace this with that language\'s starter example; it only fills an empty field on its own, and replacing text you wrote always asks first.')}</div>
      <textarea class="program-source" data-catalog-property="source" data-catalog-type="TEXT"
        spellcheck="false" required>
${escapeHtml(storedSource)}</textarea>
    </div>
    <div class="editor-grid program-test-fields">
      <div class="editor-field full"><div class="editor-label-row"><label>Test payload</label>
        ${contextualHelpButtonHtml('Test payload', 'Saved with this node. The server interprets strict JSON as structured smoke input; all other text remains literal.')}</div><input class="program-test-payload"
        data-catalog-property="testPayload" data-catalog-type="TEXT"
        value="${escapeAttribute(testPayload)}"></div>
      <div class="editor-field full"><div class="editor-label-row"><label>Artifact ID</label>
        ${contextualHelpButtonHtml('Artifact ID', 'Server-provided audit reference. Source identity and lifecycle remain server-owned.')}</div><input class="program-artifact-id"
        data-catalog-property="artifactId" data-catalog-type="STRING" value="${escapeAttribute(artifactId)}"
        readonly></div>
    </div>
    <div class="program-lifecycle">
      <button class="btn primary program-build" type="button" data-program-operation="build"
        aria-disabled="true" data-tooltip-disabled="Loading the languages this runtime supports">${artifactId
          ? 'Rebuild' : 'Build'}</button>
    </div>
    <div class="program-status">${artifactId
      ? `Artifact ${escapeHtml(artifactId)} · readiness is checked graph-wide`
      : 'Not checked by the server yet.'}</div>
    <ol class="program-timeline" aria-label="Program build phases"></ol>
    <output class="program-output" hidden></output>
  </div>`;
}

function bindProgramWorkspace(form, model) {
  const panel = form.querySelector('.program-workspace');
  if (!panel) return;
  const setBusy = busy => panel.querySelectorAll('button').forEach(button => { button.disabled = busy; });
  const fail = error => {
    setBusy(false);
    const build = panel.querySelector('[data-program-operation="build"]');
    if (build) build.disabled = false;
    panel.querySelector('.program-status').textContent = error.message;
    addActivityMessage('program artifact', error.message, 'failed');
  };

  // The language selector is populated from the runtime's own declared catalog -- never
  // listed here -- so a third language becomes choosable the moment an adapter declares it, with
  // no change to this file. Create stays aria-disabled (see the workspace's initial markup) until
  // the catalog has loaded, because there is nothing legitimate to create before then.
  const languageSelect = panel.querySelector('.program-language');
  const languageStatus = panel.querySelector('.program-language-status');
  const sourceField = panel.querySelector('.program-source');
  const buildButton = panel.querySelector('[data-program-operation="build"]');
  let loadedLanguages = [];
  // The language this node's document already recorded (see programWorkspaceContentHtml),
  // read once, up front. `languageSelect.value` cannot carry this on the FIRST catalog resolution
  // -- the select is born empty and disabled, with no options to hold a value at all -- so without
  // this the stored language could never win the `defaultLanguageId` preference and would stay
  // open even after `language` started round-tripping through `properties`.
  const storedLanguage = model?.properties?.language || '';
  const applyLanguageCatalog = languages => {
    loadedLanguages = languages;
    if (!languages.length) {
      languageStatus.textContent = 'This runtime declares no program languages, so nothing can be '
        + 'created here yet.';
      return;
    }
    const preferred = languageSelect.value || storedLanguage;
    // A document can name a
    // language a CURRENT runtime no longer declares -- an adapter was removed or reconfigured
    // since this node was last saved. `defaultLanguageId` falls back to the first declared
    // language for exactly this case, which is correct as a CONTROL VALUE (the select needs some
    // real, usable option), but it must not stand in for the
    // author's declared choice SILENTLY: "if a language declared by the document is no longer among
    // those supported by the runtime, the author sees it instead of having it substituted." So the
    // substitution is computed exactly as before, and reported explicitly below instead of being
    // folded into the ordinary status line as if nothing happened.
    const preferredKnown = !preferred || languages.some(language => language.id === preferred);
    const selected = defaultLanguageId(languages, preferred);
    languageSelect.innerHTML = programLanguageOptionsHtml(languages, selected);
    languageSelect.value = selected;
    languageSelect.disabled = false;
    if (preferred && !preferredKnown) {
      languageStatus.textContent = `This document names the language "${preferred}", which this `
        + 'runtime’s programming adapter no longer declares support for. Showing '
        + `${selected} instead -- confirm or change the language before creating a new artifact `
        + 'from the source below.';
    } else {
      // This reports only what the ADAPTER declares -- GraalVmProgramRuntime
      // returns both descriptors unconditionally, without consulting the launcher. Executing one
      // (Validate onward) additionally needs an operator-configured sandbox supervisor, which is a
      // separate, unchecked precondition (SEC-11) -- this panel does not probe for it, and
      // must not claim readiness it has not observed.
      languageStatus.textContent = `${languages.length} runtime language${languages.length === 1 ? '' : 's'} available · `
        + 'execution also requires a configured sandbox supervisor.';
    }
    // This promise resolves whenever the network happens to answer -- typically well after
    // the author has already started typing, since a program node's whole workflow is "pick a
    // language, then write". An unconditional assignment here replaced whatever the author had
    // already typed the moment the
    // catalog arrived, mid-keystroke, with no error and no visible seam -- and Build pressed
    // afterward built an artifact from the starter, not from what the author wrote or believed
    // they were looking at. The starter is only ever allowed to FILL, never to OVERWRITE: it fires
    // once, only into a field that is still empty, whether that emptiness is "nobody has typed yet"
    // or "no document-stored source existed to seed it with" (programWorkspaceContentHtml). A
    // non-empty field -- typed live, or carried over from the document -- is never touched here.
    if (!sourceField.value.trim()) sourceField.value = exampleSourceForLanguage(languages, selected);
    buildButton.removeAttribute('aria-disabled');
    delete buildButton.dataset.tooltipDisabled;
  };
  languageSelect.addEventListener('change', () => {
    // A language switch is the one place the starter is still
    // allowed to replace text the author already has -- but only by asking, never by deciding for
    // them. `confirm` is synchronous, so the select already shows the NEW language by the time this
    // runs; declining leaves that selection in place and the source field untouched. That is a
    // deliberately allowed state (the visible language and the visible source may now describe two
    // different languages) rather than this handler silently reverting the author's own select
    // choice, which would be a second decision made on their behalf in the same breath as the first.
    if (sourceField.value.trim() && !globalThis.confirm(
      'Replace the current source with the starter example for this language? What you have written will be lost.',
    )) return;
    sourceField.value = exampleSourceForLanguage(loadedLanguages, languageSelect.value);
  });
  if (!runtimeClient) connectRuntime();
  runtimeClient.programLanguages().then(applyLanguageCatalog).catch(error => {
    languageStatus.textContent = `Could not load the languages this runtime supports: ${error.message}`;
    addActivityMessage('program artifact', error.message, 'failed');
  });

  panel.querySelector('[data-program-operation="build"]').addEventListener('click', async () => {
    setBusy(true);
    try {
      if (!runtimeClient) connectRuntime();
      const draft = {
        ...model,
        properties: {
          ...(model?.properties || {}),
          language: languageSelect.value,
          source: sourceField.value,
          testPayload: panel.querySelector('.program-test-payload').value,
          artifactId: panel.dataset.artifactId || model?.properties?.artifactId || '',
        },
        propertyTypes: { ...(model?.propertyTypes || {}) },
      };
      const client = runtimeClient;
      const started = await client.buildProgramArtifacts([programBuildSubmission(draft)]);
      const settled = await observeProgramBuildSnapshots(client, started, {
        current: () => runtimeClient === client && panel.isConnected,
        onSnapshot: snapshot => {
          const result = snapshot.programs.find(program => program.nodeId === draft.id);
          if (result) applyProgramBuildResult(form, panel, result);
        },
      });
      const result = settled?.programs.find(program => program.nodeId === draft.id);
      if (result) {
        addActivityMessage('program build', `${draft.id} · ${result.phase.toLowerCase()}`,
          result.ready ? 'completed' : result.phase === 'APPROVAL_REQUIRED' ? 'running' : 'failed');
      }
    } catch (error) { fail(error); }
    finally { setBusy(false); }
  });
  const ownerState = workspace.active && programReadiness(workspace.active).phases.get(model?.id);
  renderProgramPanelState(panel, ownerState);
}

function applyProgramBuildResult(form, panel, result) {
  if (result.artifactId) {
    panel.dataset.artifactId = result.artifactId;
    const artifactField = form.querySelector('[data-catalog-property="artifactId"]');
    if (artifactField) artifactField.value = result.artifactId;
  }
  const previous = panel.programBuildState;
  const detail = programResultDetail(result);
  const history = [...(previous?.history || [])];
  if (!history.length || history.at(-1).phase !== result.phase
      || history.at(-1).revision !== result.revision || history.at(-1).detail !== detail) {
    history.push({ phase: result.phase, revision: result.revision, detail, updatedAt: result.updatedAt });
  }
  panel.programBuildState = { ...result, detail, output: result.smokeOutput, history };
  renderProgramPanelState(panel, panel.programBuildState);
  const build = panel.querySelector('[data-program-operation="build"]');
  if (build && result.artifactId) build.textContent = 'Rebuild';
}

function renderEdgeForm(model, creating) {
  contextualHelp.dismiss();
  const extras = additionalProperties(model, 'edge');
  const commandOptions = [...new Set(nodeTypeCatalog.flatMap(type => type.commands || []))]
    .map(command => String(command).trim().toLowerCase()).filter(Boolean).sort();
  const nodeOptions = graphData.nodes.map(node =>
    `<option value="${escapeAttribute(node.id)}">${escapeHtml(node.name)} · ${escapeHtml(node.id)}</option>`).join('');
  const declared = edgeDeclaresFailureRoute(model);
  const failureRoute = Boolean(edgeFailureRouteKind(model, graphData));
  document.getElementById('info-body').innerHTML = `
    <form id="edge-editor" class="editor-form" data-tooltip-exempt="persistent-form-labels">
      <div class="editor-grid">
        <div class="editor-field full"><label>ID</label><input name="id" value="${escapeAttribute(model.id)}" ${creating ? '' : 'readonly'} required></div>
        <div class="editor-field"><label>Source</label><select name="source">${nodeOptions}</select></div>
        <div class="editor-field"><label>Target</label><select name="target">${nodeOptions}</select></div>
        <div class="editor-field full" data-edge-kind><div class="editor-label-row edge-kind-row">
          <p class="edge-kind" id="edge-kind-state" role="status" aria-live="polite"></p>
          ${contextualHelpButtonHtml('Edge routing', 'An edge is either an outcome edge or a failure route, never both. Outcome edges match a value returned by the source node. Failure routes run when the source throws; an unnamed edge into an Error node is a failure route by default.')}</div>
          <label data-failure-route-control><input name="failureRoute" type="checkbox" ${declared ? 'checked' : ''}> Failure route</label></div>
        <div class="editor-field full"><label>Outcome</label><input name="outcome" list="edge-outcome-options" value="${escapeAttribute(model.outcome || DEFAULT_EDGE_OUTCOME)}" required ${failureRoute ? 'readonly aria-describedby="edge-kind-state"' : ''}><small id="edge-outcome-hint"></small><p class="edge-outcome-warning" id="edge-outcome-warning" role="status" aria-live="polite" hidden></p></div>
        <datalist id="edge-outcome-options"></datalist>
        <div class="editor-field full"><div class="editor-label-row"><label>Command</label>
          ${contextualHelpButtonHtml('Edge command', 'Optional command delivered to the target node. Values are lowercase.')}</div>
          <input name="command" list="edge-command-options" value="${escapeAttribute(model.command || '')}" placeholder="passthrough"></div>
        <datalist id="edge-command-options">${commandOptions.map(command => `<option value="${escapeAttribute(command)}"></option>`).join('')}</datalist>
        <div class="editor-field full"><label>Name</label><input name="edgeName" value="${escapeAttribute(model.edgeName || '')}"></div>
        <div class="editor-field"><label>Status</label><input name="status" type="number" value="${Number(model.status) || 0}"></div>
        <div class="editor-field"><label>Traffic weight</label><input name="trafficWeight" type="number" step="any" value="${model.trafficWeight ?? ''}"></div>
        <div class="editor-field full"><label><input name="parallel" type="checkbox" ${model.parallel ? 'checked' : ''}> Parallel branch metadata</label></div>
        <div class="editor-field full"><label>Description</label><textarea name="description">${escapeHtml(model.description || '')}</textarea></div>
      </div>
      ${propertyEditorHtml('edge-properties', extras)}
      <div id="edge-validation" class="editor-validation" role="status" aria-live="polite"></div>
      <div class="editor-actions">
        ${creating ? '' : '<button class="btn danger" type="button" id="delete-edge">Delete</button>'}
        <button class="btn primary" type="submit">${creating ? 'Add edge' : 'Save edge'}</button>
      </div>
    </form>`;
  const form = document.getElementById('edge-editor');
  form.elements.source.value = model.source || graphData.nodes[0]?.id || '';
  form.elements.target.value = model.target || graphData.nodes[1]?.id || graphData.nodes[0]?.id || '';

  // There are three states an edge can be in. The Inspector STATES which one rather than leaving it
  // to be inferred from a name, so the panel always carries a sentence naming it.
  //
  // The checkbox is NOT shown for every edge. Where the target is an `ERROR` node the default
  // already decides: an edge that declares no outcome is a failure route, and the only way to make
  // it an outcome edge is to give it an outcome — which is what the Outcome field is. A checkbox
  // there would be a second control for a fact the Outcome field already governs, and unticking it
  // could not do anything the default would not immediately undo. The control appears where the
  // default cannot reach: a target that is not an `ERROR` node.
  //
  // Outcome is held at the default while the edge is a failure route, in both kinds. `readonly`,
  // not `disabled`: a disabled input is omitted from FormData, so the submit handler below would
  // read no outcome at all and the required-field check would never fire. readonly also stays
  // focusable and reachable by a screen reader, which `disabled` is not. The one asymmetry is who
  // may lift it — for an ERROR target the field stays editable, because typing an outcome there is
  // precisely how an author overrides the default, and locking it would make the override
  // unreachable.
  const failureRouteBox = form.elements.failureRoute;
  const outcomeField = form.elements.outcome;
  const kindState = document.getElementById('edge-kind-state');
  const controlRow = form.querySelector('[data-failure-route-control]');
  // Whether the Outcome suggestions are currently suppressed. `null` rather than `false` so the
  // first coupling pass always reconciles them, whichever state the form opened in.
  let outcomeSuggestionsInert = null;
  let restorableOutcome = failureRoute
    ? DEFAULT_EDGE_OUTCOME
    : String(model.outcome || DEFAULT_EDGE_OUTCOME);
  function targetIsErrorNode() {
    return graphData.nodeMap?.[String(form.elements.target.value || '')]?.kind === 'ERROR';
  }
  function applyFailureRouteCoupling() {
    const errorTarget = targetIsErrorNode();
    // The checkbox is meaningless against an ERROR target, and a stale tick left over from before
    // the target changed would silently re-enter the model on submit.
    controlRow.hidden = errorTarget;
    const clearedByTarget = errorTarget && failureRouteBox.checked;
    if (errorTarget) failureRouteBox.checked = false;
    const explicitOutcome = errorTarget
      && String(outcomeField.value || DEFAULT_EDGE_OUTCOME).trim() !== DEFAULT_EDGE_OUTCOME;
    const isFailureRoute = errorTarget ? !explicitOutcome : failureRouteBox.checked;

    if (failureRouteBox.checked && !outcomeField.readOnly) {
      restorableOutcome = String(outcomeField.value || DEFAULT_EDGE_OUTCOME);
    }
    if (failureRouteBox.checked) {
      outcomeField.value = DEFAULT_EDGE_OUTCOME;
      outcomeField.readOnly = true;
      outcomeField.setAttribute('aria-describedby', 'edge-kind-state');
    } else if (outcomeField.readOnly) {
      outcomeField.readOnly = false;
      outcomeField.removeAttribute('aria-describedby');
      // Handing the parked outcome back is right when the USER unticks the box, and wrong when the
      // tick was cleared because the target became an `ERROR` node: the author asked for a failure
      // route and still has one, implicitly. Restoring here would give them an outcome edge named
      // whatever they had typed before, which is the opposite of what they last asked for.
      if (!clearedByTarget) outcomeField.value = restorableOutcome;
    }

    // The suggestions depend on whether this field is inert, so they are rebuilt when that flips
    // -- and ONLY when it flips. This function runs on every keystroke (revalidateEdgeForm calls
    // it), while refreshOutcomeOptions scans graphData.nodes and rebuilds a datalist; calling it
    // unconditionally here would turn a per-source-change cost into a per-character one on a
    // function that was written for the former.
    if (outcomeSuggestionsInert !== outcomeField.readOnly) {
      outcomeSuggestionsInert = outcomeField.readOnly;
      refreshOutcomeOptions();
    }

    kindState.dataset.edgeKind = isFailureRoute
      ? (errorTarget ? 'failure-implicit' : 'failure-declared')
      : 'outcome';
    kindState.textContent = isFailureRoute
      ? (errorTarget
        ? 'Failure route · Error target; an unnamed outcome carries failure. Name an outcome for an outcome edge.'
        : `Failure route · runs when the source throws; Outcome stays ${DEFAULT_EDGE_OUTCOME}.`)
      : (errorTarget
        ? `Outcome edge · fires on ${outcomeField.value || DEFAULT_EDGE_OUTCOME}, not failure; explicit outcome overrides the Error-target default.`
        : `Outcome edge · fires on ${outcomeField.value || DEFAULT_EDGE_OUTCOME}, not failure.`);
  }
  failureRouteBox.addEventListener('change', () => {
    applyFailureRouteCoupling();
    revalidateEdgeForm();
  });

  // Immediate validation: the ID, the two references and the resulting topology are
  // checked on every keystroke and every selection, not only when Save is pressed. The reason is
  // shown next to the form and the submit button is disabled while the edge would be invalid, so
  // the refusal arrives while the user is still looking at the field that caused it.
  const feedback = document.getElementById('edge-validation');
  const submit = form.querySelector('button[type="submit"]');

  // The outcome suggestions belong to the SOURCE node, because the outcome is what that node
  // produces, and among a source's non-failure-route edges the outcome string is the only thing
  // `nextEdges` matches on. They are therefore rebuilt every time Source changes, unlike the Command
  // datalist above, which is catalog-wide and built once.
  //
  // Suggestions only. The field stays free text and nothing below marks a value invalid for being
  // absent from the list: an outcome property can be edited after the edge is drawn, and a runner that
  // matches no edge retries with 'continue', so "not in the list" is not "cannot fire".
  const outcomeList = document.getElementById('edge-outcome-options');
  const outcomeHint = document.getElementById('edge-outcome-hint');
  function refreshOutcomeOptions() {
    // While the edge is a DECLARED failure route the Outcome field is readonly and
    // parked at the default, so the suggestions below describe a control the author cannot use.
    // Offering them there is not merely useless: it falsely says this failure-route edge selects on
    // the source node's outcomes. The datalist is emptied with the
    // hint, because a datalist on a readonly input still opens on some browsers.
    //
    // Read through `form` rather than the `outcomeField` const declared higher up in
    // renderEdgeForm, though the two are the same element:
    // edge-outcome-suggestions.test.js runs THIS function's source text with a
    // fixed set of injected names, and `outcomeField` is not one of them. Reaching
    // for it makes that suite fail with a ReferenceError, which is how this line was first written.
    if (form.elements.outcome.readOnly) {
      outcomeList.innerHTML = '';
      if (outcomeHint) {
        outcomeHint.textContent = 'A failure route carries this node\'s failure, not one of its'
          + ' outcomes, so it selects on none of them.';
      }
      return;
    }
    const source = graphData.nodes.find(node => node.id === String(form.elements.source.value || ''));
    const resolved = source
      ? resolveOutcomes(catalogDescriptor(source.behavior), source.properties)
      : [];
    outcomeList.innerHTML = resolved.map(entry =>
      `<option value="${escapeAttribute(entry.outcome)}">${escapeHtml(entry.description)}</option>`).join('');
    if (!outcomeHint) return;
    if (!resolved.length) {
      // Said plainly rather than left blank: an empty list here means the catalog entry declares no
      // outcomes, not that the node produces none.
      outcomeHint.textContent = source
        ? `${source.name || source.id} declares no outcomes in the catalog.`
        : 'Select a source node to see the outcomes it can produce.';
      return;
    }
    const named = resolved.map(entry => entry.outcome).join(', ');
    const parameterized = resolved.filter(entry => entry.parameterized);
    outcomeHint.textContent = parameterized.length
      // Naming the property is the point: these values are the author's own, read out of the source
      // node, so the way to change a suggestion is to edit that node rather than to retype this field.
      ? `${source.name || source.id} produces ${named} — from its `
        + `${parameterized.map(entry => entry.property).join(' and ')}.`
      : `${source.name || source.id} produces ${named}.`;
  }

  // The suggestions above say what the source CAN emit; this says when the
  // outcome actually typed is not among them. It is deliberately a WARNING and not a verdict: it does
  // not touch `submit.disabled` and adds no entry to `validateWorkflow`, so the author can always
  // save. That is not timidity, it is the shape of the claim -- `unreachableOutcome` is exact only
  // where the catalog declares outcomes read verbatim, and an author who renames a source node's
  // `trueOutcome` after drawing the edge is momentarily in a state this correctly flags and that they
  // are in the process of reconciling. Blocking the save would prevent them from completing that
  // edit.
  //
  // Amber, like `.nature-risk` and `.join-end-warning` in styles.css: the editor's established mark
  // for something to read before deploying rather than something refused. Separate from
  // `#edge-validation`, which is red and DOES gate the save; putting an advisory sentence in that box
  // would make the two indistinguishable and, over time, make the red one ignorable too.
  const outcomeWarning = document.getElementById('edge-outcome-warning');
  /**
   * Writes the warning region ONLY when what it says actually changes -- the same guard the assistant
   * banner and the run progress line already use in this file, for the same reason: a polite live
   * region re-announces on every write.
   *
   * This is load-bearing here and not a micro-optimisation. `revalidateEdgeForm` runs on every
   * keystroke, so without the guard `textContent` is reassigned once per character even while the
   * sentence is byte-identical -- measured with a MutationObserver in Chromium: six mutations for six
   * keys. Keeping the sentence stable (it never quotes the typed value) is only half the job; whether
   * an unchanged-but-rewritten region is announced once or six times is then up to the screen
   * reader's own deduplication, which is not a property to rest on. `hidden` is guarded too so that
   * the steady state produces no attribute mutation either.
   */
  function setOutcomeWarning(text) {
    if (outcomeWarning.textContent !== text) outcomeWarning.textContent = text;
    const hide = text === '';
    if (outcomeWarning.hidden !== hide) outcomeWarning.hidden = hide;
  }
  function refreshOutcomeWarning() {
    if (!outcomeWarning) return;
    // A declared failure route carries the node's FAILURE, not one of its outcomes, and its field is
    // parked readonly at the default -- /. There is no outcome to be unreachable.
    if (form.elements.outcome.readOnly) {
      setOutcomeWarning('');
      return;
    }
    const source = graphData.nodes.find(node => node.id === String(form.elements.source.value || ''));
    const resolved = source
      ? resolveOutcomes(catalogDescriptor(source.behavior), source.properties)
      : [];
    if (!unreachableOutcome(resolved, form.elements.outcome.value)) {
      setOutcomeWarning('');
      return;
    }
    // Two things this sentence deliberately does NOT do. It does not quote the typed value back, so
    // it stays byte-identical while the author keeps typing an unknown outcome — which is what makes
    // `setOutcomeWarning`'s guard able to collapse a whole word into one announcement rather than
    // one per key. And it does not list the outcomes the source produces, because
    // `#edge-outcome-hint` directly above has just listed them — naming them twice in adjacent
    // sentences reads as a stutter and pushes the part the author does not already know, the
    // consequence, to the end.
    //
    // The consequence is the whole content: `nextEdges` matches on the outcome string alone, so an
    // outcome the source cannot produce selects this edge on no run. The 'continue' retry cannot
    // rescue it either — that returns edges wired to 'continue', and this one is not, which is the
    // exemption above.
    setOutcomeWarning(`${source.name || source.id} cannot emit this outcome,`
      + ' so this edge will never be taken.');
  }

  function revalidateEdgeForm() {
    const id = String(form.elements.id.value ?? '');
    const source = String(form.elements.source.value || '');
    const target = String(form.elements.target.value || '');
    const idCheck = validateEdgeId(graphData, id, { existingId: creating ? null : model.id });
    const linkCheck = validateEdgeConnection(graphData, {
      source, target, edgeId: creating ? null : model.id,
    });
    const verdict = idCheck.ok ? linkCheck : idCheck;
    // The kind sentence is recomputed HERE as well as on the checkbox, because the Target
    // select decides it too -- dragging this edge onto an Error node makes it a failure route with
    // nothing else touched, and a panel still saying "outcome edge" would be stating the opposite of
    // what the canvas is about to draw.
    applyFailureRouteCoupling();
    // A failure route has no outcome to report: saying "outcome continue" in the one line that
    // confirms what was drawn would repeat exactly the thing the panel above it just denied.
    feedback.textContent = verdict.ok
      ? (kindState.dataset.edgeKind === 'outcome'
        ? `From ${source} to ${target}, outcome ${outcomeField.value || DEFAULT_EDGE_OUTCOME}.`
        : `From ${source} to ${target} on failure.`)
      : verdict.reason;
    feedback.classList.toggle('invalid', !verdict.ok);
    if (submit) submit.disabled = !verdict.ok;
    // After applyFailureRouteCoupling above, which is what decides whether the Outcome field is
    // readonly -- and therefore whether there is an outcome to judge at all.
    refreshOutcomeWarning();
    return verdict;
  }
  form.addEventListener('input', revalidateEdgeForm);
  form.addEventListener('change', revalidateEdgeForm);
  form.elements.source.addEventListener('change', refreshOutcomeOptions);
  refreshOutcomeOptions();
  revalidateEdgeForm();

  form.addEventListener('submit', event => {
    event.preventDefault();
    if (layoutBusy) return showFormError(form, 'Layout in progress');
    if (!modifyEnabled || !canModifyGraph(graphData, layoutMode)) return showFormError(form, 'Modify mode is OFF');
    const values = new FormData(form);
    // Stable edge identity is exact across inspector, GraphML, runtime events and D3. Do not trim:
    // leading/trailing whitespace is significant and validated without normalization.
    const id = String(values.get('id') || '');
    if (creating && graphData.edges.some(edge => edge.id === id)) return showFormError(form, `Edge ID ${id} already exists`);
    const custom = readPropertyEditor(form);
    // When the checkbox governs, it is the authority on BOTH halves of the pair, so the
    // outcome is forced back to the default here as well as held there in the field: a form can be
    // submitted by Enter from another field, and the two states must not be able to disagree on the
    // way to the model.
    //
    // When it does NOT govern -- an `ERROR` target, where the implicit failure-route default decides -- an explicit
    // declaration already on the edge has to be re-applied by hand or it is lost:
    // `readPropertyEditor` rebuilds the bag from the visible rows, and `failure.route` deliberately
    // has no row. It is re-applied only while the edge is still a failure route, though. Naming an
    // outcome against an `ERROR` target IS how an author overrides the default, and carrying the
    // declaration past that override would hand the engine `failure.route` together with an
    // explicit outcome -- the one combination it refuses AT LOAD. That would move the error from
    // the drawing to the run, introduced by the code intended to preserve the author's declaration.
    const boxGoverns = !controlRow.hidden;
    const boxTicked = boxGoverns && values.get('failureRoute') === 'on';
    const outcome = boxTicked
      ? DEFAULT_EDGE_OUTCOME
      : (String(values.get('outcome') || DEFAULT_EDGE_OUTCOME).trim() || DEFAULT_EDGE_OUTCOME);
    const declaresFailureRoute = boxGoverns
      ? boxTicked
      : edgeDeclaresFailureRoute(model) && outcome === DEFAULT_EDGE_OUTCOME;
    setEdgeFailureRoute(custom, declaresFailureRoute);
    const patch = {
      source: String(values.get('source')),
      target: String(values.get('target')),
      outcome,
      command: String(values.get('command') || '').trim().toLowerCase(),
      label: outcome,
      // Was an inline 'continue'/'default' split that didn't recognize 'failed' or
      // 'completed', so an edge authored or edited through this form here didn't pick up the
      // renderer's red-dashed/green style until a save-and-reload round trip re-parsed it.
      // The failure classification is deliberately NOT repeated here: `classifyFailureRoutes` runs
      // over the whole document inside `buildElements` on the rebuild below, and it is the only
      // place that can see the target node's kind. Computing it twice is how the two answers
      // eventually disagree.
      edgeType: outcomeToEdgeType(outcome),
      edgeName: String(values.get('edgeName') || '').trim(),
      status: Number(values.get('status')) || 0,
      trafficWeight: values.get('trafficWeight') === '' ? null : Number(values.get('trafficWeight')),
      parallel: values.get('parallel') === 'on',
      description: String(values.get('description') || '').trim(),
      properties: custom.properties,
      propertyTypes: custom.propertyTypes,
    };
    if (creating) {
      const created = createEdge(id, patch.source, patch.target, outcome);
      Object.assign(created, patch);
      insertEdgeElement(graphData, created, editHistory);
    } else if (!updateEdgeFields(graphData, model.id, patch, editHistory)) {
      return showFormError(form, 'This edge is no longer part of the document');
    }
    rebuildGraph();
    updateHistoryUi();
    showEdgeInfo(cy.getElementById(id));
  });
  document.getElementById('delete-edge')?.addEventListener('click', () => {
    if (!modifyEnabled || !canModifyGraph(graphData, layoutMode)) return;
    deleteElements(graphData, [], [model.id], editHistory);
    rebuildGraph();
    updateHistoryUi();
    closeInfo();
  });
}

function propertyEditorHtml(id, properties) {
  return `<div class="editor-section-title"><span>Additional properties</span>
      <button class="property-add" type="button" data-add-property="${escapeAttribute(id)}">＋ Add property</button></div>
    <div id="${id}" class="property-editor">${properties.map(propertyRowHtml).join('')}</div>`;
}

function propertyRowHtml(property = { name: '', type: 'string', value: '' }) {
  const types = ['string', 'boolean', 'int', 'long', 'float', 'double'];
  return `<div class="property-row">
    <input data-property-name placeholder="name" value="${escapeAttribute(property.name)}">
    <select data-property-type>${types.map(type => `<option ${type === property.type ? 'selected' : ''}>${type}</option>`).join('')}</select>
    <input data-property-value placeholder="value" value="${escapeAttribute(property.value)}">
    <button class="property-remove" type="button" data-remove-property
      aria-label="Remove property" data-tooltip="Remove property">×</button>
  </div>`;
}

function addPropertyRow(containerId) {
  document.getElementById(containerId)?.insertAdjacentHTML('beforeend', propertyRowHtml());
}

function readPropertyEditor(form) {
  const properties = {};
  const propertyTypes = {};
  form.querySelectorAll('.property-row').forEach(row => {
    const name = row.querySelector('[data-property-name]').value.trim();
    if (!name) return;
    properties[name] = row.querySelector('[data-property-value]').value;
    propertyTypes[name] = row.querySelector('[data-property-type]').value;
  });
  return { properties, propertyTypes };
}

function showReadOnlyElement(model, label) {
  const fields = Object.entries(model).filter(([, value]) =>
    ['string', 'number', 'boolean'].includes(typeof value) && value !== '');
  document.getElementById('info-body').innerHTML = `<div class="info-sec"><h4>${escapeHtml(label)}</h4>
    ${fields.map(([name, value]) => `<div class="info-row"><span class="info-k">${escapeHtml(name)}</span><span class="info-v info-mono">${escapeHtml(value)}</span></div>`).join('')}
    </div><div class="info-empty">${graphData?.format === 'graphify'
      ? 'Graphify JSON remains view-only. Export or execute a Ravenroot GraphML workflow.'
      : 'Inspect mode is active. Turn Modify ON to edit this element.'}</div>`;
}

function showFormError(form, message) {
  form.querySelector('.validation-list')?.remove();
  form.insertAdjacentHTML('afterbegin', `<ul class="validation-list"><li>${escapeHtml(message)}</li></ul>`);
}

// ═══════════════════════════════════════════════════════════════
// SEARCH
// ═══════════════════════════════════════════════════════════════

function onSearch(q) {
  if (!cy) return;
  clearFilter();
  if (!q.trim()) return;
  const lq = q.toLowerCase();
  const hit = cy.nodes().filter(n =>
    n.data('name').toLowerCase().includes(lq) ||
    (n.data('classname') || '').toLowerCase().includes(lq)
  );
  if (!hit.length) return;
  filterActive = { type: 'search', q };
  cy.elements().addClass('dim');
  hit.removeClass('dim').addClass('hi');
  hit.connectedEdges().removeClass('dim');
  if (hit.length === 1)
    cy.animate({ center: { eles: hit }, zoom: 1.6 }, { duration: 380 });
}

// ═══════════════════════════════════════════════════════════════
// LEGEND & FILTER
// ═══════════════════════════════════════════════════════════════

// This used to BE the list. It is now the one canonical export, imported -- the legend, the
// Inspector's "Visual type" select and the GraphML parser read the same ten entries, so a type added
// there reaches all three instead of two of them.
const NODE_TYPES = NODE_VISUAL_TYPES;
const EDGE_TYPES = [
  { type: 'failed', label: 'FAILED', dash: true },
  // Reads FAILURE ROUTE, not FAILED, and sits immediately beside it: the two names next to
  // each other are what tells an author the list holds two different things, which is precisely the
  // confusion this distinction prevents. The other entries are outcome tokens, spelled as an author would
  // type them into the Outcome field; this one is not an outcome at all, so it is named for what it
  // is rather than for a word someone could type.
  { type: 'failure', label: 'FAILURE ROUTE', dash: true },
  { type: 'completed', label: 'COMPLETED' }, { type: 'continue', label: 'CONTINUE' },
  { type: 'continueP', label: 'CONTINUE (parallel)', thick: true },
  { type: 'validate', label: 'VALIDATE' }, { type: 'ping', label: 'PING', dash: true },
  { type: 'outcome', label: '*_OUTCOME' }, { type: 'callback', label: 'CALLBACK_*' },
  { type: 'undefined', label: 'UNDEFINED', dash: true },
];

function buildLegend() {
  // THE CHIP CARRIES THE CANVAS GLYPH, not colour alone. In the full form the label carries
  // identity; strip the labels for the short form and COLOUR BECOMES THE SOLE CARRIER, which is a
  // plain accessibility failure — and two of these types already share `#58a6ff`, so colour cannot
  // separate them even today. `NODE_ICONS` is the same mark the canvas draws for that node type, so
  // the palette chip and the node on the stage say the same thing.
  //
  // Native buttons give the filter tiles their missing keyboard route. Their explicit accessible
  // names and delegated visual tooltips carry both action and identity in the compact form.
  visualTooltip.dismiss();
  document.getElementById('leg-nodes').innerHTML = NODE_TYPES.map(t =>
    `<button class="li" type="button" id="li-node-${t.type}" data-legend-kind="node" data-legend-type="${t.type}"
          data-tooltip="Filter node type: ${escapeAttribute(t.label)}"
          aria-label="Filter node type: ${escapeAttribute(t.label)}">
       <div class="li-dot" style="background:${NODE_TYPE_COLORS[t.type]}20;border:2px solid ${NODE_TYPE_COLORS[t.type]}"
         ><span class="li-glyph" aria-hidden="true">${escapeHtml((NODE_ICONS[t.type] || '').trim())}</span></div>
       <span class="li-label">${escapeHtml(t.label)}</span>
     </button>`
  ).join('');

  document.getElementById('leg-edges').innerHTML = EDGE_TYPES.map(t => {
    const color = EDGE_TYPE_COLORS[t.type];
    const lineEl = t.dash
      ? `<div class="li-dash" style="color:${color}"></div>`
      : `<div class="li-line" style="background:${color};height:${t.thick?4:2}px"></div>`;
    // Edge types already carry SHAPE — dash pattern and thickness — so the short form keeps the
    // tile and only drops the word. The name still rides along for assistive technology.
    return `<button class="li" type="button" id="li-edge-${t.type}" data-legend-kind="edge" data-legend-type="${t.type}"
          data-tooltip="Filter edge type: ${escapeAttribute(t.label)}"
          aria-label="Filter edge type: ${escapeAttribute(t.label)}">
        ${lineEl}
        <span class="li-label">${escapeHtml(t.label)}</span>
      </button>`;
  }).join('');
}

function renderNodeCatalog() {
  const container = document.getElementById('node-catalog');
  if (!container) return;
  visualTooltip.dismiss();
  // Catalog availability is one of the six context classes, and a catalog that just failed
  // must flip its chip in the same breath it empties this palette — the panel going DEGRADED is
  // the same fact this empty state is about, told to a different reader.
  refreshAssistantContext();
  if (!nodeTypeCatalog.length) {
    // "Unauthorised" and "unreachable" are different situations and must not read the same: the
    // single old message made a fully registered catalog look like a removed feature.
    const state = catalogEmptyState(nodeCatalogFailure, nodeCatalogLoaded ? [] : null, nodeCatalogPending);
    container.classList.remove('node-catalog--items');
    container.innerHTML = `<div class="catalog-empty" data-catalog-state="${escapeAttribute(state.kind)}">${escapeHtml(state.message)}</div>`;
    return;
  }
  renderNodeCatalogItems(container, nodeTypeCatalog, {
    iconFor: type => catalogNodeIcon(type, NODE_ICONS),
    selectedBehavior: selectedCatalogBehavior,
    onActivate: selectCatalogNodeType,
    onDragStart: (event, behavior) => {
      event.dataTransfer?.setData('application/x-ravenroot-node', behavior);
      if (event.dataTransfer) event.dataTransfer.effectAllowed = 'copy';
    },
  });
}

function selectCatalogNodeType(behavior) {
  if (!catalogDescriptor(behavior)) return;
  selectedCatalogBehavior = behavior;
  if (!modifyEnabled && canModifyGraph(graphData, layoutMode)) setModifyMode(true);
  renderNodeCatalog();
  // Keep the established inspector-first configuration route while making the type persistent for
  // stage clicks and drag-and-drop. Authors may configure-and-submit immediately or place copies.
  showAddCatalogNodeForm(behavior);
}

function toggleLegendFilter(elType, type) {
  if (!cy) return;
  const key   = elType + '/' + type;
  const liId  = 'li-' + elType + '-' + type;
  const liEl  = document.getElementById(liId);

  if (filterActive && filterActive.key === key) {
    clearFilter();
    return;
  }

  clearFilter();
  filterActive = { elType, type, key };
  liEl.classList.add('filt');
  const label = elType === 'node'
    ? NODE_TYPES.find(entry => entry.type === type)?.label
    : EDGE_TYPES.find(entry => entry.type === type)?.label;
  if (label) {
    liEl.setAttribute('aria-label', `Clear ${label} filter`);
    liEl.dataset.tooltip = `Clear ${label} filter`;
    visualTooltip.refresh();
  }

  let hit;
  if (elType === 'node') {
    hit = cy.nodes(`[nodeType="${type}"]`);
    cy.elements().addClass('dim');
    hit.removeClass('dim').addClass('hi');
    hit.connectedEdges().removeClass('dim');
  } else {
    hit = cy.edges(`[edgeType="${type}"]`);
    cy.elements().addClass('dim');
    hit.removeClass('dim').addClass('hi');
    hit.sources().removeClass('dim');
    hit.targets().removeClass('dim');
  }
}

// ═══════════════════════════════════════════════════════════════
// STATS
// ═══════════════════════════════════════════════════════════════

function updateStats() {
  if (!cy) return;
  document.getElementById('b-nodes').textContent = cy.nodes().length;
  document.getElementById('b-edges').textContent = cy.edges().length;

  const nc = {}, ec = {};
  cy.nodes().forEach(n => { const t = n.data('nodeType'); nc[t] = (nc[t]||0)+1; });
  cy.edges().forEach(e => { const t = e.data('edgeType'); ec[t] = (ec[t]||0)+1; });

  renderGraphStatistics(document.getElementById('graph-stats'), cy.nodes().length, cy.edges().length, nc, ec);
  // The graph's content just changed, so what the assistant would attach changed with it.
  // Recomposed HERE rather than on every render because this is already the "graph content
  // changed" hook and already walks every node and edge — the chips stay truthful at the same
  // cadence the counters do, and a chip that describes a graph the user has since edited is a
  // false claim on a slower clock.
  refreshAssistantContext();
}

// ═══════════════════════════════════════════════════════════════
// MINIMAP — one shared active-document overview
// ═══════════════════════════════════════════════════════════════

const mmCanvas = document.getElementById('minimap-canvas');
let minimapFrame = null;
let minimapPaintCount = 0;
let minimapLastSnapshot = null;
let minimapGesture = null;

function rendererMinimapState(owner = workspace.active) {
  const renderer = rendererFor(owner);
  if (!owner || !renderer) return null;
  if (renderer.kind === 'elastic') {
    const nodes = (renderer.nodes || []).filter(node => Number.isFinite(node.x) && Number.isFinite(node.y));
    if (!nodes.length || !renderer.host.clientWidth || !renderer.host.clientHeight) return null;
    const transform = d3.zoomTransform(renderer.svg);
    const contentBounds = normalizeBounds({
      x1: Math.min(...nodes.map(node => node.x - node.r)),
      y1: Math.min(...nodes.map(node => node.y - node.r - 20)),
      x2: Math.max(...nodes.map(node => node.x + node.r)),
      y2: Math.max(...nodes.map(node => node.y + node.r + 24)),
    });
    return {
      kind: 'elastic', contentBounds,
      visibleBounds: normalizeBounds({
        x1: -transform.x / transform.k, y1: -transform.y / transform.k,
        x2: (renderer.host.clientWidth - transform.x) / transform.k,
        y2: (renderer.host.clientHeight - transform.y) / transform.k,
      }),
      nodes: nodes.map(node => ({ x: node.x, y: node.y, color: node.color })),
      edges: (renderer.links || []).map(edge => ({
        source: { x: edge.source.x, y: edge.source.y },
        target: { x: edge.target.x, y: edge.target.y }, color: edge.color,
      })),
      zoom: transform.k,
      viewportWidth: renderer.host.clientWidth,
      viewportHeight: renderer.host.clientHeight,
    };
  }
  const target = owner.cy;
  if (!target || !target.width() || !target.height()) return null;
  return {
    kind: 'cytoscape',
    contentBounds: normalizeBounds(target.elements().boundingBox({ includeLabels: true, includeOverlays: true })),
    visibleBounds: normalizeBounds(target.extent()),
    nodes: target.nodes().map(node => ({
      ...node.position(), color: NODE_TYPE_COLORS[node.data('nodeType')] || rendererPalette.nodeBorder,
    })),
    edges: target.edges().map(edge => ({
      source: edge.source().position(), target: edge.target().position(),
      color: EDGE_TYPE_COLORS[edge.data('edgeType')] || rendererPalette.edgeType.default,
    })),
    zoom: target.zoom(),
    viewportWidth: target.width(),
    viewportHeight: target.height(),
  };
}

function paintMinimap(expectedDocumentId = workspace.activeId) {
  if (expectedDocumentId !== workspace.activeId) return;
  const owner = workspace.active;
  const state = rendererMinimapState(owner);
  const host = document.getElementById('minimap');
  if (!owner || !state || state.viewportWidth < 240 || state.viewportHeight < 150) {
    host.hidden = true;
    minimapLastSnapshot = null;
    return;
  }
  host.hidden = false;
  const width = host.clientWidth;
  const height = host.clientHeight;
  const ratio = Math.max(1, window.devicePixelRatio || 1);
  mmCanvas.width = Math.round(width * ratio);
  mmCanvas.height = Math.round(height * ratio);
  const context = mmCanvas.getContext('2d');
  context.setTransform(ratio, 0, 0, ratio, 0, 0);
  context.clearRect(0, 0, width, height);
  const projection = projectMinimap({
    contentBounds: state.contentBounds, visibleBounds: state.visibleBounds, width, height,
  });
  const point = value => ({
    x: value.x * projection.scale + projection.offsetX,
    y: value.y * projection.scale + projection.offsetY,
  });
  state.edges.forEach(edge => {
    const source = point(edge.source);
    const target = point(edge.target);
    context.beginPath();
    context.moveTo(source.x, source.y);
    context.lineTo(target.x, target.y);
    context.strokeStyle = `${edge.color || rendererPalette.edgeType.default}99`;
    context.lineWidth = 1;
    context.stroke();
  });
  state.nodes.forEach(node => {
    const center = point(node);
    context.beginPath();
    context.arc(center.x, center.y, 2, 0, Math.PI * 2);
    context.fillStyle = node.color || rendererPalette.nodeBorder;
    context.fill();
  });
  const viewport = projection.viewport;
  context.fillStyle = rendererPalette.minimapViewportFill;
  context.fillRect(viewport.x, viewport.y, viewport.width, viewport.height);
  context.strokeStyle = rendererPalette.minimapViewport;
  context.lineWidth = 2;
  context.setLineDash(mmCanvas.matches(':focus-visible') ? [4, 2] : []);
  context.strokeRect(viewport.x + 1, viewport.y + 1,
    Math.max(0, viewport.width - 2), Math.max(0, viewport.height - 2));
  context.setLineDash([]);

  const displayName = owner.displayName || owner.name || 'document';
  const rendererName = state.kind === 'elastic' ? 'Monitoring' : 'Design';
  const label = `Overview — ${displayName}`;
  const labelElement = document.getElementById('minimap-label');
  labelElement.textContent = label;
  labelElement.title = `${label} · ${rendererName}`;
  mmCanvas.setAttribute('aria-label', `${label}; ${rendererName} renderer; arrow keys pan graph, Home fits graph, Escape returns to canvas`);
  host.dataset.documentId = owner.id;
  host.dataset.rendererKind = state.kind;
  minimapPaintCount += 1;
  minimapLastSnapshot = {
    documentId: owner.id, rendererKind: state.kind, contentBounds: state.contentBounds,
    visibleBounds: state.visibleBounds, viewport: projection.viewport, map: projection.map,
    scale: projection.scale, offsetX: projection.offsetX, offsetY: projection.offsetY,
    paintCount: minimapPaintCount,
  };
}

function scheduleMinimap(owner = workspace.active) {
  if (!owner || owner !== workspace.active) return;
  const documentId = owner.id;
  if (minimapFrame?.documentId === documentId) return;
  // A pending frame belongs to the document that requested it. Activation can happen later in the
  // same task, before that callback runs; letting the old callback keep the single coalescing slot
  // would make it exit on the ownership guard without ever scheduling the new overview. Supersede
  // that request so one frame still produces at most one paint, now for the current owner.
  if (minimapFrame) cancelAnimationFrame(minimapFrame.handle);
  const handle = requestAnimationFrame(() => {
    minimapFrame = null;
    if (documentId === workspace.activeId) paintMinimap(documentId);
    else scheduleMinimap(workspace.active);
  });
  minimapFrame = { handle, documentId };
}

function cancelMinimapGesture() {
  const host = document.getElementById('minimap');
  if (minimapGesture && mmCanvas.hasPointerCapture?.(minimapGesture.pointerId)) {
    mmCanvas.releasePointerCapture(minimapGesture.pointerId);
  }
  minimapGesture = null;
  host.classList.remove('is-dragging');
}

function moveActiveViewport(center, snapshot = minimapLastSnapshot) {
  const owner = workspace.active;
  if (!owner || !snapshot || snapshot.documentId !== owner.id) return;
  const target = clampViewportCenter(snapshot.contentBounds, snapshot.visibleBounds, center);
  const renderer = rendererFor(owner);
  if (renderer?.kind === 'elastic') {
    const transform = d3.zoomTransform(renderer.svg);
    const x = renderer.host.clientWidth / 2 - target.x * transform.k;
    const y = renderer.host.clientHeight / 2 - target.y * transform.k;
    d3.select(renderer.svg).call(renderer.zoom.transform,
      d3.zoomIdentity.translate(x, y).scale(transform.k));
  } else if (owner.cy) {
    const zoom = owner.cy.zoom();
    owner.cy.pan({ x: owner.cy.width() / 2 - target.x * zoom, y: owner.cy.height() / 2 - target.y * zoom });
  }
  scheduleMinimap(owner);
}

function minimapPoint(event) {
  const rect = mmCanvas.getBoundingClientRect();
  return { x: event.clientX - rect.left, y: event.clientY - rect.top };
}

mmCanvas.addEventListener('pointerdown', event => {
  if (!minimapLastSnapshot || event.button !== 0) return;
  event.preventDefault();
  const point = minimapPoint(event);
  const viewport = minimapLastSnapshot.viewport;
  const inside = point.x >= viewport.x && point.x <= viewport.x + viewport.width
    && point.y >= viewport.y && point.y <= viewport.y + viewport.height;
  minimapGesture = {
    pointerId: event.pointerId, documentId: workspace.activeId, start: point, last: point,
    dragging: inside, moved: false,
    center: {
      x: (minimapLastSnapshot.visibleBounds.x1 + minimapLastSnapshot.visibleBounds.x2) / 2,
      y: (minimapLastSnapshot.visibleBounds.y1 + minimapLastSnapshot.visibleBounds.y2) / 2,
    },
  };
  mmCanvas.setPointerCapture(event.pointerId);
  document.getElementById('minimap').classList.toggle('is-dragging', inside);
});
mmCanvas.addEventListener('pointermove', event => {
  const gesture = minimapGesture;
  if (!gesture || gesture.pointerId !== event.pointerId || gesture.documentId !== workspace.activeId) return;
  const point = minimapPoint(event);
  const dx = point.x - gesture.start.x;
  const dy = point.y - gesture.start.y;
  gesture.last = point;
  gesture.moved ||= Math.hypot(dx, dy) >= 3;
  if (gesture.dragging) moveActiveViewport({
    x: gesture.center.x + dx / minimapLastSnapshot.scale,
    y: gesture.center.y + dy / minimapLastSnapshot.scale,
  });
});
function finishMinimapPointer(event, cancelled = false) {
  const gesture = minimapGesture;
  if (!gesture || gesture.pointerId !== event.pointerId) return;
  if (!cancelled && !gesture.dragging && !gesture.moved && gesture.documentId === workspace.activeId) {
    moveActiveViewport(minimapToWorld(minimapLastSnapshot, gesture.last));
  }
  cancelMinimapGesture();
}
mmCanvas.addEventListener('pointerup', event => finishMinimapPointer(event));
mmCanvas.addEventListener('pointercancel', event => finishMinimapPointer(event, true));
mmCanvas.addEventListener('keydown', event => {
  const owner = workspace.active;
  const state = rendererMinimapState(owner);
  const snapshot = owner && state ? {
    ...minimapLastSnapshot,
    documentId: owner.id,
    contentBounds: state.contentBounds,
    visibleBounds: state.visibleBounds,
  } : null;
  if (!snapshot) return;
  const center = {
    x: (snapshot.visibleBounds.x1 + snapshot.visibleBounds.x2) / 2,
    y: (snapshot.visibleBounds.y1 + snapshot.visibleBounds.y2) / 2,
  };
  const fraction = event.shiftKey ? 0.5 : 0.1;
  if (event.key === 'ArrowLeft') center.x -= snapshot.visibleBounds.w * fraction;
  else if (event.key === 'ArrowRight') center.x += snapshot.visibleBounds.w * fraction;
  else if (event.key === 'ArrowUp') center.y -= snapshot.visibleBounds.h * fraction;
  else if (event.key === 'ArrowDown') center.y += snapshot.visibleBounds.h * fraction;
  else if (event.key === 'Home') { event.preventDefault(); event.stopPropagation(); fitGraph(); return; }
  else if (event.key === 'Escape') {
    event.preventDefault(); event.stopPropagation();
    workspace.find(workspace.activeId)?.pane?.focus({ preventScroll: true });
    return;
  } else return;
  event.preventDefault();
  event.stopPropagation();
  moveActiveViewport(center, snapshot);
});
mmCanvas.addEventListener('focus', () => scheduleMinimap());
mmCanvas.addEventListener('blur', () => scheduleMinimap());

// ═══════════════════════════════════════════════════════════════
// WORKFLOW EDITING & RUNTIME
// ═══════════════════════════════════════════════════════════════

function newWorkflow() {
  if (!workspace.active) return openDocument();
  if (!confirmDiscardChanges()) return;
  setModifyMode(false);
  activeDocumentIncarnation = createDocumentIncarnation();
  graphData = createWorkflowDocument();
  // initCy resets the stack only when it is handed a document it has not seen; newWorkflow installs
  // the new document first, so it owns the reset. Without it a new workflow would inherit the undo
  // stack and the dirty flag of the one it replaced.
  editHistory.reset();
  updateHistoryUi();
  graphName = 'untitled.graphml';
  document.getElementById('graph-title').textContent = graphName;
  document.title = graphName + ' — Ravenroot UI';
  initCy(buildElements(graphData), graphData);
  clearActivity();
  // The message must describe the START -> Do something -> {End, Error} shape that
  // createWorkflowDocument() actually builds.
  addActivityMessage('editor', 'Minimal Start → Do something → {End, Error} workflow created', 'completed');
}

function showAddNodeForm({ skipDraftGuard = false } = {}) {
  if (!skipDraftGuard) {
    return runAfterInspectorDraft(() => showAddNodeForm({ skipDraftGuard: true }));
  }
  // Selecting or authoring reveals the Inspector: a selection that silently does nothing
  // because a panel is closed is worse than a panel reappearing.
  revealInspector();
  if (!graphData) newWorkflow();
  if (!modifyEnabled) return showInspectorMessage('Turn Modify ON before adding a node.');
  if (graphData.format === 'graphify') return showInspectorMessage(
    'Graphify JSON is view-only. Create or load a Ravenroot GraphML workflow to edit it.');
  const id = uniqueId('node', graphData.nodes);
  document.getElementById('info-title').textContent = 'Add node';
  renderNodeForm(createNode(id, 'New node', 'PASSTHROUGH'), true);
}

function showAddCatalogNodeForm(behavior, { skipDraftGuard = false } = {}) {
  if (!skipDraftGuard) {
    return runAfterInspectorDraft(() => showAddCatalogNodeForm(behavior, { skipDraftGuard: true }));
  }
  if (!graphData) newWorkflow();
  if (graphData.format === 'graphify') return showInspectorMessage(
    'Graphify JSON is view-only. Create or load a Ravenroot GraphML workflow to edit it.');
  const descriptor = catalogDescriptor(behavior);
  if (!descriptor) return showAddNodeForm();
  const id = uniqueId(behavior.replace(/[^a-zA-Z0-9_-]/g, '-') || 'node', graphData.nodes);
  const model = createNode(id, descriptor.displayName, 'BEHAVIOR');
  model.behavior = descriptor.behavior;
  model.nodeType = resolveDescriptorNodeType(descriptor);
  model.properties = Object.fromEntries((descriptor.properties || [])
    .filter(catalogPropertyHasDeclaredDefault)
    .map(property => [property.name, property.defaultValue]));
  model.propertyTypes = Object.fromEntries((descriptor.properties || [])
    .filter(catalogPropertyHasDeclaredDefault)
    .map(property => [property.name, catalogTypeToGraphMl(property.type)]));
  document.getElementById('info-title').textContent = `Add ${descriptor.displayName}`;
  renderNodeForm(model, true);
}

function showAddEdgeForm({ skipDraftGuard = false } = {}) {
  if (!skipDraftGuard) {
    return runAfterInspectorDraft(() => showAddEdgeForm({ skipDraftGuard: true }));
  }
  // Selecting or authoring reveals the Inspector: a selection that silently does nothing
  // because a panel is closed is worse than a panel reappearing.
  revealInspector();
  if (!graphData) newWorkflow();
  if (!modifyEnabled) return showInspectorMessage('Turn Modify ON before adding an edge.');
  if (graphData.format === 'graphify') return showInspectorMessage(
    'Graphify JSON is view-only. Create or load a Ravenroot GraphML workflow to edit it.');
  if (graphData.nodes.length < 2) return showInspectorMessage('At least two nodes are required to add an edge.');
  const id = uniqueId('edge', graphData.edges);
  document.getElementById('info-title').textContent = 'Add edge';
  renderEdgeForm(createEdge(id, graphData.nodes[0].id, graphData.nodes[1].id), true);
}

function prepareDocumentDownload(id) {
  const target = workspace.find(id);
  // A freshly opened active document lives in the working view until the first capture. Saving is
  // itself a capture boundary, so write that view back before asking the record what it contains.
  if (target && id === workspace.activeId) captureActiveDocument();
  if (!target?.graph || target.graph.format === 'graphify') {
    if (id === workspace.activeId) showInspectorMessage(
      'Only Ravenroot workflow documents can be exported as executable GraphML.');
    return null;
  }
  if (id === workspace.activeId) {
    syncGraphPositions();
    captureActiveDocument();
  } else if (target.layoutMode !== 'elastic') {
    syncGraphPositionsFromCy(target.graph, target.cy);
  }
  const xml = serializeGraphML(target.graph);
  return {
    target,
    xml,
    filename: target.name.endsWith('.graphml') ? target.name : `${target.name}.graphml`,
  };
}

function dispatchDocumentDownload(prepared) {
  const blob = new Blob([prepared.xml], { type: 'application/graphml+xml;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  try {
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = prepared.filename;
    anchor.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}

function markDocumentDownloaded(prepared, { announce = true } = {}) {
  const { target } = prepared;
  // Exporting GraphML is the only persistence this editor has, so it is the save point: the undo
  // stack keeps its depth and the document becomes clean at its current position.
  target.history.markSaved();
  if (target.id === workspace.activeId) {
    editHistory.markSaved();
    updateHistoryUi();
    if (announce) addActivityMessage('editor', `Saved ${prepared.filename}`, 'completed');
  } else {
    syncPaneHeaders();
    syncDocumentSwitcher();
  }
}

function downloadDocument(id) {
  const prepared = prepareDocumentDownload(id);
  if (!prepared) return false;
  dispatchDocumentDownload(prepared);
  markDocumentDownloaded(prepared);
  return true;
}

function exportGraphML() {
  return downloadDocument(workspace.activeId);
}

function rebuildGraph(options = {}) {
  if (!graphData) return;
  // Undo and redo have just written the document. Reading positions back out of the renderer here
  // would overwrite the state that was restored, so history rebuilds skip the sync.
  if (options.syncPositions !== false) syncGraphPositions();
  const activeStyle = visualStyle;
  graphData.nodeMap = Object.fromEntries(graphData.nodes.map(node => [node.id, node]));
  initCy(buildElements(graphData), graphData, {
    visualStyle: activeStyle,
  });
  // initCy built a new renderer, so the keyboard's position has to be put back on it or the next
  // arrow key would start from the top of the graph again.
  if (graphCursorId && cy.getElementById(graphCursorId).nonempty()) setGraphCursor(graphCursorId);
}

// ═══════════════════════════════════════════════════════════════
// UNDO / REDO / DIRTY STATE (UI-01)
// ═══════════════════════════════════════════════════════════════

function undoEdit() {
  if (!graphData || !editHistory.canUndo() || !finalizeInspectorBeforeHistory()) return;
  applyHistoryStep(editHistory.undo(graphData), 'Undo');
}

function redoEdit() {
  if (!graphData || !editHistory.canRedo() || !finalizeInspectorBeforeHistory()) return;
  applyHistoryStep(editHistory.redo(graphData), 'Redo');
}

function finalizeInspectorBeforeHistory() {
  const draft = inspectorDraft;
  if (!draft?.form.isConnected) return true;
  const assessment = inspectNodeDraft(draft);
  if (!assessment.changed) {
    retireInspectorDraft(draft.form);
    return true;
  }
  if (inspectorAutosave && assessment.valid
      && commitNodeDraft(draft, { coalesceKey: draft.focusKey })) {
    retireInspectorDraft(draft.form);
    return true;
  }
  showFormError(draft.form, uiText('inspector.history.pending'));
  draft.form.querySelector(':invalid')?.focus();
  return false;
}

function applyHistoryStep(command, verb) {
  if (!command) return;
  retireInspectorDraft();
  dragSnapshot = null;
  resetConnectGesture();
  rebuildGraph({ syncPositions: false });
  selectCommandTargets(command);
  updateHistoryUi();
  addActivityMessage('editor', `${verb}: ${command.label}`, 'completed');
}

// Undo restores the document; the renderer follows. Selecting whatever the step touched — when it
// still exists afterwards — is what makes the restored element visible instead of leaving the user
// to hunt for what changed.
function selectCommandTargets(command) {
  if (!cy) return;
  invalidateStableSelection();
  const targets = commandTargets(command);
  cy.elements().unselect();
  const restored = [...targets.nodeIds, ...targets.edgeIds]
    .map(id => cy.getElementById(id))
    .filter(element => element.nonempty());
  restored.forEach(element => element.select());
}

function confirmDiscardChanges() {
  if (!editHistory.isDirty()) return true;
  return confirm(discardChangesMessage(graphName));
}

function updateHistoryUi() {
  const state = editHistory.state();
  const undoButton = document.getElementById('btn-undo');
  if (undoButton) {
    undoButton.title = state.canUndo ? `Undo ${state.undoLabel}` : 'Nothing to undo';
  }
  const redoButton = document.getElementById('btn-redo');
  if (redoButton) {
    redoButton.title = state.canRedo ? `Redo ${state.redoLabel}` : 'Nothing to redo';
  }
  const indicator = document.getElementById('dirty-state');
  if (indicator) {
    indicator.classList.toggle('dirty', state.dirty);
    indicator.textContent = state.dirty ? 'unsaved changes' : 'saved';
  }
  const exportButton = document.getElementById('btn-export');
  if (exportButton) {
    exportButton.classList.toggle('primary', state.dirty && graphData?.format !== 'graphify');
  }
  // The pane strip carries the same `*` this indicator carries, for the document it names. Hooked
  // here because this already runs on every edit, undo, redo and save: a modified marker that
  // updated on document switch alone would be wrong for as long as the user kept editing.
  syncPaneHeaders();
  syncDocumentSwitcher();
  refreshCommands();
}

function setEditorAvailability() {
  updateModifyAvailability();
  updateEditingButtons();
  updateHistoryUi();
}

function uniqueId(prefix, elements) {
  return uniqueElementId(prefix, elements);
}

function updateModifyAvailability() {
  const button = document.getElementById('btn-modify');
  if (!button) return;
  const available = canModifyGraph(graphData, layoutMode);
  button.title = graphData?.format === 'graphify'
    ? 'Graphify JSON is always view-only'
    : layoutMode === 'elastic'
      ? 'Monitoring view is read-only'
      : 'Enable graph editing';
  if (!available && modifyEnabled) setModifyMode(false);
  updateConnectButton();
  refreshCommands();
}

function setModifyMode(enabled) {
  const wasEnabled = modifyEnabled;
  if (!enabled) cancelNodeMoveGesture();
  if (!enabled && edgeGestureSession) cancelEdgeGesture({ clearMessage: true });
  modifyEnabled = Boolean(enabled && canModifyGraph(graphData, layoutMode));
  navigationEnabled = false;
  if (!modifyEnabled) resetConnectGesture();
  const button = document.getElementById('btn-modify');
  if (button) {
    button.setAttribute('aria-pressed', String(modifyEnabled));
    button.classList.toggle('active', modifyEnabled);
    button.setAttribute('aria-label', modifyEnabled ? 'Editing mode active' : 'Switch to Editing mode');
  }
  const mode = document.getElementById('graph-mode-label');
  if (mode) mode.textContent = modifyEnabled ? 'Editing' : 'Viewer';
  document.getElementById('cy-wrap')?.classList.toggle('modify-on', modifyEnabled);
  applyCanvasInteraction();
  updateConnectButton();
  updateEditingButtons();
  if (cy?.$(':selected').nonempty()) showSelectionInfo();
  else if (wasEnabled && !modifyEnabled) closeInfo();
  refreshCommands();
}

function toggleNavigation() {
  cancelNodeMoveGesture();
  navigationEnabled = !navigationEnabled;
  cancelEdgeGesture();
  applyCanvasInteraction();
  announceGraph(navigationEnabled
    ? 'Navigation enabled. Drag the empty stage to pan. Press H again to return to selection.'
    : 'Navigation disabled. Drag the empty stage to select nodes and edges.');
  refreshCommands();
}

function applyCanvasInteraction(targetCy = cy) {
  if (!targetCy) return;
  const state = canvasInteractionState({ editing: modifyEnabled, navigating: navigationEnabled });
  canvasZoomBridges.get(targetCy)?.restore();
  targetCy.autoungrabify(false);
  applyNodeGrabPolicy(targetCy, state);
  targetCy.boxSelectionEnabled(state.boxSelection);
  targetCy.userPanningEnabled(state.panning);
  targetCy.userZoomingEnabled(state.zooming);
  const wrap = document.getElementById('cy-wrap');
  wrap?.classList.toggle('navigation-on', state.navigating);
  wrap?.style.setProperty('--graph-cursor', state.cursor);
}

function applyNodeGrabPolicy(targetCy, state = canvasInteractionState({
  editing: modifyEnabled,
  navigating: navigationEnabled,
})) {
  if (!targetCy || targetCy.destroyed()) return;
  targetCy.nodes().forEach(node => {
    if (nodeIsGrabbable(state, node.selected())) node.grabify();
    else node.ungrabify();
  });
}

function cancelNodeMoveGesture({ announce = false } = {}) {
  const snapshot = dragSnapshot;
  dragSnapshot = null;
  if (!snapshot) return false;
  if (snapshot.cy && !snapshot.cy.destroyed()) {
    snapshot.cy.batch(() => snapshot.nodes.forEach(entry => {
      const node = snapshot.cy.getElementById(entry.id);
      if (node.nonempty()) node.position(entry.position);
    }));
    applyNodeGrabPolicy(snapshot.cy);
  }
  if (announce) announceGraph('Node move cancelled.');
  return true;
}

function updateEditingButtons() {
  refreshCommands();
}

function retireElementSelectionGesture(targetCy) {
  if (targetCy) elementSelectionAtPointerStart.delete(targetCy);
}

function selectionRendererIsActive(owner, targetCy, rendererToken = null) {
  if (!owner || !targetCy || targetCy.destroyed() || workspace.active !== owner
      || owner.cy !== targetCy || cy !== targetCy) return false;
  const renderer = rendererFor(owner);
  return renderer?.kind === 'cytoscape' && renderer.cy === targetCy
    && (!rendererToken || renderer.token === rendererToken);
}

function scheduleStableSelection(owner, targetCy, element, originalEvent) {
  if (!targetCy || !element) return false;
  const pointerStart = elementSelectionAtPointerStart.get(targetCy);
  retireElementSelectionGesture(targetCy);
  const rendererToken = rendererFor(owner)?.token || null;
  if (!selectionRendererIsActive(owner, targetCy, rendererToken)
      || element.cy() !== targetCy) return false;
  const matchesPointerStart = pointerStart?.owner === owner
    && pointerStart.rendererToken === rendererToken
    && pointerStart.elementId === element.id();
  const selected = matchesPointerStart
    ? pointerStart.selectedIds : targetCy.$(':selected').map(item => item.id());
  const additive = matchesPointerStart
    ? pointerStart.additive : isAdditiveSelection(originalEvent);
  const next = selectionAfterClick(selected, element.id(), additive);
  const revision = invalidateStableSelection(targetCy);
  applyStableSelection(targetCy, next);
  // Cytoscape may finish the current tap by toggling the element once more in the same event task.
  // A guarded microtask runs after that bookkeeping but before any subsequent input task, making
  // the click transfer authoritative for an immediate drag or key press.
  queueMicrotask(() => {
    if (revision !== stableSelectionRevision
        || !selectionRendererIsActive(owner, targetCy, rendererToken)) return;
    applyStableSelection(targetCy, next);
  });
  // Cytoscape finishes repeated-click bookkeeping after its debounce window. Reassert only the
  // newest click on the same live renderer: an older click or document can never overwrite the
  // synchronous state the next gesture already observed.
  const timer = setTimeout(() => {
    stableSelectionTimers.delete(targetCy);
    if (revision !== stableSelectionRevision
        || !selectionRendererIsActive(owner, targetCy, rendererToken)) return;
    applyStableSelection(targetCy, next);
  }, targetCy.multiClickDebounceTime() + 1);
  stableSelectionTimers.set(targetCy, timer);
  return true;
}

function applyStableSelection(targetCy, ids) {
  const current = targetCy.$(':selected').map(element => element.id());
  // The debounce reassertion is a repair for Cytoscape changing the selection after `tap`, not a
  // request to recreate an already-correct selection. An unconditional unselect/select cycle emits
  // fresh selection events and rebuilds the Inspector; for a multi-node form that destroys typed
  // values, touched state and focus even though the selected set never changed.
  if (sameSelectedIds(current, ids)) return;
  targetCy.batch(() => {
    targetCy.elements().unselect();
    ids.forEach(id => targetCy.getElementById(id).select());
  });
}

function invalidateStableSelection(targetCy = cy) {
  stableSelectionRevision += 1;
  const timer = targetCy ? stableSelectionTimers.get(targetCy) : null;
  if (timer != null) clearTimeout(timer);
  if (targetCy) stableSelectionTimers.delete(targetCy);
  return stableSelectionRevision;
}

function clearStageInteraction(targetCy) {
  if (!targetCy || targetCy.destroyed()) return;
  // This is selection/focus cleanup only: it deliberately creates no history entry. Use the event
  // renderer rather than the module global so an empty tap in a background document cannot clear or
  // mutate another document while pane activation is being resolved.
  invalidateStableSelection(targetCy);
  targetCy.elements().unselect();
  targetCy.nodes().removeClass('graph-cursor');
  if (targetCy === cy) graphCursorId = null;
  closeInfo();
  clearFilter();
  clearTrace();
  targetCy.elements().removeClass('dim hi');
}

function toggleModify({ skipDraftGuard = false } = {}) {
  if (!canModifyGraph(graphData, layoutMode)) {
    showInspectorMessage(graphData?.format === 'graphify'
      ? 'Graphify JSON is always view-only.'
      : 'Monitoring view is read-only. Choose Design to modify the workflow.');
    return;
  }
  const next = nextModifyState(modifyEnabled, graphData, layoutMode);
  if (modifyEnabled && !next && !skipDraftGuard) {
    return runAfterInspectorDraft(() => toggleModify({ skipDraftGuard: true }));
  }
  setModifyMode(next);
}

function toggleInspectorAutosave() {
  inspectorAutosave = !inspectorAutosave;
  writeInspectorAutosavePreference(inspectorAutosave);
  if (inspectorAutosave && inspectorDraft) scheduleNodeDraftCommit(inspectorDraft, true);
  refreshCommands();
}

function updateConnectButton() {
  const button = document.getElementById('btn-connect');
  if (!button) return;
  refreshCommands();
}

function toggleConnect({ skipDraftGuard = false } = {}) {
  if (!modifyEnabled) return;
  if (!skipDraftGuard) {
    return runAfterInspectorDraft(() => toggleConnect({ skipDraftGuard: true }));
  }
  connectArmed = !connectArmed;
  if (!connectArmed) resetConnectGesture();
  updateConnectButton();
  if (connectArmed) {
    showInspectorMessage('Connect: select a source node, then a target node. '
      + 'Or drag from a node onto another. With the keyboard, focus the graph, '
      + 'move with the arrow keys and press E then Enter.', { skipDraftGuard: true });
  }
}

function resetConnectGesture(targetCy = cy) {
  connectArmed = false;
  connectSourceId = null;
  targetCy?.nodes().removeClass('connect-source');
  updateConnectButton();
}

function handleConnectTap(owner, node, originalEvent) {
  if (!edgeGestureSession) {
    if (!edgeSourceIsAvailable(node)) {
      scheduleStableSelection(owner, node.cy(), node, originalEvent);
      return;
    }
    retireElementSelectionGesture(node.cy());
    startEdgeGesture(owner, beginConnectGesture(graphData, node.id()));
    return;
  }
  retireElementSelectionGesture(node.cy());
  commitEdgeGestureAt(node.id());
}

function edgeSourceIsAvailable(node) {
  if (!node || node.empty()) return false;
  if (nodeCanSourceEdge(node.selected())) return true;
  const label = node.data('name') || node.id();
  const message = `${label} is selected and moves in Editing. Move to an unselected node to start an edge.`;
  showInspectorMessage(message);
  announceGraph(message);
  return false;
}

// ═══════════════════════════════════════════════════════════════
// EDGE GESTURES — one machine, three input routes (UI-02)
// ═══════════════════════════════════════════════════════════════

// Everything below is shared by the pointer, the keyboard and the inspector form. A gesture is
// opened, previewed against a candidate, then committed through the command model or cancelled.
// Nothing writes to the document except commitEdgeGestureAt.

function announceGraph(message) {
  const live = document.getElementById('graph-live');
  if (!live || !message) return;
  // A cursor/layout callback can already be queued when Enter commits an edge. Never let its old
  // composing sentence replace the terminal result after the gesture has been retired.
  if (/^(Connecting|Reconnecting)\b/.test(message) && !edgeGestureSession) return;
  // Re-announcing identical text is silently dropped by screen readers, so a repeated refusal
  // would go unheard. Clearing first forces it to be read again.
  live.textContent = '';
  live.textContent = message;
}

function prepareEdgeGestureOwner(owner, targetCy = owner?.cy) {
  if (!owner || workspace.find(owner.id) !== owner || owner.cy !== targetCy
      || !owner.pane?.classList.contains('doc-pane--shown')) return false;
  if (workspace.activeId !== owner.id) activateDocument(owner.id);
  if (workspace.active === owner) captureActiveDocument();
  return workspace.active === owner && cy === targetCy && graphData === owner.graph
    && editHistory === owner.history;
}

function edgeGestureSessionIsCurrent(session = edgeGestureSession) {
  if (!session) return false;
  return edgeGestureSessionOwns(session, {
    documentId: session.owner.id,
    owner: session.owner,
    renderer: session.owner.renderer,
    cy: session.owner.cy,
    graph: graphData,
    history: editHistory,
  }) && workspace.find(session.documentId) === session.owner
    && workspace.activeId === session.documentId
    && session.owner.pane?.classList.contains('doc-pane--shown')
    && rendererSessions.isLive(session.renderer.token)
    && edgeGestureSession === session;
}

function requireCurrentEdgeGestureSession() {
  const session = edgeGestureSession;
  if (session && !edgeGestureSessionIsCurrent(session)) cancelEdgeGesture({ clearMessage: true });
  return edgeGestureSessionIsCurrent(session) ? session : null;
}

function startEdgeGesture(owner, gesture, {
  announce = true, deferVisuals = false, skipDraftGuard = false,
} = {}) {
  if (!skipDraftGuard) {
    const immediate = () => startEdgeGesture(owner, gesture, {
      announce, deferVisuals, skipDraftGuard: true,
    });
    const deferred = () => startEdgeGesture(owner, gesture, {
      announce: true, deferVisuals: false, skipDraftGuard: true,
    });
    return runAfterInspectorDraft(immediate, { deferredAction: deferred, deferredResult: false });
  }
  if (!prepareEdgeGestureOwner(owner) || !modifyEnabled
      || !canModifyGraph(graphData, layoutMode) || gesture.mode === 'idle') {
    if (gesture.mode === 'idle') announceGraph('That element cannot start an edge.');
    return false;
  }
  if (edgeGestureSession) cancelEdgeGesture({ clearMessage: true });
  const renderer = owner.renderer;
  const ghost = documentEdgeGhost(owner);
  document.querySelectorAll('.edge-ghost#edge-ghost').forEach(element => element.removeAttribute('id'));
  ghost.id = 'edge-ghost';
  edgeGestureSession = createEdgeGestureSession({
    documentId: owner.id,
    owner,
    renderer,
    cy: owner.cy,
    graph: graphData,
    history: editHistory,
    ghost,
    gesture,
    visualStyle,
    layoutMode,
    fontSize,
  });
  edgeGestureSession.cy.elements().removeClass('connect-source connect-valid connect-invalid edge-reconnecting');
  if (!deferVisuals) activateEdgeGestureVisuals();
  const description = describeEdgeGesture(gesture, edgeGestureSession.graph, null);
  setEdgeGestureSemanticState(deferVisuals ? 'idle' : 'pressed');
  if (announce) {
    showInspectorMessage(description);
    announceGraph(description);
    setEdgeGestureSemanticState('composing');
  }
  if (!deferVisuals) previewEdgeGestureAt(graphCursorId);
  refreshCommands();
  return true;
}

function activateEdgeGestureVisuals() {
  const session = requireCurrentEdgeGestureSession();
  if (!session) return;
  if (session.gesture.mode === 'connect') session.cy.getElementById(session.gesture.sourceId).addClass('connect-source');
  else session.cy.getElementById(session.gesture.edgeId).addClass('edge-reconnecting');
}

// Judges a candidate without committing anything: paints the node, colours the ghost line and
// speaks the verdict. Called on every pointer move and every cursor move.
function previewEdgeGestureAt(candidateId) {
  const session = requireCurrentEdgeGestureSession();
  if (!session) return null;
  session.cy.nodes().removeClass('connect-valid connect-invalid');
  if (!candidateId) {
    setGhostValidity(session, true);
    return null;
  }
  const candidate = edgeGestureCandidate(session.gesture, session.graph, candidateId);
  session.cy.getElementById(candidateId).addClass(candidate.ok ? 'connect-valid' : 'connect-invalid');
  setGhostValidity(session, candidate.ok);
  return candidate;
}

function commitEdgeGestureAt(candidateId, { skipDraftGuard = false } = {}) {
  const session = requireCurrentEdgeGestureSession();
  if (!session) return false;
  const candidate = edgeGestureCandidate(session.gesture, session.graph, candidateId);
  if (!candidate.ok) {
    // A refused drop keeps the gesture open so the user can aim somewhere else, and says why.
    const refusal = `Cannot connect: ${candidate.reason}.`;
    showInspectorMessage(refusal);
    announceGraph(refusal);
    addActivityMessage('editor', refusal, 'failed');
    return false;
  }
  if (!skipDraftGuard) {
    return runAfterInspectorDraft(() => commitEdgeGestureAt(candidateId, { skipDraftGuard: true }));
  }
  const gesture = session.gesture;
  const committed = gesture.mode === 'connect'
    ? connectNodes(session.graph, gesture.sourceId, candidateId, session.history)
    : reconnectEdge(session.graph, gesture.edgeId, gesture.endpoint, candidateId, session.history);
  cancelEdgeGesture();
  if (!committed) return false;

  const edgeId = gesture.mode === 'connect' ? committed.id : gesture.edgeId;
  // A reconnect changes an edge's endpoints, which Cytoscape cannot do in place, so the renderer is
  // rebuilt from the document rather than patched. A new edge is only an addition and can be added.
  if (gesture.mode === 'connect') {
    // Add and style in one Cytoscape batch. In Cyto mode this prevents the new loop from flashing
    // once with the base bezier style before receiving the renderer's final loop geometry.
    session.cy.batch(() => {
      session.cy.add(buildElements({ nodes: [], edges: [committed] }));
      // A newly added element starts with Cytoscape's generic stylesheet. Apply the exact same
      // renderer contract that styled the existing edges in this batch, so it never paints as a
      // default bezier before its active layout geometry arrives.
      applyActiveEdgeVisualContract(session.cy, session.visualStyle);
      onFontSize(session.fontSize || DEFAULT_FONT_SIZE, session.cy, false);
    });
  } else {
    rebuildGraph();
    // The rebuild replaced every renderer element, so the edge is re-selected: pressing R again
    // must reconnect the edge the user just moved, not nothing.
    session.owner.cy.getElementById(edgeId).select();
  }
  updateStats();
  scheduleMinimap();
  updateHistoryUi();
  const edge = session.graph.edges.find(candidate2 => candidate2.id === edgeId);
  showEdgeInfo(session.owner.cy.getElementById(edgeId));
  setGraphCursor(edge ? edge.target : candidateId);
  const done = gesture.mode === 'connect'
    ? `Connected. ${describeEdge(edge, session.graph)}`
    : `Reconnected. ${describeEdge(edge, session.graph)}`;
  announceGraph(done);
  addActivityMessage('editor', done, 'completed');
  return true;
}

function cancelEdgeGesture({ announce = false, clearMessage = false } = {}) {
  const session = edgeGestureSession;
  const wasActive = Boolean(session);
  edgeGestureSession = null;
  // Escape can arrive while the button is still down, and the tapend handler that would normally
  // restore the renderer returns early once pointerGesture is null. Cancelling therefore has to
  // hand the canvas back itself, or panning and node dragging stay dead until a rebuild.
  restoreRendererInteraction(session);
  hideEdgeGhost(session);
  session?.cy?.elements().removeClass('connect-source connect-valid connect-invalid edge-reconnecting');
  resetConnectGesture(session?.cy);
  setEdgeGestureSemanticState('idle');
  if (wasActive && announce) {
    announceGraph('Edge gesture cancelled.');
    showInspectorMessage('Edge gesture cancelled.');
  } else if (wasActive && clearMessage) {
    clearEdgeGestureMessages();
  }
  if (wasActive) refreshCommands();
  return wasActive;
}

function setEdgeGestureSemanticState(state) {
  const wrap = document.getElementById('cy-wrap');
  const live = document.getElementById('graph-live');
  if (wrap) {
    wrap.dataset.edgeGestureState = state;
    if (state === 'idle') delete wrap.dataset.edgeGestureSource;
    else if (edgeGestureSession?.gesture.mode === 'connect') {
      wrap.dataset.edgeGestureSource = edgeGestureSession.gesture.sourceId;
      wrap.dataset.edgeGestureDocument = edgeGestureSession.documentId;
    }
    if (state === 'idle') delete wrap.dataset.edgeGestureDocument;
  }
  if (live) live.dataset.edgeGestureState = state;
}

function clearEdgeGestureMessages() {
  const live = document.getElementById('graph-live');
  if (live) live.textContent = '';
  // Pointer previews temporarily own the inspector. A cancelled drop returns it to a neutral,
  // accurate state without changing the graph selection or adding an activity entry.
  document.getElementById('info-title').textContent = 'Inspector';
  document.getElementById('info-body').innerHTML =
    '<div class="info-empty">Select a node or edge, or create a new one.</div>';
}

// ── The ghost edge ─────────────────────────────────────────────
// Drawn as an SVG overlay instead of a real Cytoscape element: an abandoned gesture then leaves
// nothing in the graph to clean up, and it can never be selected, exported or serialized by mistake.

function edgeGhost(session) {
  return session?.ghost ?? null;
}

function showEdgeGhost(session, route, { selfLoop = false, center = null, bounds = null } = {}) {
  const ghost = edgeGhost(session);
  if (!ghost || (!route && !selfLoop)) return;
  ghost.innerHTML = `<defs><marker id="edge-ghost-arrow" viewBox="0 0 10 10" refX="9" refY="5"
      markerWidth="6" markerHeight="6" orient="auto-start-reverse">
      <path class="ghost-arrow" d="M 0 0 L 10 5 L 0 10 z"/></marker></defs>
    ${selfLoop ? selfLoopGhostPath(center, bounds) : provisionalEdgePath(route)}`;
  ghost.dataset.previewKind = selfLoop ? 'self-loop' : 'edge';
  ghost.classList.add('on');
}

function modelPositionFromRendered(position, targetCy) {
  const zoom = targetCy.zoom();
  const pan = targetCy.pan();
  return { x: (position.x - pan.x) / zoom, y: (position.y - pan.y) / zoom };
}

function prospectiveEdgeRoute(renderedPosition, candidateId = null) {
  const session = requireCurrentEdgeGestureSession();
  if (!session || !renderedPosition) return null;
  const { cy: targetCy, graph, gesture } = session;
  const edgeId = gesture.mode === 'connect'
    ? uniqueId('edge', graph.edges || []) : gesture.edgeId;
  const virtualId = '__rr-edge-preview-target__';
  const movingId = candidateId || virtualId;
  let sourceId;
  let targetId;
  if (gesture.mode === 'connect') {
    sourceId = gesture.sourceId;
    targetId = movingId;
  } else {
    const edge = graph.edges.find(candidate => candidate.id === gesture.edgeId);
    if (!edge) return null;
    sourceId = gesture.endpoint === 'source' ? movingId : edge.source;
    targetId = gesture.endpoint === 'target' ? movingId : edge.target;
  }
  const prospective = { edge: { id: edgeId, source: sourceId, target: targetId } };
  if (!candidateId) {
    const position = modelPositionFromRendered(renderedPosition, targetCy);
    prospective.node = { id: virtualId, ...position, width: 0, height: 0 };
  }
  const modelRoute = rendererRouteSet(targetCy, session.visualStyle, prospective, session.owner).get(edgeId);
  return modelRoute
    ? rendererEdgeRouteToRendered(modelRoute, { zoom: targetCy.zoom(), pan: targetCy.pan() }) : null;
}

function provisionalEdgePath(route) {
  return `<path class="ghost-edge ghost-${route.family}" d="${rendererEdgePath(route)}"></path>
    <circle class="ghost-target" cx="${route.end.x}" cy="${route.end.y}" r="6"></circle>`;
}

function selfLoopGhostPath(center, bounds) {
  const halfWidth = Math.max(24, Number(bounds?.w || 48) / 2);
  const halfHeight = Math.max(18, Number(bounds?.h || 36) / 2);
  const right = center.x + halfWidth;
  const top = center.y - halfHeight;
  const reach = Math.max(44, halfWidth * 0.9);
  const rise = Math.max(38, halfHeight * 1.4);
  const startY = center.y + Math.min(14, halfHeight * 0.45);
  const endX = center.x + Math.min(16, halfWidth * 0.45);
  return `<path class="ghost-edge ghost-loop" d="M ${right} ${startY} C ${right + reach} ${startY}, ${right + reach} ${top - rise}, ${endX} ${top}"></path>
    <circle class="ghost-target ghost-target-self" cx="${endX}" cy="${top}" r="6"></circle>`;
}

function setGhostValidity(session, valid) {
  edgeGhost(session)?.classList.toggle('invalid', !valid);
}

function hideEdgeGhost(session) {
  const ghost = edgeGhost(session);
  if (!ghost) return;
  ghost.classList.remove('on', 'invalid');
  delete ghost.dataset.previewKind;
  ghost.innerHTML = '';
}

// The pinned end a ghost line grows from: the source for a new edge, and for a reconnect the end
// that is NOT travelling.
function gestureAnchorPosition() {
  const session = requireCurrentEdgeGestureSession();
  if (!session) return null;
  const { gesture, graph } = session;
  if (gesture.mode === 'connect') return renderedCenter(session, gesture.sourceId);
  const edge = graph.edges.find(candidate => candidate.id === gesture.edgeId);
  if (!edge) return null;
  return renderedCenter(session, gesture.endpoint === 'source' ? edge.target : edge.source);
}

function renderedCenter(session, nodeId) {
  const element = session?.cy?.getElementById(nodeId);
  if (!element || element.empty()) return null;
  return element.renderedPosition();
}

function gestureAnchorBounds() {
  const session = requireCurrentEdgeGestureSession();
  if (!session) return null;
  const { gesture, graph } = session;
  const nodeId = gesture.mode === 'connect'
    ? gesture.sourceId
    : graph.edges.find(candidate => candidate.id === gesture.edgeId)?.[
      gesture.endpoint === 'source' ? 'target' : 'source'];
  const element = nodeId ? session.cy.getElementById(nodeId) : null;
  return element && element.nonempty()
    ? { w: element.renderedWidth(), h: element.renderedHeight() } : null;
}

// ── The pointer route ──────────────────────────────────────────

// While an edge is being dragged the nodes must stop being draggable, or the same gesture would
// both draw an edge and move the node it started from.
// The renderer interactions a pointer gesture has to borrow, and the single place that gives them
// back. They are written as one pair on purpose: when the suspension and the restoration live in
// separate functions they drift, and that drift is what left the canvas frozen after Escape while
// every test stayed green. A fourth flag added to suspend() is now returned by restore() for free.
function suspendRendererInteraction(session) {
  if (!session?.cy) return;
  canvasZoomBridges.get(session.cy)?.restore();
  session.cy.autoungrabify(true);
  session.cy.boxSelectionEnabled(false);
  // An edge is not grabbable, so without this the drag would be read as a background pan: the
  // viewport would slide under the pointer and the drop would land back where it started.
  session.cy.userPanningEnabled(false);
  // Zoom is suspended by the same ownership lease: changing the viewport would move the ghost and
  // candidate under an in-flight pointer. Escape, commit and tap classification all restore it via
  // `applyCanvasInteraction`, so blocking is atomic and never rewrites the gesture.
  session.cy.userZoomingEnabled(false);
}

function restoreRendererInteraction(session) {
  if (!session?.cy || session.cy.destroyed()) return;
  applyCanvasInteraction(session.cy);
}

function beginPointerGesture(event) {
  const session = requireCurrentEdgeGestureSession();
  if (!session) return;
  session.pointer = beginPointerEdgeGesture(event.renderedPosition, pointerTimestamp(event));
  const clientX = Number(event.originalEvent?.clientX);
  const clientY = Number(event.originalEvent?.clientY);
  const stageRect = session.cy.container().getBoundingClientRect();
  if (Number.isFinite(clientX) && Number.isFinite(clientY)
      && clientX >= stageRect.left && clientX <= stageRect.right
      && clientY >= stageRect.top && clientY <= stageRect.bottom) {
    session.pointer.clientOrigin = { x: clientX, y: clientY };
  }
  if (session.gesture.mode === 'connect' && session.pointer.clientOrigin) {
    const sourcePosition = session.cy.getElementById(session.gesture.sourceId).renderedPosition();
    session.pointer.sourceClientCenter = {
      x: session.pointer.clientOrigin.x - (event.renderedPosition.x - sourcePosition.x),
      y: session.pointer.clientOrigin.y - (event.renderedPosition.y - sourcePosition.y),
    };
  }
  session.lastPointerPosition = null;
  // Prevent Cytoscape from moving the source while this press is being classified. This reservation
  // is deliberately invisible: a click still looks and behaves like selection, and tapend restores
  // the renderer; authoring feedback starts only after the intent threshold is crossed.
  suspendRendererInteraction(session);
}

function pointerTimestamp(event) {
  const timestamp = Number(event?.originalEvent?.timeStamp);
  return Number.isFinite(timestamp) ? timestamp : performance.now();
}

function updatePointerEdgePreview(event, candidateId) {
  const session = requireCurrentEdgeGestureSession();
  if (!session?.pointer) return;
  const candidate = candidateId
    ? edgeGestureCandidate(session.gesture, session.graph, candidateId) : null;
  const previous = session.pointer;
  session.pointer = updatePointerEdgeGesture(session.pointer, {
    position: event.renderedPosition || session.pointer.latest,
    timestamp: pointerTimestamp(event),
    candidateId,
    candidateValid: Boolean(candidate?.ok),
    sourceId: session.gesture.mode === 'connect' ? session.gesture.sourceId : null,
  });
  const activated = previous?.phase === 'pressed' && session.pointer.phase !== 'pressed';
  if (activated) {
    activateEdgeGestureVisuals();
  }
  if (event.renderedPosition && session.pointer.phase !== 'pressed') {
    const validTargetId = candidate?.ok ? candidateId : null;
    const selfLoop = session.pointer.phase === 'target-self';
    showEdgeGhost(session, prospectiveEdgeRoute(event.renderedPosition, validTargetId), {
      selfLoop,
      center: selfLoop ? gestureAnchorPosition() : null,
      bounds: selfLoop ? gestureAnchorBounds() : null,
    });
  }
  if (session.pointer.phase !== 'pressed') previewEdgeGestureAt(candidateId);
  announcePointerGestureTransition(previous, session.pointer, candidateId);
}

function updatePointerEdgePreviewFromNativeEvent(originalEvent) {
  const session = requireCurrentEdgeGestureSession();
  if (!session?.pointer || !session.cy.container()) return;
  const nextPosition = { x: originalEvent.clientX, y: originalEvent.clientY };
  // Browsers dispatch a compatibility mousemove after pointermove. Supporting both makes mouse
  // and pen/touch explicit without painting twice for the same physical position.
  if (session.lastPointerPosition?.x === nextPosition.x
      && session.lastPointerPosition?.y === nextPosition.y) return;
  session.lastPointerPosition = nextPosition;
  const rect = session.cy.container().getBoundingClientRect();
  const renderedPosition = {
    x: originalEvent.clientX - rect.left,
    y: originalEvent.clientY - rect.top,
  };
  if (session.gesture.mode === 'connect' && !session.pointer.sourceClientCenter) {
    const sourcePosition = session.cy.getElementById(session.gesture.sourceId).renderedPosition();
    session.pointer.sourceClientCenter = {
      x: originalEvent.clientX - (renderedPosition.x - sourcePosition.x),
      y: originalEvent.clientY - (renderedPosition.y - sourcePosition.y),
    };
    session.pointer.clientOrigin = {
      x: session.pointer.sourceClientCenter.x + session.pointer.origin.x - sourcePosition.x,
      y: session.pointer.sourceClientCenter.y + session.pointer.origin.y - sourcePosition.y,
    };
  }
  let candidateId = session.gesture.mode === 'connect'
    ? edgeGestureTargetAtClientPosition(originalEvent)
    : edgeGestureTargetAtRenderedPosition(renderedPosition);
  // Returning to the physical press corridor is the renderer-independent definition of A→A. It
  // remains correct even when a multi-pane host and the shared stage use different coordinate
  // origins, and only becomes authoring after the pure movement/time threshold has been crossed.
  const origin = session.pointer.clientOrigin;
  if (!candidateId && session.gesture.mode === 'connect'
      && Number.isFinite(origin?.x) && Number.isFinite(origin?.y)) {
    const source = session.cy.getElementById(session.gesture.sourceId);
    const snapX = source.renderedWidth() / 2 + 18;
    const snapY = source.renderedHeight() / 2 + 18;
    if (Math.abs(originalEvent.clientX - origin.x) <= snapX
        && Math.abs(originalEvent.clientY - origin.y) <= snapY) candidateId = session.gesture.sourceId;
  }
  updatePointerEdgePreview(
    { renderedPosition, originalEvent },
    candidateId,
  );
}

function captureEdgePointerOrigin(originalEvent) {
  if (originalEvent.button !== undefined && originalEvent.button !== 0) return;
  const owner = edgeGestureOwnerFromEvent(originalEvent);
  if (!owner || !prepareEdgeGestureOwner(owner)) return;
  const targetCy = owner.cy;
  // Cytoscape classifies a press at the crossing of an edge and node as an edge hit before its
  // delegated `tapstart` reaches the node handler. The native capture phase sees the physical
  // coordinate first, so it can give visible nodes authoring priority. This is deliberately
  // limited to a node under the active canvas: all empty-space and edge-only presses continue to
  // Cytoscape, preserving the established reconnection gesture.
  if (!isOwnerCanvasPointerEvent(originalEvent, owner)) return;
  if (!edgeGestureSession && modifyEnabled && !navigationEnabled && !connectArmed) {
    const rect = targetCy.container().getBoundingClientRect();
    const renderedPosition = {
      x: originalEvent.clientX - rect.left,
      y: originalEvent.clientY - rect.top,
    };
    const sourceId = nodeAtRenderedPosition(renderedPosition, targetCy);
    const source = sourceId ? targetCy.getElementById(sourceId) : null;
    if (sourceId && nodeCanSourceEdge(source.selected())
        && startEdgeGesture(owner, beginConnectGesture(graphData, sourceId), {
      announce: false, deferVisuals: true,
    })) {
      beginPointerGesture({
        renderedPosition,
        originalEvent,
      });
    }
  }
  const session = requireCurrentEdgeGestureSession();
  if (!session?.pointer || session.gesture.mode !== 'connect' || session.owner !== owner) return;
  const rect = session.cy.container().getBoundingClientRect();
  const renderedPosition = {
    x: originalEvent.clientX - rect.left,
    y: originalEvent.clientY - rect.top,
  };
  const sourcePosition = session.cy.getElementById(session.gesture.sourceId).renderedPosition();
  session.pointer.clientOrigin = { x: originalEvent.clientX, y: originalEvent.clientY };
  session.pointer.sourceClientCenter = {
    x: originalEvent.clientX - (renderedPosition.x - sourcePosition.x),
    y: originalEvent.clientY - (renderedPosition.y - sourcePosition.y),
  };
}

function edgeGestureOwnerFromEvent(event) {
  const path = typeof event.composedPath === 'function' ? event.composedPath() : [];
  const container = path.find(element => element?.classList?.contains('doc-canvas'))
    || event.target?.closest?.('.doc-canvas');
  return container?.dataset.documentId ? workspace.find(container.dataset.documentId) : null;
}

function isOwnerCanvasPointerEvent(event, owner) {
  const container = owner?.cy?.container();
  if (!container) return false;
  if (nodeActionGestureIsClaimed(event)) return false;
  const path = typeof event.composedPath === 'function' ? event.composedPath() : [];
  if (path.some(element => element?.classList?.contains('graph-node-actions-overlay'))
      || event.target?.closest?.('.graph-node-actions-overlay')) return false;
  // An overlay can cover the same page coordinates as a graph node. Bounds alone therefore are
  // insufficient: the event must have travelled through THIS renderer's container, not merely land
  // above its rectangle. The contains fallback keeps the rule for older event implementations.
  if (path.length ? !path.includes(container) : !container.contains(event.target)) return false;
  const rect = container.getBoundingClientRect();
  const x = Number(event.clientX);
  const y = Number(event.clientY);
  return Number.isFinite(x) && Number.isFinite(y)
    && x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom;
}

function edgeGestureTargetAtClientPosition(originalEvent) {
  const session = requireCurrentEdgeGestureSession();
  if (!session) return null;
  const sourceCenter = session.pointer?.sourceClientCenter;
  const source = session.cy.getElementById(session.gesture.sourceId);
  if (!sourceCenter || !source || source.empty()) return null;
  const sourceRendered = source.renderedPosition();
  const snap = 18;
  const nearby = session.cy.nodes().filter(node => {
    const rendered = node.renderedPosition();
    const center = {
      x: sourceCenter.x + rendered.x - sourceRendered.x,
      y: sourceCenter.y + rendered.y - sourceRendered.y,
    };
    return Math.abs(originalEvent.clientX - center.x) <= node.renderedWidth() / 2 + snap
      && Math.abs(originalEvent.clientY - center.y) <= node.renderedHeight() / 2 + snap;
  }).sort((left, right) => {
    const leftRendered = left.renderedPosition();
    const rightRendered = right.renderedPosition();
    const leftCenter = {
      x: sourceCenter.x + leftRendered.x - sourceRendered.x,
      y: sourceCenter.y + leftRendered.y - sourceRendered.y,
    };
    const rightCenter = {
      x: sourceCenter.x + rightRendered.x - sourceRendered.x,
      y: sourceCenter.y + rightRendered.y - sourceRendered.y,
    };
    return Math.hypot(originalEvent.clientX - leftCenter.x, originalEvent.clientY - leftCenter.y)
      - Math.hypot(originalEvent.clientX - rightCenter.x, originalEvent.clientY - rightCenter.y);
  });
  return nearby.length ? nearby.first().id() : null;
}

function captureStagePointerSelection(originalEvent) {
  if (originalEvent.button !== undefined && originalEvent.button !== 0) return;
  const owner = edgeGestureOwnerFromEvent(originalEvent);
  if (!owner || !isOwnerCanvasPointerEvent(originalEvent, owner)) return;
  // `pointerdown` is the canonical event. The subsequent compatibility `mousedown` must not
  // overwrite this pre-activation snapshot after the pane's focus handler has cleared selection.
  if (originalEvent.type === 'mousedown' && stageSelectionAtPointerStart.has(owner.cy)) return;
  stageSelectionAtPointerStart.set(owner.cy, {
    hasSelection: owner.cy.$(':selected').nonempty(),
  });
}

// Cytoscape may stop its synthetic drag stream at a node boundary. The bubbling native event is
// the final authority for the visible pointer position and runs after the renderer's handlers.
document.addEventListener('pointermove', updatePointerEdgePreviewFromNativeEvent);
document.addEventListener('mousemove', updatePointerEdgePreviewFromNativeEvent);
// Capture is intentional: it must run before Cytoscape's canvas listener classifies an edge that
// visually passes through a node. `mousedown` remains as the fallback for browsers without
// Pointer Events; once the pointer route began the duplicate compatibility event is a no-op.
document.addEventListener('pointerdown', captureStagePointerSelection, true);
document.addEventListener('pointerdown', captureEdgePointerOrigin, true);
document.addEventListener('mousedown', captureStagePointerSelection, true);
document.addEventListener('mousedown', captureEdgePointerOrigin, true);
document.addEventListener('pointercancel', () => {
  workspace.documents.forEach(owner => retireElementSelectionGesture(owner.cy));
  if (edgeGestureSession?.pointer) cancelEdgeGesture({ clearMessage: true });
  cancelNodeMoveGesture();
});

function announcePointerGestureTransition(previous, current, candidateId) {
  const session = requireCurrentEdgeGestureSession();
  if (!session) return;
  if (!current || current.phase === 'pressed') {
    setEdgeGestureSemanticState('idle');
    return;
  }
  if (previous?.phase === current.phase && previous?.candidateId === current.candidateId) return;
  setEdgeGestureSemanticState(current.phase);
  let description;
  if (current.phase === 'dragging') {
    description = `${describeEdgeGesture(session.gesture, session.graph, null)} Release on the canvas cancels.`;
  } else {
    description = describeEdgeGesture(session.gesture, session.graph, candidateId);
    if (current.phase === 'target-self') description += ' Release to create a self-loop.';
    else if (current.phase === 'target-valid') description += ' Release to connect.';
  }
  showInspectorMessage(description);
  announceGraph(description);
}

// The node under a model-space point, topmost first. Cytoscape draws later elements over earlier
// ones, so the last match is the one the user believes they are pointing at.
function nodeAtModelPosition(position, targetCy = cy) {
  if (!position || !targetCy) return null;
  const hits = targetCy.nodes().filter(node => {
    const box = node.boundingBox();
    return position.x >= box.x1 && position.x <= box.x2
      && position.y >= box.y1 && position.y <= box.y2;
  });
  return hits.length ? hits.last().id() : null;
}

function nodeAtRenderedPosition(position, targetCy = edgeGestureSession?.cy) {
  if (!position || !targetCy) return null;
  const hits = targetCy.nodes().filter(node => {
    const center = node.renderedPosition();
    const halfWidth = node.renderedWidth() / 2;
    const halfHeight = node.renderedHeight() / 2;
    return position.x >= center.x - halfWidth && position.x <= center.x + halfWidth
      && position.y >= center.y - halfHeight && position.y <= center.y + halfHeight;
  });
  return hits.length ? hits.last().id() : null;
}

function edgeGestureTargetAtRenderedPosition(position) {
  const session = requireCurrentEdgeGestureSession();
  if (!session) return null;
  const direct = nodeAtRenderedPosition(position, session.cy);
  if (direct || !position) return direct;
  // A modest magnetic corridor makes the target state predictable at node boundaries and gives
  // coarse or unsteady pointers the same explicit snap feedback as a pixel-perfect mouse.
  const snap = 18;
  const nearby = session.cy.nodes().filter(node => {
    const center = node.renderedPosition();
    return Math.abs(position.x - center.x) <= node.renderedWidth() / 2 + snap
      && Math.abs(position.y - center.y) <= node.renderedHeight() / 2 + snap;
  }).sort((left, right) => {
    const leftPosition = left.renderedPosition();
    const rightPosition = right.renderedPosition();
    return Math.hypot(position.x - leftPosition.x, position.y - leftPosition.y)
      - Math.hypot(position.x - rightPosition.x, position.y - rightPosition.y);
  });
  return nearby.length ? nearby.first().id() : null;
}

// A completed drag is followed by `tapend` and then `tap`, so this path also swallows that trailing
// tap. The cancel path deliberately does not: a cancel can arrive from the keyboard with no pointer
// involved, and suppressing the next tap there would swallow an unrelated click.
function endPointerGesture() {
  const session = requireCurrentEdgeGestureSession();
  if (!session) return;
  session.pointer = null;
  suppressedEdgeTaps.add(session.cy);
  restoreRendererInteraction(session);
}

function consumeSuppressedEdgeTap(targetCy) {
  if (!targetCy || !suppressedEdgeTaps.has(targetCy)) return false;
  suppressedEdgeTaps.delete(targetCy);
  return true;
}

// ── The keyboard route ─────────────────────────────────────────

function setGraphCursor(nodeId) {
  if (!cy) return;
  const element = nodeId ? cy.getElementById(nodeId) : null;
  if (!element || element.empty()) return;
  graphCursorId = nodeId;
  cy.nodes().removeClass('graph-cursor');
  element.addClass('graph-cursor');
  document.getElementById('b-sel').textContent = element.data('name');
  const graph = document.getElementById('cy-wrap');
  if (graph?.contains(document.activeElement)) {
    showNodeActionOverlay(workspace.active, element, { pointer: false });
  }
}

function ensureGraphCursor() {
  if (graphCursorId && cy?.getElementById(graphCursorId).nonempty()) return graphCursorId;
  const first = cy?.nodes().first();
  if (!first || first.empty()) return null;
  setGraphCursor(first.id());
  return graphCursorId;
}

// Spatial movement, not document order: the user is looking at a diagram, so "right" must mean the
// node to the right. Candidates are ranked by distance along the requested axis, with the
// off-axis distance as a penalty so a node roughly in line wins over a nearer one far off to a side.
function moveGraphCursor(direction) {
  if (!cy) return;
  const currentId = ensureGraphCursor();
  const current = currentId ? cy.getElementById(currentId) : null;
  if (!current || current.empty()) return;
  const from = current.position();
  const axis = direction === 'left' || direction === 'right' ? 'x' : 'y';
  const sign = direction === 'right' || direction === 'down' ? 1 : -1;
  let best = null;
  cy.nodes().forEach(node => {
    if (node.id() === currentId) return;
    const to = node.position();
    const along = (to[axis] - from[axis]) * sign;
    if (along <= 0) return;
    const off = Math.abs(axis === 'x' ? to.y - from.y : to.x - from.x);
    const score = along + (off * 2);
    if (!best || score < best.score) best = { id: node.id(), score };
  });
  if (!best) {
    announceGraph(`No node to the ${direction}.`);
    return;
  }
  setGraphCursor(best.id);
  announceCursor();
}

// Steps through the edges touching the cursor node, selecting each one so R can act on it and
// speaking its direction and outcome — the two things an arrowhead and a label convey visually.
function cycleIncidentEdge(step, { skipDraftGuard = false } = {}) {
  const cursor = ensureGraphCursor();
  if (!cursor || !graphData) return;
  const incident = graphData.edges.filter(edge => edge.source === cursor || edge.target === cursor);
  if (!incident.length) {
    announceGraph(`${cursor} has no edges.`);
    return;
  }
  const selectedId = cy.edges(':selected').first()?.id();
  const current = incident.findIndex(edge => edge.id === selectedId);
  const next = incident[(current + step + incident.length * 2) % incident.length];
  if (!skipDraftGuard) {
    return runAfterInspectorDraft(() => cycleIncidentEdge(step, { skipDraftGuard: true }));
  }
  invalidateStableSelection();
  cy.elements().unselect();
  cy.getElementById(next.id).select();
  showEdgeInfo(cy.getElementById(next.id));
  announceGraph(`${describeEdge(next, graphData)} Press R to move its target, Shift plus R for its source.`);
}

function announceCursor() {
  if (!graphCursorId || !graphData) return;
  const node = graphData.nodeMap[graphCursorId];
  if (!node) return;
  if (edgeGestureSession) {
    const session = requireCurrentEdgeGestureSession();
    if (!session) return;
    const description = describeEdgeGesture(session.gesture, session.graph, graphCursorId);
    previewEdgeGestureAt(graphCursorId);
    updateGhostToCursor();
    showInspectorMessage(description);
    announceGraph(description);
    return;
  }
  const degree = graphData.edges.filter(edge =>
    edge.source === graphCursorId || edge.target === graphCursorId).length;
  announceGraph(`${node.name} (${node.id}), ${node.kind}, ${degree} connected edge(s).`);
}

function updateGhostToCursor() {
  const session = requireCurrentEdgeGestureSession();
  if (!session) return;
  const target = renderedCenter(session, graphCursorId);
  if (!target) return;
  const candidate = edgeGestureCandidate(session.gesture, session.graph, graphCursorId);
  const selfLoop = candidate.ok && session.gesture.mode === 'connect'
    && session.gesture.sourceId === graphCursorId;
  showEdgeGhost(session, prospectiveEdgeRoute(target, candidate.ok ? graphCursorId : null), {
    selfLoop,
    center: selfLoop ? gestureAnchorPosition() : null,
    bounds: selfLoop ? gestureAnchorBounds() : null,
  });
}

// Keys handled while the graph widget itself has focus. They are deliberately scoped to the widget
// rather than the document: arrow keys must keep scrolling the page everywhere else.
function handleGraphKeydown(event) {
  if (!cy || !graphData) return;
  const directions = { ArrowLeft: 'left', ArrowRight: 'right', ArrowUp: 'up', ArrowDown: 'down' };
  // Shift turns the arrows from "move between nodes" into "step through this node's edges", which
  // is the only way a keyboard user can put an edge under the R shortcut. Without it, reconnection
  // would be a pointer-only capability and the accessible route would be the lesser one.
  if (event.shiftKey && (event.key === 'ArrowLeft' || event.key === 'ArrowRight')) {
    event.preventDefault();
    cycleIncidentEdge(event.key === 'ArrowRight' ? 1 : -1);
    return;
  }
  if (directions[event.key]) {
    event.preventDefault();
    moveGraphCursor(directions[event.key]);
    return;
  }
  if (event.key === 'Escape' && (cancelNodeMoveGesture({ announce: true })
      || cancelEdgeGesture({ announce: true }))) {
    event.preventDefault();
    return;
  }
  if (event.key === 'Enter') {
    const cursor = ensureGraphCursor();
    if (!cursor) return;
    event.preventDefault();
    if (edgeGestureSession) {
      commitEdgeGestureAt(cursor);
      return;
    }
    invalidateStableSelection();
    cy.getElementById(cursor).select();
    showSelectionInfo();
    announceGraph(`Selected ${cursor}. It now moves in Editing. Move to an unselected node and press E to start an edge.`);
    return;
  }
  if (!modifyEnabled) return;
  const key = String(event.key || '').toLowerCase();
  if (key === 'e') {
    event.preventDefault();
    const cursor = ensureGraphCursor();
    const source = cursor ? cy.getElementById(cursor) : null;
    if (source && edgeSourceIsAvailable(source)) {
      startEdgeGesture(workspace.active, beginConnectGesture(graphData, cursor));
    }
    return;
  }
  if (key === 'r') {
    event.preventDefault();
    const selected = cy.edges(':selected').first();
    if (!selected || selected.empty()) {
      announceGraph('Select an edge first, then press R to reconnect it.');
      return;
    }
    // Shift picks the end that is usually the harder one to reach with a pointer.
    const endpoint = event.shiftKey ? 'source' : 'target';
    startEdgeGesture(workspace.active, beginReconnectGesture(graphData, selected.id(), endpoint));
  }
}

function createNodeFromStage(position, behavior = selectedCatalogBehavior, sourceId = null,
  { skipDraftGuard = false } = {}) {
  if (!skipDraftGuard) {
    return runAfterInspectorDraft(() =>
      createNodeFromStage(position, behavior, sourceId, { skipDraftGuard: true }));
  }
  if (layoutBusy) {
    announceGraph('Layout in progress. Graph editing is available when it finishes.');
    return null;
  }
  const descriptor = behavior ? catalogDescriptor(behavior) : null;
  const connected = sourceId
    ? addConnectedNodeAt(graphData, position, sourceId, editHistory, { descriptor })
    : null;
  if (connected && !connected.node) {
    const refusal = `Cannot add and connect node: ${connected.reason}.`;
    showInspectorMessage(refusal);
    announceGraph(refusal);
    addActivityMessage('editor', refusal, 'failed');
    return null;
  }
  const node = connected?.node || addNodeAt(graphData, position, editHistory, { descriptor });
  if (!node) return;
  node._positionIsCenter = true;
  const edge = connected?.edge || null;
  cy.add(buildElements({ nodes: [node], edges: edge ? [edge] : [] }));
  cy.batch(() => applyVisualStyle(visualStyle, cy, workspace.active));
  updateStats();
  scheduleMinimap();
  updateHistoryUi();
  showNodeInfo(cy.getElementById(node.id));
  addActivityMessage('editor', edge
    ? `Created ${node.id} and connected ${sourceId} → ${node.id}`
    : `Created ${node.id}`, 'completed');
  return node;
}

function deleteCurrentSelection({ skipDraftGuard = false } = {}) {
  if (!skipDraftGuard) {
    return runAfterInspectorDraft(() => deleteCurrentSelection({ skipDraftGuard: true }));
  }
  if (!modifyEnabled || !cy) return false;
  const selectedNodes = cy.nodes(':selected').map(node => node.id());
  const selectedEdges = cy.edges(':selected').map(edge => edge.id());
  if (!selectedNodes.length && !selectedEdges.length) return false;
  const removed = deleteElements(graphData, selectedNodes, selectedEdges, editHistory);
  cy.remove(cy.$(':selected'));
  closeInfo();
  updateStats();
  scheduleMinimap();
  updateHistoryUi();
  addActivityMessage('editor',
    `Deleted ${removed.nodeIds.length} node(s) and ${removed.edgeIds.length} edge(s)`, 'completed');
  return true;
}

// This is an explicit authored action whose visible consequence is stated before it happens, never
// something open or save does on the reader's behalf. The
// confirmation lists every node the migration is about to touch and exactly what it writes onto each,
// so the author sees the plan, not just a prompt to trust it.
function migrateJoinSemanticsAction({ skipDraftGuard = false } = {}) {
  if (!skipDraftGuard) {
    return runAfterInspectorDraft(() => migrateJoinSemanticsAction({ skipDraftGuard: true }));
  }
  if (!graphData || !modifyEnabled || !canModifyGraph(graphData, layoutMode)) return false;
  const plan = planJoinSemanticsMigration(graphData);
  if (plan.alreadyDeclared) {
    addActivityMessage('editor', 'This document already declares join.semantics=declared', 'completed');
    return false;
  }
  const summary = plan.changes.length
    ? plan.changes.map(change => `${change.nodeId}: ${change.property}=${change.value}`).join('\n')
    : '(no existing fan-in needs a materialised property)';
  const confirmed = globalThis.confirm(
    'Migrate this document to declared join semantics?\n\n'
    + 'This writes join.semantics=declared and materialises the currently-inferred policy onto every '
    + `existing join, so nothing about how the document runs changes today:\n\n${summary}\n\n`
    + 'It is an ordinary authored edit -- a new version, a new hash -- and can be undone.');
  if (!confirmed) return false;
  migrateJoinSemantics(graphData, editHistory);
  rebuildGraph();
  updateHistoryUi();
  refreshCommands();
  addActivityMessage('editor',
    `Migrated to declared join semantics: ${plan.changes.length} node(s) updated`, 'completed');
  const selected = cy?.nodes(':selected');
  if (selected?.length === 1) showNodeInfo(selected.first());
  return true;
}

function duplicateNodeInDocument(owner, instance, nodeId, { skipDraftGuard = false } = {}) {
  if (!skipDraftGuard) {
    return runAfterInspectorDraft(() =>
      duplicateNodeInDocument(owner, instance, nodeId, { skipDraftGuard: true }));
  }
  if (workspace.activeId !== owner?.id) activateDocument(owner.id);
  if (workspace.active !== owner || cy !== instance || graphData !== owner.graph
      || !modifyEnabled || !canDuplicateNode(graphData, nodeId, layoutMode)) return false;
  const copy = duplicateNode(graphData, nodeId, editHistory, { layoutMode });
  if (!copy) return false;
  invalidateStableSelection(instance);
  const added = instance.add(buildElements({ nodes: [copy], edges: [] })).first();
  instance.batch(() => applyVisualStyle(visualStyle, instance, owner));
  instance.$(':selected').unselect();
  added.select();
  setGraphCursor(copy.id);
  updateStats();
  scheduleMinimap();
  updateHistoryUi();
  refreshCommands();
  showNodeInfo(added);
  owner.pane?.focus({ preventScroll: true });
  addActivityMessage('editor', `Duplicated ${nodeId} as ${copy.id}`, 'completed');
  return true;
}

function duplicateSelectedNode() {
  const selected = cy?.nodes(':selected');
  if (!selected || selected.length !== 1) return false;
  return duplicateNodeInDocument(workspace.active, cy, selected.first().id());
}

function syncGraphPositions() {
  if (!cy || !graphData || graphData.format === 'graphify' || layoutMode === 'elastic') return;
  syncGraphPositionsFromCy(graphData, cy);
}

// `atBoot` marks the attempt the page makes for itself on load, with nobody watching the tab yet.
// It changes two things and nothing else: the wording of the in-flight state, and the refusal to
// raise a modal confirmation that no user asked for.
function connectRuntime(atBoot = false) {
  const input = document.getElementById('service-url');
  const baseUrl = input.value.trim().replace(/\/$/, '');
  input.value = baseUrl;
  if (baseUrl) {
    let target;
    try {
      target = new URL(baseUrl, location.origin);
      if (!['http:', 'https:'].includes(target.protocol)) throw new Error('unsupported protocol');
    } catch {
      return setRuntimeConnectionState('error', 'Enter a valid HTTP(S) service URL');
    }
    if (target.origin !== location.origin && confirmedServiceOrigin !== target.origin) {
      // A page load must never raise a confirmation the user did not ask for. The field ships
      // empty, so this only happens when a browser restores a cross-origin value across a reload:
      // the token stays put and the decision waits for the user's next deliberate action.
      if (atBoot) {
        return setRuntimeConnectionState('authentication-required',
          'Confirm the external service origin to connect');
      }
      const accepted = globalThis.confirm(
        `Connect Ravenroot UI to the external service origin exactly as shown?\n\n${target.origin}\n\n` +
        'The in-memory bearer token will be sent to this origin.');
      if (!accepted) {
        input.value = '';
        // Declining ends the previous connection as well. Every other path through this function
        // replaces the client; leaving it running here kept a connection alive that no longer
        // matched the now-empty field, and its transport retries overwrote the very message that
        // explains what just happened. That was invisible until the page began connecting on its
        // own, because before that no client existed yet at this point.
        if (runtimeDisconnect) runtimeDisconnect();
        runtimeDisconnect = null;
        runtimeClient = null;
        return setRuntimeConnectionState('authentication-required', 'External service connection cancelled');
      }
      confirmedServiceOrigin = target.origin;
    }
  }
  if (runtimeDisconnect) runtimeDisconnect();
  runtimeClient = new RavenrootRuntimeClient(baseUrl, {
    tokenProvider: runtimeTokenProvider,
  });
  // The assistant reaches THE SAME Ravenroot service with THE SAME user authentication, and
  // nothing else — it has no base URL of its own to be pointed elsewhere. That is what makes "a
  // denial to the user is a denial to the panel" true here rather than merely intended, and it is
  // why no provider host appears anywhere in this file.
  assistantClient = new RavenrootAssistantClient(baseUrl, { tokenProvider: runtimeTokenProvider });
  void refreshAssistantAvailability();
  // A construction site of the same shape, for the same reason. The credential window reaches
  // THE SAME Ravenroot service with THE SAME authentication and has no base URL of its own — which is what
  // makes "a value typed there goes to your own service and nowhere else" true by construction
  // rather than by inspection. Re-listing here is also what refreshes the node inspector's
  // SECRET_REFERENCE choices after an authentication, without the window ever being opened.
  void credentialsWindow?.setClient(
    new RavenrootCredentialClient(baseUrl, { tokenProvider: runtimeTokenProvider }));
  // the Deployments window's client IS the runtime client, not a construction of its
  // own -- `/v1/deployments` is one of `RavenrootRuntimeClient`'s own routes (see `runtime-client.js`),
  // unlike credentials, which has a transport entirely to itself.
  void deploymentsWindow?.setClient(runtimeClient);
  const connectedClient = runtimeClient;
  setRuntimeConnectionState(atBoot ? 'connecting' : 'reconnecting',
    atBoot ? 'Connecting to the service — the access token is kept in memory only'
      : 'Connecting with an in-memory bearer token');
  nodeCatalogPending = true;
  renderNodeCatalog();
  try {
    runtimeDisconnect = runtimeClient.connect(handleRuntimeEvent, (status, message) => {
      setRuntimeConnectionState(status, message);
    });
    connectedClient.nodeTypes().then(catalog => {
      if (runtimeClient !== connectedClient) return;
      nodeTypeCatalog = catalog;
      nodeCatalogFailure = null;
      nodeCatalogLoaded = true;
      nodeCatalogPending = false;
      renderNodeCatalog();
      workspace.documents.forEach(scheduleProgramGraphReadiness);
    }).catch(error => {
      if (runtimeClient !== connectedClient) return;
      nodeTypeCatalog = [];
      nodeCatalogFailure = error;
      nodeCatalogLoaded = true;
      nodeCatalogPending = false;
      renderNodeCatalog();
      addActivityMessage('catalog', catalogEmptyState(error, []).message, 'failed');
    });
  } catch (error) {
    nodeCatalogPending = false;
    renderNodeCatalog();
    setRuntimeConnectionState('error', error.message);
    addActivityMessage('connection', error.message, 'failed');
  }
}

function authenticateRuntime() {
  const input = document.getElementById('access-token');
  const token = input.value.trim();
  input.value = '';
  if (!token) {
    setRuntimeConnectionState('authentication-required', 'Paste an access token to authenticate');
    input.focus();
    return;
  }
  runtimeTokenProvider.setAccessToken(token);
  hasRuntimeToken = true;
  refreshCommands();
  connectRuntime();
}

function revokeRuntimeAccess() {
  runtimeTokenProvider.clearAccessToken();
  hasRuntimeToken = false;
  runtimeDisconnect?.();
  runtimeDisconnect = null;
  runtimeClient = null;
  document.getElementById('access-token').value = '';
  // The credential window loses its client with everything else. `setClient(null)` empties the
  // listing AND republishes `{loaded: false}`, so the node inspector's SECRET_REFERENCE control goes
  // back to preserving whatever a node already declares rather than continuing to offer a list read
  // under an authentication that has just been withdrawn.
  credentialsWindow?.close();
  void credentialsWindow?.setClient(null);
  // the deployment window loses its client with everything else, for the identical
  // reason -- a listing read under an authentication that has just been withdrawn must not linger.
  deploymentsWindow?.close();
  void deploymentsWindow?.setClient(null);
  nodeTypeCatalog = [];
  nodeCatalogFailure = null;
  nodeCatalogLoaded = false;
  nodeCatalogPending = false;
  renderNodeCatalog();
  setRuntimeConnectionState('revoked', 'Access token removed from memory');
  refreshCommands();
}

function setRuntimeConnectionState(status, message) {
  const state = document.getElementById('runtime-connection');
  state.className = `connection-state ${status}`;
  state.textContent = status.replaceAll('-', ' ');
  state.title = message;
  state.setAttribute('aria-label', `Runtime status: ${status.replaceAll('-', ' ')}. ${message}`);
}

function graphLifecycleCommand(action) {
  if (!workspace.active || !graphData) return showInspectorMessage('Create or load a workflow first.');
  if (action === 'stop' && sourceSessionIsActive(activeSourceSession)) {
    void stopActiveSourceSession(workspace.active);
    return { status: 'stopping', deploymentId: activeSourceSession.sessionId };
  }
  const result = requestGraphLifecycle(action, {
    documentId: workspace.activeId,
    graphName: graphDisplayName,
    // A transient Test graph version is deliberately not treated as a durable deployment ID.
    deploymentId: null,
  });
  const target = result.deploymentId
    ? `deployment ${shortId(result.deploymentId)}`
    : `current graph “${result.graphName || result.documentId}”`;
  const message = `${result.status}: ${result.message} The command is scoped to ${target}, never the shared ActorSystem.`;
  addActivityMessage(action === 'forceStop' ? 'Force stop' : action, message, 'failed');
  document.getElementById('info-title').textContent = `${action === 'forceStop' ? 'Force stop' : action} · not yet implemented`;
  document.getElementById('info-body').innerHTML = `<div class="info-empty">${escapeHtml(message)}</div>`;
  return result;
}

function updateSourceSession(owner, status, token = null, { observationUnavailable = false } = {}) {
  if (token && !sourceSessionCommandIsCurrent(owner, token)) return false;
  const session = owner.sourceSession;
  const changed = session.state !== status.state || session.diagnostic !== (status.diagnostic || '')
    || session.observationUnavailable !== observationUnavailable;
  session.state = status.state;
  session.sourceCount = status.sourceCount ?? session.sourceCount;
  session.diagnostic = status.diagnostic || '';
  session.observationUnavailable = observationUnavailable;
  if (owner === workspace.active) {
    syncSourceSessionChrome(owner);
    refreshCommands();
    if (changed) {
      const detail = status.diagnostic || `${status.sourceCount} source node${status.sourceCount === 1 ? '' : 's'} · local process only`;
      addActivityMessage(`Source session ${status.state.toLowerCase()}`, detail,
        status.state === 'FAILED' || status.state === 'DEGRADED' || status.state === 'UNKNOWN'
          ? 'failed' : 'completed');
    }
  }
  return true;
}

function sourceSessionCommandIsCurrent(owner, token) {
  return workspace.find(owner.id) === owner && sourceSessionCleanupIsCurrent(owner, token);
}

// Stop claims backend cleanup before it waits for a pending start. Closing the document detaches its
// projection, but does not revoke that already-claimed DELETE. Identity still fences a rebound owner.
function sourceSessionCleanupIsCurrent(owner, token) {
  return owner.sourceSession === token?.session && sourceSessionTokenIsCurrent(token);
}

async function observeSourceSession(owner, token) {
  const { client, sessionId } = token;
  const controller = new AbortController();
  owner.sourceSession.pollController?.abort();
  owner.sourceSession.pollController = controller;
  while (!controller.signal.aborted && sourceSessionCommandIsCurrent(owner, token)) {
    try {
      await new Promise(resolve => setTimeout(resolve, 250));
      if (controller.signal.aborted) break;
      const status = await client.sourceSession(sessionId, { signal: controller.signal });
      if (!sourceSessionCommandIsCurrent(owner, token)) break;
      updateSourceSession(owner, status, token);
      if (status.state === 'FAILED' || status.state === 'STOPPED') break;
    } catch (error) {
      if (controller.signal.aborted) break;
      if (!sourceSessionCommandIsCurrent(owner, token)) break;
      if (error?.status === 404) {
        updateSourceSession(owner, {
          state: 'STOPPED', sourceCount: owner.sourceSession.sourceCount,
          diagnostic: 'No source session exists in this server process.',
        }, token);
        break;
      }
      const wasUnavailable = owner.sourceSession.observationUnavailable;
      updateSourceSession(owner, {
        state: recoverSourceSessionState(owner.sourceSession.state),
        sourceCount: owner.sourceSession.sourceCount, diagnostic: '',
      }, token, { observationUnavailable: true });
      if (!wasUnavailable && owner === workspace.active) addActivityMessage('Source session status unavailable',
        'The local session may still be running. The editor will keep checking; Stop remains available.', 'failed');
    }
  }
  if (owner.sourceSession.pollController === controller) owner.sourceSession.pollController = null;
}

function nextSourceSessionId(owner) {
  return globalThis.crypto?.randomUUID?.()
    || `${owner.id}-${Date.now().toString(36)}-${owner.sourceSession.generation + 1}`;
}

async function startSourceSession(owner, client, graphMl, sourceCount) {
  const session = owner.sourceSession;
  session.pollController?.abort();
  if (!session.sessionId || session.state === 'STOPPED' || session.state === 'FAILED') {
    session.sessionId = nextSourceSessionId(owner);
  }
  session.client = client;
  session.generation += 1;
  const sessionId = session.sessionId;
  session.stopRequested = false;
  const token = captureSourceSessionToken(session);
  updateSourceSession(owner, {
    sessionId, state: 'STARTING', sourceCount, scope: 'LOCAL_PROCESS', diagnostic: null,
  }, token);
  if (owner === workspace.active) addActivityMessage('Source session request',
    'Starting listeners in this server process. No initial payload or traversal was submitted.');
  const startPromise = client.startSourceSession(sessionId, graphMl);
  session.startPromise = startPromise;
  try {
    const status = await startPromise;
    if (!sourceSessionCommandIsCurrent(owner, token)) return false;
    updateSourceSession(owner, status, token);
    if (!session.stopRequested
        && (status.state === 'STARTING' || status.state === 'LISTENING' || status.state === 'DEGRADED')) {
      void observeSourceSession(owner, token);
    }
    return true;
  } catch (error) {
    if (!sourceSessionCommandIsCurrent(owner, token)) return false;
    const explicitFailure = Number.isInteger(error?.status);
    updateSourceSession(owner, {
      state: explicitFailure ? 'FAILED' : 'UNKNOWN', sourceCount,
      diagnostic: explicitFailure ? 'The server rejected the source session start.' : '',
    }, token, { observationUnavailable: !explicitFailure });
    if (owner === workspace.active) addActivityMessage('Source session request failed',
      explicitFailure
        ? 'The server rejected the local listener start. Correct the graph or runtime issue, then retry.'
        : 'The start response was lost. The editor will observe the existing id before allowing another Run.',
      'failed');
    if (!explicitFailure && !session.stopRequested) void observeSourceSession(owner, token);
    return false;
  } finally {
    if (sourceSessionCommandIsCurrent(owner, token) && session.startPromise === startPromise) {
      session.startPromise = null;
    }
  }
}

async function stopActiveSourceSession(owner) {
  const session = owner.sourceSession;
  if (session.stopPromise) return session.stopPromise;
  if (!session.client || !session.sessionId || !sourceSessionIsActive(session)) return false;
  const token = captureSourceSessionToken(session);
  const stateBeforeStop = session.state;
  const claimedStartPromise = session.startPromise;
  session.stopRequested = true;
  updateSourceSession(owner, {
    state: 'STOPPING', sourceCount: session.sourceCount, diagnostic: null,
  }, token);
  const stopPromise = (async () => {
    try {
      try {
        await claimedStartPromise;
      } catch {
        // A rejected start is still followed by DELETE: it closes the ambiguity without a lost Stop.
      }
      if (!sourceSessionCleanupIsCurrent(owner, token)) return false;
      if (sourceSessionCommandIsCurrent(owner, token)) {
        updateSourceSession(owner, {
          state: 'STOPPING', sourceCount: session.sourceCount, diagnostic: null,
        }, token);
      }
      session.pollController?.abort();
      const status = await token.client.stopSourceSession(token.sessionId);
      if (!sourceSessionCleanupIsCurrent(owner, token)) return false;
      if (sourceSessionCommandIsCurrent(owner, token)) updateSourceSession(owner, status, token);
      return status.state === 'STOPPED';
    } catch (error) {
      if (!sourceSessionCleanupIsCurrent(owner, token)) return false;
      if (error?.status === 404) {
        if (sourceSessionCommandIsCurrent(owner, token)) {
          updateSourceSession(owner, {
            state: 'STOPPED', sourceCount: session.sourceCount,
            diagnostic: 'No source session exists in this server process.',
          }, token);
        }
        return true;
      }
      if (!sourceSessionCommandIsCurrent(owner, token)) return false;
      updateSourceSession(owner, {
        state: recoverSourceSessionState(stateBeforeStop), sourceCount: session.sourceCount, diagnostic: '',
      }, token, { observationUnavailable: true });
      if (owner === workspace.active) addActivityMessage('Source session stop failed',
        'The stop response was unavailable. The editor will keep checking and Stop can be retried.', 'failed');
      void observeSourceSession(owner, token);
      return false;
    } finally {
      if (sourceSessionCleanupIsCurrent(owner, token)) {
        session.stopRequested = false;
        if (session.stopPromise === stopPromise) session.stopPromise = null;
        if (owner === workspace.active) refreshCommands();
      }
    }
  })();
  session.stopPromise = stopPromise;
  return stopPromise;
}

// SSE is the low-latency projection, not a reliable completion latch. A terminal frame can land
// while the stream reconnects. GET failures are not completion either: after a bounded fast phase
// the execution becomes explicitly UNKNOWN and Test/Run become discoverable again, but their action
// performs a reconciliation-only preflight and sends no new POST until GET proves this run terminal.
function reportExecutionOutcome(outcome) {
  for (const message of executionOutcomeMessages(outcome)) {
    addActivityMessage(message.title, message.detail, message.css);
  }
}

function reportExecutionOutcomeOnce(owner, token, outcome) {
  if (!claimExecutionOutcomeReport(token)) return false;
  // The activity panel follows exactly one document. Claiming before this check ensures a result
  // that became terminal in the background is not replayed later into an unrelated active view.
  if (workspace.activeId !== owner.id) return false;
  reportExecutionOutcome(outcome);
  return true;
}

async function fetchAndReportExecutionOutcome(owner, token) {
  if (!token?.client || !token.executionId || token.executionId === PENDING_EXECUTION
      || !claimExecutionOutcomeFetch(token)) return;
  try {
    const outcome = await token.client.execution(token.executionId, {
      signal: executionOutcomeFetchSignal(token),
    });
    reportExecutionOutcomeOnce(owner, token, outcome);
  } catch {
    // The terminal event is still truthful. A failed lookup produces no fabricated clean/failure
    // outcome and never exposes the request error, payload or protected diagnostic in the panel.
  } finally {
    completeExecutionOutcomeFetch(token);
  }
}

function settleReconciledExecution(owner, executionId, client, generation, outcome, recoveredFromUnknown,
  { fetchOutcome = false } = {}) {
  if (owner.execution.finished.has(executionId)) return false;
  const token = captureExecutionOutcomeToken(owner.execution, executionId, client, generation);
  if (!token) return false;
  owner.execution.finished.add(executionId);
  owner.execution.reconciliationController?.abort();
  owner.execution.reconciliationController = null;
  setExecutionReconciliationState(owner, 'known');
  if (fetchOutcome) void fetchAndReportExecutionOutcome(owner, token);
  else reportExecutionOutcomeOnce(owner, token, outcome);
  if (workspace.activeId === owner.id && recoveredFromUnknown) {
    addActivityMessage('Execution status recovered',
      `Execution ${shortId(executionId)} is ${String(outcome.status).toLowerCase()}. Test and Run are available again.`,
      'completed');
    document.getElementById('activity-summary').textContent =
      `Reconciled ${outcome.status} · execution ${shortId(executionId)}`;
    document.getElementById('activity-summary').dataset.executionReconciliation = 'recovered';
    document.getElementById('activity-summary').removeAttribute('aria-label');
  }
  return true;
}

async function reconcileTestCompletion(owner, executionId, client) {
  const generation = owner.execution.generation;
  const controller = new AbortController();
  owner.execution.reconciliationController = controller;
  try {
    return await reconcileExecution({
      lookup: () => client.execution(executionId, { signal: controller.signal }),
      isCurrent: () => owner.execution.executionId === executionId
        && !owner.execution.finished.has(executionId),
      onUnknown: ({ error, failureCount }) => {
        setExecutionReconciliationState(owner, 'unknown');
        if (owner.id !== workspace.activeId) return;
        const reason = error?.message || 'the runtime returned no readable reason';
        addActivityMessage('Execution status unknown',
          `Execution ${shortId(executionId)} could not be reconciled after ${failureCount} checks: ${reason}. `
          + 'Test and Run now perform a status check before they can submit. Reconnect to the runtime '
          + 'or inspect this execution; automatic reconciliation continues.',
          'failed');
        document.getElementById('activity-summary').textContent =
          `Status unknown · execution ${shortId(executionId)} · Test/Run will check first`;
        document.getElementById('activity-summary').dataset.executionReconciliation = 'unknown';
        document.getElementById('activity-summary').setAttribute('aria-label',
          `Execution ${executionId} status is unknown. Test and Run will check it before submitting.`);
      },
      onKnown: outcome => {
        setExecutionReconciliationState(owner, 'known');
        if (owner.id !== workspace.activeId) return;
        syncExecutionReconciliationChrome(true);
        addActivityMessage('Execution status available',
          `Execution ${shortId(executionId)} is ${String(outcome?.status || 'non-terminal').toLowerCase()}. `
          + 'Test and Run remain unavailable while it is active.');
      },
      onTerminal: ({ outcome, recoveredFromUnknown }) => {
        settleReconciledExecution(owner, executionId, client, generation, outcome, recoveredFromUnknown);
      },
    });
  } finally {
    if (owner.execution.reconciliationController === controller) {
      owner.execution.reconciliationController = null;
    }
  }
}

async function preflightUnknownExecution(owner, flight) {
  const executionId = flight.executionId;
  if (!flight.unknown || !executionId || owner.execution.finished.has(executionId)) return true;
  const result = await preflightBoundExecutionCommand({
    flight,
    onTerminal: outcome => {
      if (executionCommandIsCurrent(flight)) {
        settleReconciledExecution(owner, executionId, flight.client, flight.generation, outcome, true);
      }
    },
    onNonTerminal: outcome => {
      if (!executionCommandIsCurrent(flight)) return;
      setExecutionReconciliationState(owner, 'known');
      if (workspace.activeId === owner.id) {
        syncExecutionReconciliationChrome(true);
        addActivityMessage('Execution still active',
          `Execution ${shortId(executionId)} is ${String(outcome?.status || 'non-terminal').toLowerCase()}. `
          + 'No new execution was submitted.', 'failed');
      }
    },
    onUnavailable: error => {
      if (executionCommandIsCurrent(flight) && workspace.activeId === owner.id) {
        addActivityMessage('Execution status still unknown',
          `Execution ${shortId(executionId)} could not be checked: ${error.message}. `
          + 'No new execution was submitted; reconnect to the runtime or inspect this execution.', 'failed');
      }
    },
  });
  return result.allowed;
}

async function playGraph(mode = 'test') {
  const owner = workspace.active;
  const ownerGraph = graphData;
  if (!owner || !ownerGraph) return showInspectorMessage('Create or load a workflow first.');
  if (programNodes(ownerGraph).length) {
    const ready = await ensureProgramGraphReady(owner, { automatic: true });
    if (!ready) {
      const phases = programReadiness(owner).phases;
      const blocked = programNodes(ownerGraph).find(node => phases.get(node.id)?.phase !== 'READY');
      const state = blocked && phases.get(blocked.id);
      document.getElementById('info-title').textContent = 'Program readiness';
      document.getElementById('info-body').innerHTML = `<div class="info-sec"><h4>Cannot execute</h4>
        <p>${escapeHtml(blocked ? `${blocked.id}: ${state?.detail || 'artifact is not ACTIVE'}`
          : 'A program artifact is not ACTIVE')}</p></div>`;
      addActivityMessage('program readiness', 'Graph execution is gated until every program artifact is ACTIVE',
        'failed');
      return;
    }
  }
  const flight = acquireExecutionCommand(owner.execution);
  if (!flight) {
    addActivityMessage('Execution status check already in progress',
      'No new execution was submitted. Wait for the current status check to finish.', 'failed');
    return;
  }

  // Everything a later POST can consume is captured before the first await. The graph is serialized
  // now, not read from whichever document may become active while a delayed terminal GET is pending.
  syncGraphPositions();
  const graphMl = serializeGraphML(ownerGraph);
  const payload = document.getElementById('execution-payload').value;
  const displayName = graphDisplayName;
  const ownerCy = cy;
  const ownerLayoutMode = layoutMode;
  const ownerVisualStyle = visualStyle;
  const violations = validateWorkflow(ownerGraph);
  if (violations.length) {
    releaseExecutionCommand(flight);
    document.getElementById('info-title').textContent = 'Validation';
    document.getElementById('info-body').innerHTML = `<div class="info-sec"><h4>Cannot execute</h4>
      <ul class="validation-list">${violations.map(item => `<li>${escapeHtml(item)}</li>`).join('')}</ul></div>`;
    addActivityMessage('validation', `${violations.length} violation(s)`, 'failed');
    return;
  }
  if (mode === 'run' && (!nodeCatalogLoaded || nodeCatalogFailure)) {
    releaseExecutionCommand(flight);
    const message = nodeCatalogFailure
      ? 'The trusted node catalog is unavailable, so Run cannot safely decide whether this graph requires a listener session.'
      : 'The trusted node catalog is still loading. Wait for it before running this graph.';
    addActivityMessage('Run unavailable', message, 'failed');
    return;
  }
  const sourceCount = mode === 'run' ? effectiveSourceCount(ownerGraph, nodeTypeCatalog) : 0;
  // Run has two meanings. A graph with an
  // effective SOURCE starts a local listener session (unchanged below). Every other graph gets
  // a real one-shot execution with effects (mode=run). The deployments panel lets that graph also be
  // "registered and controlled as a local deployment" without
  // hijacking this button. This is the Deployments-window contract.
  const confirmation = sourceCount > 0
    ? `Start “${displayName}” as a local listener session?\n\n${sourceCount} inbound source node${sourceCount === 1 ? '' : 's'} will listen in this server process until you press Stop. No initial payload or traversal is sent; each later external event starts its own traversal.`
    : `Run “${displayName}” now?\n\nThis is a real operational execution: node behaviors may send messages, call external services, write data, or perform other configured effects.`;
  if (mode === 'run' && !globalThis.confirm(confirmation)) {
    releaseExecutionCommand(flight);
    return;
  }
  if (!runtimeClient) connectRuntime();
  const executionClient = runtimeClient;
  if (!await preflightUnknownExecution(owner, flight)) {
    releaseExecutionCommand(flight);
    return;
  }
  const ownerStillActive = workspace.activeId === owner.id && workspace.find(owner.id) === owner;
  if (!ownerStillActive || !executionCommandIsCurrent(flight)) {
    releaseExecutionCommand(flight);
    return;
  }

  // Only a graph with an effective SOURCE
  // starts a listener session here; a source-less Run falls through to the one-shot submission below,
  // as required by the confirmation-text contract above.
  if (sourceCount > 0) {
    try {
      await startSourceSession(owner, executionClient, graphMl, sourceCount);
    } catch (error) {
      // startSourceSession normally converts request failures to an honest, fenced lifecycle state.
      // Keep this guard for unexpected programming failures without mutating an unrelated binding.
      if (workspace.find(owner.id) === owner && sourceSessionIsActive(owner.sourceSession)) {
        const token = captureSourceSessionToken(owner.sourceSession);
        updateSourceSession(owner, {
          state: 'UNKNOWN', sourceCount: owner.sourceSession.sourceCount, diagnostic: '',
        }, token, { observationUnavailable: true });
      }
    } finally {
      releaseExecutionCommand(flight);
      refreshCommands();
    }
    return;
  }

  const cancelledOutcome = enforceExecutionOutcomeCapacity(owner.execution);
  if (cancelledOutcome) {
    addActivityMessage('Outcome lookup cancelled',
      `Execution ${shortId(cancelledOutcome.executionId)} outcome details were still unavailable when a newer `
      + 'run needed the bounded monitoring slot. Its pending lookup was cancelled; inspect that execution '
      + 'in the runtime if those details are required.', 'failed');
  }

  resetRuntimeState(owner, ownerCy, ownerGraph, ownerLayoutMode, ownerVisualStyle);
  setDocumentExecution(owner, PENDING_EXECUTION, null, executionClient);
  refreshCommands();
  addActivityMessage('request', mode === 'run'
    ? 'Submitting immutable GraphML snapshot for real node execution…'
    : 'Submitting immutable GraphML snapshot for passthrough test…');
  try {
    const submission = await executionClient[mode === 'run' ? 'run' : 'start'](
      graphMl, payload);
    if (workspace.find(owner.id) !== owner || owner.execution.executionId !== PENDING_EXECUTION
        || owner.execution.reconciliationClient !== executionClient) return;
    setDocumentExecution(owner, submission.executionId, submission.graphVersion, executionClient,
      submission.processInstanceId ?? null);
    void reconcileTestCompletion(owner, submission.executionId, executionClient);
    if (workspace.activeId !== owner.id) return;
    addActivityMessage('accepted',
      `${submission.executionPolicy || 'policy unreported'} · execution ${shortId(submission.executionId)} · graph ${shortId(submission.graphVersion)}`,
      'completed');
    if (owner.execution.finished.has(submission.executionId)) refreshCommands();
  } catch (error) {
    if (workspace.find(owner.id) !== owner || owner.execution.executionId !== PENDING_EXECUTION
        || owner.execution.reconciliationClient !== executionClient) return;
    setDocumentExecution(owner, null, null);
    if (workspace.activeId !== owner.id) return;
    refreshCommands();
    addActivityMessage('request failed', error.message, 'failed');
    showInspectorMessage(error.message);
  }
}

// One stream serves every open document, so the first question is which document the event is
// about. An event that matches no open document is dropped: painting it on whichever graph happens
// to be in front of the user is how a run in one document used to light up another.
function handleRuntimeEvent(event) {
  const target = documentForRuntimeEvent(workspace, event);
  if (!target) return;
  const isTerminal = event.type === 'EXECUTION_COMPLETED' || event.type === 'EXECUTION_FAILED';
  const isActive = target === workspace.active;
  // The activity log is one panel and follows the active document, so only its events are logged.
  if (isActive) appendActivityEvent(event);

  // The assistant's `events` tail is written to THE DOCUMENT THAT OWNS THE RUN, active or
  // not — the same rule `execution.finished` below already follows. A background document's run
  // belongs to that document, and because the buffer is on its record it can never be attached to
  // a question about a different graph.
  const eventTail = target.execution.events;
  // METADATA ONLY. Legacy `detail` and the bounded author-only `message`/`output` diagnostics never
  // enter the assistant's copy — see the closed allowlist in `runtimeEventProjection`.
  eventTail.push(runtimeEventProjection(event));
  if (eventTail.length > RUNTIME_EVENT_TAIL) {
    eventTail.splice(0, eventTail.length - RUNTIME_EVENT_TAIL);
  }
  // The `events` chip has just changed meaning, so it is recomposed here rather than waiting for
  // an unrelated graph edit to call `updateStats()`. Found by the cross-document provenance test:
  // without this the chip read "unavailable" all the way through a live run, which is the
  // false-context defect pointing the other way — understating context is still lying about it.
  // Cheap because `refreshAssistantContext` returns immediately while the panel is closed, which
  // is its default state.
  if (isActive) refreshAssistantContext();

  if (isTerminal) {
    const recoveredFromUnknown = target.execution.reconciliationState === 'unknown';
    settleReconciledExecution(target, event.executionId, target.execution.reconciliationClient,
      target.execution.generation, {
      status: event.type === 'EXECUTION_COMPLETED' ? 'COMPLETED' : 'FAILED',
    }, recoveredFromUnknown, { fetchOutcome: true });
    const binding = target.execution.executionId;
    if (isActive && (binding === event.executionId || binding === PENDING_EXECUTION)) {
      refreshCommands();
    }
  }

  // Past this point the event paints nodes, and it paints them on the document that owns the run —
  // which is not necessarily the one on screen.
  const targetCy = isActive ? cy : target.cy;
  const targetGraph = isActive ? graphData : target.graph;
  if (event.type === 'EDGE_TRAVERSED') {
    target.execution.monitoringFlow ||= createMonitoringRuntimeState();
    const knownEdgeIds = new Set((targetGraph?.edges || []).map(edge => edge.id));
    const observation = observeEdgeTraversal(target.execution.monitoringFlow, event, { knownEdgeIds });
    if (observation.changed) updateD3RuntimeEdge(target, observation.edgeId);
    return;
  }
  if (!event.nodeId || !targetCy) return;
  const node = targetCy.getElementById(event.nodeId);
  if (!node.length) return;

  let state = node.data('runtimeState') || 'idle';
  if (event.type === 'NODE_STARTED') state = 'active';
  if (event.type === 'NODE_DEFAULTED') state = 'fallback';
  if (event.type === 'NODE_BYPASSED') state = 'bypassed';
  if (event.type === 'NODE_COMPLETED' && state !== 'fallback' && state !== 'bypassed') state = 'completed';
  if (event.type === 'NODE_FAILED') state = 'failed';
  // A retried attempt leaves the node ACTIVE, not failed: the visit has not settled, and the next
  // attempt's own NODE_STARTED is already on its way. Rendering it as failed and then back to active
  // would flash a terminal state the traversal never reached.
  if (event.type === 'NODE_RETRY_SCHEDULED') state = 'active';
  // `activeInstances` is the count of LIVE INSTANCES of this node's actor -- 1 for a resident
  // nature however much traffic crosses it, one per concurrent invocation for the default one. It is
  // what the node is rendered by. `inFlightArrivals` is the queue depth and is deliberately kept in a
  // separate field: the two are equal for an ordinary worker node and differ for a resident one, and
  // rendering the second under the first's name reports the wrong quantity.
  const instances = Number(event.activeInstances) || 0;
  const arrivals = Number(event.inFlightArrivals) || 0;
  node.data('instances', instances);
  node.data('arrivals', arrivals);
  node.data('runtimeState', state);
  node.data('runtimeObserved', true);
  node.data('lastEventType', event.type);
  node.data('lastOccurredAt', event.occurredAt || null);
  node.data('processingDuration', event.processingDuration ?? null);
  node.data('fallback', Boolean(event.fallback));
  const model = targetGraph?.nodeMap[event.nodeId];
  if (model) {
    model.instances = instances;
    model.arrivals = arrivals;
    model.runtimeState = state;
    model.runtimeObserved = true;
    model.lastEventType = event.type;
    model.lastOccurredAt = event.occurredAt || null;
    model.processingDuration = event.processingDuration ?? null;
    model.fallback = Boolean(event.fallback);
  }
  applyRuntimeVisual(node);
  updateD3RuntimeNode(target, event.nodeId, instances, state, arrivals, event);
}

function resetRuntimeState(owner, targetCy, targetGraph, targetLayoutMode, targetVisualStyle) {
  owner.execution.monitoringFlow ||= createMonitoringRuntimeState();
  resetMonitoringRuntimeState(owner.execution.monitoringFlow, null);
  if (!targetCy) return;
  targetCy.nodes().forEach(node => {
    node.removeStyle('border-color border-width underlay-color underlay-opacity underlay-padding label');
    node.data('instances', 0);
    node.data('runtimeState', 'idle');
    node.data('runtimeObserved', false);
    node.data('lastEventType', '');
    node.data('lastOccurredAt', '');
    node.data('processingDuration', null);
    node.data('fallback', false);
    // `removeStyle` drops the run's inline border and hands the node back to the stylesheet,
    // where `node[?bypassed]` still applies -- the flag is a property of the DOCUMENT, not of the
    // run, so clearing run state must not clear it. The label is rebuilt through the same helper for
    // the same reason: the marker belongs to the idle node too.
    node.data('label',
      `${NODE_ICONS[node.data('nodeType')] || '• '}${bypassedNodeName(node.data('name'), node.data('bypassed'))}`);
    const model = targetGraph.nodeMap[node.id()];
    if (model) {
      model.instances = 0;
      model.runtimeState = 'idle';
      model.runtimeObserved = false;
      model.lastEventType = null;
      model.lastOccurredAt = null;
      model.processingDuration = null;
      model.fallback = false;
    }
  });
  if (targetLayoutMode === 'elastic') {
    startD3Elastic(owner, targetCy, owner.layoutSessionToken);
  } else if (isN8nFamilyLayout(targetVisualStyle)) {
    applyN8nNodeStyle(targetCy, owner);
  }
}

function runtimeColor(state) {
  if (state === 'active') return rendererPalette.selection;
  if (state === 'completed') return rendererPalette.edgeType.completed;
  if (state === 'fallback') return rendererPalette.edgeType.validate;
  if (state === 'bypassed') return rendererPalette.edgeType.outcome;
  if (state === 'failed') return rendererPalette.edgeType.failed;
  return rendererPalette.runtimeIdle;
}

/**
 * The runtime caption for a node, in both renderers.
 *
 * <p>It says "instance"/"instances" rather than the old "N active" because "active" never said active
 * *what*, and the number behind it was the wrong quantity: arrivals waiting on one shared actor were
 * being read as a workforce. The word now names what is counted, so the caption is checkable against
 * the thing it describes.
 *
 * <p>The queue depth is appended only when it exceeds the instance count, which is exactly when the two
 * differ and therefore the only time the second number tells the reader anything the first did not.
 * For an ordinary worker node they are equal and one number is shown -- printing "10 instances · 10 in
 * flight" everywhere would train the eye to ignore a pair that matters precisely when it stops matching.
 */
function runtimeCountLabel(name, instances, arrivals = 0) {
  if (!(instances > 0)) return name;
  const noun = instances === 1 ? 'instance' : 'instances';
  const queued = arrivals > instances ? ` · ${arrivals} in flight` : '';
  return `${name} · ${instances} ${noun}${queued}`;
}

function runtimeNodeLabel(node) {
  const instances = Number(node.data('instances')) || 0;
  const arrivals = Number(node.data('arrivals')) || 0;
  // The switched-off marker rides on the node's name, so it survives a run painting over the
  // label. A bypassed node can still report instances -- a run that crosses it emits NODE_BYPASSED
  // with a count -- and the two facts belong on the same label, not one replacing the other.
  const name = bypassedNodeName(node.data('name'), node.data('bypassed'));
  if (!(instances > 0)) return name;
  // Same text as the elastic caption, on the line break this renderer uses. Composed against the RAW
  // name and recombined with the display name afterwards, so the `.replace` below keeps operating on
  // the one separator pinned it to: the bypass marker uses the same ` · `, and a first-match
  // replace over the display name would put the line break INSIDE the name instead of after it.
  const [, stats] = runtimeCountLabel(node.data('name'), instances, arrivals)
    .replace(' · ', '\n').split('\n');
  return `${name}\n${stats}`;
}

function applyRuntimeVisual(node) {
  const state = node.data('runtimeState') || 'idle';
  const active = Number(node.data('instances')) || 0;
  node.data('label', `${NODE_ICONS[node.data('nodeType')] || '• '}${runtimeNodeLabel(node)}`);
  if (isN8nFamilyLayout()) node.style('label', runtimeNodeLabel(node));
  if (state === 'idle') return;
  const color = runtimeColor(state);
  node.style({
    'border-color': color,
    'border-width': state === 'active' ? 6 : 4,
    'underlay-color': color,
    'underlay-opacity': state === 'active' ? 0.28 : 0.12,
    'underlay-padding': state === 'active' ? 12 + Math.min(active, 8) * 2 : 7,
  });
}

function updateD3RuntimeNode(owner, nodeId, activeInstances, state, inFlightArrivals = 0, event = {}) {
  const renderer = elasticRendererFor(owner);
  if (!renderer?.nodes || !renderer.nodeSelection) return;
  const datum = renderer.nodes.find(node => node.id === nodeId);
  if (!datum) return;
  datum.instances = activeInstances;
  datum.arrivals = inFlightArrivals;
  datum.runtimeState = state;
  datum.runtimeObserved = true;
  datum.lastEventType = event.type || null;
  datum.lastOccurredAt = event.occurredAt || null;
  datum.processingDuration = event.processingDuration ?? null;
  datum.fallback = Boolean(event.fallback);
  // Sized by instances, which is the workload of the node AS A ROLE. A resident node
  // therefore keeps a constant radius under any load instead of swelling with its queue -- that
  // swelling was the visible symptom of the wrong number, not a feature being lost here.
  datum.r = Math.max(9, Math.min(34, 10 + Math.sqrt(Math.max(0, activeInstances)) * 7));
  renderer.nodeSelection.filter(node => node.id === nodeId)
    .transition().duration(180)
    .attr('r', datum.r)
    .attr('stroke', runtimeColor(state))
    .attr('stroke-width', state === 'active' ? 5 : 3);
  if (renderer.nodeLabelSelection) {
    renderer.nodeLabelSelection.filter(node => node.id === nodeId)
      .text(node => runtimeCountLabel(node.label, activeInstances, inFlightArrivals));
  }
  if (renderer.simulation && rendererSessions.isLive(renderer.token)) {
    renderer.simulation.force('collision', d3.forceCollide().radius(node => node.r + 8));
    renderer.simulation.alpha(0.22).restart();
  }
  renderer.updateNode?.(nodeId, datum);
}

function updateD3RuntimeEdge(owner, edgeId) {
  const renderer = elasticRendererFor(owner);
  if (!renderer?.updateEdgeFlow || !rendererSessions.isLive(renderer.token)) return;
  const paint = () => {
    if (!rendererSessions.isLive(renderer.token)) return;
    const flow = edgeFlowSnapshot(owner.execution.monitoringFlow, edgeId);
    const remainingPulseMs = flow.expiresAt == null ? 0 : Math.max(0, flow.expiresAt - Date.now());
    renderer.updateEdgeFlow(edgeId, flow, {
      reducedMotion: window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true,
      decayMs: remainingPulseMs || FLOW_PULSE_MS,
      onDecay: flow.recent > 0 ? paint : null,
    });
    scheduleMinimap(owner);
  };
  paint();
}

function rehydrateD3RuntimeEdges(owner) {
  const renderer = elasticRendererFor(owner);
  if (!renderer?.links || !rendererSessions.isLive(renderer.token)) return;
  renderer.links.forEach(link => updateD3RuntimeEdge(owner, link.id));
}

// Process, traversal and invocation are not synonyms — a process is the long-lived instance,
// a traversal is one crossing of the graph (a process can have several, which is the whole point of
// A new traversal resumes the same process after a wait), and an invocation is one node
// executing inside a traversal, carrying attempts beneath it. Flat, labelled rows, not collapsible
// groups: the log is a live region events arrive into continuously, and a group header appearing or
// a row re-parenting as events land is exactly the focus-stability/over-announcement problem
// exists to avoid — introducing it here a day later would undo that. Each row instead states which
// process/traversal/invocation/attempt it belongs to as plain text, inside the SAME `#activity-log`
// entry that `aria-live="polite"` already announces (index.html) — nothing new to make accessible.
//
// This renders ONLY what `event` itself asserts: sharing a `processInstanceId` is a fact the event
// carries, but nothing about ORDER or RESUMPTION CAUSALITY between two traversals is on the event,
// so nothing here claims it — nesting or sequencing traversals would be inventing a relationship the
// data does not support.
function activityIdentifiersHtml(event) {
  const identifiers = [
    ['process', event.processInstanceId],
    ['traversal', event.traversalId],
    ['invocation', event.invocationId],
    ['attempt', event.attemptId],
  ];
  // A fifth level, and the only one rendered conditionally. The four above are the execution
  // hierarchy every event sits somewhere in, so an em dash for an absent one reads as "above that
  // level". A handler is not part of that hierarchy: it is the durable wait a process is parked on,
  // and only handler-lifecycle events carry one. Emitting an empty `handler —` on every node event
  // would assert that every event has a handler slot, which is the opposite of the distinction this
  // row exists to make.
  if (event.handlerId) {
    identifiers.push(['handler', event.handlerId]);
  }
  return identifiers.map(([kind, value]) =>
    `<span class="activity-id" data-id-kind="${kind}">${escapeHtml(kind)} <code>${escapeHtml(shortId(value))}</code></span>`
  ).join('');
}

function appendActivityEvent(event) {
  const type = String(event.type || 'EVENT');
  const css = type.includes('FAILED') ? 'failed'
    : type.includes('DEFAULTED') ? 'fallback'
    : type.includes('BYPASSED') ? 'bypassed'
    : type.includes('COMPLETED') ? 'completed' : '';
  const title = event.nodeId ? `${type} · ${event.nodeId}` : type;
  // Named for what each number is. `active=` was the old label and it named neither.
  const counts = event.nodeId
    ? ` · instances=${Number(event.activeInstances) || 0} inFlight=${Number(event.inFlightArrivals) || 0}`
    : '';
  // `detail` is an internal diagnostic channel and may contain exception/payload text. Older peers
  // send no public description, so their safe rendering is the fixed type fallback -- never detail.
  const message = runtimeActivityMessage(event.message, {
    redacted: event.messageRedacted,
    truncated: event.messageTruncated,
  });
  const output = Object.hasOwn(event, 'output') ? runtimeActivityOutput(event.output, {
    redacted: event.outputRedacted,
    truncated: event.outputTruncated,
  }) : null;
  const diagnosticFlags = projection => [projection.redacted ? 'redacted' : '', projection.truncated ? 'truncated' : '']
    .filter(Boolean).join(', ');
  const detail = `${publicExecutionDescription(event.description, type, event.publicReason)}${counts}`
    + (message.value ? ` · ${message.value}${diagnosticFlags(message) ? ` (${diagnosticFlags(message)})` : ''}` : '')
    + (output ? ` · output=${output.displayValue}${diagnosticFlags(output) ? ` (${diagnosticFlags(output)})` : ''}` : '');
  appendActivity(title, detail, css, event.occurredAt, activityIdentifiersHtml(event));
  document.getElementById('activity-summary').textContent =
    `${event.engineId || 'engine'} · execution ${shortId(event.executionId)}`;
  if (activeExecutionReconciliation === 'unknown') syncExecutionReconciliationChrome(true);
}

function addActivityMessage(title, detail, css = '') {
  appendActivity(title, detail, css, new Date().toISOString());
}

// `identifiersHtml` is optional: `addActivityMessage`'s own synthetic UI messages ("Submitting…",
// "request failed") are not about one specific execution event and carry no identifiers to show.
function appendActivity(title, detail, css, occurredAt, identifiersHtml = '') {
  const log = document.getElementById('activity-log');
  const time = new Date(occurredAt).toLocaleTimeString([], { hour12: false });
  const entry = document.createElement('div');
  entry.className = `activity-entry ${css}`;
  const timeElement = document.createElement('span');
  timeElement.className = 'activity-time';
  timeElement.textContent = time;
  const dot = document.createElement('span');
  dot.className = 'activity-dot';
  const content = document.createElement('div');
  content.className = 'activity-content';
  const titleElement = document.createElement('div');
  titleElement.className = 'activity-title';
  titleElement.textContent = String(title || '');
  const detailElement = document.createElement('div');
  detailElement.className = 'activity-detail';
  // A text sink by construction: markup, URLs and entity syntax remain literal log text.
  detailElement.textContent = String(detail || '');
  content.append(titleElement, detailElement);
  if (identifiersHtml) {
    const identifiersRow = document.createElement('div');
    identifiersRow.className = 'activity-ids';
    // Produced only by activityIdentifiersHtml, which escapes every value before returning markup.
    identifiersRow.innerHTML = identifiersHtml;
    content.append(identifiersRow);
  }
  entry.append(timeElement, dot, content);
  log.append(entry);
  while (log.children.length > 400) log.firstElementChild.remove();
  log.scrollTop = log.scrollHeight;
}

function clearActivity() {
  document.getElementById('activity-log').innerHTML = '';
  const summary = document.getElementById('activity-summary');
  summary.textContent = 'Waiting for events';
  delete summary.dataset.executionReconciliation;
  summary.removeAttribute('aria-label');
  if (activeExecutionReconciliation === 'unknown') syncExecutionReconciliationChrome(true);
}

// ═══════════════════════════════════════════════════════════════
// AUTHORING ASSISTANT (ADR 0025) — CONTROLLED GRAPH PROPOSALS
// ═══════════════════════════════════════════════════════════════
//
// Transcript text remains inert. Only the optional typed proposal member can produce a preview, and
// only an explicit per-proposal click can route its revalidated composite command into history.
//
// The server-side Assistant is optional by deployment. An absent route remains a named inert state;
// there is no echo, simulation, or local proposal fallback.

// The sources for the six context classes, as functions. `composeContext` calls each exactly once.
// A function that THROWS means the source was lost (the panel goes DEGRADED); one that returns
// `null` means there is simply nothing yet; an id absent from this object means the class is not
// wired in this build. Those three are different facts and the chips keep them different.
function assistantContextSources() {
  return {
    graph: () => (graphData ? assistantGraphSummary() : null),

    // A catalog that FAILED to load is not the same as one that has not loaded yet, and the panel
    // must not read "unavailable" identically for both — the first is a broken connection the user
    // can act on, the second is a boot still in progress.
    catalog: () => {
      // `catalogEmptyState` is the product's OWN sentence for a failed catalog, already shown in
      // the palette. Reusing it keeps the chip and the palette telling one story, and — the reason
      // this is not the raw error — keeps a transport artifact out of the UI: the raw failure here
      // was `Unexpected token 'o', "not found" is not valid JSON`, which describes a JSON parser,
      // not anything the reader can act on.
      if (nodeCatalogFailure) {
        throw new Error(catalogEmptyState(nodeCatalogFailure, null, false).message);
      }
      if (!nodeCatalogLoaded || !nodeTypeCatalog.length) return null;
      return assistantCatalogSnapshot(nodeTypeCatalog);
    },

    validation: () => assistantValidationFindings(),

    // METADATA ONLY: execution payloads can carry tenant data, so the safe default exposes the shape
    // of the run and never its contents.
    execution: () => (activeExecutionId
      ? {
        executionId: activeExecutionId,
        graphVersion: activeGraphVersion,
        finished: finishedExecutions.has(activeExecutionId),
        note: 'metadata only; result payloads are not attached',
      }
      : null),

    // Tier 0 in ADR 0025's scheme: the product's own server-side logs, reached in-process under the
    // author's own SecurityContext. THAT PATH IS PART OF THE ASSISTANT SERVICE, WHICH DOES NOT
    // EXIST YET, so the class is deliberately not wired and its chip says so. Claiming it from the
    // browser's own console buffer would be a different thing wearing the same label.
    // (Intentionally absent: `logs`.)

    // The active document's tail, so the class can only ever describe the graph the question is
    // about. A copy, so a later event cannot alter a payload already composed.
    events: () => (recentRuntimeEvents.length ? recentRuntimeEvents.slice() : null),
  };
}

// A summary, not the source XML: the assistant needs the shape of the graph, and the raw document
// is both larger and — for a `graphml` document — full of presentation attributes that answer
// nothing. Credential material never reaches here because it is not in these fields; node property
// VALUES are deliberately excluded for the same reason ADR 0018's redaction obligation exists.
function assistantGraphSummary() {
  return graphSummary(graphData, graphDisplayName);
}

// The UI has no aggregate graph validator today, so this attaches THE CHECKS IT ACTUALLY RAN rather
// than a class called "validation errors" that quietly means something narrower. `checks` is part
// of the payload for that reason: a reader — human or model — can see the boundary of the claim.
function assistantValidationFindings() {
  return validationFindings(graphData);
}

// Recomposed rather than cached, because a cached context is a context that can go stale between
// the chip the user read and the payload that was sent — which is the false-context defect with an
// extra step.
//
// BUT ONLY WHILE THE PANEL IS OPEN. Composition is the expensive half: it serialises the whole
// graph summary and the catalog to measure them against the budget, and walks every node and edge
// again for the validation findings. This runs from `updateStats()`, which fires on every node
// create, delete, edge reconnect, document activate and graph init — and THE PANEL SHIPS CLOSED,
// so without this gate every user pays that cost forever whether or not they ever open it.
// Guarding only the payload view would guard the cheap half while leaving composition unconditional.
//
// Nothing goes stale: `openAssistantPanel` below recomposes on the way open, and
// `submitAssistantDraft` recomposes immediately before it reads the payload, so what is sent is
// always freshly composed regardless of what the chips last rendered.
function refreshAssistantContext() {
  if (!isAssistantPanelOpen()) return;
  assistantContext = composeContext(assistantContextSources());
  renderAssistantChips();
  renderAssistantPayload();
  renderAssistantState();
}

function isAssistantPanelOpen() {
  const entry = panelLayout?.panels?.find(panel => panel.id === 'assistant');
  return Boolean(entry) && !entry.closed;
}

// The chips and `#assistant-payload` are BOTH projections of `assistantContext`, which is itself a
// single pass in `assistant-context.js`. Neither is allowed to describe the context independently:
// that is what makes "attached" a claim a test can falsify rather than a label a renderer writes.
function renderAssistantChips() {
  const host = document.getElementById('assistant-chips');
  if (!host || !assistantContext) return;
  host.innerHTML = assistantContext.classes.map(entry => `<li class="assistant-chip"
      data-context-class="${escapeAttribute(entry.id)}" data-state="${escapeAttribute(entry.state)}"
      data-reason="${escapeAttribute(entry.reason || '')}"
    ><span class="assistant-chip-mark" aria-hidden="true">${entry.state === ATTACHED ? '●' : '○'}</span>${escapeHtml(contextChipDescription(entry))}</li>`).join('');
}

function renderAssistantPayload() {
  const view = document.getElementById('assistant-payload');
  if (!view || !assistantContext) return;
  // Only refresh the text while it is open; a hidden inspector does not need to be kept warm, and
  // serialising a large graph on every keystroke would be felt.
  if (view.hidden) return;
  view.textContent = JSON.stringify(assistantContext.payload, null, 2);
}

function renderAssistantState() {
  const banner = document.getElementById('assistant-state');
  const draft = document.getElementById('assistant-draft');
  const send = document.getElementById('assistant-send');
  if (!banner || !draft || !send) return;

  const state = deriveState({
    availability: assistantAvailability, context: assistantContext, error: assistantError,
  });

  banner.dataset.state = state.state;
  const text = state.message
    || (assistantBusy ? 'Waiting for the assistant…' : 'Ready. Graph proposals require confirmation.');
  // Written only when it CHANGES. A polite live region re-announces on every write, and a render
  // triggered by an unrelated edit must not make a screen reader repeat a sentence nothing altered.
  if (banner.textContent !== text) banner.textContent = text;

  // ── FOCUS MUST NOT BE DROPPED BY A STATE CHANGE THE USER DID NOT MAKE ──────────────────────────
  //
  // Disabling the element that currently holds focus sends focus to `<body>`, which is a silent
  // loss: keyboard users restart from the top of the document and screen-reader users get no
  // announcement of where they now are. Two transitions here can do it — the send button
  // disabling while a request is in flight, and the whole composer disabling when the service
  // turns out to be gone — and neither is a user action, so neither may move focus by accident.
  //
  // So the composer stays usable WHILE BUSY (the next question can be typed while the last is
  // answered, which is also why the textarea does not follow `assistantBusy`), and when a focused
  // control must be disabled anyway, focus is handed deliberately to the nearest still-usable
  // thing: the textarea if it survives, otherwise the transcript, which is where the notice
  // explaining the change has just landed.
  const composable = state.canCompose;
  const sendable = composable && !assistantBusy;
  const focused = document.activeElement;
  if (!sendable && focused === send) moveAssistantFocus(composable ? draft : null);
  if (!composable && focused === draft) moveAssistantFocus(null);

  draft.disabled = !composable;
  send.disabled = !sendable;

  renderAssistantConnection(state);
}

// ── THE CONNECTION CONTROL ────────────────────────────────────────────────────────────────
//
// Visibility comes from `offersConnection`, which is the only place the condition is written. This
// function decides nothing about WHEN — it renders what that predicate already decided, so widening
// the condition means editing the module the test table points at rather than this render pass.
//
// The region's structure never changes: elements ship in `index.html` and are revealed with
// `hidden`. See the markup comment for why.
function renderAssistantConnection(state) {
  const region = document.getElementById('assistant-connection');
  const step = document.getElementById('assistant-connection-step');
  const connect = document.getElementById('assistant-connect');
  if (!region || !step || !connect) return;

  const offered = offersConnection(state);
  region.hidden = !offered;
  if (!offered) {
    // The state moved on — the author connected, or something more fundamental broke. Either way
    // a pending grant is now about a question nobody is asking, so it stops rather than polling on
    // behind a hidden region.
    if (assistantConnection) abandonAssistantConnection();
    step.hidden = true;
    return;
  }
  // A grant that has been ASKED FOR is not one that has arrived. Revealing the step while the
  // request is still in flight would show an empty code and an empty address — a live region
  // announcing two blanks, and an instruction the author cannot follow.
  const pending = Boolean(assistantConnection && !assistantConnection.starting);
  step.hidden = !pending;
  // The button says which of the two things it does. "Connect" while a code is already on screen
  // would be a second door to a state the author is already in.
  connect.textContent = pending ? 'Start again with a new code' : 'Connect to the model provider';
  connect.disabled = Boolean(assistantConnection?.starting);
}

// Starts a grant and shows what the author has to do with it. Everything shown comes from the
// service's answer; nothing here is synthesized, and a partial answer is refused rather than
// half-rendered — see `beginConnection`.
async function startAssistantConnection() {
  if (!assistantClient || assistantConnection?.starting) return;
  abandonAssistantConnection();
  assistantConnection = { starting: true, timer: null, interval: null };
  renderAssistantState();
  let grant;
  try {
    grant = await assistantClient.beginConnection();
  } catch (error) {
    assistantConnection = null;
    // Reported where the author is looking, and NOT as a transcript turn: nothing was asked of the
    // model, so a turn would put words in a conversation that did not happen.
    showAssistantConnectionProgress(String(error?.message
      || 'The connection could not be started.'));
    renderAssistantState();
    return;
  }
  assistantConnection = {
    starting: false,
    timer: null,
    // The provider's own interval, in seconds, floored at one. RFC 8628 leaves the default to the
    // server, which speaks the protocol; the floor here is only a guard against a body that said
    // something absurd, and it is a floor rather than a fixed value so `slow_down` can raise it.
    interval: Math.max(1, grant.interval || 5),
  };
  showAssistantConnectionGrant(grant);
  scheduleAssistantConnectionPoll();
  renderAssistantState();
}

function showAssistantConnectionGrant(grant) {
  const code = document.getElementById('assistant-connection-code');
  const uri = document.getElementById('assistant-connection-uri');
  if (code) code.textContent = grant.userCode;
  if (uri) {
    const target = grant.verificationUriComplete || grant.verificationUri;
    // TEXT ALWAYS, LINK ONLY IF IT IS ONE. The address arrives over the wire, and a wire value
    // written into `href` unchecked is how `javascript:` reaches a click handler. The author can
    // always read and copy it; they can only click it when it is an ordinary web address.
    uri.textContent = grant.verificationUri;
    if (isHttpUrl(target)) uri.setAttribute('href', target);
    else uri.removeAttribute('href');
  }
  showAssistantConnectionProgress(connectionFailureText('AUTHORIZATION_PENDING'));
}

function isHttpUrl(value) {
  try {
    const parsed = new URL(String(value));
    return parsed.protocol === 'http:' || parsed.protocol === 'https:';
  } catch {
    return false;
  }
}

function showAssistantConnectionProgress(message) {
  const progress = document.getElementById('assistant-connection-progress');
  if (!progress) return;
  const text = String(message ?? '');
  // Written only when it changes, for the same reason the state banner is: this sits inside a
  // polite live region, and a poll that rewrote an identical sentence every few seconds would have
  // a screen reader repeat it every few seconds.
  if (progress.textContent !== text) progress.textContent = text;
}

function scheduleAssistantConnectionPoll() {
  if (!assistantConnection) return;
  clearTimeout(assistantConnection.timer);
  assistantConnection.timer = setTimeout(() => {
    void pollAssistantConnection();
  }, assistantConnection.interval * 1000);
}

async function pollAssistantConnection() {
  if (!assistantClient || !assistantConnection) return;
  let progress;
  try {
    progress = await assistantClient.connectionProgress();
  } catch (error) {
    // A transport failure is not a verdict on the grant. It is reported and the loop stops, rather
    // than retrying into a service that just failed — the author still has the code and can start
    // again, which is a decision they can make and this loop cannot.
    stopAssistantConnectionTimer();
    showAssistantConnectionProgress(String(error?.message
      || 'The connection could not be checked.'));
    return;
  }
  if (!assistantConnection) return;
  if (progress.state === 'linked') {
    stopAssistantConnectionTimer();
    assistantConnection = null;
    // The panel does not declare itself connected: it asks the deployment again. The status route
    // is the only thing that knows whether this author can now be served, and a panel that decided
    // for itself would be back to claiming a state it cannot observe.
    void refreshAssistantAvailability();
    return;
  }
  if (progress.state !== 'waiting') {
    // The server has no grant for this author any more — it expired out of the pending register, or
    // the process restarted. Saying so is better than polling something that no longer exists.
    stopAssistantConnectionTimer();
    showAssistantConnectionProgress(connectionFailureText('EXPIRED_TOKEN'));
    return;
  }
  const known = connectionFailureText(progress.reason);
  showAssistantConnectionProgress(known
    // An unrecognised token is not mapped onto a neighbour — same rule as a failed turn. The panel
    // says what it does know: the attempt is still open and it is still watching.
    || connectionFailureText('AUTHORIZATION_PENDING'));
  if (progress.reason === 'ACCESS_DENIED' || progress.reason === 'EXPIRED_TOKEN') {
    // Terminal. A denial is an answer, and polling past it argues with a decision the author made
    // on the provider's own page.
    stopAssistantConnectionTimer();
    return;
  }
  // `slow_down` arrives as a longer interval from the server, which is the side that read the
  // response; it is honoured rather than recomputed here.
  if (progress.retryAfter) assistantConnection.interval = Math.max(1, progress.retryAfter);
  scheduleAssistantConnectionPoll();
}

function stopAssistantConnectionTimer() {
  if (assistantConnection?.timer) clearTimeout(assistantConnection.timer);
  if (assistantConnection) assistantConnection.timer = null;
}

// Stops watching AND tells the service to forget the grant. Only the local half is guaranteed: the
// request may fail, and a grant nobody redeems expires on its own, so a failure here costs an
// author a wait rather than leaving something usable behind.
function abandonAssistantConnection() {
  if (!assistantConnection) return;
  stopAssistantConnectionTimer();
  assistantConnection = null;
  const step = document.getElementById('assistant-connection-step');
  if (step) step.hidden = true;
  // The progress line survives the step being hidden — it is outside it — so it is cleared here.
  // Leaving "waiting for you to finish" under a Connect button would describe an attempt that no
  // longer exists.
  showAssistantConnectionProgress('');
  void assistantClient?.abandonConnection?.().catch(() => { });
}

// `null` means "the composer is gone, use the transcript". Focusing before the disable, never
// after, so the browser never routes through `<body>` on the way.
function moveAssistantFocus(preferred) {
  const target = preferred || document.getElementById('assistant-transcript');
  target?.focus();
}

// APPEND ONLY. The live region's children are added at the end and never re-parented, regrouped or
// reordered — the rule `#activity-log` follows and for the same reason: a region whose
// structure moves as content arrives destroys focus stability and over-announces. The cap drops the
// OLDEST from the top, exactly as `appendActivity` does, so the entry that just arrived is never
// the one that vanishes.
//
// NOTHING HERE MOVES FOCUS. A user typing their second question must not be thrown out of the
// textarea by the answer to their first.
// EVERY turn enters through `admitTurn`, including the ones that owe nothing. Routing only the
// assistant's replies through the gate would leave two admission paths, and the disclosure would
// then depend on this function picking the right one — which is the "renderer must remember"
// failure the gate exists to remove. One path, and it decides for itself what a turn owes.
//
// The append function passed in does BOTH halves — array and DOM — so a turn the gate emits cannot
// reach the transcript without being rendered. The disclosure is not appended by this code at all;
// it arrives as an ordinary admitted turn, ahead of the reply that made it due.
function pushAssistantTurn(turn) {
  assistantTranscript = admitTurn(assistantTranscript, turn, (transcript, entry) => {
    const next = appendTurn(transcript, entry);
    renderAssistantTurn(next[next.length - 1], next.length);
    return next;
  });
}

function renderAssistantTurn(entry, size) {
  const log = document.getElementById('assistant-transcript');
  if (!log) return;
  const attached = entry.attached.length
    ? `<div class="assistant-turn-attached">context: ${escapeHtml(entry.attached.join(', '))}</div>`
    : '';
  // The disclosure carries the id that AI-origin turns point at, and it is the ONLY element that
  // may: two nodes sharing an id make `aria-describedby` resolve to whichever comes first, which
  // is a coin toss dressed as an association. `clearAssistantConversation` empties the region, so
  // a re-disclosed conversation replaces the element rather than adding a second one.
  const id = entry.role === DISCLOSURE_ROLE ? ` id="${escapeAttribute(entry.id)}"` : '';
  // Programmatic association, not adjacency — see `describedById`. Applied to the turn ELEMENT so
  // a screen reader reaching the reply by any route reads the disclosure with it.
  const describedBy = describedById(entry.role);
  const described = describedBy ? ` aria-describedby="${escapeAttribute(describedBy)}"` : '';
  // The disclosure is not a speaker in the conversation, so it gets no role chip. Its distinct
  // class is what carries the visual separation the Regulation's "distinguishable" asks for.
  const role = entry.role === DISCLOSURE_ROLE
    ? ''
    : `<div class="assistant-turn-role">${escapeHtml(assistantRoleLabel(entry.role))}</div>`;
  log.insertAdjacentHTML('beforeend', `<div class="assistant-turn assistant-turn--${escapeAttribute(entry.role)}"${id}${described}>
    ${role}<div class="assistant-turn-text">${escapeHtml(entry.text)}</div>${attached}</div>`);
  // Mirrors `appendTurn`'s cap exactly: the oldest turn that is NOT the disclosure. Removing
  // `firstElementChild` unconditionally would delete the disclosure element while the array kept
  // its entry, leaving every reply's `aria-describedby` pointing at nothing.
  while (log.children.length > size) {
    const oldest = [...log.children]
      .find(node => !node.classList.contains(`assistant-turn--${DISCLOSURE_ROLE}`));
    if (!oldest) break;
    oldest.querySelectorAll('.assistant-proposal').forEach(card => {
      assistantProposals.delete(card.dataset.proposalKey);
    });
    oldest.remove();
  }
  log.scrollTop = log.scrollHeight;
}

function assistantRoleLabel(role) {
  if (role === AUTHOR) return 'You';
  if (role === ASSISTANT) return 'Assistant';
  return 'Ravenroot';
}

function clearAssistantConversation() {
  assistantTranscript = [];
  assistantProposals.clear();
  assistantError = null;
  const log = document.getElementById('assistant-transcript');
  if (log) log.innerHTML = '';
  setAssistantDraftError('');
  renderAssistantState();
}

// The error is bound to the control that produced it, not merely announced beside it:
// `aria-errormessage` plus `aria-invalid` is what makes it reachable from the textarea by a screen
// reader user who arrives at the field later, rather than a sentence that was spoken once and lost.
function setAssistantDraftError(message) {
  const error = document.getElementById('assistant-error');
  const draft = document.getElementById('assistant-draft');
  if (!error || !draft) return;
  if (message) {
    if (error.textContent !== message) error.textContent = message;
    error.hidden = false;
    draft.setAttribute('aria-invalid', 'true');
    draft.setAttribute('aria-errormessage', 'assistant-error');
    return;
  }
  error.textContent = '';
  error.hidden = true;
  draft.removeAttribute('aria-invalid');
  draft.removeAttribute('aria-errormessage');
}

async function submitAssistantDraft() {
  const draft = document.getElementById('assistant-draft');
  if (!draft || assistantBusy) return;

  // Composed unconditionally here, not through the open-gated `refreshAssistantContext`: what is
  // about to be SENT must be composed now, never inherited from the last render.
  assistantContext = composeContext(assistantContextSources());
  renderAssistantChips();
  renderAssistantPayload();
  const state = deriveState({
    availability: assistantAvailability, context: assistantContext, error: assistantError,
  });
  const verdict = validateDraft(draft.value, state);
  if (!verdict.ok) {
    setAssistantDraftError(verdict.message);
    // Focus moves to the control the message is about — a deliberate move on a USER ACTION, which
    // is the opposite case from an arriving turn.
    draft.focus();
    return;
  }
  setAssistantDraftError('');

  // The author's turn records the classes actually attached, so the transcript keeps the disclosure
  // beside the question rather than only in the chips, which by then describe the NEXT message.
  const attached = assistantContext.classes
    .filter(entry => entry.state === ATTACHED).map(entry => entry.id);
  pushAssistantTurn({ role: AUTHOR, text: verdict.text, attached });
  draft.value = '';

  assistantBusy = true;
  assistantError = null;
  renderAssistantState();
  try {
    const owner = workspace.active;
    const documentBinding = owner && graphData?.format === 'graphml'
      ? {
        incarnation: activeDocumentIncarnation,
        revision: editHistory.revision(),
        catalogDigest: catalogProposalDigest(nodeTypeCatalog),
      }
      : null;
    const reply = await assistantClient.send({
      prompt: verdict.text, context: assistantContext, document: documentBinding,
    });
    pushAssistantTurn({ role: ASSISTANT, text: reply.text });
    if (reply.proposal) renderAssistantProposal(reply.proposal);
  } catch (error) {
    // Reported in the transcript AND in the state banner. Never a silent failure, and never a
    // fabricated answer standing in for one.
    assistantError = { reason: error?.reason || null, message: error?.message || 'The assistant request failed.' };
    pushAssistantTurn({ role: NOTICE, text: assistantError.message });
    // A failure that tells us the service is gone updates availability too, so the panel settles
    // into the inert state that names the cause rather than staying in a transient error.
    if (error?.reason === 'service-unavailable') {
      assistantAvailability = { reachable: false, configured: false, allowlisted: false, signedIn: false };
      assistantError = null;
    }
  } finally {
    assistantBusy = false;
    renderAssistantState();
  }
}

function liveAssistantProposalContext() {
  return {
    documentIncarnation: activeDocumentIncarnation,
    revision: editHistory.revision(),
    catalogDigest: catalogProposalDigest(nodeTypeCatalog),
    graph: graphData,
    catalog: nodeTypeCatalog,
    history: editHistory,
  };
}

function renderAssistantProposal(proposal) {
  const log = document.getElementById('assistant-transcript');
  const host = log?.lastElementChild;
  if (!host) return;
  let planned = planAssistantGraphProposal(proposal, liveAssistantProposalContext());
  const proposalId = typeof proposal?.id === 'string' ? proposal.id : '';
  const duplicate = proposalId && assistantProposals.has(proposalId);
  if (duplicate) planned = { ok: false, errors: ['This proposal id was already used in this conversation.'] };
  const proposalKey = duplicate || !proposalId
    ? `invalid-proposal-${++assistantProposalSequence}` : proposalId;
  const card = document.createElement('section');
  card.className = 'assistant-proposal';
  card.dataset.proposalId = proposalId || proposalKey;
  card.dataset.proposalKey = proposalKey;
  const title = document.createElement('h3');
  title.textContent = 'Proposed graph edit';
  const summary = document.createElement('p');
  summary.className = 'assistant-proposal-summary';
  summary.textContent = planned.ok ? planned.preview.summary : 'This proposal cannot be applied.';
  const changes = document.createElement('ul');
  changes.className = 'assistant-proposal-changes';
  for (const change of planned.ok ? planned.preview.changes : planned.errors) {
    const item = document.createElement('li');
    item.textContent = change;
    changes.append(item);
  }
  const status = document.createElement('p');
  status.className = 'assistant-proposal-status';
  status.setAttribute('role', 'status');
  status.setAttribute('aria-live', 'polite');
  status.textContent = planned.ok ? 'Review this preview, then confirm or reject it.' : 'Invalid proposal.';
  const actions = document.createElement('div');
  actions.className = 'assistant-proposal-actions';
  const confirmButton = document.createElement('button');
  confirmButton.type = 'button';
  confirmButton.className = 'btn primary';
  confirmButton.dataset.action = 'confirm-assistant-proposal';
  confirmButton.dataset.proposalId = proposalKey;
  confirmButton.textContent = 'Apply proposal';
  confirmButton.disabled = !planned.ok;
  const rejectButton = document.createElement('button');
  rejectButton.type = 'button';
  rejectButton.className = 'btn';
  rejectButton.dataset.action = 'reject-assistant-proposal';
  rejectButton.dataset.proposalId = proposalKey;
  rejectButton.textContent = 'Reject';
  actions.append(confirmButton, rejectButton);
  card.append(title, summary, changes, status, actions);
  host.append(card);
  assistantProposals.set(proposalKey, {
    proposal: planned.ok ? proposal : null,
    card, status, confirmButton, rejectButton, state: planned.ok ? 'pending' : 'invalid',
  });
  log.scrollTop = log.scrollHeight;
}

function confirmAssistantProposal(proposalId) {
  const pending = assistantProposals.get(proposalId);
  if (!pending || pending.state !== 'pending') return;
  const result = applyAssistantGraphProposal(pending.proposal, liveAssistantProposalContext());
  if (!result.ok) {
    pending.state = 'invalid';
    pending.proposal = null;
    pending.confirmButton.disabled = true;
    pending.status.textContent = result.errors.join(' ');
    return;
  }
  pending.state = 'applied';
  pending.proposal = null;
  pending.confirmButton.disabled = true;
  pending.rejectButton.disabled = true;
  pending.status.textContent = 'Applied as one undoable edit.';
  retireInspectorDraft();
  resetConnectGesture();
  rebuildGraph({ syncPositions: false });
  selectCommandTargets(result.command);
  updateHistoryUi();
}

function rejectAssistantProposal(proposalId) {
  const pending = assistantProposals.get(proposalId);
  if (!pending || !['pending', 'invalid'].includes(pending.state)) return;
  rejectAssistantGraphProposal(pending.proposal);
  pending.state = 'rejected';
  pending.proposal = null;
  pending.confirmButton.disabled = true;
  pending.rejectButton.disabled = true;
  pending.status.textContent = 'Rejected. The graph was not changed.';
}

function toggleAssistantPayload() {
  const view = document.getElementById('assistant-payload');
  const toggle = document.getElementById('assistant-payload-toggle');
  if (!view || !toggle) return;
  const open = view.hidden;
  view.hidden = !open;
  toggle.setAttribute('aria-expanded', String(open));
  toggle.textContent = open ? 'Hide payload' : 'View payload';
  renderAssistantPayload();
}

// Asks the deployment what it offers instead of deciding for itself, exactly as `connectRuntime`
// does for the node catalog. A failure here fills the panel with a REASON; it never blanks it.
async function refreshAssistantAvailability() {
  if (!assistantClient) {
    assistantAvailability = { reachable: false, configured: false, allowlisted: false, signedIn: false };
    renderAssistantState();
    return;
  }
  try {
    assistantAvailability = await assistantClient.status();
  } catch {
    assistantAvailability = { reachable: false, configured: false, allowlisted: false, signedIn: false };
  }
  renderAssistantState();
}

function showInspectorMessage(message, { skipDraftGuard = false } = {}) {
  if (!skipDraftGuard) {
    return runAfterInspectorDraft(() => showInspectorMessage(message, { skipDraftGuard: true }));
  }
  document.getElementById('info-title').textContent = 'Ravenroot';
  document.getElementById('info-body').innerHTML = `<div class="info-empty">${escapeHtml(message)}</div>`;
  return true;
}

function shortId(value) {
  return value ? String(value).slice(0, 8) : '—';
}

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, char => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  })[char]);
}

function escapeAttribute(value) {
  return escapeHtml(value).replace(/`/g, '&#96;');
}

function contextualHelpButtonHtml(title, content) {
  if (!String(content || '').trim()) return '';
  const accessibleName = `Help: ${title}`;
  return `<button class="contextual-help-trigger" type="button"
      aria-label="${escapeAttribute(accessibleName)}" aria-expanded="false"
      aria-controls="contextual-help-popover" data-tooltip="${escapeAttribute(accessibleName)}"
      data-contextual-help-title="${escapeAttribute(title)}"
      data-contextual-help="${escapeAttribute(content)}">
    <svg class="ctl-glyph" aria-hidden="true" focusable="false"><use href="#i-context-help"/></svg>
  </button>`;
}

// ═══════════════════════════════════════════════════════════════
// FILE LOADING
// ═══════════════════════════════════════════════════════════════

function onFileInput(evt) {
  const f = evt.target.files[0];
  // Clearing the control keeps re-selecting the same file working after a cancelled confirm.
  evt.target.value = '';
  if (f) loadFileObj(f);
}

function onReplaceFileInput(event) {
  const file = event.target.files[0];
  event.target.value = '';
  if (!file) return;
  showLoading();
  const reader = new FileReader();
  reader.onload = () => {
    try {
      replaceActiveDocumentFromText(String(reader.result), file.name, document.getElementById('menu-file'));
    } catch (error) {
      alert('Error: ' + error.message);
    } finally {
      hideLoading();
    }
  };
  reader.onerror = () => {
    hideLoading();
    alert('Error: ' + (reader.error?.message || 'The file could not be read'));
  };
  reader.readAsText(file);
}

function parsePreparedGraph(text, name, { automatic = false } = {}) {
  let graph = detectAndParse(text, name);
  if (graph.format === 'graphify' && graph.nodes.length > GFY_MAX_WARN) {
    if (automatic) return sampleLargeGraph(graph, GFY_SAMPLE);
    const keep = GFY_SAMPLE;
    const sample = confirm(
      `Very large graph: ${graph.nodes.length.toLocaleString()} nodes, ${graph.edges.length.toLocaleString()} edges.\n\n` +
      `For best performance, the ${keep.toLocaleString()} most connected nodes (main hubs) will be shown.\n\n` +
      `OK = sample top-${keep.toLocaleString()} hubs  |  Cancel = load all (may be slow)`,
    );
    if (sample) graph = sampleLargeGraph(graph, keep);
  }
  return graph;
}

function loadFileObj(file) {
  loadLocalGraphInput(file, {
    createReader: () => new FileReader(),
    onStart: showLoading,
    parseAndRender: text => {
      // Parse and make the large-graph decision BEFORE allocating a record: failures leave the
      // workspace, active id and every existing history exactly as they were.
      const graph = parsePreparedGraph(text, file.name);
      openDocument({ name: file.name, graph });
      clearActivity();
      addActivityMessage('editor', `Loaded ${file.name}`, 'completed');
    },
    onRejected: err => alert('Error: ' + err.message),
    onError: err => {
      alert('Error: ' + err.message);
      console.error(err);
    },
    onComplete: hideLoading,
  });
}

async function autoLoadUrl(url) {
  const name = url.split('/').pop();
  await loadUrlGraphInput(url, {
    fetchImpl: fetch,
    onStart: showLoading,
    parseAndRender: text => {
      const gd = parsePreparedGraph(text, name, { automatic: true });
      const active = workspace.active;
      active.name = name;
      active.displayName = allocateDocumentDisplayName(name);
      graphName = name;
      graphDisplayName = active.displayName;
      initLoadedGraph(gd, visualStyle);
      syncActiveDocumentChrome();
    },
    onError: err => console.warn('Auto-load failed:', err.message),
    onComplete: hideLoading,
  });
}

function showLoading() { document.getElementById('loading').classList.remove('off'); }
function hideLoading() { document.getElementById('loading').classList.add('off'); }

// ═══════════════════════════════════════════════════════════════
// DRAG & DROP
// ═══════════════════════════════════════════════════════════════

const wrap = document.getElementById('cy-wrap');
function isCatalogDrag(event) {
  return [...(event.dataTransfer?.types || [])].includes('application/x-ravenroot-node');
}
wrap.addEventListener('dragover', e => {
  e.preventDefault();
  if (isCatalogDrag(e)) {
    if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy';
    return;
  }
  document.getElementById('dropzone').classList.add('on');
});
wrap.addEventListener('dragleave', e => { if (!wrap.contains(e.relatedTarget)) document.getElementById('dropzone').classList.remove('on'); });
wrap.addEventListener('drop', e => {
  e.preventDefault();
  document.getElementById('dropzone').classList.remove('on');
  const behavior = e.dataTransfer?.getData('application/x-ravenroot-node');
  if (behavior) {
    if (!modifyEnabled || !canModifyGraph(graphData, layoutMode)) {
      showInspectorMessage('Switch to Editing before dropping a node type.');
      return;
    }
    const rect = cy.container()?.getBoundingClientRect();
    if (!rect) return;
    const position = modelPositionFromClient({ x: e.clientX, y: e.clientY }, rect, cy.pan(), cy.zoom());
    createNodeFromStage(position, behavior, nodeAtModelPosition(position));
    return;
  }
  const f = e.dataTransfer.files[0];
  if (f) loadFileObj(f);
});

// ═══════════════════════════════════════════════════════════════
// KEYBOARD
// ═══════════════════════════════════════════════════════════════

const capturedDeleteShortcutEvents = new WeakSet();

function executeGlobalShortcut(event, expectedCommandId = '') {
  const id = commandRegistry.matchShortcut(event, commandContext(), 'global');
  if (!id || (expectedCommandId && id !== expectedCommandId)) return false;
  event.preventDefault();
  executeCommand(id, { event, control: event.target });
  return true;
}

// Delete is an editing command for the graph selection, not for whichever non-editable child last
// received focus. Some legitimate controls own and stop their keydown contract, so a document-level
// bubbling listener cannot guarantee that a released canvas gesture reaches deletion. Capture only
// these two keys; every other global shortcut retains its established bubbling/menu behaviour.
document.addEventListener('keydown', event => {
  if (event.key !== 'Delete' && event.key !== 'Backspace') return;
  if (executeGlobalShortcut(event, 'edit.deleteSelection')) capturedDeleteShortcutEvents.add(event);
}, true);

document.addEventListener('keydown', event => {
  if (capturedDeleteShortcutEvents.has(event)) return;
  executeGlobalShortcut(event);
});

let helpOrigin = null;

function toggleHelp(origin = document.activeElement) {
  const overlay = document.getElementById('help-overlay');
  const closing = overlay.classList.contains('on');
  overlay.classList.toggle('on', !closing);
  if (!closing) {
    closePopovers();
    helpOrigin = origin;
    overlay.querySelector('.help-close')?.focus();
    return;
  }
  const restore = helpOrigin;
  helpOrigin = null;
  if (restore?.isConnected) restore.focus();
}

function renderShortcutHelp() {
  const list = document.getElementById('shortcut-help-list');
  list.innerHTML = commandRegistry.listPlacement('help').map(command => {
    const shortcuts = (command.shortcuts || []).map(shortcut => commandRegistry.shortcutLabel(shortcut));
    return `<div class="hk"><kbd>${escapeHtml(shortcuts.join(' / '))}</kbd><span>${escapeHtml(command.help || command.label)}</span></div>`;
  }).join('');
}

// ── THE PANEL LAYOUT (UI-04) ───────────────────────────────────────────────────
//
// `panel-layout.js` owns what a layout IS and what makes one invalid. This owns turning one into
// DOM, and the single call to `localStorage`.
//
// WHY THE STORE LIVES HERE AND NOT IN A MODULE OF ITS OWN: `ui-security-boundary.test.js` used to
// assert that app.js contained no `localStorage` at all, under a service-trust-persistence heading.
// Moving the store elsewhere would have passed that assertion while evading the thing it was
// guarding, so it is deliberately NOT moved. The assertion is replaced instead by a behavioural
// one that watches what actually ends up in storage, whichever file put it there — a strictly
// stronger guard than the text search it replaces, because a text search cannot tell layout
// persistence from token persistence.

const ZONE_HOSTS = { left: 'sidebar-scroll', right: 'info-body-zone', bottom: 'dock' };
const COLUMN_ELEMENTS = { left: 'sidebar', right: 'info' };

let panelLayout = readStoredLayout();

function readStoredLayout() {
  // Degrades without exception, in every direction: no storage API at all (private mode, a
  // sandboxed frame), a key that is not JSON, or JSON that is not a layout. The UI keeps working
  // and simply stops remembering, which is the honest failure for a convenience.
  try {
    const raw = globalThis.localStorage?.getItem(LAYOUT_STORAGE_KEY);
    if (!raw) return defaultLayout();
    return validateLayout(JSON.parse(raw)).layout;
  } catch {
    return defaultLayout();
  }
}

function persistPanelLayout() {
  try {
    globalThis.localStorage?.setItem(LAYOUT_STORAGE_KEY, JSON.stringify(panelLayout));
  } catch {
    // Storage full, or unavailable. Nothing to tell the user: their layout is correct on screen,
    // it just will not survive a reload.
  }
}

function updatePanelLayout(next) {
  visualTooltip.dismiss();
  const assistantWasOpen = isAssistantPanelOpen();
  panelLayout = next;
  applyPanelLayout();
  persistPanelLayout();
  refreshCommands();
  // The assistant's context is composed only while its panel is open, so the moment it opens
  // is the moment that has to recompose. Without this it would show whatever was last rendered
  // before it was closed, which is precisely the stale-chip defect the gate must not introduce.
  if (!assistantWasOpen && isAssistantPanelOpen()) refreshAssistantContext();
}

function panelElement(id) {
  return document.querySelector(`.panel[data-panel-id="${id}"]`);
}

// ── THE SAME SPLITTER, ON THE PANEL ZONES ────────────────────────────────────────────────
//
// Graph panes already established the product's separator grammar: a measured pair goes through
// `resizeSplit`, the result is expressed as a relative share, arrows move it by the same 32px step,
// Home restores the default and the live pair updates aria-valuenow. The static zone splitters use
// that grammar rather than growing a second drag convention beside it.
const DOCK_MIN_HEIGHT = 72;
const PANEL_MIN_HEIGHT = 72;

// CSS owns the geometry formula (including the compact cell, its padding, rail and border). The
// splitter reads the computed result so rendering, pointer clamps and ARIA can never drift onto
// three copies of a pixel constant. A collapsed rail is briefly measured as expanded when an
// outward gesture needs to know the floor it is expanding toward; the class is restored before a
// frame can paint.
function columnMinimumWidth(zone, { expanded = false } = {}) {
  const column = document.getElementById(zone === 'left' ? 'sidebar' : 'info');
  if (!column) return 0;
  const wasCollapsed = expanded && column.classList.contains('zone--collapsed');
  if (wasCollapsed) column.classList.remove('zone--collapsed');
  const minimum = Number.parseFloat(getComputedStyle(column).minWidth) || 0;
  if (wasCollapsed) column.classList.add('zone--collapsed');
  return minimum;
}

function layoutSplitter(zone) {
  return document.querySelector(`[data-layout-splitter="${zone}"]`);
}

function panelStackUnavailable(zone) {
  const host = document.getElementById(ZONE_HOSTS[zone]);
  if (!host || host.hidden) return true;
  return zone !== 'bottom' && document.getElementById(COLUMN_ELEMENTS[zone])?.classList.contains('zone--collapsed');
}

function panelSplitterKey(zone, before, after) {
  return `${zone}:${before.id}:${after.id}`;
}

function createPanelSplitter(zone, before, after) {
  const splitter = document.createElement('div');
  splitter.className = 'pane-separator layout-splitter layout-splitter--horizontal panel-splitter';
  splitter.dataset.layoutSplitter = `panel:${panelSplitterKey(zone, before, after)}`;
  splitter.dataset.splitterKind = 'panel';
  splitter.dataset.panelZone = zone;
  splitter.dataset.panelBefore = before.id;
  splitter.dataset.panelAfter = after.id;
  splitter.setAttribute('role', 'separator');
  splitter.setAttribute('aria-orientation', 'horizontal');
  const label = `Resize ${panelDescriptor(before.id)?.title || before.id} and ${panelDescriptor(after.id)?.title || after.id} panels`;
  splitter.setAttribute('aria-label', label);
  splitter.dataset.tooltip = label;
  splitter.tabIndex = 0;
  splitter.addEventListener('keydown', onPanelSplitterKeydown);
  splitter.addEventListener('pointerdown', onPanelSplitterPointerDown);
  return splitter;
}

function syncPanelSplitters(zone) {
  const host = document.getElementById(ZONE_HOSTS[zone]);
  const open = openPanelsInZone(panelLayout, zone);
  const wanted = open.slice(0, -1).map((before, index) => ({ before, after: open[index + 1] }));
  const wantedKeys = new Set(wanted.map(({ before, after }) => panelSplitterKey(zone, before, after)));
  const existing = new Map([...host.querySelectorAll(':scope > .panel-splitter')]
    .map(splitter => [panelSplitterKey(zone, { id: splitter.dataset.panelBefore }, { id: splitter.dataset.panelAfter }), splitter]));

  for (const [key, splitter] of existing) if (!wantedKeys.has(key)) splitter.remove();
  for (const { before, after } of wanted) {
    const key = panelSplitterKey(zone, before, after);
    const splitter = existing.get(key) || createPanelSplitter(zone, before, after);
    host.insertBefore(splitter, panelElement(after.id));
  }
}

function panelWeights(entries) {
  const saved = entries.map(entry => entry.size).filter(size => size !== null);
  const fallback = saved.length ? saved.reduce((sum, size) => sum + size, 0) / saved.length : 1;
  const raw = entries.map(entry => entry.size ?? fallback);
  const total = raw.reduce((sum, size) => sum + size, 0) || 1;
  return raw.map(size => size / total);
}

function applyPanelStackGeometry(zone) {
  const host = document.getElementById(ZONE_HOSTS[zone]);
  const open = openPanelsInZone(panelLayout, zone);
  const sized = open.some(entry => entry.size !== null);
  host.classList.toggle('panel-stack--sized', sized);
  const weights = sized ? panelWeights(open) : [];

  for (const entry of panelsInZone(panelLayout, zone)) {
    const panel = panelElement(entry.id);
    panel.style.removeProperty('flex-grow');
    panel.style.removeProperty('flex-shrink');
    panel.style.removeProperty('flex-basis');
  }
  if (sized) open.forEach((entry, index) => {
    const panel = panelElement(entry.id);
    panel.style.flexGrow = String(weights[index]);
    panel.style.flexShrink = '1';
    panel.style.flexBasis = '0px';
  });

  syncPanelSplitterValues(zone);
}

function syncPanelSplitterValues(zone) {
  const host = document.getElementById(ZONE_HOSTS[zone]);
  const unavailable = panelStackUnavailable(zone);
  host.querySelectorAll(':scope > .panel-splitter').forEach(splitter => {
    const before = panelElement(splitter.dataset.panelBefore);
    const after = panelElement(splitter.dataset.panelAfter);
    if (!before || !after) return;
    const sizeA = before.getBoundingClientRect().height;
    const sizeB = after.getBoundingClientRect().height;
    const range = separatorRange({ sizeA, sizeB, minA: PANEL_MIN_HEIGHT, minB: PANEL_MIN_HEIGHT });
    const disabled = unavailable || sizeA + sizeB < PANEL_MIN_HEIGHT * 2 || range.min >= range.max;
    splitter.setAttribute('aria-controls', `${before.id} ${after.id}`);
    splitter.setAttribute('aria-valuenow', String(separatorPosition({ widthA: sizeA, widthB: sizeB })));
    splitter.setAttribute('aria-valuemin', String(range.min));
    splitter.setAttribute('aria-valuemax', String(range.max));
    splitter.setAttribute('aria-disabled', String(disabled));
    splitter.tabIndex = disabled ? -1 : 0;
    if (disabled) {
      delete splitter.dataset.tooltip;
      splitter.dataset.tooltipExempt = 'disabled-boundary';
    } else {
      splitter.dataset.tooltip = splitter.getAttribute('aria-label');
      delete splitter.dataset.tooltipExempt;
    }
  });
}

function movePanelSplitter(splitter, deltaPx) {
  if (splitter.getAttribute('aria-disabled') === 'true') return;
  const zone = splitter.dataset.panelZone;
  const open = openPanelsInZone(panelLayout, zone);
  const before = panelElement(splitter.dataset.panelBefore);
  const after = panelElement(splitter.dataset.panelAfter);
  if (!before || !after) return;
  const heights = Object.fromEntries(open.map(entry => [entry.id, panelElement(entry.id).getBoundingClientRect().height]));
  const next = resizeSplit({
    sizeA: heights[splitter.dataset.panelBefore],
    sizeB: heights[splitter.dataset.panelAfter],
    deltaPx,
    minA: PANEL_MIN_HEIGHT,
    minB: PANEL_MIN_HEIGHT,
  });
  heights[splitter.dataset.panelBefore] = next.sizeA;
  heights[splitter.dataset.panelAfter] = next.sizeB;
  const total = Object.values(heights).reduce((sum, height) => sum + height, 0);
  if (!(total > 0)) return;
  panelLayout = setPanelSizes(panelLayout, zone,
    Object.fromEntries(Object.entries(heights).map(([id, height]) => [id, height / total])));
  // Do not rebuild/reorder the stack while this node owns pointer capture. Only geometry changed,
  // so keeping the existing boundary in place preserves capture, focus and its accessible identity.
  applyPanelStackGeometry(zone);
  persistPanelLayout();
}

function onPanelSplitterKeydown(event) {
  if (event.currentTarget.getAttribute('aria-disabled') === 'true') return;
  if (event.key === 'Home') {
    event.preventDefault();
    const zone = event.currentTarget.dataset.panelZone;
    panelLayout = setPanelSizes(panelLayout, zone, null);
    applyPanelStackGeometry(zone);
    persistPanelLayout();
    return;
  }
  const direction = event.key === 'ArrowUp' ? -1 : event.key === 'ArrowDown' ? 1 : 0;
  if (!direction) return;
  event.preventDefault();
  event.stopPropagation();
  movePanelSplitter(event.currentTarget, direction * SPLITTER_KEY_STEP);
}

function onPanelSplitterPointerDown(event) {
  const splitter = event.currentTarget;
  if (event.button !== 0 || splitter.getAttribute('aria-disabled') === 'true') return;
  event.preventDefault();
  splitter.classList.add('pane-separator--active');
  splitter.focus({ preventScroll: true });
  splitter.setPointerCapture(event.pointerId);
  let lastY = event.clientY;
  const onMove = moveEvent => {
    movePanelSplitter(splitter, moveEvent.clientY - lastY);
    lastY = moveEvent.clientY;
  };
  const onRelease = () => {
    splitter.classList.remove('pane-separator--active');
    splitter.removeEventListener('pointermove', onMove);
    splitter.removeEventListener('pointerup', onRelease);
    splitter.removeEventListener('pointercancel', onRelease);
  };
  splitter.addEventListener('pointermove', onMove);
  splitter.addEventListener('pointerup', onRelease);
  splitter.addEventListener('pointercancel', onRelease);
}

function mainAvailableWidth() {
  const main = document.getElementById('main');
  const splitters = [...main.querySelectorAll(':scope > .layout-splitter:not([hidden])')];
  return Math.max(0, main.clientWidth - splitters.reduce((sum, item) => sum + item.offsetWidth, 0));
}

function applyZoneGeometry() {
  const stage = document.getElementById('stage');
  const left = document.getElementById('sidebar');
  const right = document.getElementById('info');
  const dock = document.getElementById('dock');
  const dockSplitter = layoutSplitter('bottom');

  left.style.removeProperty('width');
  right.style.removeProperty('width');
  const available = mainAvailableWidth();
  if (panelLayout.zones.left.dimension !== null && !left.classList.contains('zone--collapsed')) {
    left.style.width = `${panelLayout.zones.left.dimension * available}px`;
  }
  if (panelLayout.zones.right.dimension !== null && !right.classList.contains('zone--collapsed')) {
    right.style.width = `${panelLayout.zones.right.dimension * available}px`;
  }

  // A stored share may be opened on a smaller viewport. It remains the stored intent, while the
  // live projection gives the stage its 360px floor before taking width from either expanded
  // column. Pixel values exist only in this projection and never enter the descriptor.
  let leftWidth = left.getBoundingClientRect().width;
  let rightWidth = right.getBoundingClientRect().width;
  const maximumColumns = Math.max(0, available - PANE_MIN_WIDTH);
  let excess = Math.max(0, leftWidth + rightWidth - maximumColumns);
  if (excess) {
    const rightFloor = columnMinimumWidth('right');
    const takeRight = Math.min(excess, Math.max(0, rightWidth - rightFloor));
    rightWidth -= takeRight;
    excess -= takeRight;
    const leftFloor = columnMinimumWidth('left');
    leftWidth -= Math.min(excess, Math.max(0, leftWidth - leftFloor));
    if (!left.classList.contains('zone--collapsed')) left.style.width = `${leftWidth}px`;
    if (!right.classList.contains('zone--collapsed')) right.style.width = `${rightWidth}px`;
  }

  dockSplitter.hidden = dock.hidden;
  dock.style.removeProperty('height');
  dock.style.removeProperty('flex-basis');
  if (!dock.hidden) {
    const pairHeight = Math.max(0, stage.clientHeight - dockSplitter.offsetHeight);
    const stored = panelLayout.zones.bottom.dimension;
    let wanted = stored === null ? dock.getBoundingClientRect().height : stored * pairHeight;
    if (panelLayout.zones.bottom.maximised) wanted = pairHeight - STAGE_MIN_HEIGHT;
    const dockHeight = Math.max(0, Math.min(pairHeight - STAGE_MIN_HEIGHT,
      Math.max(DOCK_MIN_HEIGHT, wanted)));
    dock.style.height = `${dockHeight}px`;
    dock.style.flexBasis = `${dockHeight}px`;
  }

  syncLayoutSplitterValues();
}

function syncLayoutSplitterValues() {
  const stage = document.getElementById('stage');
  const pairs = {
    left: [document.getElementById('sidebar'), stage,
      columnMinimumWidth('left'), PANE_MIN_WIDTH],
    right: [stage, document.getElementById('info'), PANE_MIN_WIDTH,
      columnMinimumWidth('right')],
    bottom: [document.getElementById('cy-wrap'), document.getElementById('dock'),
      STAGE_MIN_HEIGHT, DOCK_MIN_HEIGHT],
  };
  for (const [zone, [before, after, minA, minB]] of Object.entries(pairs)) {
    const splitter = layoutSplitter(zone);
    if (!splitter || splitter.hidden) continue;
    const axis = zone === 'bottom' ? 'height' : 'width';
    const sizeA = before.getBoundingClientRect()[axis];
    const sizeB = after.getBoundingClientRect()[axis];
    const range = separatorRange({ sizeA, sizeB, minA, minB });
    splitter.setAttribute('aria-valuenow', String(separatorPosition({ widthA: sizeA, widthB: sizeB })));
    splitter.setAttribute('aria-valuemin', String(range.min));
    splitter.setAttribute('aria-valuemax', String(range.max));
  }
}

function moveLayoutSplitter(zone, deltaPx) {
  const splitter = layoutSplitter(zone);
  if (splitter?.getAttribute('aria-disabled') === 'true') return;
  const stage = document.getElementById('stage');
  if (zone === 'bottom') {
    const canvas = document.getElementById('cy-wrap').getBoundingClientRect();
    const dock = document.getElementById('dock').getBoundingClientRect();
    const next = resizeSplit({
      sizeA: canvas.height, sizeB: dock.height, deltaPx,
      minA: STAGE_MIN_HEIGHT, minB: DOCK_MIN_HEIGHT,
    });
    updatePanelLayout(setZoneDimension(panelLayout, 'bottom', next.sizeB / (next.sizeA + next.sizeB)));
    return;
  }

  const column = document.getElementById(zone === 'left' ? 'sidebar' : 'info');
  const stageBox = stage.getBoundingClientRect();
  const columnBox = column.getBoundingClientRect();
  const collapsed = panelLayout.zones[zone].collapsed;
  if (collapsed || isZoneEmpty(panelLayout, zone)) {
    const outward = zone === 'left' ? deltaPx > 0 : deltaPx < 0;
    // An inward gesture against a rail has nowhere to go. It is a true no-op: no invisible
    // dimension is stored while width and aria-valuenow remain pinned at 28px.
    if (!outward || isZoneEmpty(panelLayout, zone)) return;

    const pairTotal = stageBox.width + columnBox.width;
    const minimum = columnMinimumWidth(zone, { expanded: true });
    const expanded = zone === 'left'
      ? resizeSplit({ sizeA: minimum, sizeB: pairTotal - minimum, deltaPx,
        minA: minimum, minB: PANE_MIN_WIDTH })
      : resizeSplit({ sizeA: pairTotal - minimum, sizeB: minimum, deltaPx,
        minA: PANE_MIN_WIDTH, minB: minimum });
    const width = zone === 'left' ? expanded.sizeA : expanded.sizeB;
    let nextLayout = setZoneCollapsed(panelLayout, zone, false);
    nextLayout = setZoneDimension(nextLayout, zone, width / mainAvailableWidth());
    updatePanelLayout(nextLayout);
    return;
  }
  const next = zone === 'left'
    ? resizeSplit({ sizeA: columnBox.width, sizeB: stageBox.width, deltaPx,
      minA: columnMinimumWidth('left'), minB: PANE_MIN_WIDTH })
    : resizeSplit({ sizeA: stageBox.width, sizeB: columnBox.width, deltaPx,
      minA: PANE_MIN_WIDTH, minB: columnMinimumWidth('right') });
  const columnWidth = zone === 'left' ? next.sizeA : next.sizeB;
  if (Math.abs(columnWidth - columnBox.width) < 0.5) return;
  updatePanelLayout(setZoneDimension(panelLayout, zone, columnWidth / mainAvailableWidth()));
}

function onLayoutSplitterKeydown(event) {
  const splitter = event.currentTarget;
  if (splitter.getAttribute('aria-disabled') === 'true') return;
  const zone = splitter.dataset.layoutSplitter;
  if (event.key === 'Home') {
    event.preventDefault();
    updatePanelLayout(setZoneDimension(panelLayout, zone, null));
    return;
  }
  if (event.key === 'End' && zone === 'bottom') {
    event.preventDefault();
    updatePanelLayout(setDockMaximised(panelLayout, true));
    return;
  }
  const direction = zone === 'bottom'
    ? (event.key === 'ArrowUp' ? -1 : event.key === 'ArrowDown' ? 1 : 0)
    : (event.key === 'ArrowLeft' ? -1 : event.key === 'ArrowRight' ? 1 : 0);
  if (!direction) return;
  event.preventDefault();
  event.stopPropagation();
  moveLayoutSplitter(zone, direction * SPLITTER_KEY_STEP);
}

function onLayoutSplitterPointerDown(event) {
  const splitter = event.currentTarget;
  if (splitter.getAttribute('aria-disabled') === 'true') return;
  const zone = splitter.dataset.layoutSplitter;
  event.preventDefault();
  splitter.classList.add('pane-separator--active');
  splitter.focus({ preventScroll: true });
  splitter.setPointerCapture(event.pointerId);
  let last = zone === 'bottom' ? event.clientY : event.clientX;
  const onMove = moveEvent => {
    const current = zone === 'bottom' ? moveEvent.clientY : moveEvent.clientX;
    moveLayoutSplitter(zone, current - last);
    last = current;
  };
  const onRelease = () => {
    splitter.classList.remove('pane-separator--active');
    splitter.removeEventListener('pointermove', onMove);
    splitter.removeEventListener('pointerup', onRelease);
    splitter.removeEventListener('pointercancel', onRelease);
  };
  splitter.addEventListener('pointermove', onMove);
  splitter.addEventListener('pointerup', onRelease);
  splitter.addEventListener('pointercancel', onRelease);
}

function applyPanelLayout() {
  visualTooltip.dismiss();
  for (const zone of ZONES) {
    const host = document.getElementById(ZONE_HOSTS[zone]);
    if (!host) continue;
    for (const entry of panelsInZone(panelLayout, zone)) {
      const panel = panelElement(entry.id);
      if (!panel) continue;
      panel.id = `panel-${entry.id}`;
      panel.dataset.panelZone = zone;
      // Hidden, never detached — see `.panel[hidden]` in styles.css for why that is load-bearing.
      panel.hidden = entry.closed;
      panel.classList.toggle('panel--short', Boolean(entry.short));
      panel.dataset.panelState = entry.short ? 'short' : 'full';
      // Appending in layout order is what renders the order; the DOM is the projection, the
      // descriptor is the truth.
      host.append(panel);
    }
  }

  for (const zone of ['left', 'right']) {
    const column = document.getElementById(COLUMN_ELEMENTS[zone]);
    const empty = isZoneEmpty(panelLayout, zone);
    const compact = isPanelStackCompact(panelLayout, zone);
    const focusedClose = column.contains(document.activeElement)
      ? document.activeElement.closest?.('.panel-close') : null;
    // A column with nothing open in it shows as its rail. That is a CONSEQUENCE of being empty
    // rather than a stored collapse, so reopening a panel brings the column back exactly as the
    // user left it instead of silently rewriting their choice.
    const collapsed = panelLayout.zones[zone].collapsed || empty;
    column.classList.toggle('zone--compact', compact);
    column.classList.toggle('zone--collapsed', collapsed);
    // Compact CSS intentionally keeps the direct Close visible. Retain the focus repair as a
    // defensive contract for any responsive/programmatic transition that hides a focused Close:
    // focus must move to the same panel's menu rather than remain on unrendered chrome.
    if (focusedClose && getComputedStyle(focusedClose).display === 'none') {
      focusedClose.closest('.panel')?.querySelector('[data-action="panel-menu"]')
        ?.focus({ preventScroll: true });
    }

    // Keep the boundary legible when the column has no open panels, but do not advertise an
    // impossible resize. The rail mark / Panels index is the deliberate route back. Reopening a
    // panel restores the separator to the same keyboard and pointer contract as any other side.
    const splitter = layoutSplitter(zone);
    splitter.setAttribute('aria-disabled', String(empty));
    splitter.tabIndex = empty ? -1 : 0;
    if (empty) {
      delete splitter.dataset.tooltip;
      splitter.dataset.tooltipExempt = 'disabled-boundary';
    } else {
      splitter.dataset.tooltip = splitter.getAttribute('aria-label').replace(/ and graph stage$/, '')
        .replace(/^Resize graph stage and /, 'Resize ');
      delete splitter.dataset.tooltipExempt;
    }

    const toggle = column.querySelector('.rail-toggle');
    toggle.setAttribute('aria-expanded', String(!collapsed));
    // The toggle would do nothing at all while the column is empty, and a control that does
    // nothing is an inaccessible dead control. It remains disabled and states why.
    toggle.disabled = false;
    toggle.setAttribute('aria-disabled', String(empty));
    const owner = zone === 'left' ? 'left toolbox' : 'right sidebar';
    const label = `${collapsed ? 'Expand' : 'Collapse'} ${owner}`;
    const reason = `No open panels in the ${owner}; use Panels to reopen one`;
    toggle.setAttribute('aria-label', empty ? `${label} unavailable: ${reason}` : label);
    toggle.dataset.tooltip = label;
    if (empty) toggle.dataset.tooltipDisabled = reason;
    else delete toggle.dataset.tooltipDisabled;
  }

  renderRailPanels();

  // AN EMPTY DOCK IS ABSENT. The route back is the Panels index on the rails.
  document.getElementById('dock').hidden = isZoneEmpty(panelLayout, 'bottom');

  for (const zone of ZONES) {
    syncPanelSplitters(zone);
    applyPanelStackGeometry(zone);
  }

  applyZoneGeometry();
  for (const zone of ZONES) syncPanelSplitterValues(zone);
  syncPaneLayout();
}

// One identity mark per panel in the column, in layout order. WHEN A COLUMN IS COLLAPSED THIS ROW
// IS THE ONLY THING NAMING ITS PANELS — the mark IS the title — which is why each button keeps an
// `aria-label` and delegated visual tooltip carrying the full name. The rail must never drop the
// accessible name, and native browser tooltips must not race the shared primitive.
function renderRailPanels() {
  visualTooltip.dismiss();
  for (const zone of ['left', 'right']) {
    const host = document.querySelector(`[data-rail-panels="${zone}"]`);
    if (!host) continue;
    host.innerHTML = panelsInZone(panelLayout, zone).map(entry => {
      const descriptor = PANELS.find(panel => panel.id === entry.id);
      const name = descriptor ? descriptor.title : entry.id;
      // Open state is carried by an accent bar AND by `aria-expanded`, never by colour alone.
      return `<button class="rail-btn rail-panel-btn" type="button"
          data-rail-panel="${escapeAttribute(entry.id)}"
          aria-expanded="${entry.closed ? 'false' : 'true'}"
          aria-label="Show ${escapeAttribute(name)} panel" data-tooltip="Show ${escapeAttribute(name)} panel"
        ><svg class="ctl-glyph" aria-hidden="true" focusable="false"><use href="#i-p-${escapeAttribute(entry.id)}"/></svg></button>`;
    }).join('');
  }
}

// A selection that silently does nothing because the Inspector happens to be closed is worse than
// a panel reappearing, so selecting reopens it — and expands its column if that is what is hiding
// it.
function revealInspector() {
  const entry = panelLayout.panels.find(panel => panel.id === 'inspector');
  if (!entry) return;
  let next = panelLayout;
  if (entry.closed) next = setPanelClosed(next, 'inspector', false);
  if (next.zones[entry.zone].collapsed) next = setZoneCollapsed(next, entry.zone, false);
  if (next !== panelLayout) updatePanelLayout(next);
}

// ── DRAGGING A PANEL (UI-04) ───────────────────────────────────────────────────
//
// A PROGRESSIVE ENHANCEMENT OVER THE `⋮` MENU, NOT A SECOND IMPLEMENTATION OF IT. Every outcome a
// drag can produce is reachable from the menu, and both end in the same
// `panel-layout.js` operations — which is what satisfies WCAG 2.5.7 (Dragging Movements) by
// construction rather than by a bolted-on alternative.
//
// Pointer Events with capture, NOT HTML5 drag-and-drop: the latter hands you an OS-rendered drag
// image you cannot style, no live drop preview, and inconsistent behaviour over a canvas — and this
// product is mostly canvas.

const DRAG_THRESHOLD = 4;

let drag = null;

// A press on a control is a click, not a drag. Without this, every ✕ that moves one pixel under the
// finger becomes a drag — the classic defect in hand-rolled dragging, and cheap to prevent.
function isDragSurface(target) {
  if (target.closest('.panel-ctl, .panel-text-ctl, .activity-clear, button, input, select, textarea')) {
    return false;
  }
  return Boolean(target.closest('.panel-hd'));
}

function beginPanelDrag(event) {
  if (event.button !== 0) return;
  if (!isDragSurface(event.target)) return;
  const panel = event.target.closest('.panel');
  if (!panel) return;

  visualTooltip.dismiss();

  drag = {
    id: panel.dataset.panelId,
    pointerId: event.pointerId,
    startX: event.clientX,
    startY: event.clientY,
    started: false,
    header: event.target.closest('.panel-hd'),
    zone: null,
    index: null,
  };
  // Capture on the header, so the gesture keeps receiving events even once the pointer is over the
  // Cytoscape canvas — which owns its own pointer handling and would otherwise swallow them.
  drag.header.setPointerCapture(event.pointerId);
  drag.header.addEventListener('pointermove', onPanelDragMove);
  drag.header.addEventListener('pointerup', onPanelDragEnd);
  drag.header.addEventListener('pointercancel', cancelPanelDrag);
}

function onPanelDragMove(event) {
  if (!drag || event.pointerId !== drag.pointerId) return;
  if (!drag.started) {
    if (Math.abs(event.clientX - drag.startX) < DRAG_THRESHOLD
      && Math.abs(event.clientY - drag.startY) < DRAG_THRESHOLD) return;
    startPanelDrag();
  }
  moveCarrier(event.clientX, event.clientY);
  updateDropTarget(event.clientX, event.clientY);
}

function startPanelDrag() {
  drag.started = true;
  document.body.classList.add('panels-dragging');
  panelElement(drag.id)?.classList.add('panel--dragging');
  if (isZoneEmpty(panelLayout, 'bottom')) document.getElementById('dock-drop-band').classList.add('armed');

  const carrier = document.getElementById('panel-carrier');
  const descriptor = PANELS.find(panel => panel.id === drag.id);
  carrier.textContent = descriptor ? descriptor.title : drag.id;
  carrier.hidden = false;
}

function moveCarrier(x, y) {
  const carrier = document.getElementById('panel-carrier');
  carrier.style.left = `${Math.round(x + 12)}px`;
  carrier.style.top = `${Math.round(y - 16)}px`;
}

// Which zone the pointer is over, and where in it the panel would land. The insertion index is the
// count of open panels whose MIDPOINT is above the pointer — the same rule every list-reorder uses,
// and the one that makes the marker appear where the eye expects it.
function dropTargetAt(x, y) {
  const band = document.getElementById('dock-drop-band');
  if (band.classList.contains('armed')) {
    const box = band.getBoundingClientRect();
    if (box.width && y >= box.top && y <= box.bottom && x >= box.left && x <= box.right) {
      return { zone: 'bottom', index: 0 };
    }
  }

  for (const zone of ZONES) {
    const host = document.getElementById(ZONE_HOSTS[zone]);
    if (!host || host.hidden) continue;
    const box = host.getBoundingClientRect();
    if (!box.width || !box.height) continue;
    if (x < box.left || x > box.right || y < box.top || y > box.bottom) continue;

    const siblings = [...host.querySelectorAll(':scope > .panel')]
      .filter(panel => !panel.hidden && panel.dataset.panelId !== drag.id);
    let index = siblings.length;
    for (const [position, sibling] of siblings.entries()) {
      const rect = sibling.getBoundingClientRect();
      if (y < rect.top + rect.height / 2) { index = position; break; }
    }
    return { zone, index };
  }
  return null;
}

function updateDropTarget(x, y) {
  const target = dropTargetAt(x, y);
  const band = document.getElementById('dock-drop-band');
  band.classList.toggle('over', Boolean(target) && target.zone === 'bottom'
    && band.classList.contains('armed'));

  document.getElementById('panel-drop-marker')?.remove();
  drag.zone = target?.zone ?? null;
  drag.index = target?.index ?? null;
  if (!target || (target.zone === 'bottom' && band.classList.contains('armed'))) return;

  // The marker IS the gap. A space that was not there before is a shape signal, and it carries an
  // accent rule inside it — so the feedback is never colour alone.
  const host = document.getElementById(ZONE_HOSTS[target.zone]);
  const marker = document.createElement('div');
  marker.id = 'panel-drop-marker';
  marker.className = 'panel-drop-marker';
  const siblings = [...host.querySelectorAll(':scope > .panel')]
    .filter(panel => !panel.hidden && panel.dataset.panelId !== drag.id);
  host.insertBefore(marker, siblings[target.index] ?? null);
}

function onPanelDragEnd() {
  if (!drag) return;
  const { id, zone, index, started } = drag;
  finishPanelDrag();
  // A press that never crossed the threshold was a click on the header, which does nothing. Leave
  // the layout alone rather than committing a zero-distance "move".
  if (!started || zone === null) return;
  updatePanelLayout(movePanelToZoneAt(panelLayout, id, zone, index ?? 0));
}

// Escape and pointercancel are CANCELLATIONS, not errors: the panel returns to where it was and
// nothing is said about it. A drag that cannot be abandoned is worse than no drag.
function cancelPanelDrag() {
  if (!drag) return false;
  finishPanelDrag();
  return true;
}

function finishPanelDrag() {
  document.getElementById('panel-drop-marker')?.remove();
  const band = document.getElementById('dock-drop-band');
  band.classList.remove('armed', 'over');
  document.body.classList.remove('panels-dragging');
  panelElement(drag.id)?.classList.remove('panel--dragging');
  document.getElementById('panel-carrier').hidden = true;

  drag.header.removeEventListener('pointermove', onPanelDragMove);
  drag.header.removeEventListener('pointerup', onPanelDragEnd);
  drag.header.removeEventListener('pointercancel', cancelPanelDrag);
  if (drag.header.hasPointerCapture?.(drag.pointerId)) {
    drag.header.releasePointerCapture(drag.pointerId);
  }
  drag = null;
}

document.addEventListener('pointerdown', beginPanelDrag);

// ── Popovers: the overflow menu and the Panels index ─────────────────────────────────────────────

function documentAccessibleName(document_, index) {
  const states = [];
  if (paneIsDirty(document_)) states.push('modified');
  if (document_.id === workspace.activeId) states.push('current');
  return `${document_.displayName}${states.length ? `, ${states.join(', ')}` : ''}, document ${index + 1} of ${workspace.size}`;
}

function syncDocumentSwitcher() {
  const trigger = document.getElementById('document-switcher');
  const list = document.querySelector('#document-switcher-dialog .document-switcher-list');
  if (!trigger || !list) return;
  const activeIndex = workspace.documents.findIndex(document_ => document_.id === workspace.activeId);
  const active = activeIndex >= 0 ? workspace.documents[activeIndex] : null;
  trigger.disabled = !active;
  trigger.setAttribute('aria-label', active ? documentAccessibleName(active, activeIndex) : 'No open documents');
  trigger.title = trigger.getAttribute('aria-label');
  document.getElementById('document-position').textContent = active
    ? `${activeIndex + 1} of ${workspace.size}`
    : '';

  list.innerHTML = workspace.documents.map((document_, index) => {
    const activeDocument = document_.id === workspace.activeId;
    const dirty = paneIsDirty(document_);
    const states = [activeDocument ? 'current' : '', dirty ? 'modified' : ''].filter(Boolean);
    return `<li class="document-switcher-row" data-document-row="${escapeAttribute(document_.id)}">
      <button class="document-switcher-activate" type="button"
        data-document-activate="${escapeAttribute(document_.id)}"
        ${activeDocument ? 'aria-current="true"' : ''}
        aria-label="${escapeAttribute(documentAccessibleName(document_, index))}"
        title="${escapeAttribute(document_.displayName)}">
        <span class="document-switcher-name">${escapeHtml(document_.displayName)}</span>
        <span class="document-switcher-state">${escapeHtml(states.join(', '))}</span>
      </button>
      <button class="document-switcher-close" type="button"
        data-document-close="${escapeAttribute(document_.id)}"
        aria-label="Close ${escapeAttribute(document_.displayName)}"
        title="Close ${escapeAttribute(document_.displayName)}">×</button>
    </li>`;
  }).join('');
}

function closeDocumentSwitcher({ restoreFocus = false } = {}) {
  const dialog = document.getElementById('document-switcher-dialog');
  const trigger = document.getElementById('document-switcher');
  if (!dialog.hidden) dialog.hidden = true;
  trigger.setAttribute('aria-expanded', 'false');
  if (restoreFocus) trigger.focus();
}

function openDocumentSwitcher() {
  const trigger = document.getElementById('document-switcher');
  const dialog = document.getElementById('document-switcher-dialog');
  if (trigger.disabled) return;
  closePopovers();
  syncDocumentSwitcher();
  placePopover(dialog, trigger);
  trigger.setAttribute('aria-expanded', 'true');
  dialog.querySelector(`[data-document-activate="${CSS.escape(workspace.activeId)}"]`)?.focus();
}

let pendingDocumentAction = null;

function closeUnsavedDocumentDialog(outcome) {
  const dialog = document.getElementById('unsaved-document-dialog');
  const pending = pendingDocumentAction;
  if (!pending) return;
  if (outcome === 'cancel') {
    pendingDocumentAction = null;
    dialog.close('cancel');
    if (pending.origin?.isConnected) pending.origin.focus();
    return;
  }

  // A pending replace is bound to the record that opened this dialog. Revalidate that binding
  // before Save can download or mark history clean; completion keeps the same guard as a final
  // defense against a later re-entrant change.
  if (pending.eligible && !pending.eligible()) {
    pendingDocumentAction = null;
    dialog.close(outcome);
    return;
  }
  if (outcome === 'save' && !downloadDocument(pending.documentId)) return;
  pendingDocumentAction = null;
  dialog.close(outcome);
  pending.complete();
}

function openUnsavedDocumentDialog({ documentId, origin, kind = 'close', eligible = null, complete }) {
  const target = workspace.find(documentId);
  if (!target) return false;
  pendingDocumentAction = { documentId, origin, eligible, complete };
  const replacing = kind === 'replace';
  document.getElementById('unsaved-document-title').textContent =
    `Save changes to “${target.displayName}”?`;
  document.getElementById('unsaved-document-description').textContent = replacing
    ? 'Replacing will discard changes that have not been downloaded.'
    : 'Closing will discard changes that have not been downloaded.';
  document.querySelector('[data-unsaved-action="save"]').textContent =
    replacing ? 'Save and Replace' : 'Save and Close';
  const dialog = document.getElementById('unsaved-document-dialog');
  dialog.showModal();
  document.querySelector('[data-unsaved-action="cancel"]').focus();
  return true;
}

function completeCloseDocument(id) {
  if (!closeDocument(id)) return false;
  closeDocumentSwitcher();
  syncDocumentSwitcher();
  const trigger = document.getElementById('document-switcher');
  (trigger.disabled ? document.getElementById('btn-new') : trigger).focus();
  return true;
}

function requestCloseDocument(id, origin = document.activeElement) {
  captureActiveDocument();
  const target = workspace.find(id);
  if (!target) return false;
  if (!target.history?.isDirty()) return proceedToCloseDocument(id, origin);
  return openUnsavedDocumentDialog({
    documentId: id,
    origin,
    complete: () => proceedToCloseDocument(id, origin),
  });
}

let pendingCloseAllDocuments = null;

function closeAllDocumentsDialog() {
  return document.getElementById('close-all-documents-dialog');
}

function closeAllDescription(oneKey, manyKey, count) {
  return uiText(count === 1 ? oneKey : manyKey, { count });
}

function renderCloseAllList(documents, kind) {
  const list = document.getElementById('close-all-documents-list');
  list.replaceChildren(...documents.map(document_ => {
    const item = document.createElement('li');
    if (kind === 'sessions') {
      const count = document_.sourceSession.sourceCount;
      item.textContent = uiText(count === 1 ? 'closeAll.sessions.item.one' : 'closeAll.sessions.item.many', {
        name: document_.displayName,
        count,
      });
    } else {
      item.textContent = document_.displayName;
    }
    return item;
  }));
}

function renderCloseAllActions(actions) {
  const host = document.getElementById('close-all-documents-actions');
  host.replaceChildren(...actions.map(({ action, label, kind = '' }) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = `btn${kind ? ` ${kind}` : ''}`;
    button.dataset.closeAllAction = action;
    button.textContent = label;
    return button;
  }));
}

function showCloseAllStep(step, targets) {
  const dialog = closeAllDocumentsDialog();
  const title = document.getElementById('close-all-documents-title');
  const description = document.getElementById('close-all-documents-description');
  const status = document.getElementById('close-all-documents-status');
  dialog.removeAttribute('aria-busy');
  status.textContent = '';
  delete status.dataset.state;
  if (step === 'dirty') {
    title.textContent = uiText('closeAll.dirty.title');
    description.textContent = closeAllDescription(
      'closeAll.dirty.description.one', 'closeAll.dirty.description.many', targets.length);
    renderCloseAllList(targets, 'dirty');
    renderCloseAllActions([
      { action: 'save', label: uiText('closeAll.dirty.save'), kind: 'primary' },
      { action: 'discard', label: uiText('closeAll.dirty.discard'), kind: 'danger' },
      { action: 'cancel', label: uiText('closeAll.cancel') },
    ]);
  } else {
    title.textContent = uiText('closeAll.sessions.title');
    description.textContent = closeAllDescription(
      'closeAll.sessions.description.one', 'closeAll.sessions.description.many', targets.length);
    renderCloseAllList(targets, 'sessions');
    renderCloseAllActions([
      { action: 'stop', label: uiText('closeAll.sessions.stop'), kind: 'danger' },
      { action: 'keep', label: uiText('closeAll.sessions.keep') },
      { action: 'cancel', label: uiText('closeAll.cancel') },
    ]);
  }
  if (!dialog.open) dialog.showModal();
  dialog.querySelector('[data-close-all-action="cancel"]')?.focus();
}

function showCloseAllWorking() {
  const dialog = closeAllDocumentsDialog();
  dialog.setAttribute('aria-busy', 'true');
  document.getElementById('close-all-documents-title').textContent = uiText('closeAll.working.title');
  document.getElementById('close-all-documents-description').textContent =
    uiText('closeAll.working.description');
  document.getElementById('close-all-documents-list').replaceChildren();
  document.getElementById('close-all-documents-status').textContent = '';
  renderCloseAllActions([]);
}

function showCloseAllFailure(stage) {
  const dialog = closeAllDocumentsDialog();
  dialog.removeAttribute('aria-busy');
  document.getElementById('close-all-documents-title').textContent = uiText('closeAll.failure.title');
  document.getElementById('close-all-documents-description').textContent =
    uiText('closeAll.failure.description');
  document.getElementById('close-all-documents-list').replaceChildren();
  const status = document.getElementById('close-all-documents-status');
  status.dataset.state = 'error';
  status.textContent = uiText(stage === 'stop' ? 'closeAll.failure.stop' : 'closeAll.failure.save');
  renderCloseAllActions([
    { action: 'retry', label: uiText('closeAll.retry'), kind: 'primary' },
    { action: 'cancel', label: uiText('closeAll.cancel') },
  ]);
  dialog.querySelector('[data-close-all-action="retry"]')?.focus();
}

function focusAfterCloseAll() {
  const newDocument = document.getElementById('btn-new');
  const target = workspace.size
    ? document.getElementById('document-switcher')
    : (newDocument?.getClientRects().length ? newDocument : menuTrigger('file'));
  target?.focus();
}

function cancelCloseAllDocuments() {
  const pending = pendingCloseAllDocuments;
  if (!pending) return false;
  pending.cancelled = true;
  pendingCloseAllDocuments = null;
  const dialog = closeAllDocumentsDialog();
  if (dialog.open) dialog.close('cancel');
  const focusTarget = pending.origin?.isConnected ? pending.origin : menuTrigger('view');
  focusTarget?.focus();
  return true;
}

function finishCloseAllDocuments(snapshot) {
  const dialog = closeAllDocumentsDialog();
  pendingCloseAllDocuments = null;
  if (dialog.open) dialog.close('closed');
  closeDocumentSwitcher();
  closeDocumentSnapshot(snapshot);
  focusAfterCloseAll();
}

async function commitCloseAllDocuments() {
  const pending = pendingCloseAllDocuments;
  if (!pending || pending.committing) return false;
  const targets = resolveDocumentCloseSnapshot(workspace, pending.snapshot);
  if (!targets.length) {
    finishCloseAllDocuments(pending.snapshot);
    return true;
  }
  const classified = classifyDocumentCloseTargets(targets);
  if (classified.dirty.length && !pending.dirtyChoice) {
    showCloseAllStep('dirty', classified.dirty);
    return true;
  }
  if (classified.activeSessions.length && !pending.sessionChoice) {
    showCloseAllStep('sessions', classified.activeSessions);
    return true;
  }

  pending.committing = true;
  showCloseAllWorking();
  let stage = 'save';
  try {
    const prepared = pending.dirtyChoice === 'save'
      ? classified.dirty.map(document_ => {
        const download = prepareDocumentDownload(document_.id);
        if (!download) throw new Error('Document download preparation failed.');
        return download;
      })
      : [];

    if (pending.sessionChoice === 'stop') {
      stage = 'stop';
      for (const owner of classified.activeSessions) {
        if (!resolveDocumentCloseSnapshot(workspace, pending.snapshot).includes(owner)) continue;
        const stopped = sourceSessionIsActive(owner.sourceSession)
          ? await stopActiveSourceSession(owner) : true;
        if (pendingCloseAllDocuments !== pending || pending.cancelled) return false;
        if (!stopped) {
          throw new Error('Source session did not stop.');
        }
      }
    }

    stage = 'save';
    if (pendingCloseAllDocuments !== pending || pending.cancelled) return false;
    const liveTargets = new Set(resolveDocumentCloseSnapshot(workspace, pending.snapshot));
    const liveDownloads = prepared.filter(item => liveTargets.has(item.target));
    liveDownloads.forEach(dispatchDocumentDownload);
    liveDownloads.forEach(item => markDocumentDownloaded(item, { announce: false }));
    finishCloseAllDocuments(pending.snapshot);
    return true;
  } catch {
    pending.committing = false;
    showCloseAllFailure(stage);
    return false;
  }
}

function beginCloseAllDocuments(snapshot, origin) {
  const targets = resolveDocumentCloseSnapshot(workspace, snapshot);
  if (!targets.length) return false;
  pendingCloseAllDocuments = {
    snapshot,
    origin,
    dirtyChoice: null,
    sessionChoice: null,
    committing: false,
  };
  const { dirty, activeSessions } = classifyDocumentCloseTargets(targets);
  if (dirty.length) return showCloseAllStep('dirty', dirty) || true;
  if (activeSessions.length) return showCloseAllStep('sessions', activeSessions) || true;
  finishCloseAllDocuments(snapshot);
  return true;
}

function requestCloseAllDocuments(origin = document.activeElement) {
  captureActiveDocument();
  const snapshot = captureDocumentCloseSnapshot(workspace.documents);
  if (!snapshot.length || pendingCloseAllDocuments) return false;
  const begin = () => beginCloseAllDocuments(snapshot, origin);
  return runAfterInspectorDraft(begin, { deferredAction: begin, deferredResult: true });
}

function handleCloseAllDocumentsAction(action) {
  const pending = pendingCloseAllDocuments;
  if (!pending || pending.committing) return false;
  if (action === 'cancel') return cancelCloseAllDocuments();
  if (action === 'retry') {
    void commitCloseAllDocuments();
    return true;
  }
  if (action === 'save' || action === 'discard') pending.dirtyChoice = action;
  else if (action === 'stop' || action === 'keep') pending.sessionChoice = action;
  else return false;
  void commitCloseAllDocuments();
  return true;
}

/**
 * Closing is never Stop, and that difference has to reach the operator as a real
 * choice, not merely a code comment on {@link closeDocument}. A document with an active local
 * listener session started by Run gets an explicit dialog naming the distinction before the close
 * proceeds; a document with no active session closes immediately.
 * Reached both directly (no unsaved changes) and after the unsaved-changes dialog resolves
 * (save/discard), so the two axes -- persistence of edits, lifecycle of a server-side session -- are
 * asked about independently rather than folded into one dialog that could not state either clearly.
 *
 * This is strictly about `owner.sourceSession`, which exists only for a graph with an
 * effective SOURCE (Run's other branch is a one-shot execution with no ongoing session to ask about).
 * A deployment registered through the separate Deployments panel (see `deployment-panel.js`) has no
 * document tying it to this dialog at all -- it is tenant-scoped, not document-scoped, so closing any
 * document neither stops nor undeploys it. That is also why this dialog offers no Undeploy action:
 * undeploy exists only on that panel, next to the deployment it removes, never behind a document close.
 */
function proceedToCloseDocument(id, origin) {
  const target = workspace.find(id);
  if (!target) return false;
  if (!sourceSessionIsActive(target.sourceSession)) return completeCloseDocument(id);
  return openActiveDeploymentCloseDialog({ documentId: id, origin });
}

let pendingActiveDeploymentClose = null;

function openActiveDeploymentCloseDialog({ documentId, origin }) {
  const target = workspace.find(documentId);
  if (!target) return false;
  pendingActiveDeploymentClose = { documentId, origin };
  const count = target.sourceSession.sourceCount;
  document.getElementById('active-deployment-description').textContent =
    `Closing “${target.displayName}” only stops watching it from this browser tab. The local listener `
    + `session (${count} inbound source node${count === 1 ? '' : 's'}, scope LOCAL_PROCESS) keeps `
    + 'running in this server process until you Stop it.';
  const dialog = document.getElementById('active-deployment-dialog');
  dialog.showModal();
  document.querySelector('[data-active-deployment-action="cancel"]').focus();
  return true;
}

function closeActiveDeploymentDialog(outcome) {
  const dialog = document.getElementById('active-deployment-dialog');
  const pending = pendingActiveDeploymentClose;
  if (!pending) return;
  pendingActiveDeploymentClose = null;
  dialog.close(outcome);
  if (outcome === 'cancel') {
    if (pending.origin?.isConnected) pending.origin.focus();
    return;
  }
  const target = workspace.find(pending.documentId);
  if (!target) return;
  if (outcome === 'stop-and-close') {
    // Stop is claimed here, on the still-open document, and only then does the close proceed --
    // matching `stopActiveSourceSession`'s own token discipline: closing first would abort the poll
    // controller closeDocument itself owns, but the stop request it kicks off here is unaffected by
    // that abort (see `sourceSessionCleanupIsCurrent`, which does not depend on the pollController).
    void stopActiveSourceSession(target).then(stopped => {
      if (stopped) completeCloseDocument(pending.documentId);
      else if (pending.origin?.isConnected) pending.origin.focus();
    });
    return;
  }
  // outcome === 'close': detach observation only, the exact contract closeDocument's own comment
  // states -- the local session remains owned by the server until its explicit Stop.
  completeCloseDocument(pending.documentId);
}

const commandRegistry = createCommandRegistry(createAppCommands({
  newDocument: () => openDocument(),
  openFile: () => document.getElementById('file-inp').click(),
  replaceActive: () => document.getElementById('replace-file-inp').click(),
  save: () => exportGraphML(),
  closeDocument: (_context, invocation) => requestCloseDocument(workspace.activeId,
    invocation.control?.closest('#application-menu') ? menuTrigger('file') : invocation.control),
  undo: () => undoEdit(),
  redo: () => redoEdit(),
  toggleModify: () => toggleModify(),
  toggleAutosave: () => toggleInspectorAutosave(),
  toggleNavigation: () => toggleNavigation(),
  toggleConnect: () => toggleConnect(),
  addNode: () => showAddNodeForm(),
  addEdge: () => showAddEdgeForm(),
  duplicateNode: () => duplicateSelectedNode(),
  deleteSelection: () => deleteCurrentSelection(),
  migrateJoinSemantics: () => migrateJoinSemanticsAction(),
  fit: () => fitGraph(),
  zoomIn: () => zoomBy(1.2),
  zoomOut: () => zoomBy(0.8),
  openDocumentSwitcher: () => openDocumentSwitcher(),
  closeAllDocuments: (_context, invocation) => requestCloseAllDocuments(
    invocation.control?.closest('#application-menu') ? menuTrigger('view') : invocation.control),
  openPanels: () => openPanelsIndex(document.querySelector('.rail-index')),
  toggleLeftPanels: () => updatePanelLayout(setZoneCollapsed(panelLayout, 'left', !panelLayout.zones.left.collapsed)),
  toggleRightInspector: () => updatePanelLayout(setZoneCollapsed(panelLayout, 'right', !panelLayout.zones.right.collapsed)),
  setTheme: theme => themePreference.select(theme),
  toggleHelp: (_context, invocation) => toggleHelp(
    invocation.control?.closest('#application-menu') ? menuTrigger('view') : invocation.control),
  setWorkspaceLayout: mode => setWorkspaceLayoutMode(mode),
  resetWorkspaceLayout: () => resetWorkspaceLayout(),
  setRenderMode: name => setRenderMode(name),
  arrange: name => arrangeDesign(name),
  play: () => playGraph(),
  run: () => playGraph('run'),
  pause: () => graphLifecycleCommand('pause'),
  stop: () => graphLifecycleCommand('stop'),
  forceStop: () => graphLifecycleCommand('forceStop'),
  authenticate: () => authenticateRuntime(),
  forgetToken: () => revokeRuntimeAccess(),
  openCredentials: () => credentialsWindow?.open(),
  openDeployments: () => deploymentsWindow?.open(),
  graphKey: (_context, invocation) => handleGraphKeydown(invocation.event),
  dismiss: () => dismissTransientUi(),
}));

function commandContext() {
  const history = editHistory.state();
  const transientRunning = Boolean(activeExecutionId) && !finishedExecutions.has(activeExecutionId);
  const sourceSessionActive = sourceSessionIsActive(activeSourceSession);
  const running = transientRunning || sourceSessionActive;
  const selectedNodes = cy?.nodes(':selected');
  return {
    hasDocument: Boolean(workspace.active && graphData),
    hasOpenDocuments: workspace.size > 0,
    editable: Boolean(graphData && graphData.format !== 'graphify'),
    canModify: canModifyGraph(graphData, layoutMode) && !layoutBusy,
    layoutBusy,
    modifyEnabled,
    inspectorAutosave,
    navigationEnabled,
    connectArmed,
    edgeGestureActive: Boolean(edgeGestureSession),
    nodeMoveActive: Boolean(dragSnapshot),
    hasSelection: Boolean(cy?.$(':selected').length),
    canDuplicateSelectedNode: Boolean(!layoutBusy && selectedNodes?.length === 1
      && canDuplicateNode(graphData, selectedNodes.first().id(), layoutMode)),
    hasJoinSemanticsMarker: Boolean(graphData && hasDeclaredJoinSemantics(graphData)),
    layoutMode,
    visualStyle,
    renderMode,
    workspaceLayoutMode: workspaceLayout.mode,
    workspaceLayoutDefault: workspaceLayoutIsDefault(),
    canUndo: history.canUndo && !layoutBusy,
    canRedo: history.canRedo && !layoutBusy,
    running,
    transientRunning,
    sourceSessionActive,
    executionUnknown: activeExecutionReconciliation === 'unknown',
    hasToken: hasRuntimeToken,
    leftCollapsed: Boolean(panelLayout.zones.left.collapsed),
    rightCollapsed: Boolean(panelLayout.zones.right.collapsed),
    applicationTheme,
  };
}

function executeCommand(id, invocation = {}) {
  return commandRegistry.execute(id, commandContext(), invocation);
}

function refreshCommands({ menu = true } = {}) {
  const context = commandContext();
  const focusedMenuCommand = document.activeElement?.closest('#application-menu [data-command-id]')?.dataset.commandId;
  document.querySelectorAll('[data-command-id]').forEach(control => {
    const id = control.dataset.commandId;
    const command = commandRegistry.get(id);
    const state = commandRegistry.state(id, context);
    if ('disabled' in control) control.disabled = !state.enabled;
    control.setAttribute('aria-disabled', String(!state.enabled));
    control.classList.toggle('active', state.checked === true);
    if (command.kind === 'checkbox') control.setAttribute('aria-pressed', String(state.checked === true));
    if (command.kind === 'radio') control.setAttribute('aria-checked', String(state.checked === true));
    if (control.hasAttribute('data-command-label')) control.textContent = command.label;
    if (command.help && control.hasAttribute('data-command-label')) control.title = command.help;
    const firstShortcut = command.shortcuts?.find(shortcut => (shortcut.scope || 'global') === 'global');
    if (firstShortcut) control.setAttribute('aria-keyshortcuts', commandRegistry.ariaShortcut(firstShortcut));
  });
  syncLayoutToolbarTabStop();
  if (menu && openApplicationMenuName) {
    renderApplicationMenu(openApplicationMenuName);
    if (focusedMenuCommand) document.querySelector(`#application-menu [data-command-id="${focusedMenuCommand}"]`)?.focus();
  }
}

function layoutToolbarRadios() {
  return [...document.querySelectorAll('.layout-mirrors[role="radiogroup"] > [role="radio"]')];
}

function syncLayoutToolbarTabStop() {
  const radios = layoutToolbarRadios();
  const checked = radios.find(control => control.getAttribute('aria-checked') === 'true'
    && control.getAttribute('aria-disabled') !== 'true');
  const tabStop = checked || radios.find(control => control.getAttribute('aria-disabled') !== 'true');
  radios.forEach(control => { control.tabIndex = control === tabStop ? 0 : -1; });
}

let openApplicationMenuName = null;
let applicationMenuOrigin = null;
let menuTypeahead = '';
let menuTypeaheadTimer = null;

function menuTrigger(name) {
  return document.querySelector(`#application-menubar [data-menu="${name}"]`);
}

function renderApplicationMenu(name) {
  const popup = document.getElementById('application-menu');
  const context = commandContext();
  const commands = commandRegistry.listPlacement(`menu.${name}`);
  let lastGroup = null;
  popup.innerHTML = commands.map(command => {
    const state = commandRegistry.state(command.id, context);
    const separator = lastGroup !== null && lastGroup !== command.group
      ? '<div class="application-menu-separator" role="separator"></div>' : '';
    lastGroup = command.group;
    const role = command.kind === 'checkbox' ? 'menuitemcheckbox'
      : command.kind === 'radio' ? 'menuitemradio' : 'menuitem';
    const shortcuts = command.shortcuts?.filter(shortcut => (shortcut.scope || 'global') === 'global') || [];
    const shortcut = shortcuts.length ? shortcuts.map(item => commandRegistry.shortcutLabel(item)).join(' / ') : '';
    const ariaShortcut = shortcuts[0] ? ` aria-keyshortcuts="${escapeAttribute(commandRegistry.ariaShortcut(shortcuts[0]))}"` : '';
    const checked = command.kind ? ` aria-checked="${state.checked === true}"` : '';
    return `${separator}<button type="button" class="application-menu-item" role="${role}"
      data-command-id="${escapeAttribute(command.id)}" aria-disabled="${!state.enabled}"${checked}${ariaShortcut}>
      <span>${escapeHtml(command.label)}</span><span class="application-menu-shortcut" aria-hidden="true">${escapeHtml(shortcut)}</span>
    </button>`;
  }).join('');
  popup.setAttribute('aria-labelledby', menuTrigger(name)?.id || '');
}

function openApplicationMenu(name, { focus = true } = {}) {
  const dialog = document.getElementById('unsaved-document-dialog');
  const activeDeploymentDialog = document.getElementById('active-deployment-dialog');
  const closeAllDialog = closeAllDocumentsDialog();
  if (dialog.open || activeDeploymentDialog.open || closeAllDialog.open) return false;
  closePopovers({ applicationMenu: false });
  const trigger = menuTrigger(name);
  const popup = document.getElementById('application-menu');
  if (!trigger) return false;
  openApplicationMenuName = name;
  applicationMenuOrigin = trigger;
  renderApplicationMenu(name);
  popup.hidden = false;
  popup.style.left = `${Math.round(trigger.getBoundingClientRect().left)}px`;
  popup.style.top = `${Math.round(trigger.getBoundingClientRect().bottom + 2)}px`;
  document.querySelectorAll('#application-menubar [data-menu]').forEach(item =>
    item.setAttribute('aria-expanded', String(item === trigger)));
  if (focus) firstEnabledMenuItem()?.focus();
  return true;
}

function closeApplicationMenu({ restoreFocus = false } = {}) {
  const popup = document.getElementById('application-menu');
  if (popup.hidden) return false;
  popup.hidden = true;
  document.querySelectorAll('#application-menubar [data-menu]').forEach(item => item.setAttribute('aria-expanded', 'false'));
  openApplicationMenuName = null;
  menuTypeahead = '';
  if (restoreFocus) applicationMenuOrigin?.focus();
  return true;
}

function applicationMenuItems() {
  return [...document.querySelectorAll('#application-menu .application-menu-item')];
}

function enabledMenuItems() {
  return applicationMenuItems().filter(item => item.getAttribute('aria-disabled') !== 'true');
}

function firstEnabledMenuItem() {
  return enabledMenuItems()[0] || null;
}

function focusRelativeMenuItem(delta) {
  const items = enabledMenuItems();
  if (!items.length) return;
  const current = items.indexOf(document.activeElement);
  items[(current + delta + items.length) % items.length].focus();
}

function switchApplicationMenu(delta) {
  const triggers = [...document.querySelectorAll('#application-menubar [data-menu]')];
  const current = triggers.indexOf(menuTrigger(openApplicationMenuName));
  const next = triggers[(current + delta + triggers.length) % triggers.length];
  setMenubarTabStop(next);
  openApplicationMenu(next.dataset.menu);
}

function setMenubarTabStop(trigger) {
  document.querySelectorAll('#application-menubar [data-menu]').forEach(item => item.tabIndex = item === trigger ? 0 : -1);
}

function resumeDocumentTabOrder(reverse) {
  const selector = 'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), '
    + 'a[href], [tabindex]:not([tabindex="-1"])';
  const controls = [...document.querySelectorAll(selector)].filter(control =>
    !control.closest('#application-menu') && !control.hidden && control.getClientRects().length);
  const index = controls.indexOf(applicationMenuOrigin);
  const offset = reverse ? -1 : 1;
  const next = controls[(index + offset + controls.length) % controls.length];
  closeApplicationMenu();
  next?.focus();
}

function syncCommandBarDensity() {
  const topbar = document.getElementById('topbar');
  // Design/Monitoring is the primary graph-view decision and remains reachable on responsive
  // widths. Secondary view and file mirrors collapse first; the application menus remain as their
  // textual counterpart.
  const densityClasses = [
    'density-hide-view', 'density-hide-file', 'density-hide-monitoring-controls',
  ];
  topbar.classList.remove(...densityClasses);
  for (const className of densityClasses) {
    if (topbar.scrollWidth <= topbar.clientWidth) break;
    topbar.classList.add(className);
  }
}

function dismissTransientUi() {
  if (cancelNodeMoveGesture({ announce: true })) return true;
  if (cancelEdgeGesture({ announce: true })) return true;
  if (contextualHelp.dismiss({ restoreFocus: true })) return true;
  if (visualTooltip.dismiss()) return true;
  if (cy && hideNodeActionOverlay(cy, { restoreFocus: true })) return true;
  if (cancelPanelDrag()) return true;
  if (closeApplicationMenu({ restoreFocus: true })) return true;
  if (closePopovers()) return true;
  if (document.getElementById('help-overlay').classList.contains('on')) {
    toggleHelp();
    return true;
  }
  closeInfo(); clearFilter(); clearTrace();
  return true;
}

function closePopovers({ applicationMenu = true } = {}) {
  contextualHelp.dismiss();
  visualTooltip.dismiss();
  let closed = false;
  for (const id of ['panel-menu', 'panels-index', 'document-switcher-dialog']) {
    const popover = document.getElementById(id);
    if (!popover.hidden) { popover.hidden = true; closed = true; }
  }
  for (const selector of ['.panel-menu', '.rail-index', '#document-switcher']) {
    document.querySelectorAll(`${selector}[aria-expanded="true"]`)
      .forEach(button => button.setAttribute('aria-expanded', 'false'));
  }
  if (applicationMenu && closeApplicationMenu()) closed = true;
  return closed;
}

function placePopover(popover, anchor) {
  popover.hidden = false;
  const box = anchor.getBoundingClientRect();
  const size = popover.getBoundingClientRect();
  // Clamped to the viewport on both axes: a menu opened from a panel at the foot of a column must
  // not open below the fold, which would make its commands unreachable.
  const left = Math.min(Math.max(8, box.left), window.innerWidth - size.width - 8);
  const top = box.bottom + 4 + size.height > window.innerHeight
    ? Math.max(8, box.top - size.height - 4)
    : box.bottom + 4;
  popover.style.left = `${Math.round(left)}px`;
  popover.style.top = `${Math.round(top)}px`;
}

function openPanelMenu(anchor) {
  const id = anchor.dataset.panel;
  const commands = panelMenuCommands(panelLayout, id);
  const menu = document.getElementById('panel-menu');
  // Only commands that would do something are rendered, so the menu can never offer an inert
  // entry — the same rule as a grip that does not drag, one level up.
  menu.innerHTML = commands.map(entry => `<button class="popover-item" role="menuitem" type="button"
      data-menu-command="${escapeAttribute(entry.command)}" data-menu-panel="${escapeAttribute(id)}"
      ${entry.zone ? `data-menu-zone="${escapeAttribute(entry.zone)}"` : ''}
    >${escapeHtml(entry.label)}</button>`).join('');
  closePopovers();
  placePopover(menu, anchor);
  anchor.setAttribute('aria-expanded', 'true');
  menu.querySelector('.popover-item')?.focus();
}

function openPanelsIndex(anchor) {
  const index = document.getElementById('panels-index');
  const items = PANELS.map(descriptor => {
    const entry = panelLayout.panels.find(panel => panel.id === descriptor.id);
    const closed = Boolean(entry?.closed);
    const where = closed ? 'closed' : zoneLabel(entry.zone);
    return `<button class="popover-item" type="button" data-index-panel="${escapeAttribute(descriptor.id)}"
        data-closed="${closed}"
      ><span class="popover-name">${escapeHtml(descriptor.title)}</span
      ><span class="popover-where">${escapeHtml(where)}</span></button>`;
  }).join('');
  index.innerHTML = `<div class="popover-title">Panels</div>${items}
    <div class="popover-sep"></div>
    <button class="popover-item" type="button" data-index-reset="1">Reset layout</button>`;
  closePopovers();
  placePopover(index, anchor);
  anchor.setAttribute('aria-expanded', 'true');
  index.querySelector('.popover-item')?.focus();
}

// Reopening from the index has to actually SHOW the panel, which means undoing whatever is hiding
// it — being closed, or living in a collapsed column. Reopening a panel into a column the user
// cannot see would be a route back that does not arrive.
function revealPanel(id) {
  const entry = panelLayout.panels.find(panel => panel.id === id);
  if (!entry) return;
  let next = setPanelClosed(panelLayout, id, false);
  if (next.zones[entry.zone]?.collapsed) next = setZoneCollapsed(next, entry.zone, false);
  updatePanelLayout(next);
  panelElement(id)?.querySelector('.panel-title')?.scrollIntoView({ block: 'nearest' });
}

// `aria-disabled` keeps explanatory controls discoverable and focusable. One capture-phase gate
// owns every activation route (pointer click, keyboard-synthesised click and delegated handlers),
// so individual features cannot accidentally make an unavailable control live again.
function blockAriaDisabledActivation(event) {
  const control = event.target.closest?.('[aria-disabled="true"]');
  if (!control) return;
  event.preventDefault();
  event.stopImmediatePropagation();
}

document.addEventListener('click', blockAriaDisabledActivation, true);
document.addEventListener('keydown', event => {
  if (event.key === 'Enter' || event.key === ' ') blockAriaDisabledActivation(event);
}, true);

document.addEventListener('click', event => {
  // ── Popovers first: their items are transient markup, so they are matched before anything that
  // walks the static document.
  const inspectorUnsavedAction = event.target.closest('[data-inspector-unsaved-action]');
  if (inspectorUnsavedAction) {
    completeInspectorTransition(inspectorUnsavedAction.dataset.inspectorUnsavedAction);
    return;
  }
  const closeAllAction = event.target.closest('[data-close-all-action]');
  if (closeAllAction) {
    handleCloseAllDocumentsAction(closeAllAction.dataset.closeAllAction);
    return;
  }
  const unsavedAction = event.target.closest('[data-unsaved-action]');
  if (unsavedAction) {
    closeUnsavedDocumentDialog(unsavedAction.dataset.unsavedAction);
    return;
  }
  // The closing-is-not-Stop dialog's own three actions.
  const activeDeploymentAction = event.target.closest('[data-active-deployment-action]');
  if (activeDeploymentAction) {
    closeActiveDeploymentDialog(activeDeploymentAction.dataset.activeDeploymentAction);
    return;
  }
  const applicationTrigger = event.target.closest('#application-menubar [data-menu]');
  if (applicationTrigger) {
    setMenubarTabStop(applicationTrigger);
    if (openApplicationMenuName === applicationTrigger.dataset.menu) closeApplicationMenu({ restoreFocus: true });
    else openApplicationMenu(applicationTrigger.dataset.menu);
    return;
  }
  const applicationCommand = event.target.closest('[data-command-id]');
  if (applicationCommand) {
    event.preventDefault();
    const id = applicationCommand.dataset.commandId;
    const fromMenu = Boolean(applicationCommand.closest('#application-menu'));
    if (fromMenu) closeApplicationMenu({ restoreFocus: false });
    executeCommand(id, { event, control: applicationCommand });
    refreshCommands();
    return;
  }
  const documentActivation = event.target.closest('[data-document-activate]');
  if (documentActivation) {
    const id = documentActivation.dataset.documentActivate;
    closeDocumentSwitcher();
    activateDocument(id);
    workspace.find(id)?.pane?.focus({ preventScroll: true });
    return;
  }
  const documentClose = event.target.closest('[data-document-close], [data-pane-document-close]');
  if (documentClose) {
    requestCloseDocument(documentClose.dataset.documentClose || documentClose.dataset.paneDocumentClose,
      documentClose);
    return;
  }
  const menuItem = event.target.closest('[data-menu-command]');
  if (menuItem) {
    const id = menuItem.dataset.menuPanel;
    const command = menuItem.dataset.menuCommand;
    let next = panelLayout;
    if (command === 'move-zone') next = movePanelToZone(panelLayout, id, menuItem.dataset.menuZone);
    else if (command === 'move-up') next = movePanelWithinZone(panelLayout, id, 'up');
    else if (command === 'move-down') next = movePanelWithinZone(panelLayout, id, 'down');
    else if (command === 'shorten') next = setPanelShort(panelLayout, id, true);
    else if (command === 'lengthen') next = setPanelShort(panelLayout, id, false);
    else if (command === 'maximise') next = setDockMaximised(panelLayout, true);
    else if (command === 'restore') next = setDockMaximised(panelLayout, false);
    else if (command === 'close') next = setPanelClosed(panelLayout, id, true);
    closePopovers();
    updatePanelLayout(next);
    return;
  }
  const railPanel = event.target.closest('[data-rail-panel]');
  if (railPanel) {
    closePopovers();
    revealPanel(railPanel.dataset.railPanel);
    return;
  }
  const indexItem = event.target.closest('[data-index-panel]');
  if (indexItem) {
    closePopovers();
    revealPanel(indexItem.dataset.indexPanel);
    return;
  }
  if (event.target.closest('[data-index-reset]')) {
    closePopovers();
    // Always available, and it is what makes every other layout command safe to offer: no
    // arrangement a user can reach costs more than one command to undo.
    updatePanelLayout(defaultLayout());
    return;
  }
  // A click anywhere else dismisses an open popover, unless it landed on the control that opens
  // one — which toggles it instead, below.
  if (!event.target.closest('.popover, .contextual-help-popover')
      && !event.target.closest('[aria-haspopup], [data-contextual-help]')) closePopovers();

  const removeProperty = event.target.closest('[data-remove-property]');
  if (removeProperty) {
    const form = removeProperty.closest('form');
    removeProperty.closest('.property-row')?.remove();
    if (form === inspectorDraft?.form) {
      inspectorDraft.dirty = inspectNodeDraft(inspectorDraft).changed;
      scheduleNodeDraftCommit(inspectorDraft, true);
    }
    return;
  }
  const addProperty = event.target.closest('[data-add-property]');
  if (addProperty) {
    addPropertyRow(addProperty.dataset.addProperty);
    return;
  }
  const legend = event.target.closest('[data-legend-kind]');
  if (legend) {
    toggleLegendFilter(legend.dataset.legendKind, legend.dataset.legendType);
    return;
  }
  const control = event.target.closest('[data-action]');
  if (!control) return;
  const action = control.dataset.action;
  if (action === 'fit') fitGraph();
  else if (action === 'help') toggleHelp();
  else if (action === 'new-document') openDocument();
  else if (action === 'modify') toggleModify();
  else if (action === 'connect') toggleConnect();
  else if (action === 'add-node') showAddNodeForm();
  else if (action === 'add-edge') showAddEdgeForm();
  else if (action === 'undo') undoEdit();
  else if (action === 'redo') redoEdit();
  else if (action === 'export') exportGraphML();
  else if (action === 'authenticate') authenticateRuntime();
  else if (action === 'revoke') revokeRuntimeAccess();
  else if (action === 'play') playGraph();
  else if (action === 'zoom') zoomBy(Number(control.dataset.value));
  else if (action === 'close-info') closeInfo();
  else if (action === 'clear-activity') clearActivity();
  else if (action === 'clear-assistant') clearAssistantConversation();
  else if (action === 'confirm-assistant-proposal') confirmAssistantProposal(control.dataset.proposalId);
  else if (action === 'reject-assistant-proposal') rejectAssistantProposal(control.dataset.proposalId);
  else if (action === 'connect-assistant') void startAssistantConnection();
  else if (action === 'cancel-assistant-connection') {
    abandonAssistantConnection();
    renderAssistantState();
  }
  else if (action === 'panel-close') {
    updatePanelLayout(setPanelClosed(panelLayout, control.dataset.panel, true));
  } else if (action === 'panel-menu') {
    if (control.getAttribute('aria-expanded') === 'true') closePopovers();
    else openPanelMenu(control);
  } else if (action === 'panels-index') {
    if (control.getAttribute('aria-expanded') === 'true') closePopovers();
    else openPanelsIndex(control);
  } else if (action === 'document-switcher') {
    if (control.getAttribute('aria-expanded') === 'true') closeDocumentSwitcher({ restoreFocus: true });
    else openDocumentSwitcher();
  } else if (action === 'zone-toggle') {
    const zone = control.dataset.zone;
    updatePanelLayout(setZoneCollapsed(panelLayout, zone, !panelLayout.zones[zone].collapsed));
  }
});

document.getElementById('inspector-unsaved-dialog').addEventListener('cancel', event => {
  event.preventDefault();
  completeInspectorTransition('cancel');
});

document.addEventListener('input', event => {
  const action = event.target.dataset.inputAction;
  if (action === 'font-size') onFontSize(event.target.value);
  else if (action === 'elastic-repulsion') onElasticRepulsion(event.target.value);
  else if (action === 'elastic-attraction') onElasticAttraction(event.target.value);
  else if (action === 'search') onSearch(event.target.value);
});

// The graph widget owns its arrow keys, E, R, Enter and Escape while it has focus (UI-02).
// Registering on the container rather than the document keeps those keys free everywhere else.
// The panes sit INSIDE this element, so a pane that holds focus still delivers those keys here:
// the contract is untouched by the pane layer rather than reimplemented beside it.
document.getElementById('cy-wrap').addEventListener('keydown', event => {
  if (layoutBusy
      && ['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Enter', 'e', 'E', 'r', 'R'].includes(event.key)) {
    event.preventDefault();
    event.stopPropagation();
    announceGraph('Layout in progress. Graph editing is available when it finishes.');
    return;
  }
  const id = commandRegistry.matchShortcut(event, commandContext(), 'canvas');
  if (id) {
    executeCommand(id, { event, control: event.target });
    if (event.defaultPrevented) event.stopPropagation();
  }
});

document.querySelector('.layout-mirrors[role="radiogroup"]')?.addEventListener('keydown', event => {
  const current = event.target.closest('[role="radio"]');
  if (!current) return;
  const radios = layoutToolbarRadios().filter(control => control.getAttribute('aria-disabled') !== 'true');
  const currentIndex = radios.indexOf(current);
  if (currentIndex < 0) return;
  let nextIndex = currentIndex;
  if (event.key === 'ArrowRight' || event.key === 'ArrowDown') nextIndex = (currentIndex + 1) % radios.length;
  else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') nextIndex = (currentIndex - 1 + radios.length) % radios.length;
  else if (event.key === 'Home') nextIndex = 0;
  else if (event.key === 'End') nextIndex = radios.length - 1;
  else return;
  event.preventDefault();
  event.stopPropagation();
  const next = radios[nextIndex];
  next.focus();
  next.click();
});

// The bottom dock is clamped in CSS so the stage keeps its floor, but the floor itself is decided
// in `panes.js` beside the width floors it belongs with. Publishing it as the custom property the
// clamp reads keeps ONE source of truth: the alternative is the same measured number written in two
// files, which is how a floor drifts away from the measurement that produced it.
document.documentElement.style.setProperty('--stage-min-h', `${STAGE_MIN_HEIGHT}px`);
document.documentElement.style.setProperty('--stage-min-w', `${PANE_MIN_WIDTH}px`);

document.querySelectorAll('[data-splitter-kind="workspace"]').forEach(splitter => {
  splitter.addEventListener('keydown', onLayoutSplitterKeydown);
  splitter.addEventListener('pointerdown', onLayoutSplitterPointerDown);
});

// Render the stored layout before anything measures the viewport: a column that expands one frame
// after boot would make the first pane plan wrong.
applyPanelLayout();

// Constructed BEFORE `connectRuntime(true)` at the bottom of this file so the page's own first
// connection hands it a client. The window binds itself to its own container and owns its own
// listeners, so nothing about it reaches this file's delegated `click`/`input` handlers or the
// `data-action` namespace. `onCredentials` is the only wire between the window and the rest of the
// application: the node inspector's SECRET_REFERENCE control reads exactly what the last listing
// established, and nothing else about a credential ever reaches this file.
credentialsWindow = createCredentialsWindow({
  dialog: document.getElementById('credentials-dialog'),
  onCredentials: held => {
    credentialReferences = held;
    // In place, and only the SECRET_REFERENCE controls — see `refreshSecretReferenceChoices` for why
    // this is not a re-render of the inspector.
    refreshSecretReferenceChoices();
  },
});

// The Deployments window follows the same construction rule as the Credentials window above.
// `currentDocument` captures the active document's graph fresh on every register
// attempt, the same way `playGraph` captures its own snapshot before its first `await`: syncing
// positions and serializing NOW, not reading `graphData` again after a request has already started
// against whichever document happened to be active when it returns.
deploymentsWindow = createDeploymentsWindow({
  dialog: document.getElementById('deployments-dialog'),
  currentDocument: () => {
    if (!workspace.active || !graphData) return null;
    syncGraphPositions();
    return { displayName: graphDisplayName, graphMl: serializeGraphML(graphData) };
  },
});

// Panel contents arrive asynchronously (notably the Node Catalog). Keep the separator's live ARIA
// range tied to rendered geometry instead of freezing the values captured during initial boot.
const panelResizeObserver = new ResizeObserver(entries => {
  const zones = new Set(entries.map(entry => entry.target.dataset.panelZone).filter(Boolean));
  for (const zone of zones) syncPanelSplitterValues(zone);
});
document.querySelectorAll('.panel[data-panel-id]').forEach(panel => panelResizeObserver.observe(panel));

// Whether a split is possible is a question about the current width, so it has to be re-asked when
// the width changes — a window resize, the inspector opening, the palette collapsing. Cytoscape does
// not observe its container either, so this is also where a resized pane tells its renderer.
new ResizeObserver(() => syncPaneLayout()).observe(document.getElementById('cy-wrap'));
new ResizeObserver(syncCommandBarDensity).observe(document.getElementById('topbar'));
new ResizeObserver(() => {
  applyZoneGeometry();
  syncPaneLayout();
}).observe(document.getElementById('main'));
document.getElementById('cy-wrap').addEventListener('focus', () => {
  if (!cy || !graphData) return;
  ensureGraphCursor();
  announceCursor();
});

document.getElementById('file-inp').addEventListener('change', onFileInput);
document.getElementById('replace-file-inp').addEventListener('change', onReplaceFileInput);
document.getElementById('application-menubar').addEventListener('keydown', event => {
  const trigger = event.target.closest('[data-menu]');
  if (!trigger) return;
  const triggers = [...event.currentTarget.querySelectorAll('[data-menu]')];
  let index = triggers.indexOf(trigger);
  if (event.key === 'ArrowRight') index = (index + 1) % triggers.length;
  else if (event.key === 'ArrowLeft') index = (index - 1 + triggers.length) % triggers.length;
  else if (event.key === 'Home') index = 0;
  else if (event.key === 'End') index = triggers.length - 1;
  else if (['ArrowDown', 'ArrowUp', 'Enter', ' '].includes(event.key)) {
    event.preventDefault();
    openApplicationMenu(trigger.dataset.menu);
    if (event.key === 'ArrowUp') enabledMenuItems().at(-1)?.focus();
    return;
  } else return;
  event.preventDefault();
  setMenubarTabStop(triggers[index]);
  triggers[index].focus();
  if (openApplicationMenuName) openApplicationMenu(triggers[index].dataset.menu);
});
document.getElementById('application-menu').addEventListener('keydown', event => {
  if (event.key === 'ArrowDown') focusRelativeMenuItem(1);
  else if (event.key === 'ArrowUp') focusRelativeMenuItem(-1);
  else if (event.key === 'Home') firstEnabledMenuItem()?.focus();
  else if (event.key === 'End') enabledMenuItems().at(-1)?.focus();
  else if (event.key === 'ArrowRight') switchApplicationMenu(1);
  else if (event.key === 'ArrowLeft') switchApplicationMenu(-1);
  else if (event.key === 'Escape') closeApplicationMenu({ restoreFocus: true });
  else if (event.key === 'Tab') {
    event.preventDefault();
    resumeDocumentTabOrder(event.shiftKey);
    return;
  } else if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault();
    if (event.target.getAttribute('aria-disabled') !== 'true') event.target.click();
    return;
  } else if (event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) {
    clearTimeout(menuTypeaheadTimer);
    menuTypeahead += event.key.toLowerCase();
    menuTypeaheadTimer = setTimeout(() => { menuTypeahead = ''; }, 600);
    const match = enabledMenuItems().find(item => item.textContent.trim().toLowerCase().startsWith(menuTypeahead));
    match?.focus();
    event.preventDefault();
    return;
  } else return;
  event.preventDefault();
  event.stopPropagation();
});
document.getElementById('document-switcher-dialog').addEventListener('keydown', event => {
  if (event.key === 'Escape') {
    event.preventDefault();
    event.stopPropagation();
    closeDocumentSwitcher({ restoreFocus: true });
    return;
  }
  const activation = event.target.closest('[data-document-activate]');
  if (!activation) return;
  const items = [...event.currentTarget.querySelectorAll('[data-document-activate]')];
  let index = items.indexOf(activation);
  if (event.key === 'ArrowDown') index = (index + 1) % items.length;
  else if (event.key === 'ArrowUp') index = (index - 1 + items.length) % items.length;
  else if (event.key === 'Home') index = 0;
  else if (event.key === 'End') index = items.length - 1;
  else return;
  event.preventDefault();
  event.stopPropagation();
  items[index]?.focus();
});
document.getElementById('unsaved-document-dialog').addEventListener('keydown', event => {
  // Stop canvas/global Escape semantics behind the modal. The dialog's cancel event below owns it.
  event.stopPropagation();
});
document.getElementById('unsaved-document-dialog').addEventListener('cancel', event => {
  event.preventDefault();
  closeUnsavedDocumentDialog('cancel');
});
document.getElementById('close-all-documents-dialog').addEventListener('keydown', event => {
  event.stopPropagation();
});
document.getElementById('close-all-documents-dialog').addEventListener('cancel', event => {
  event.preventDefault();
  cancelCloseAllDocuments();
});
// Same containment as the unsaved-changes dialog above, for the same reason -- Escape must
// resolve this modal's own cancel action, not fall through to canvas or global shortcuts behind it.
document.getElementById('active-deployment-dialog').addEventListener('keydown', event => {
  event.stopPropagation();
});
document.getElementById('active-deployment-dialog').addEventListener('cancel', event => {
  event.preventDefault();
  closeActiveDeploymentDialog('cancel');
});
// The same guard, for the same reason, and it matters more here than there. Every global
// single-key shortcut in this application is live on `document` — `h`, `f`, `e`, `r`, the digits —
// and this dialog is the one place in the product where a person types a SECRET into a field. A
// keystroke that both lands in the value box and switches the layout behind the modal is the kind of
// surprise that makes someone retype, or paste, in the wrong place. Escape still closes the window:
// that is the dialog's own `cancel`, which `credential-panel.js` binds to clear the value.
document.getElementById('credentials-dialog').addEventListener('keydown', event => {
  event.stopPropagation();
});
// the same containment as the two dialogs above, for the same reason -- this window has
// its own text field (the deployment id), and every global single-key shortcut is live on `document`.
document.getElementById('deployments-dialog').addEventListener('keydown', event => {
  event.stopPropagation();
});
// Wrapped, not passed by reference: `connectRuntime` now takes a flag, and a listener would hand it
// the Event object, which is truthy.
document.getElementById('service-url').addEventListener('change', () => connectRuntime());
document.getElementById('access-token').addEventListener('keydown', event => {
  if (event.key === 'Enter') authenticateRuntime();
});

// ── Authoring assistant ───────────────────────────────────────────────────────────────────
// Submit through the FORM's own event, so Enter in the textarea and a click on Ask are one path
// rather than two that can drift. `novalidate` on the form keeps refusals ours: the browser's own
// validation bubble is neither associated with the control the way `aria-errormessage` is, nor
// styled by this product.
document.getElementById('assistant-composer').addEventListener('submit', event => {
  event.preventDefault();
  void submitAssistantDraft();
});
document.getElementById('assistant-draft').addEventListener('keydown', event => {
  // Enter sends, Shift+Enter is a newline — the convention every editor assistant uses. Stopped
  // from propagating so the canvas's global key handling never sees a keystroke meant for prose.
  event.stopPropagation();
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault();
    void submitAssistantDraft();
  }
});
// Typing clears the refusal it caused. Leaving a stale error bound to the field would keep
// `aria-invalid` asserting something that is no longer true.
document.getElementById('assistant-draft').addEventListener('input', () => setAssistantDraftError(''));
document.getElementById('assistant-payload-toggle').addEventListener('click', toggleAssistantPayload);
document.getElementById('help-box').addEventListener('click', event => {
  const command = event.target.closest('[data-command-id]');
  if (command) executeCommand(command.dataset.commandId, { event, control: command });
  event.stopPropagation();
});

// ═══════════════════════════════════════════════════════════════
// BOOT
// ═══════════════════════════════════════════════════════════════

// Browsers ignore custom beforeunload text and only honour that a handler asked to warn, so this
// is the whole contract: a dirty document gets the browser's own leave-site confirmation.
window.addEventListener('beforeunload', event => {
  // Every open document, not only the visible one: a modified document in a background pane is
  // exactly as unsaved as the one in front of the user, and it is the one more easily forgotten.
  // The active document's edits live in the working view until they are written back, so the record
  // has to be brought up to date first — otherwise the document actually being edited is the single
  // document that would not count.
  captureActiveDocument();
  if (!shouldWarnBeforeUnload(hasUnsavedWork(workspace))) return;
  event.preventDefault();
  event.returnValue = '';
});

window.addEventListener('load', () => {
  hideLoading();
  renderShortcutHelp();
  updateHistoryUi();
  syncCommandBarDensity();
  const params = new URLSearchParams(location.search);
  // The page asks the service what it offers instead of deciding on its own that it may not ask.
  // Whether authentication is required is the service's answer — a 401 still produces exactly the
  // state and message it produced before, because the gate lives on the server and is untouched.
  // The attempt is unconditional: a build flag or an environment variable here would be the same
  // assumption behind a switch. It runs before the graph loads so the request departs immediately,
  // and a failure only fills the palette with a reason — the editor stays usable offline.
  connectRuntime(true);
  // Composed once at boot so the chips are truthful from the first paint rather than blank
  // until something happens. `connectRuntime` above has already asked the service what it offers.
  refreshAssistantContext();
  // The workspace always holds at least one document; the editor has simply never had a way to open
  // a second one. Creating it here means `initCy` always has a document to draw into.
  addDocumentRecord();
  // Auto-load from ?file= query parameter. Supports .graphml, .xml, .json (graphify).
  // Examples:
  // ?file=my-flow.graphml
  // ?file=/graphs/project/graph.json
  // ?file=examples/graph.json
  const fileParam = params.get('file');
  if (fileParam) autoLoadUrl(fileParam);
  else newWorkflow();
});
