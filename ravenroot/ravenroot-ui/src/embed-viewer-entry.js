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
  resetDeploymentViewRuntime,
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
export function createEmbedViewer(container, {
  theme = 'dark', onRunSelected = () => {}, onStartExecution = () => {},
} = {}) {
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
  const semanticModes = mode.querySelector('option[value="design"]') !== null;
  if (!semanticModes && !(elasticOption instanceof HTMLOptionElement)) {
    throw new Error('Embed viewer mode choices unavailable.');
  }
  const runSelect = container.querySelector('[data-viewer-run]');
  const runEmpty = container.querySelector('[data-viewer-run-empty]');
  const startExecution = container.querySelector('[data-viewer-start]');
  const monitoringOption = mode.querySelector('option[value="monitoring"]');
  mode.value = semanticModes ? 'design' : 'cyto';
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
  let runGeneration = 0;
  const modeStates = new Map();
  const isMonitoring = () => semanticModes ? mode.value === 'monitoring' : mode.value === 'elastic';
  const designStyle = () => semanticModes ? 'cyto' : mode.value;

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
    if (!mounted || destroyed || isMonitoring() || instance.nodes().length === 0
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

  const activeRenderer = () => isMonitoring() && elasticMount !== null ? elasticMount : core;
  const runCommand = command => {
    if (command === 'render' && semanticModes) {
      if (isMonitoring()) elasticMount?.simulation.alpha(1).restart();
      else instance.layout({ name: 'cose', animate: false, fit: false }).run();
      status.textContent = `${isMonitoring() ? 'Monitoring' : 'Design'} view rendered.`;
    } else if (command === 'fit') activeRenderer().fit(60);
    else if (command === 'zoom-in') activeRenderer().zoomBy(1.2);
    else if (command === 'zoom-out') activeRenderer().zoomBy(1 / 1.2);
  };
  const stopElastic = () => {
    elasticMount?.destroy();
    elasticMount = null;
    elasticSvg.setAttribute('hidden', '');
    canvas.dataset.activeRenderer = 'cytoscape';
  };
  const startElastic = ({ recompute = false } = {}) => {
    stopElastic();
    if (currentSnapshot === null || currentSnapshot.nodes.length === 0) return;
    const width = canvas.clientWidth || 800;
    const height = canvas.clientHeight || 500;
    const elements = elasticElements(currentSnapshot, palette, width, height);
    const saved = modeStates.get('monitoring');
    if (saved?.positions) elements.nodes.forEach(node => Object.assign(node, saved.positions[node.id] || {}));
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
      isLive: () => !destroyed && isMonitoring(),
      onViewportChange: scheduleMinimap,
      initialTransform: saved?.transform || null,
      startSimulation: recompute,
    });
    if (deploymentState) {
      const runtimeColor = state => state === 'active' ? palette.selection
        : state === 'completed' ? palette.edgeType.completed
          : state === 'fallback' ? palette.edgeType.validate
            : state === 'bypassed' ? palette.edgeType.outcome
              : state === 'failed' ? palette.edgeType.failed : palette.runtimeIdle;
      deploymentState.nodeStates.forEach((runtime, nodeId) => elasticMount.updateNode(nodeId, {
        runtimeObserved: true, runtimeState: runtime.runtimeState,
        instances: runtime.activeInstances, arrivals: runtime.arrivals,
        fallback: runtime.fallback, stroke: runtimeColor(runtime.runtimeState),
        strokeWidth: runtime.runtimeState === 'active' ? 5 : 3,
      }));
      deploymentState.edgeStates.forEach((runtime, edgeId) => elasticMount.updateEdgeFlow(edgeId, runtime));
    }
  };
  const captureMode = () => {
    if (isMonitoring() && elasticMount) {
      modeStates.set('monitoring', {
        positions: Object.fromEntries(elasticMount.nodes.map(node => [node.id, { x: node.x, y: node.y }])),
        transform: elasticSvg.__zoom ? { x: elasticSvg.__zoom.x, y: elasticSvg.__zoom.y, k: elasticSvg.__zoom.k } : null,
      });
    } else {
      modeStates.set('design', {
        positions: Object.fromEntries(instance.nodes().map(node => [node.id(), node.position()])),
        zoom: instance.zoom(), pan: instance.pan(),
      });
    }
  };
  const applyMode = (announce = true) => {
    if (!semanticModes && mode.value === 'elastic' && elasticOption.disabled) mode.value = 'cyto';
    if (isMonitoring()) {
      startElastic({ recompute: !semanticModes });
      concealMinimap();
    }
    else {
      stopElastic();
      enableMinimap();
      // A previous Cyto route plan uses bypass styles. Clear those renderer-local overrides before
      // changing modes so N8N's shared taxi contract is not masked by stale Bezier properties.
      instance.elements().removeStyle();
      instance.style(viewerStylesheet(designStyle(), viewerTheme));
      applyResolvedRoutes(instance, designStyle());
      const saved = modeStates.get('design');
      if (saved) {
        instance.nodes().forEach(node => { if (saved.positions[node.id()]) node.position(saved.positions[node.id()]); });
        if (saved.zoom) instance.viewport({ zoom: saved.zoom, pan: saved.pan });
      }
    }
    container.dataset.viewerRenderer = mode.value;
    scheduleMinimap();
    if (announce) status.textContent = `${mode.options[mode.selectedIndex].text} view ready.`;
  };
  const click = event => runCommand(event.currentTarget.dataset.viewerCommand);
  controls.forEach(control => control.addEventListener('click', click));
  let previousMode = mode.value;
  const modeChange = event => {
    const nextMode = event.target.value;
    mode.value = previousMode;
    captureMode();
    mode.value = nextMode;
    previousMode = mode.value;
    applyMode(true);
  };
  mode.addEventListener('change', modeChange);
  const runChange = () => {
    runGeneration += 1;
    if (deploymentState) {
      deploymentState.binding = Object.freeze({ ...deploymentState.binding,
        processInstanceId: runSelect.value || null });
      deploymentState.generation = runGeneration;
      resetDeploymentViewRuntime(deploymentState, runSelect.value ? 'CONNECTING' : 'DETACHED',
        runSelect.value ? null : 'NO_AUTHORIZED_RUNS');
      applyDeploymentViewStateToRenderer(instance, deploymentState);
    }
    onRunSelected(runSelect.value || null, runGeneration);
    status.textContent = runSelect.value ? 'Connecting to selected run.' : 'No authorized runs.';
  };
  const startClick = () => onStartExecution();
  runSelect?.addEventListener('change', runChange);
  startExecution?.addEventListener('click', startClick);
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
    if (isMonitoring() || minimap.hidden || event.button !== 0 || !minimapProjection) return;
    event.preventDefault();
    const rect = minimap.getBoundingClientRect();
    minimapCenter({ x: event.clientX - rect.left, y: event.clientY - rect.top });
  };
  const minimapKeydown = event => {
    if (isMonitoring() || minimap.hidden || !minimapProjection) return;
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
    runSelect?.removeEventListener('change', runChange);
    startExecution?.removeEventListener('click', startClick);
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
      if (isMonitoring()) {
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
        const envelope = ['1', '2'].includes(projection?.viewerSourceVersion) ? projection : null;
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
          if (elasticOption) elasticOption.disabled = !elasticAvailable;
          if (monitoringOption) monitoringOption.disabled = !elasticAvailable;
          container.dataset.viewerElasticPolicy = elasticAvailable ? 'available' : 'size-limited';
          if (elasticOption) elasticOption.title = elasticAvailable
            ? '' : 'Elastic view is unavailable for large graphs.';
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
        if (elasticOption) elasticOption.disabled = true;
        if (monitoringOption) monitoringOption.disabled = true;
        container.dataset.viewerElasticPolicy = 'unavailable';
        mounted = false;
        currentSnapshot = null;
        teardown(true);
        throw failure;
      }
    },
    observe(frame, generation = runGeneration) {
      if (!deploymentState || destroyed) return { accepted: false, reason: 'snapshot' };
      if (generation !== runGeneration) return { accepted: false, reason: 'stale-generation' };
      const result = applyDeploymentViewFrame(deploymentState, frame);
      applyDeploymentViewStateToRenderer(instance, deploymentState);
      if (elasticMount && frame.event?.nodeId) {
        const runtime = deploymentState.nodeStates.get(frame.event.nodeId);
        const runtimeColor = state => state === 'active' ? palette.selection
          : state === 'completed' ? palette.edgeType.completed
            : state === 'fallback' ? palette.edgeType.validate
              : state === 'bypassed' ? palette.edgeType.outcome
                : state === 'failed' ? palette.edgeType.failed : palette.runtimeIdle;
        if (runtime) elasticMount.updateNode(frame.event.nodeId, {
          runtimeObserved: true, runtimeState: runtime.runtimeState,
          instances: runtime.activeInstances, arrivals: runtime.arrivals,
          fallback: runtime.fallback, stroke: runtimeColor(runtime.runtimeState),
          strokeWidth: runtime.runtimeState === 'active' ? 5 : 3,
        });
      }
      if (elasticMount && frame.event?.edgeId) {
        const runtime = deploymentState.edgeStates.get(frame.event.edgeId);
        if (runtime) elasticMount.updateEdgeFlow(frame.event.edgeId, runtime);
      }
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
    updateRuns(envelope, preferredProcessInstanceId = null) {
      if (!(runSelect instanceof HTMLSelectElement) || !Array.isArray(envelope?.runs)) return null;
      const previous = preferredProcessInstanceId || runSelect.value || null;
      const authorized = envelope.runs.filter(run => typeof run?.processInstanceId === 'string');
      runSelect.replaceChildren();
      if (authorized.length > 1) {
        const prompt = document.createElement('option');
        prompt.value = '';
        prompt.textContent = 'Choose a run';
        runSelect.append(prompt);
      }
      authorized.forEach(run => {
        const option = document.createElement('option');
        option.value = run.processInstanceId;
        option.textContent = `${run.status.toLowerCase()} · ${run.processInstanceId.slice(0, 8)}`;
        option.dataset.outcome = run.outcome || '';
        runSelect.append(option);
      });
      runSelect.disabled = authorized.length === 0;
      if (runEmpty) runEmpty.hidden = authorized.length > 0;
      const selected = authorized.some(run => run.processInstanceId === previous)
        ? previous : authorized.length === 1 ? authorized[0].processInstanceId : null;
      runSelect.value = selected || '';
      if (selected !== previous) runChange();
      return selected;
    },
    clearRuntime(reason = 'Run is no longer authorized.') {
      runGeneration += 1;
      if (deploymentState) {
        resetDeploymentViewRuntime(deploymentState, 'DETACHED', reason);
        applyDeploymentViewStateToRenderer(instance, deploymentState);
      }
      status.textContent = reason;
    },
    destroy({ preserveState = false } = {}) { teardown(preserveState); },
  });
}
