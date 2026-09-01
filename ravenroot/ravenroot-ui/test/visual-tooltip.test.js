import { afterEach, describe, expect, it, vi } from 'vitest';
import { readFile } from 'node:fs/promises';

import {
  VISUAL_TOOLTIP_POINTER_DELAY,
  createVisualTooltip,
  placeVisualTooltip,
  tooltipDescriptor,
} from '../src/visual-tooltip.js';

const rect = (left, top, width, height) => ({
  left, top, width, height, right: left + width, bottom: top + height,
});

function event(type, relatedTarget = null) {
  return new MouseEvent(type, { bubbles: true, relatedTarget });
}

function setup(markup = '<button id="control" data-tooltip="Do the thing">Do</button>') {
  document.body.innerHTML = `<main data-tooltip-scope>${markup}</main><div id="visual-tooltip"></div>`;
  const scope = document.querySelector('[data-tooltip-scope]');
  const tooltip = document.getElementById('visual-tooltip');
  const controller = createVisualTooltip({ scope, tooltip, window });
  return { controller, scope, tooltip, control: document.getElementById('control') };
}

afterEach(() => {
  vi.useRealTimers();
  document.body.innerHTML = '';
});

describe('tooltip applicability', () => {
  // The eight-panel count is written out rather than derived from the markup on purpose: it is an
  // INVENTORY, so a panel that arrives without its menu tooltip,
  // its close tooltip or its text-control exemption has to be noticed here rather than pass by
  // agreeing with itself.
  it('keeps the static eight-panel chrome in one explicit tooltip inventory', async () => {
    const html = await readFile('index.html', 'utf8');
    const page = new DOMParser().parseFromString(html, 'text/html');

    expect(page.querySelectorAll('.panel[data-panel-id]')).toHaveLength(8);
    expect(page.querySelectorAll('.panel-hd [data-action="panel-menu"][data-tooltip]')).toHaveLength(8);
    expect(page.querySelectorAll('.panel-hd [data-action="panel-close"][data-tooltip]')).toHaveLength(8);
    expect(page.querySelectorAll('.panel-hd [title], .rail [title]')).toHaveLength(0);
    expect(page.querySelectorAll('.rail-toggle[data-tooltip], .rail-index[data-tooltip]')).toHaveLength(4);
    expect(page.querySelectorAll('[data-splitter-kind="workspace"][data-tooltip]')).toHaveLength(3);
    // Four: the Inspector's and Runtime activity's Clear, plus the Clear and View payload.
    expect(page.querySelectorAll('[data-tooltip-exempt="persistent-text"]')).toHaveLength(4);
    expect(page.getElementById('visual-tooltip')?.getAttribute('role')).toBe('tooltip');
  });

  it('uses explicit normal, short-state and disabled descriptions without leaking outside its scope', () => {
    document.body.innerHTML = `
      <section id="scope"><button id="normal" data-tooltip="Normal">Normal</button>
        <div class="panel--short"><button id="short" data-tooltip="Normal" data-tooltip-short="Compact">Short</button></div>
        <button id="disabled" aria-disabled="true" data-tooltip="Save" data-tooltip-disabled="Complete a name first">Save</button>
      </section>
      <button id="outside" data-tooltip="Outside">Outside</button>`;
    const scopes = [document.getElementById('scope')];

    expect(tooltipDescriptor(document.getElementById('normal'), { scopes })).toMatchObject({ text: 'Normal' });
    expect(tooltipDescriptor(document.getElementById('short'), { scopes })).toMatchObject({ text: 'Compact' });
    expect(tooltipDescriptor(document.getElementById('disabled'), { scopes })).toMatchObject({ text: 'Complete a name first' });
    expect(tooltipDescriptor(document.getElementById('outside'), { scopes })).toBeNull();
  });

  it('only exposes a short-only marker while its panel is shortened', () => {
    document.body.innerHTML = '<div id="scope"><button id="control" data-tooltip="Add node" data-tooltip-short>+</button></div>';
    const control = document.getElementById('control');
    const scopes = [document.getElementById('scope')];

    expect(tooltipDescriptor(control, { scopes })).toBeNull();
    control.parentElement.classList.add('panel--short');
    expect(tooltipDescriptor(control, { scopes })).toMatchObject({ text: 'Add node' });
  });
});

describe('visual tooltip placement', () => {
  const viewport = rect(0, 0, 320, 220);
  const trigger = rect(150, 100, 20, 20);
  const tip = rect(0, 0, 80, 30);

  it('uses the preferred top candidate when it is viewport and trigger safe', () => {
    expect(placeVisualTooltip(trigger, tip, viewport)).toEqual({ placement: 'top', left: 120, top: 62 });
  });

  it('selects a safe alternate at the viewport edge and clamps it into view', () => {
    const placement = placeVisualTooltip(rect(8, 8, 20, 20), tip, viewport);
    expect(placement.placement).not.toBe('top');
    expect(placement.left).toBeGreaterThanOrEqual(8);
    expect(placement.top).toBeGreaterThanOrEqual(8);
    const rendered = rect(placement.left, placement.top, 80, 30);
    expect(rendered.right).toBeLessThanOrEqual(312);
    expect(rendered.bottom).toBeLessThanOrEqual(212);
    expect(Math.max(0, Math.min(rendered.right, 28) - Math.max(rendered.left, 8))
      * Math.max(0, Math.min(rendered.bottom, 28) - Math.max(rendered.top, 8))).toBe(0);
  });

  it('prefers a placement outside the owning panel when a safe candidate exists', () => {
    const placement = placeVisualTooltip(
      rect(20, 80, 20, 20), tip, viewport,
      { ownerRect: rect(0, 40, 120, 160) },
    );
    expect(placement.placement).toBe('right');
  });

  it('places compact panel tips beyond the panel edge instead of over adjacent tiles', () => {
    const placement = placeVisualTooltip(
      rect(20, 80, 20, 20), tip, viewport,
      { ownerRect: rect(0, 40, 120, 160), ownerSide: 'right' },
    );
    expect(placement).toEqual({ placement: 'right', left: 128, top: 75 });
  });
});

describe('the delegated visual tooltip controller', () => {
  it('shows after the standard pointer delay, preserves other describedby ids, and dismisses on leave', () => {
    vi.useFakeTimers();
    const { controller, tooltip, control } = setup('<button id="control" aria-describedby="existing" data-tooltip="Do the thing">Do</button>');

    control.dispatchEvent(event('pointerover'));
    vi.advanceTimersByTime(VISUAL_TOOLTIP_POINTER_DELAY - 1);
    expect(tooltip.hidden).toBe(true);
    vi.advanceTimersByTime(1);
    expect(tooltip.hidden).toBe(false);
    expect(tooltip.textContent).toBe('Do the thing');
    expect(control.getAttribute('aria-describedby').split(' ')).toEqual(['existing', 'visual-tooltip']);

    control.dispatchEvent(event('pointerout'));
    vi.runOnlyPendingTimers();
    expect(tooltip.hidden).toBe(true);
    expect(control.getAttribute('aria-describedby')).toBe('existing');
    controller.destroy();
  });

  it('shows immediately on keyboard focus and removes describedby only while displayed', () => {
    const { controller, tooltip, control } = setup();

    control.dispatchEvent(new FocusEvent('focusin', { bubbles: true }));
    expect(tooltip.hidden).toBe(false);
    expect(control.getAttribute('aria-describedby')).toBe('visual-tooltip');

    control.dispatchEvent(new FocusEvent('focusout', { bubbles: true }));
    expect(tooltip.hidden).toBe(true);
    expect(control.hasAttribute('aria-describedby')).toBe(false);
    controller.destroy();
  });

  it('keeps keyboard focus authoritative over an unrelated stationary pointer', () => {
    vi.useFakeTimers();
    const { controller, tooltip, control } = setup(
      '<button id="control" data-tooltip="Focused action">Focus</button>'
      + '<button id="pointer" data-tooltip="Pointer action">Pointer</button>',
    );
    const pointer = document.getElementById('pointer');

    control.dispatchEvent(new FocusEvent('focusin', { bubbles: true }));
    pointer.dispatchEvent(event('pointerover'));
    vi.advanceTimersByTime(VISUAL_TOOLTIP_POINTER_DELAY);

    expect(tooltip.textContent).toBe('Focused action');
    expect(control.getAttribute('aria-describedby')).toBe('visual-tooltip');
    expect(pointer.hasAttribute('aria-describedby')).toBe(false);
    controller.destroy();
  });

  it('keeps a hoverable tip open while the pointer moves from its owner to the tip', () => {
    vi.useFakeTimers();
    const { controller, tooltip, control } = setup();
    control.dispatchEvent(event('pointerover'));
    vi.advanceTimersByTime(VISUAL_TOOLTIP_POINTER_DELAY);

    control.dispatchEvent(event('pointerout', tooltip));
    tooltip.dispatchEvent(event('pointerover', control));
    vi.runOnlyPendingTimers();
    expect(tooltip.hidden).toBe(false);

    tooltip.dispatchEvent(event('pointerout'));
    vi.runOnlyPendingTimers();
    expect(tooltip.hidden).toBe(true);
    controller.destroy();
  });

  it.each([
    ['Escape', target => target.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, key: 'Escape' }))],
    ['pointer down', target => target.dispatchEvent(event('pointerdown'))],
    ['scroll', () => window.dispatchEvent(new Event('scroll'))],
    ['resize', () => window.dispatchEvent(new Event('resize'))],
    ['visibility change', () => document.dispatchEvent(new Event('visibilitychange'))],
  ])('dismisses on %s', (_reason, dismiss) => {
    const { controller, tooltip, control } = setup();
    control.dispatchEvent(new FocusEvent('focusin', { bubbles: true }));
    expect(tooltip.hidden).toBe(false);
    dismiss(control);
    expect(tooltip.hidden).toBe(true);
    controller.destroy();
  });

  it('is safe to destroy twice and removes its delegated behavior', () => {
    vi.useFakeTimers();
    const { controller, tooltip, control } = setup();
    controller.destroy();
    controller.destroy();
    control.dispatchEvent(event('pointerover'));
    vi.advanceTimersByTime(VISUAL_TOOLTIP_POINTER_DELAY);
    expect(tooltip.hidden).toBe(true);
  });
});
