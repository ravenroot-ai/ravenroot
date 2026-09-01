import { beforeEach, describe, expect, it, vi } from 'vitest';

import { renderNodeCatalogItems } from '../src/node-catalog-view.js';

const TYPES = [
  {
    behavior: 'alpha', displayName: 'Alpha template', category: 'Actions', origin: 'CORE',
    description: 'Creates alpha output', visualType: 'flow', agentic: false,
  },
  {
    behavior: 'beta', displayName: 'Beta template', category: 'Communication', origin: 'BUNDLE', bundleId: 'mail',
    description: 'Creates beta output', visualType: 'flow', agentic: false,
  },
];

describe('the Node Catalog view', () => {
  let container;

  beforeEach(() => {
    document.body.innerHTML = '<div id="node-catalog"><div>stale</div></div>';
    container = document.getElementById('node-catalog');
  });

  it('renders every descriptor in response and focus order as a native button', () => {
    renderNodeCatalogItems(container, TYPES, { iconFor: () => '⚙ ', onActivate: vi.fn() });

    const buttons = [...container.querySelectorAll('.catalog-item')];
    expect(container.classList.contains('node-catalog--items')).toBe(true);
    expect(buttons.map(button => button.tagName)).toEqual(['BUTTON', 'BUTTON']);
    expect(buttons.map(button => button.type)).toEqual(['button', 'button']);
    expect(buttons.map(button => button.dataset.catalogAdd)).toEqual(['alpha', 'beta']);
    expect(buttons.map(button => button.dataset.catalogOrder)).toEqual(['0', '1']);
    expect(buttons.map(button => button.querySelector('b').textContent))
      .toEqual(['Alpha template', 'Beta template']);
    expect(buttons.map(button => button.querySelector('small').textContent))
      .toEqual(['Actions', 'Communication']);
    expect([...container.querySelectorAll('.catalog-toolbox-title')].map(node => node.textContent))
      .toEqual(['Core nodes', 'Installed bundles']);
    expect([...container.querySelectorAll('.catalog-category summary')].map(node => node.textContent))
      .toEqual(['Actions', 'Communication']);
  });

  it('keeps duplicate glyphs distinguishable without exposing decoration to assistive technology', () => {
    renderNodeCatalogItems(container, TYPES, { iconFor: () => '⚙ ', onActivate: vi.fn() });

    const [alpha, beta] = container.querySelectorAll('.catalog-item');
    expect(alpha.getAttribute('aria-label')).toBe('Add Alpha template node, category Actions');
    expect(beta.getAttribute('aria-label')).toBe('Add Beta template node, category Communication');
    expect(alpha.dataset.tooltip).toBe('Add Alpha template · Actions');
    expect(beta.dataset.tooltip).toBe('Add Beta template · Communication');
    expect(alpha.hasAttribute('title')).toBe(false);
    expect(beta.hasAttribute('title')).toBe(false);
    expect(alpha.querySelector('.catalog-item-icon').getAttribute('aria-hidden')).toBe('true');
    expect(beta.querySelector('.catalog-item-icon').getAttribute('aria-hidden')).toBe('true');
  });

  it('marks a persistent selected type and exposes native toolbox drag data', () => {
    const onDragStart = vi.fn((event, behavior) => event.dataTransfer.setData('application/x-ravenroot-node', behavior));
    renderNodeCatalogItems(container, TYPES, {
      iconFor: () => '⚙ ', onActivate: vi.fn(), onDragStart, selectedBehavior: 'beta',
    });
    const beta = container.querySelector('[data-catalog-add="beta"]');
    expect(beta.draggable).toBe(true);
    expect(beta.getAttribute('aria-pressed')).toBe('true');
    expect(beta.classList.contains('selected')).toBe(true);

    const data = new Map();
    const event = new Event('dragstart');
    Object.defineProperty(event, 'dataTransfer', { value: { setData: (type, value) => data.set(type, value) } });
    beta.dispatchEvent(event);
    expect(onDragStart).toHaveBeenCalledOnce();
    expect(data.get('application/x-ravenroot-node')).toBe('beta');
  });

  it('activates the matching behavior through the production button', () => {
    const onActivate = vi.fn();
    renderNodeCatalogItems(container, TYPES, { iconFor: () => '⚙ ', onActivate });

    container.querySelector('[data-catalog-add="beta"]').click();
    expect(onActivate).toHaveBeenCalledOnce();
    expect(onActivate).toHaveBeenCalledWith('beta');
  });
});
