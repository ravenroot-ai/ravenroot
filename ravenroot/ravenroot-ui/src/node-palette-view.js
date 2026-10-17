import { uiText } from './ui-text.js';
const DISCLOSURE_KEY = 'ravenroot.ui.node-palettes.open.v1';

function readOpen() {
  try {
    const value = JSON.parse(globalThis.localStorage?.getItem(DISCLOSURE_KEY) || '[]');
    return new Set(Array.isArray(value) ? value.filter(id => typeof id === 'string') : []);
  } catch { return new Set(); }
}

function persistOpen(open) {
  try { globalThis.localStorage?.setItem(DISCLOSURE_KEY, JSON.stringify([...open])); } catch { /* optional */ }
}

export function renderNodePalettes(container, state, callbacks) {
  const document = container.ownerDocument;
  const fragment = document.createDocumentFragment();
  const create = document.createElement('form');
  create.className = 'palette-create';
  const createLabel = document.createElement('label');
  createLabel.append(uiText('workspace.node.palette.create.label') + ' ');
  const createInput = document.createElement('input');
  createInput.name = 'name'; createInput.maxLength = 120; createInput.required = true;
  createInput.dataset.tooltip = uiText('workspace.node.palette.create.nameHelp');
  createLabel.append(createInput);
  const createButton = document.createElement('button');
  createButton.type = 'submit';
  createButton.dataset.tooltip = uiText('workspace.node.palette.create.submitHelp');
  createButton.textContent = uiText('workspace.node.palette.create.submit');
  create.append(createLabel, createButton);
  create.addEventListener('submit', event => {
    event.preventDefault();
    const name = String(new FormData(create).get('name') || '').trim();
    if (name) callbacks.onCreatePalette(name);
  });
  fragment.append(create);

  if (state.pending) {
    const status = document.createElement('p');
    status.className = 'catalog-empty';
    status.textContent = uiText("workspace.node.palette.view.loading.personal.palettes");
    fragment.append(status);
  } else if (state.error) {
    const status = document.createElement('p');
    status.className = 'catalog-empty';
    status.textContent = state.error;
    fragment.append(status);
  } else if (!state.palettes.length) {
    const status = document.createElement('p');
    status.className = 'catalog-empty';
    status.textContent = uiText("workspace.node.palette.view.create.a.palette.to.save.reusable.configured");
    fragment.append(status);
  }

  const open = readOpen();
  for (const palette of state.palettes) {
    const details = document.createElement('details');
    details.className = 'node-palette';
    details.dataset.paletteId = palette.id;
    details.open = open.has(palette.id);
    details.addEventListener('toggle', () => {
      if (details.open) open.add(palette.id); else open.delete(palette.id);
      persistOpen(open);
    });
    const summary = document.createElement('summary');
    summary.textContent = palette.name;
    summary.setAttribute('aria-label', uiText("workspace.node.palette.view.personal.palette", { name: palette.name }));
    details.append(summary);

    const actions = document.createElement('div');
    actions.className = 'palette-actions';
    for (const [labelKey, action] of [
      ['workspace.node.palette.action.saveSelected', 'save'],
      ['workspace.node.palette.view.rename', 'rename'],
      ['workspace.node.palette.view.delete', 'delete'],
    ]) {
      const label = uiText(labelKey);
      const button = document.createElement('button');
      button.type = 'button'; button.textContent = label;
      button.dataset.tooltip = uiText("workspace.node.palette.view.in", { label, palette: palette.name });
      button.addEventListener('click', () => callbacks[`on${action[0].toUpperCase()}${action.slice(1)}`](palette));
      actions.append(button);
    }
    details.append(actions);

    const templates = state.templates.filter(template => template.paletteId === palette.id);
    for (const template of templates) {
      const row = document.createElement('div');
      row.className = 'node-template';
      const insert = document.createElement('button');
      insert.type = 'button'; insert.draggable = true;
      insert.className = 'node-template-insert';
      insert.textContent = template.name;
      insert.setAttribute('aria-label', uiText("workspace.node.palette.view.insert.saved.node", { name: template.name }));
      insert.dataset.tooltip = uiText("workspace.node.palette.view.insert.saved.node.2", { name: template.name });
      insert.addEventListener('click', () => callbacks.onInsert(template, null));
      insert.addEventListener('dragstart', event => callbacks.onDragStart(event, template));
      const destination = document.createElement('select');
      destination.setAttribute('aria-label', uiText("workspace.node.palette.view.move.saved.node.to.palette", { name: template.name }));
      destination.dataset.tooltip = uiText("workspace.node.palette.view.move.saved.node.to.palette.2", { name: template.name });
      for (const candidate of state.palettes) {
        const option = document.createElement('option');
        option.value = candidate.id;
        option.textContent = candidate.name;
        option.selected = candidate.id === template.paletteId;
        destination.append(option);
      }
      destination.addEventListener('change', () => callbacks.onMoveTemplate(template, destination.value));
      const rename = document.createElement('button');
      rename.type = 'button'; rename.textContent = uiText("workspace.node.palette.view.rename");
      rename.setAttribute('aria-label', uiText("workspace.node.palette.view.rename.saved.node", { name: template.name }));
      rename.dataset.tooltip = uiText("workspace.node.palette.view.rename.saved.node.2", { name: template.name });
      rename.addEventListener('click', () => callbacks.onRenameTemplate(template));
      const remove = document.createElement('button');
      remove.type = 'button'; remove.textContent = uiText("workspace.node.palette.view.delete");
      remove.setAttribute('aria-label', uiText("workspace.node.palette.view.delete.saved.node", { name: template.name }));
      remove.dataset.tooltip = uiText("workspace.node.palette.view.delete.saved.node.2", { name: template.name });
      remove.addEventListener('click', () => callbacks.onDeleteTemplate(template));
      row.append(insert, destination, rename, remove);
      details.append(row);
    }
    fragment.append(details);
  }
  container.replaceChildren(fragment);
}
