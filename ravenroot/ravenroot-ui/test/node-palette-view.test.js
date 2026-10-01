import { beforeEach, describe, expect, it, vi } from 'vitest';

import { renderNodePalettes } from '../src/node-palette-view.js';

const STATE = {
  pending: false,
  error: '',
  palettes: [
    { id: 'palette-a', name: 'Daily', version: 1 },
    { id: 'palette-b', name: 'Operations', version: 2 },
  ],
  templates: [{
    id: 'template-a', paletteId: 'palette-a', name: 'Configured AMQP', version: 3,
    node: { kind: 'BEHAVIOR', behavior: 'amqp.publish' },
  }],
};

function callbacks() {
  return {
    onCreatePalette: vi.fn(), onSave: vi.fn(), onRename: vi.fn(), onDelete: vi.fn(),
    onInsert: vi.fn(), onDragStart: vi.fn((event, template) =>
      event.dataTransfer.setData('application/x-ravenroot-node-template', template.id)),
    onRenameTemplate: vi.fn(), onMoveTemplate: vi.fn(), onDeleteTemplate: vi.fn(),
  };
}

describe('personal node palette view', () => {
  let container;

  beforeEach(() => {
    localStorage.clear();
    document.body.innerHTML = '<div id="node-palettes"></div>';
    container = document.getElementById('node-palettes');
  });

  it('uses keyboard-native disclosures and preserves each custom palette expansion state', () => {
    const handlers = callbacks();
    renderNodePalettes(container, STATE, handlers);
    const first = container.querySelector('[data-palette-id="palette-a"]');
    expect(first.tagName).toBe('DETAILS');
    expect(first.querySelector('summary').getAttribute('aria-label')).toBe('Daily personal palette');
    first.open = true;
    first.dispatchEvent(new Event('toggle'));

    renderNodePalettes(container, STATE, handlers);
    expect(container.querySelector('[data-palette-id="palette-a"]').open).toBe(true);
    expect(container.querySelector('[data-palette-id="palette-b"]').open).toBe(false);
  });

  it('exposes click, drag, rename, move, delete, save, and create controls', () => {
    const handlers = callbacks();
    renderNodePalettes(container, STATE, handlers);
    const insert = container.querySelector('.node-template-insert');
    expect(insert.tagName).toBe('BUTTON');
    expect(insert.draggable).toBe(true);
    insert.click();
    expect(handlers.onInsert).toHaveBeenCalledWith(STATE.templates[0], null);

    const data = new Map();
    const drag = new Event('dragstart');
    Object.defineProperty(drag, 'dataTransfer', {
      value: { setData: (type, value) => data.set(type, value) },
    });
    insert.dispatchEvent(drag);
    expect(data.get('application/x-ravenroot-node-template')).toBe('template-a');

    const destination = container.querySelector('select[aria-label^="Move saved node"]');
    destination.value = 'palette-b';
    destination.dispatchEvent(new Event('change'));
    expect(handlers.onMoveTemplate).toHaveBeenCalledWith(STATE.templates[0], 'palette-b');

    container.querySelector('button[aria-label^="Rename saved node"]').click();
    container.querySelector('button[aria-label^="Delete saved node"]').click();
    expect(handlers.onRenameTemplate).toHaveBeenCalledWith(STATE.templates[0]);
    expect(handlers.onDeleteTemplate).toHaveBeenCalledWith(STATE.templates[0]);

    const palette = container.querySelector('[data-palette-id="palette-a"]');
    [...palette.querySelectorAll('.palette-actions button')].forEach(button => button.click());
    expect(handlers.onSave).toHaveBeenCalledWith(STATE.palettes[0]);
    expect(handlers.onRename).toHaveBeenCalledWith(STATE.palettes[0]);
    expect(handlers.onDelete).toHaveBeenCalledWith(STATE.palettes[0]);

    const input = container.querySelector('input[name="name"]');
    input.value = 'Reusable';
    input.form.dispatchEvent(new Event('submit', { cancelable: true }));
    expect(handlers.onCreatePalette).toHaveBeenCalledWith('Reusable');
  });
});
