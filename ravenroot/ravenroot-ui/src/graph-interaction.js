export const GRAPH_MODE = Object.freeze({ VIEWER: 'viewer', EDITING: 'editing' });
export const STAGE_TAP_ACTION = Object.freeze({
  CLEAR: 'clear',
  CREATE: 'create',
  IGNORE: 'ignore',
});

export function canvasInteractionState({ editing = false, navigating = false } = {}) {
  return Object.freeze({
    mode: editing ? GRAPH_MODE.EDITING : GRAPH_MODE.VIEWER,
    navigating: Boolean(navigating),
    nodeGrabPolicy: editing && !navigating ? 'selected' : 'all',
    boxSelection: !navigating,
    panning: Boolean(navigating),
    zooming: true,
    cursor: navigating ? 'grab' : editing ? 'crosshair' : 'default',
  });
}

export function nodeIsGrabbable({ nodeGrabPolicy = 'all' } = {}, selected = false) {
  return nodeGrabPolicy === 'all' || (nodeGrabPolicy === 'selected' && Boolean(selected));
}

export function nodeCanSourceEdge(selected = false) {
  return !Boolean(selected);
}

export function selectionAfterClick(selectedIds = [], clickedId, additive = false) {
  if (!clickedId) return [];
  if (!additive) return [clickedId];
  const selected = new Set(selectedIds);
  if (selected.has(clickedId)) selected.delete(clickedId);
  else selected.add(clickedId);
  return [...selected];
}

export function isAdditiveSelection(event) {
  return Boolean(event?.ctrlKey || event?.metaKey);
}

// Stage taps have a deliberately small, renderer-independent interaction machine.  In particular,
// selection clearing precedes Editing insertion: otherwise whether a first empty tap creates a node
// depends on Cytoscape's select/unselect event ordering.  A gesture that moved belongs to pan or box
// selection and is never allowed to author a node.
export function stageTapAction({
  editing = false,
  navigating = false,
  gestureStarted = false,
  gestureMoved = false,
  hasSelection = false,
} = {}) {
  if (!gestureStarted || gestureMoved) return STAGE_TAP_ACTION.IGNORE;
  if (!editing || navigating || hasSelection) return STAGE_TAP_ACTION.CLEAR;
  return STAGE_TAP_ACTION.CREATE;
}

export function modelPositionFromClient(client, bounds, pan = {}, zoom = 1) {
  const safeZoom = Number(zoom) || 1;
  return {
    x: (Number(client?.x) - Number(bounds?.left || 0) - Number(pan?.x || 0)) / safeZoom,
    y: (Number(client?.y) - Number(bounds?.top || 0) - Number(pan?.y || 0)) / safeZoom,
  };
}

export function catalogDropPlan({ behavior = '', position, targetId = null } = {}) {
  if (!behavior || !position) return null;
  return Object.freeze({ behavior, position, sourceId: targetId || null });
}
