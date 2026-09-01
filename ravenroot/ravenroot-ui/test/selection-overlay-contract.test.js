import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const APP_SOURCE_PATH = resolve(dirname(fileURLToPath(import.meta.url)), '../src/app.js');
const APP_SOURCE = readFileSync(APP_SOURCE_PATH, 'utf8');

function functionBody(source, signature) {
  const start = source.indexOf(signature);
  if (start < 0) throw new Error(`Signature not found: ${signature}`);
  let depth = 0;
  for (let index = start + signature.length - 1; index < source.length; index += 1) {
    if (source[index] === '{') depth += 1;
    else if (source[index] === '}') {
      depth -= 1;
      if (depth === 0) return source.slice(start, index + 1);
    }
  }
  throw new Error(`Unbalanced braces after: ${signature}`);
}

describe('selected-node overlay source contract', () => {
  it('keeps semantic Cytoscape borders out of the selection state', () => {
    expect(APP_SOURCE).not.toContain("selector: 'node:selected'");
  });

  it('projects an eight-pixel screen-space gap without labels or renderer overlays', () => {
    expect(APP_SOURCE).toContain('const SELECTION_OUTLINE_GAP = 8;');
    const body = functionBody(APP_SOURCE, 'function selectionOutlineRect(node) {');
    expect(body).toContain('node.renderedBoundingBox({');
    expect(body).toContain('includeLabels: false');
    expect(body).toContain('includeOverlays: false');
    expect(body).toContain('includeUnderlays: false');
    expect(body).toContain('width: values[2] + SELECTION_OUTLINE_GAP * 2');
    expect(body).toContain('height: values[3] + SELECTION_OUTLINE_GAP * 2');
  });

  it('creates one inert per-document layer with four equal-corner hooks', () => {
    expect(APP_SOURCE).toContain("const SELECTION_OUTLINE_CORNERS = ['nw', 'ne', 'se', 'sw'];");
    const install = functionBody(APP_SOURCE, 'function installSelectionOverlay(owner, instance, container) {');
    expect(install).toContain("root.className = 'graph-selection-overlay';");
    expect(install).toContain("root.dataset.ready = 'true';");
    expect(install).toContain("root.setAttribute('aria-hidden', 'true');");
    expect(install).toContain('container.append(root);');
    const create = functionBody(APP_SOURCE, 'function createSelectionBox(nodeId) {');
    expect(create).toContain("element.className = 'graph-selection-box';");
    expect(create).toContain("handle.className = 'graph-selection-handle';");
    expect(create).toContain('handle.dataset.corner = corner;');
  });

  it('reuses one live root and removes stale siblings when Cytoscape is registered again', () => {
    const install = functionBody(APP_SOURCE, 'function installSelectionOverlay(owner, instance, container) {');
    expect(install).toContain(".filter(child => child.classList?.contains('graph-selection-overlay'));");
    expect(install).toContain('installed?.root.parentElement === container');
    expect(install).toContain('installed.root.dataset.documentId === owner.id');
    expect(install).toContain('siblingRoots.filter(root => root !== installed.root).forEach(root => root.remove());');
    expect(install).toContain('if (installed) destroySelectionOverlay(instance);');
  });

  it('tracks selection and rendered geometry and tears down with renderer ownership', () => {
    const install = functionBody(APP_SOURCE, 'function installSelectionOverlay(owner, instance, container) {');
    expect(install).toContain("instance.on('select unselect position style add remove', 'node', schedule);");
    expect(install).toContain("instance.on('pan zoom resize render', schedule);");
    const destroy = functionBody(APP_SOURCE, 'function destroySelectionOverlay(instance) {');
    expect(destroy).toContain("instance.off('select unselect position style add remove', 'node', overlay.schedule);");
    expect(destroy).toContain("instance.off('pan zoom resize render', overlay.schedule);");
    expect(destroy).toContain('overlay.root.remove();');
    expect(functionBody(APP_SOURCE, "function destroyDocumentRenderer(owner, reason = 'destroyed') {")).toContain(
      "if (renderer.kind === 'cytoscape') destroySelectionOverlay(renderer.cy);");
  });

  it('installs through the shared Cytoscape registration seam, including Elastic restoration', () => {
    const register = functionBody(APP_SOURCE, 'function registerCytoscapeRenderer(owner, target = owner?.cy) {');
    expect(register).toContain('installSelectionOverlay(owner, target, target.container());');
    const stopElastic = functionBody(APP_SOURCE, "function stopElasticRendering(owner = workspace.active, reason = 'stopped') {");
    expect(stopElastic).toContain('registerCytoscapeRenderer(owner);');
    const init = functionBody(APP_SOURCE, 'function initCy(elements, gd, options = {}) {');
    expect(init).toContain('registerCytoscapeRenderer(rendererOwner, cy);');
    expect(init).not.toContain('installSelectionOverlay(rendererOwner');
  });
});
