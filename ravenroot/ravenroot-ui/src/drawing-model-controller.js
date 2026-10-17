import {
  FRONTEND_DRAWING_MODEL_PROPERTY,
  FRONTEND_PRESENTATION_PROPERTY,
  drawingModelSnapshot,
  presentationMappingValue,
  providerCompatibility,
  readPresentationMapping,
  validateLayoutResult,
} from './frontend-plugin-api.js';
import {
  installFrontendPackage,
  listFrontendPackages,
  packageFromFiles,
  removeFrontendPackage,
  setFrontendPackageEnabled,
} from './frontend-plugin-store.js';
import { createFrontendPluginSandbox } from './frontend-plugin-sandbox.js';

const SVG = 'http://www.w3.org/2000/svg';
const INTEGRATED = 'integrated';

function modelKey(packageId, modelId) {
  return `model|${packageId}|${modelId}`;
}

function compositionKey(layoutPackage, layoutId, rendererPackage, rendererId) {
  return `compose|${layoutPackage}|${layoutId}|${rendererPackage}|${rendererId}`;
}

function option(document_, value, text) {
  const element = document_.createElement('option');
  element.value = value;
  element.textContent = text;
  return element;
}

function sceneSvg(document_, scene, onEvidence) {
  const svg = document_.createElementNS(SVG, 'svg');
  svg.classList.add('drawing-model-scene');
  svg.setAttribute('viewBox', `0 0 ${scene.width} ${scene.height}`);
  svg.setAttribute('role', 'img');
  svg.setAttribute('aria-label', 'Plugin drawing model. Highlights are mapped workflow evidence, not a formal acceptance result.');
  const defs = document_.createElementNS(SVG, 'defs');
  const marker = document_.createElementNS(SVG, 'marker');
  marker.id = 'drawing-model-arrow';
  marker.setAttribute('markerWidth', '10'); marker.setAttribute('markerHeight', '8');
  marker.setAttribute('viewBox', '0 0 10 8');
  marker.setAttribute('markerUnits', 'userSpaceOnUse');
  marker.setAttribute('refX', '9'); marker.setAttribute('refY', '4'); marker.setAttribute('orient', 'auto');
  const arrow = document_.createElementNS(SVG, 'path');
  arrow.setAttribute('d', 'M 0 0 L 10 4 L 0 8 z'); marker.append(arrow); defs.append(marker); svg.append(defs);
  for (const item of scene.elements) {
    const element = document_.createElementNS(SVG, item.type);
    for (const field of ['x', 'y', 'x1', 'y1', 'x2', 'y2', 'r', 'd']) {
      if (!Object.hasOwn(item, field)) continue;
      const attribute = item.type === 'circle' && field === 'x' ? 'cx'
        : item.type === 'circle' && field === 'y' ? 'cy' : field;
      element.setAttribute(attribute, String(item[field]));
    }
    if (item.className) element.setAttribute('class', item.className);
    if (item.type === 'text') { element.textContent = item.text || ''; element.setAttribute('text-anchor', 'middle'); }
    if (item.type === 'path' || item.type === 'line') element.setAttribute('marker-end', 'url(#drawing-model-arrow)');
    const evidenceId = item.targetId || item.id;
    if (item.role && evidenceId) {
      element.dataset.evidenceRole = item.role;
      element.dataset.evidenceId = evidenceId;
      element.setAttribute('tabindex', '0');
      element.setAttribute('role', 'button');
      element.setAttribute('aria-label', item.label || evidenceId);
      element.addEventListener('click', () => onEvidence(item.role, evidenceId));
      element.addEventListener('keydown', event => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault(); event.stopPropagation(); onEvidence(item.role, evidenceId);
        }
      });
    }
    svg.append(element);
  }
  return svg;
}

export function createDrawingModelController({ document: document_, getContext, editGraphProperties,
  selectEvidence, notify, indexedDB } = {}) {
  const select = document_.getElementById('drawing-model-select');
  const installInput = document_.getElementById('drawing-model-package-input');
  const installButton = document_.getElementById('drawing-model-install');
  const manageButton = document_.getElementById('drawing-model-manage');
  const mappingButton = document_.getElementById('drawing-model-mapping');
  const fullFlowButton = document_.getElementById('drawing-model-full-flow');
  const dialog = document_.getElementById('drawing-model-dialog');
  const packageDialog = document_.getElementById('drawing-model-package-dialog');
  const packageList = document_.getElementById('drawing-model-package-list');
  const textarea = document_.getElementById('drawing-model-mapping-json');
  const error = document_.getElementById('drawing-model-error');
  let packages = [];
  let sandbox = null;
  let abort = null;
  let generation = 0;
  let refreshFrame = null;

  function context() { return getContext?.() || null; }
  function selectedKey() { return context()?.graph?.graphProperties?.[FRONTEND_DRAWING_MODEL_PROPERTY] || INTEGRATED; }

  function clearOverlays() {
    document_.querySelectorAll('.drawing-model-overlay').forEach(element => element.remove());
    document_.querySelectorAll('.doc-canvas--drawing-model')
      .forEach(element => element.classList.remove('doc-canvas--drawing-model'));
  }

  function stop() {
    generation += 1;
    abort?.abort(); abort = null;
    sandbox?.destroy(); sandbox = null;
  }

  function fallback(message) {
    stop(); clearOverlays();
    select.value = INTEGRATED;
    fullFlowButton.hidden = true;
    if (message) notify?.(message, 'failed');
  }

  function evidence(role, id, mapping, owner) {
    const current = context();
    if (!current || current.documentId !== owner.documentId) return;
    if (role === 'state') {
      const state = mapping.states.find(item => item.id === id);
      if (state) selectEvidence?.({ role, id, nodeIds: [state.nodeId], edgeIds: [],
        documentId: owner.documentId });
    } else {
      const transition = mapping.transitions.find(item => item.id === id);
      if (transition) selectEvidence?.({ role, id, nodeIds: [], edgeIds: transition.edgePath,
        documentId: owner.documentId });
    }
  }

  async function render() {
    stop();
    const currentGeneration = ++generation;
    const current = context();
    const key = selectedKey();
    if (!current?.graph || key === INTEGRATED) { clearOverlays(); fullFlowButton.hidden = true; return; }
    const parts = key.split('|');
    let layoutPackage;
    let rendererPackage;
    let layoutProvider;
    let rendererProvider;
    if (parts[0] === 'model') {
      const package_ = packages.find(item => item.id === parts[1] && item.enabled);
      const model = package_?.manifest.drawingModels.find(item => item.id === parts[2]);
      if (model) {
        layoutPackage = package_; rendererPackage = package_;
        layoutProvider = package_.manifest.layouts.find(item => item.id === model.layout);
        rendererProvider = package_.manifest.renderers.find(item => item.id === model.renderer);
      }
    } else if (parts[0] === 'compose') {
      layoutPackage = packages.find(item => item.id === parts[1] && item.enabled);
      rendererPackage = packages.find(item => item.id === parts[3] && item.enabled);
      layoutProvider = layoutPackage?.manifest.layouts.find(item => item.id === parts[2]);
      rendererProvider = rendererPackage?.manifest.renderers.find(item => item.id === parts[4]);
    }
    if (!layoutProvider || !rendererProvider) {
      fallback('The selected drawing model is unavailable. Integrated Design remains active.'); return;
    }
    const compatibility = providerCompatibility(layoutProvider, rendererProvider);
    if (!compatibility.compatible) {
      fallback(`The selected layout and renderer are incompatible (${[...compatibility.missingForRenderer,
        ...compatibility.missingForLayout].join(', ')}).`); return;
    }
    let mapping;
    try { mapping = readPresentationMapping(current.graph); }
    catch (cause) { fallback(`Presentation mapping is invalid: ${cause.message}`); return; }
    if (!mapping) { fallback('This drawing model needs an explicit presentation mapping.'); return; }
    abort = new AbortController();
    const layoutSandbox = createFrontendPluginSandbox(layoutPackage.files[layoutProvider.entry], { target: document_.body, timeout: 3000 });
    const rendererSandbox = layoutPackage.id === rendererPackage.id && layoutProvider.entry === rendererProvider.entry
      ? layoutSandbox : createFrontendPluginSandbox(rendererPackage.files[rendererProvider.entry], { target: document_.body, timeout: 3000 });
    sandbox = { destroy() { layoutSandbox.destroy(); if (rendererSandbox !== layoutSandbox) rendererSandbox.destroy(); } };
    try {
      const snapshot = drawingModelSnapshot(current.graph, mapping, current.operational);
      const layout = validateLayoutResult(await layoutSandbox.layout(snapshot, { signal: abort.signal }), snapshot);
      if (currentGeneration !== generation || context()?.documentId !== current.documentId) return;
      const scene = await rendererSandbox.render({ snapshot, layout }, { signal: abort.signal });
      if (currentGeneration !== generation || context()?.documentId !== current.documentId) return;
      clearOverlays();
      const overlay = document_.createElement('div');
      overlay.className = 'drawing-model-overlay';
      const notice = document_.createElement('p');
      notice.className = 'drawing-model-evidence-note';
      notice.textContent = snapshot.evidence.description;
      overlay.append(sceneSvg(document_, scene, (role, id) => {
        if (currentGeneration === generation) evidence(role, id, mapping, current);
      }), notice);
      current.container.append(overlay);
      current.container.classList.add('doc-canvas--drawing-model');
      fullFlowButton.hidden = false;
    } catch (cause) {
      if (cause.name !== 'AbortError' && currentGeneration === generation
          && context()?.documentId === current.documentId) {
        fallback(`Drawing model failed: ${cause.message}. Integrated Design remains active.`);
      }
    }
  }

  async function reload() {
    // Package lookup is asynchronous. Retire the previous document synchronously so its controls
    // cannot remain live while the newly active document is still loading its provider catalog.
    stop(); clearOverlays(); fullFlowButton.hidden = true;
    try { packages = await listFrontendPackages(indexedDB); }
    catch (cause) { packages = []; notify?.(cause.message, 'failed'); }
    const wanted = selectedKey();
    select.replaceChildren(option(document_, INTEGRATED, 'Integrated Design'));
    for (const package_ of packages) {
      for (const model of package_.manifest.drawingModels) {
        const label = `${model.name} — ${package_.manifest.name}${package_.enabled ? '' : ' (disabled)'}`;
        const item = option(document_, modelKey(package_.id, model.id), label);
        item.disabled = !package_.enabled;
        select.append(item);
      }
    }
    const layouts = packages.filter(item => item.enabled)
      .flatMap(package_ => package_.manifest.layouts.map(provider => ({ package_, provider })));
    const renderers = packages.filter(item => item.enabled)
      .flatMap(package_ => package_.manifest.renderers.map(provider => ({ package_, provider })));
    for (const layout of layouts) for (const renderer of renderers) {
      if (layout.package_.id === renderer.package_.id
          && layout.package_.manifest.drawingModels.some(model => model.layout === layout.provider.id
            && model.renderer === renderer.provider.id)) continue;
      if (!providerCompatibility(layout.provider, renderer.provider).compatible) continue;
      select.append(option(document_, compositionKey(layout.package_.id, layout.provider.id,
        renderer.package_.id, renderer.provider.id),
      `${layout.provider.name} + ${renderer.provider.name}`));
    }
    select.value = [...select.options].some(item => item.value === wanted && !item.disabled) ? wanted : INTEGRATED;
    await render();
  }

  select.addEventListener('change', () => {
    const value = select.value;
    editGraphProperties?.({ [FRONTEND_DRAWING_MODEL_PROPERTY]: value }, [], 'Select drawing model');
    void render();
  });
  fullFlowButton.addEventListener('click', () => {
    editGraphProperties?.({ [FRONTEND_DRAWING_MODEL_PROPERTY]: INTEGRATED }, [], 'Show full operational flow');
    select.value = INTEGRATED; fallback();
  });
  installButton.addEventListener('click', () => installInput.click());
  installInput.addEventListener('change', async () => {
    try {
      const package_ = await packageFromFiles(installInput.files);
      await installFrontendPackage(package_, indexedDB);
      notify?.(`Installed frontend package ${package_.manifest.name} ${package_.manifest.version}`, 'completed');
      await reload();
    } catch (cause) { notify?.(`Frontend package was not installed: ${cause.message}`, 'failed'); }
    finally { installInput.value = ''; }
  });
  const selectedReferencesPackage = packageId => {
    const parts = selectedKey().split('|');
    return (parts[0] === 'model' && parts[1] === packageId)
      || (parts[0] === 'compose' && (parts[1] === packageId || parts[3] === packageId));
  };
  const renderPackageManager = () => {
    packageList.replaceChildren();
    if (!packages.length) {
      const empty = document_.createElement('p'); empty.textContent = 'No frontend packages are installed.';
      packageList.append(empty); return;
    }
    for (const package_ of packages) {
      const row = document_.createElement('div'); row.className = 'drawing-model-package-row';
      const description = document_.createElement('span');
      description.textContent = `${package_.manifest.name} ${package_.manifest.version} — ${package_.enabled ? 'enabled' : 'disabled'}`;
      const toggle = document_.createElement('button'); toggle.type = 'button'; toggle.className = 'btn';
      toggle.textContent = package_.enabled ? 'Disable' : 'Enable';
      toggle.setAttribute('aria-label', `${toggle.textContent} ${package_.manifest.name}`);
      toggle.addEventListener('click', async () => {
        await setFrontendPackageEnabled(package_.id, !package_.enabled, indexedDB);
        if (package_.enabled && selectedReferencesPackage(package_.id)) {
          editGraphProperties?.({ [FRONTEND_DRAWING_MODEL_PROPERTY]: INTEGRATED }, [], 'Use integrated drawing model');
        }
        await reload(); renderPackageManager();
      });
      const remove = document_.createElement('button'); remove.type = 'button'; remove.className = 'btn';
      remove.textContent = 'Remove'; remove.setAttribute('aria-label', `Remove ${package_.manifest.name}`);
      remove.addEventListener('click', async () => {
        await removeFrontendPackage(package_.id, indexedDB);
        if (selectedReferencesPackage(package_.id)) {
          editGraphProperties?.({ [FRONTEND_DRAWING_MODEL_PROPERTY]: INTEGRATED }, [], 'Use integrated drawing model');
        }
        await reload(); renderPackageManager();
      });
      row.append(description, toggle, remove); packageList.append(row);
    }
  };
  manageButton.addEventListener('click', () => { renderPackageManager(); packageDialog.showModal(); });
  packageDialog.querySelector('[data-drawing-model-package-close]')
    .addEventListener('click', () => packageDialog.close());
  mappingButton.addEventListener('click', () => {
    const mapping = context()?.graph ? readPresentationMapping(context().graph) : null;
    textarea.value = mapping ? JSON.stringify(mapping, null, 2) : '{\n  "schema": "ravenroot.presentation-mapping/v1",\n  "states": [],\n  "transitions": [],\n  "positions": {}\n}';
    error.textContent = ''; dialog.showModal(); textarea.focus();
  });
  dialog.querySelector('[data-drawing-model-cancel]').addEventListener('click', () => dialog.close());
  dialog.querySelector('form').addEventListener('submit', event => {
    event.preventDefault();
    try {
      const current = context();
      const value = presentationMappingValue(JSON.parse(textarea.value), current.graph);
      editGraphProperties?.({ [FRONTEND_PRESENTATION_PROPERTY]: value }, [], 'Edit drawing model mapping');
      error.textContent = ''; dialog.close(); void render();
    } catch (cause) { error.textContent = cause.message; }
  });
  function scheduleRefresh(documentId = context()?.documentId) {
    if (!documentId || context()?.documentId !== documentId) return;
    if (refreshFrame != null) return;
    refreshFrame = requestAnimationFrame(() => {
      refreshFrame = null;
      if (context()?.documentId === documentId) void render();
    });
  }
  return Object.freeze({ reload, refresh: render, scheduleRefresh, destroy() {
    if (refreshFrame != null) cancelAnimationFrame(refreshFrame);
    stop(); clearOverlays();
  } });
}
