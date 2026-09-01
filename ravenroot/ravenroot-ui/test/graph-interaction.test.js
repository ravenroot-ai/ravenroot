import { describe, expect, it } from 'vitest';

import {
  canvasInteractionState,
  catalogDropPlan,
  isAdditiveSelection,
  modelPositionFromClient,
  nodeCanSourceEdge,
  nodeIsGrabbable,
  selectionAfterClick,
  stageTapAction,
  STAGE_TAP_ACTION,
} from '../src/graph-interaction.js';

describe('Viewer and Editing canvas interaction', () => {
  it('keeps box selection as the default and reserves panning for Navigation', () => {
    expect(canvasInteractionState()).toMatchObject({
      mode: 'viewer', nodeGrabPolicy: 'all', boxSelection: true, panning: false, zooming: true, cursor: 'default',
    });
    expect(canvasInteractionState({ editing: true })).toMatchObject({
      mode: 'editing', nodeGrabPolicy: 'selected', boxSelection: true, panning: false, zooming: true,
      cursor: 'crosshair',
    });
    expect(canvasInteractionState({ navigating: true })).toMatchObject({
      nodeGrabPolicy: 'all', boxSelection: false, panning: true, zooming: true, cursor: 'grab',
    });
    expect(nodeIsGrabbable(canvasInteractionState({ editing: true }), false)).toBe(false);
    expect(nodeIsGrabbable(canvasInteractionState({ editing: true }), true)).toBe(true);
    expect(nodeIsGrabbable(canvasInteractionState(), false)).toBe(true);
    expect(nodeCanSourceEdge(false)).toBe(true);
    expect(nodeCanSourceEdge(true)).toBe(false);
  });

  it('keeps a repeated plain click selected and toggles Ctrl/Cmd-click against the pointer-start set', () => {
    expect(selectionAfterClick(['a'], 'a')).toEqual(['a']);
    expect(selectionAfterClick(['a'], 'b')).toEqual(['b']);
    expect(selectionAfterClick(['a'], 'b', true)).toEqual(['a', 'b']);
    expect(selectionAfterClick(['a', 'b'], 'b', true)).toEqual(['a']);
    expect(selectionAfterClick(['a'], 'a', true)).toEqual([]);
    expect(isAdditiveSelection({ ctrlKey: true })).toBe(true);
    expect(isAdditiveSelection({ metaKey: true })).toBe(true);
    expect(isAdditiveSelection({ ctrlKey: false, metaKey: false })).toBe(false);
  });

  it('gives empty-stage selection clearing precedence over Editing insertion', () => {
    expect(stageTapAction({ editing: false, gestureStarted: true })).toBe(STAGE_TAP_ACTION.CLEAR);
    expect(stageTapAction({ editing: true, gestureStarted: true, hasSelection: true })).toBe(STAGE_TAP_ACTION.CLEAR);
    expect(stageTapAction({ editing: true, gestureStarted: true, hasSelection: false })).toBe(STAGE_TAP_ACTION.CREATE);
    expect(stageTapAction({ editing: true, navigating: true, gestureStarted: true })).toBe(STAGE_TAP_ACTION.CLEAR);
    expect(stageTapAction({ editing: true, gestureStarted: true, gestureMoved: true })).toBe(STAGE_TAP_ACTION.IGNORE);
    expect(stageTapAction({ editing: true, gestureStarted: false })).toBe(STAGE_TAP_ACTION.IGNORE);
  });

  it('projects a toolbox drop into model space and distinguishes empty stage from a node target', () => {
    const position = modelPositionFromClient({ x: 230, y: 150 }, { left: 10, top: 20 }, { x: 20, y: 10 }, 2);
    expect(position).toEqual({ x: 100, y: 60 });
    expect(catalogDropPlan({ behavior: 'template', position })).toMatchObject({ sourceId: null });
    expect(catalogDropPlan({ behavior: 'template', position, targetId: 'start' }))
      .toMatchObject({ sourceId: 'start' });
  });
});
