import cytoscape from 'cytoscape';

import { createEmbedShellLifecycle, EmbedShellFailure } from './embed-shell-lifecycle.js';
import { createReadOnlyViewerCore } from './viewer-core.js';
import {
  createCytoscapeReadOnlyRendererAdapter,
  createReadOnlyRendererAdapter,
} from './viewer-renderer-adapter.js';
import { getRendererPalette } from './theme-palette.js';
import { requireEmbedTheme } from './theme-resolution.js';
import { createViewerStylesheet, viewerNodeType } from './viewer-presentation.js';
import {
  applyDeploymentViewFrame,
  applyDeploymentViewStateToRenderer,
  createDeploymentViewState,
} from './deployment-view-state.js';
import { applyViewerSimpleRoute, applyViewerUnbundledRoute } from './viewer-edge-style.js';
import {
  resolveViewerRoutesWithinBudget,
  viewerSupportsElastic,
} from './viewer-route-budget.js';
import { mountD3ElasticRenderer } from './viewer-elastic-renderer.js';
import {
  clampViewportCenter,
  minimapToWorld,
  normalizeBounds,
  projectMinimap,
} from './minimap-geometry.js';

export function viewerStylesheet(mode = 'cyto', theme = 'dark') {
  return createViewerStylesheet(requireEmbedTheme(theme), mode);
}

export function applyResolvedRoutes(instance, mode) {
  if (mode !== 'cyto') return;
  const nodes = instance.nodes().map(node => ({
    id: node.id(), x: node.position().x, y: node.position().y,
    width: node.width(), height: node.height(),
  }));
  const edges = instance.edges().map(edge => ({
    id: edge.id(), source: edge.source().id(), target: edge.target().id(), label: '',
  }));
  const plan = resolveViewerRoutesWithinBudget({ nodes, edges });
  instance.edges().forEach(edge => {
    if (plan.strategy === 'simple') {
      applyViewerSimpleRoute(edge);
      return;
    }
    const route = plan.routes.get(edge.id());
    if (!route || edge.source().id() === edge.target().id()) return;
    applyViewerUnbundledRoute(edge, route, { lineCap: 'round' });
  });
}

function elasticElements(snapshot, palette, width, height) {
  const columns = Math.max(1, Math.ceil(Math.sqrt(snapshot.nodes.length)));
  const nodes = snapshot.nodes.map((node, index) => ({
    id: node.id,
    label: node.label || node.id,
    r: 14,
    color: palette.nodeType[viewerNodeType(node)] ?? palette.selection,
    stroke: palette.runtimeIdle,
    strokeWidth: 1.5,
    x: node.layout?.x ?? ((index % columns) + 1) * width / (columns + 1),
    y: node.layout?.y ?? (Math.floor(index / columns) + 1) * height
      / (Math.ceil(snapshot.nodes.length / columns) + 1),
    instances: null,
  }));
  const links = snapshot.edges.map(edge => ({
    id: edge.id,
    source: edge.source,
    target: edge.target,
    baseWidth: 1.8,
    restLen: 130,
    color: palette.edgeType[edge.visualType] ?? palette.edgeType.default,
    label: edge.label || '',
    traffic: null,
  }));
  return { nodes, links };
}

function requiredElement(root, selector) {
  const element = root.querySelector(selector);
  if (element === null) throw new Error('Embed viewer shell unavailable.');
  return element;
}

/**
 * Narrow internal mount point for the bootstrap closure. The projection
 * is passed directly and is never published on window, storage, or the DOM.
 */
export function createEmbedViewer(container, { theme = 'dark' } = {}) {
  if (!(container instanceof Element)) throw new TypeError('Embed viewer container is required.');
  const viewerTheme = requireEmbedTheme(theme);
  const palette = getRendererPalette(viewerTheme);
  const canvas = requiredElement(container, '[data-viewer-canvas]');
  const status = requiredElement(container, '[data-viewer-status]');
  const metadata = requiredElement(container, '[data-viewer-metadata]');
  const alternative = requiredElement(container, '[data-viewer-alternative]');
  const minimap = requiredElement(container, '[data-viewer-minimap]');
  const mode = requiredElement(container, '[data-viewer-mode]');
  const elasticOption = mode.querySelector('option[value="elastic"]');
  if (!(elasticOption instanceof HTMLOptionElement)) {
    throw new Error('Embed viewer Elastic option unavailable.');
  }
  mode.value = 'cyto';
  mode.disabled = true;
  const controls = [...container.querySelectorAll('[data-viewer-command]')];
  const focusBoundaries = [...container.ownerDocument.querySelectorAll('.embed-focus-sentinel')];
  if (focusBoundaries.length !== 2) throw new Error('Embed viewer focus boundary unavailable.');
  const lifecycle = createEmbedShellLifecycle({
    root: container,
    status,
    alternative,
    before: focusBoundaries[0],
    after: focusBoundaries[1],
  });
  const instance = cytoscape({
    container: canvas,
    elements: [],
    layout: { name: 'preset' },
    style: viewerStylesheet(mode.value, viewerTheme),
    autoungrabify: true,
    boxSelectionEnabled: false,
    minZoom: 0.05,
    maxZoom: 5,
    wheelSensitivity: 0.25,
  });
  const elasticSvg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  elasticSvg.classList.add('embed-viewer-elastic');
  elasticSvg.dataset.viewerElastic = '';
  elasticSvg.setAttribute('aria-hidden', 'true');
  elasticSvg.setAttribute('hidden', '');
  canvas.append(elasticSvg);
  const cytoscapeAdapter = createCytoscapeReadOnlyRendererAdapter(instance);
  let resizeFrame = null;
  let minimapFrame = null;
  let minimapProjection = null;
  let destroyed = false;
  let mounted = false;
  let currentSnapshot = null;
  let elasticMount = null;
  let deploymentState = null;

  const concealMinimap = () => {
    if (minimapFrame !== null) cancelAnimationFrame(minimapFrame);
    minimapFrame = null;
    minimapProjection = null;
    minimap.hidden = true;
    minimap.tabIndex = -1;
    minimap.setAttribute('aria-hidden', 'true');
  };

  const enableMinimap = () => {
    minimap.tabIndex = 0;
    minimap.removeAttribute('aria-hidden');
  };

  const paintMinimap = () => {
    minimapFrame = null;
    if (!mounted || destroyed || mode.value === 'elastic' || instance.nodes().length === 0
        || canvas.clientWidth < 240 || canvas.clientHeight < 150) {
      concealMinimap();
      return;
    }
    enableMinimap();
    minimap.hidden = false;
    const ratio = Math.max(1, devicePixelRatio || 1);
    const width = minimap.clientWidth || 160;
    const height = minimap.clientHeight || 104;
    minimap.width = Math.round(width * ratio);
    minimap.height = Math.round(height * ratio);
    const context = minimap.getContext('2d');
    context.setTransform(ratio, 0, 0, ratio, 0, 0);
    context.clearRect(0, 0, width, height);
    minimapProjection = projectMinimap({
      contentBounds: normalizeBounds(instance.elements().boundingBox({ includeLabels: true })),
      visibleBounds: normalizeBounds(instance.extent()), width, height, header: 6,
    });
    const point = value => ({
      x: value.x * minimapProjection.scale + minimapProjection.offsetX,
      y: value.y * minimapProjection.scale + minimapProjection.offsetY,
    });
    context.strokeStyle = `${palette.edgeType.default}99`;
    context.lineWidth = 1;
    instance.edges().forEach(edge => {
      const source = point(edge.source().position());
      const target = point(edge.target().position());
      context.beginPath(); context.moveTo(source.x, source.y); context.lineTo(target.x, target.y); context.stroke();
    });
    context.fillStyle = palette.nodeBorder;
    instance.nodes().forEach(node => {
      const center = point(node.position());
      context.beginPath(); context.arc(center.x, center.y, 2.25, 0, Math.PI * 2); context.fill();
    });
    const viewport = minimapProjection.viewport;
    context.fillStyle = palette.minimapViewportFill;
    context.fillRect(viewport.x, viewport.y, viewport.width, viewport.height);
    context.strokeStyle = palette.minimapViewport; context.lineWidth = 2;
    context.strokeRect(viewport.x + 1, viewport.y + 1,
      Math.max(0, viewport.width - 2), Math.max(0, viewport.height - 2));
  };
  const scheduleMinimap = () => {
    if (minimapFrame !== null || destroyed) return;
    minimapFrame = requestAnimationFrame(paintMinimap);
  };
  const observer = new ResizeObserver(() => {
    if (resizeFrame !== null || destroyed) return;
    resizeFrame = requestAnimationFrame(() => {
      resizeFrame = null;
      instance.resize();
      scheduleMinimap();
    });
  });
  observer.observe(canvas);

  const core = createReadOnlyViewerCore(createReadOnlyRendererAdapter({
    render: snapshot => cytoscapeAdapter.render(snapshot),
    fit: padding => cytoscapeAdapter.fit(padding),
    zoomBy: factor => cytoscapeAdapter.zoomBy(factor),
    panBy: delta => cytoscapeAdapter.panBy(delta),
    destroy: () => {
      observer.disconnect();
      if (resizeFrame !== null) cancelAnimationFrame(resizeFrame);
      cytoscapeAdapter.destroy();
    },
  }));

  const activeRenderer = () => mode.value === 'elastic' && elasticMount !== null ? elasticMount : core;
  const runCommand = command => {
    if (command === 'fit') activeRenderer().fit(60);
    else if (command === 'zoom-in') activeRenderer().zoomBy(1.2);
    else if (command === 'zoom-out') activeRenderer().zoomBy(1 / 1.2);
  };
  const stopElastic = () => {
    elasticMount?.destroy();
    elasticMount = null;
    elasticSvg.setAttribute('hidden', '');
    canvas.dataset.activeRenderer = 'cytoscape';
  };
  const startElastic = () => {
    stopElastic();
    if (currentSnapshot === null || currentSnapshot.nodes.length === 0) return;
    const width = canvas.clientWidth || 800;
    const height = canvas.clientHeight || 500;
    const elements = elasticElements(currentSnapshot, palette, width, height);
    elasticSvg.removeAttribute('hidden');
    canvas.dataset.activeRenderer = 'elastic';
    elasticMount = mountD3ElasticRenderer({
      svg: elasticSvg,
      nodes: elements.nodes,
      links: elements.links,
      width,
      height,
      palette,
      markerKey: 'embed',
      isLive: () => !destroyed && mode.value === 'elastic',
      onViewportChange: scheduleMinimap,
    });
  };
  const applyMode = (announce = true) => {
    if (mode.value === 'elastic' && elasticOption.disabled) mode.value = 'cyto';
    if (mode.value === 'elastic') {
      startElastic();
      concealMinimap();
    }
    else {
      stopElastic();
      enableMinimap();
      // A previous Cyto route plan uses bypass styles. Clear those renderer-local overrides before
      // changing modes so N8N's shared taxi contract is not masked by stale Bezier properties.
      instance.elements().removeStyle();
      instance.style(viewerStylesheet(mode.value, viewerTheme));
      applyResolvedRoutes(instance, mode.value);
    }
    container.dataset.viewerRenderer = mode.value;
    scheduleMinimap();
    if (announce) status.textContent = `${mode.options[mode.selectedIndex].text} view ready.`;
  };
  const click = event => runCommand(event.currentTarget.dataset.viewerCommand);
  controls.forEach(control => control.addEventListener('click', click));
  const modeChange = () => applyMode(true);
  mode.addEventListener('change', modeChange);
  const keydown = event => {
    const commands = {
      '+': 'zoom-in', '=': 'zoom-in', '-': 'zoom-out', '0': 'fit', Home: 'fit',
    };
    if (commands[event.key]) {
      event.preventDefault();
      runCommand(commands[event.key]);
      return;
    }
    const step = event.shiftKey ? 80 : 32;
    const pans = {
      ArrowLeft: { x: step, y: 0 }, ArrowRight: { x: -step, y: 0 },
      ArrowUp: { x: 0, y: step }, ArrowDown: { x: 0, y: -step },
    };
    if (pans[event.key]) {
      event.preventDefault();
      activeRenderer().panBy(pans[event.key]);
    }
  };
  canvas.addEventListener('keydown', keydown);
  instance.on('select unselect', 'node', () => {
    const selected = instance.nodes(':selected');
    status.textContent = selected.length === 0
      ? 'Graph ready.'
      : `${selected.length} node${selected.length === 1 ? '' : 's'} selected.`;
  });
  instance.on('pan zoom position', scheduleMinimap);

  const minimapCenter = point => {
    if (!minimapProjection) return;
    const world = minimapToWorld(minimapProjection, point);
    const center = clampViewportCenter(minimapProjection.contentBounds,
      minimapProjection.visibleBounds, world);
    const zoom = instance.zoom();
    instance.pan({ x: instance.width() / 2 - center.x * zoom, y: instance.height() / 2 - center.y * zoom });
  };
  const minimapPointer = event => {
    if (mode.value === 'elastic' || minimap.hidden || event.button !== 0 || !minimapProjection) return;
    event.preventDefault();
    const rect = minimap.getBoundingClientRect();
    minimapCenter({ x: event.clientX - rect.left, y: event.clientY - rect.top });
  };
  const minimapKeydown = event => {
    if (mode.value === 'elastic' || minimap.hidden || !minimapProjection) return;
    if (event.key === 'Home') { event.preventDefault(); core.fit(60); return; }
    if (event.key === 'Escape') { event.preventDefault(); canvas.focus(); return; }
    const center = {
      x: (minimapProjection.visibleBounds.x1 + minimapProjection.visibleBounds.x2) / 2,
      y: (minimapProjection.visibleBounds.y1 + minimapProjection.visibleBounds.y2) / 2,
    };
    const fraction = event.shiftKey ? .5 : .1;
    if (event.key === 'ArrowLeft') center.x -= minimapProjection.visibleBounds.w * fraction;
    else if (event.key === 'ArrowRight') center.x += minimapProjection.visibleBounds.w * fraction;
    else if (event.key === 'ArrowUp') center.y -= minimapProjection.visibleBounds.h * fraction;
    else if (event.key === 'ArrowDown') center.y += minimapProjection.visibleBounds.h * fraction;
    else return;
    event.preventDefault(); minimapCenter({
      x: center.x * minimapProjection.scale + minimapProjection.offsetX,
      y: center.y * minimapProjection.scale + minimapProjection.offsetY,
    });
  };
  minimap.addEventListener('pointerdown', minimapPointer);
  minimap.addEventListener('keydown', minimapKeydown);

  const teardown = preserveState => {
    if (destroyed) return;
    destroyed = true;
    stopElastic();
    controls.forEach(control => control.removeEventListener('click', click));
    mode.removeEventListener('change', modeChange);
    canvas.removeEventListener('keydown', keydown);
    minimap.removeEventListener('pointerdown', minimapPointer);
    minimap.removeEventListener('keydown', minimapKeydown);
    concealMinimap();
    alternative.replaceChildren();
    elasticSvg.remove();
    metadata.textContent = '';
    lifecycle.destroy({ preserveState });
    core.destroy();
    if (!preserveState) status.textContent = 'Viewer closed.';
  };

  return Object.freeze({
    get state() { return lifecycle.state; },
    presentationSnapshot() {
      if (mode.value === 'elastic') {
        return {
          renderer: 'elastic',
          nodes: [...elasticSvg.querySelectorAll('.d3-nodes circle')].map(node => ({
            fill: node.getAttribute('fill'), stroke: node.getAttribute('stroke'), r: node.getAttribute('r'),
          })),
          edges: [...elasticSvg.querySelectorAll('.d3-edges path')].map(edge => ({
            stroke: edge.getAttribute('stroke'), width: edge.getAttribute('stroke-width'),
          })),
        };
      }
      const nodeStyle = ['shape', 'width', 'height', 'background-color', 'background-image',
        'background-width', 'background-height', 'background-fit', 'border-color',
        'border-width', 'border-style', 'font-size', 'font-weight', 'text-valign',
        'text-halign', 'text-margin-y'];
      const edgeStyle = ['width', 'line-color', 'line-style', 'target-arrow-shape',
        'target-arrow-color', 'curve-style'];
      return {
        renderer: mode.value,
        nodes: instance.nodes().map(node => ({
          id: node.id(), label: node.style('label'), icon: node.style('background-image'),
          type: node.data('nodeType'),
          position: { x: node.position('x'), y: node.position('y') },
          style: Object.fromEntries(nodeStyle.map(name => [name, node.style(name)])),
        })),
        edges: instance.edges().map(edge => ({
          id: edge.id(), source: edge.source().id(), target: edge.target().id(),
          label: edge.data('label'), type: edge.data('edgeType'),
          style: Object.fromEntries(edgeStyle.map(name => [name, edge.style(name)])),
        })),
      };
    },
    async mount(projection, { signal } = {}) {
      try {
        const envelope = projection?.viewerSourceVersion === '1' ? projection : null;
        if (envelope?.source?.kind === 'deployment') {
          deploymentState = createDeploymentViewState({
            deploymentId: envelope.source.deploymentId,
            graphVersion: envelope.source.graphVersion,
            incarnationId: envelope.source.incarnationId,
            lifecycle: envelope.lifecycle,
          });
        } else {
          deploymentState = null;
        }
        const snapshot = await lifecycle.run(async signal => {
          let rendered;
          try {
            rendered = await core.mount(projection);
          } catch (failure) {
            if (core.state === 'incompatible') throw new EmbedShellFailure('incompatible');
            throw failure;
          }
          if (signal.aborted) throw signal.reason;
          currentSnapshot = rendered;
          mounted = true;
          const elasticAvailable = viewerSupportsElastic(rendered.nodes.length, rendered.edges.length);
          elasticOption.disabled = !elasticAvailable;
          container.dataset.viewerElasticPolicy = elasticAvailable ? 'available' : 'size-limited';
          elasticOption.title = elasticAvailable
            ? ''
            : 'Elastic view is unavailable for large graphs.';
          if (rendered.nodes.length > 0) {
            applyMode(false);
            if (signal.aborted) throw signal.reason;
            activeRenderer().fit(60);
            scheduleMinimap();
          }
          return rendered;
        }, { signal });
        metadata.textContent = `Version ${snapshot.graphVersionId}`;
        if (lifecycle.state === 'ready') {
          mode.disabled = false;
          activeRenderer().fit(60);
          scheduleMinimap();
        }
        return lifecycle.state;
      } catch (failure) {
        mode.disabled = true;
        elasticOption.disabled = true;
        container.dataset.viewerElasticPolicy = 'unavailable';
        mounted = false;
        currentSnapshot = null;
        teardown(true);
        throw failure;
      }
    },
    observe(frame) {
      if (!deploymentState || destroyed) return { accepted: false, reason: 'snapshot' };
      const result = applyDeploymentViewFrame(deploymentState, frame);
      applyDeploymentViewStateToRenderer(instance, deploymentState);
      container.dataset.viewerContinuity = deploymentState.continuity.toLowerCase();
      container.dataset.viewerLifecycle = deploymentState.lifecycle.toLowerCase();
      if (result.terminal || deploymentState.continuity !== 'LIVE') {
        status.textContent = deploymentState.continuity === 'GAP'
          ? 'Live observation has a gap. Reattach to reconcile the deployment.'
          : deploymentState.continuity === 'VERSION_MISMATCH'
            ? 'The deployment version changed. This view is no longer live.'
            : deploymentState.gap === 'AUTHORITY_CHANGED'
              ? 'Live observation authorization ended.'
              : `Deployment ${deploymentState.lifecycle.toLowerCase()}.`;
      }
      scheduleMinimap();
      return result;
    },
    destroy({ preserveState = false } = {}) { teardown(preserveState); },
  });
}
