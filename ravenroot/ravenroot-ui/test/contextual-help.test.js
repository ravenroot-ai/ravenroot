import { afterEach, describe, expect, it } from 'vitest';

import {
  contextualHelpDescriptor,
  createContextualHelp,
  placeContextualHelp,
} from '../src/contextual-help.js';

const rect = (left, top, width, height) => ({
  left, top, width, height, right: left + width, bottom: top + height,
});

function setup({ popoverRect = rect(0, 0, 220, 120), suppliedWindow = window } = {}) {
  document.body.innerHTML = `<main>
    <input id="editor" value="draft">
    <button id="first" type="button" aria-expanded="false" aria-controls="contextual-help-popover"
      data-contextual-help-title="Runtime nature" data-contextual-help="Stable lifecycle guidance">?</button>
    <button id="second" type="button" aria-expanded="false" aria-controls="contextual-help-popover"
      data-contextual-help-title="Edge routing" data-contextual-help="Stable routing guidance">?</button>
    <div id="outside">Outside</div>
  </main>
  <section id="contextual-help-popover" role="region" tabindex="-1" hidden>
    <h2 data-contextual-help-heading></h2><p data-contextual-help-body></p>
    <button type="button" data-contextual-help-close>Close</button>
  </section>`;
  const popover = document.getElementById('contextual-help-popover');
  const first = document.getElementById('first');
  first.getBoundingClientRect = () => rect(260, 80, 20, 20);
  document.getElementById('second').getBoundingClientRect = () => rect(260, 120, 20, 20);
  popover.getBoundingClientRect = () => popoverRect;
  const controller = createContextualHelp({ root: document, popover, window: suppliedWindow });
  return {
    controller,
    popover,
    first,
    second: document.getElementById('second'),
    editor: document.getElementById('editor'),
    outside: document.getElementById('outside'),
  };
}

function pointerDown(target) {
  const event = new MouseEvent('pointerdown', { bubbles: true, cancelable: true });
  target.dispatchEvent(event);
  return event;
}

function pointerActivate(target) {
  const down = pointerDown(target);
  target.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, detail: 1 }));
  return down;
}

afterEach(() => { document.body.innerHTML = ''; });

describe('contextual help content selection', () => {
  it('requires both a title and stable content and ignores hidden triggers', () => {
    document.body.innerHTML = `<button id="complete" data-contextual-help-title="Title"
      data-contextual-help="Content"></button><button id="empty" data-contextual-help-title="Title"></button>
      <div hidden><button id="hidden" data-contextual-help-title="Title" data-contextual-help="Content"></button></div>`;
    expect(contextualHelpDescriptor(document.getElementById('complete'))).toMatchObject({
      title: 'Title', content: 'Content',
    });
    expect(contextualHelpDescriptor(document.getElementById('empty'))).toBeNull();
    expect(contextualHelpDescriptor(document.getElementById('hidden'))).toBeNull();
    expect(contextualHelpDescriptor(null)).toBeNull();
  });
});

describe('contextual help placement', () => {
  it('prefers the canvas-facing side of a right Inspector without covering its trigger', () => {
    expect(placeContextualHelp(
      rect(940, 100, 20, 20), rect(0, 0, 300, 180), rect(0, 0, 1280, 800),
      { ownerRect: rect(920, 40, 360, 720), ownerSide: 'left' },
    )).toEqual({ placement: 'left', left: 612, top: 20 });
  });

  it('clamps a tall narrow-viewport surface inside the eight-pixel safety margin', () => {
    const placement = placeContextualHelp(
      rect(286, 610, 26, 26), rect(0, 0, 296, 360), rect(0, 0, 320, 640),
      { ownerRect: rect(0, 0, 320, 640), ownerSide: 'left' },
    );
    expect(placement.left).toBeGreaterThanOrEqual(8);
    expect(placement.top).toBeGreaterThanOrEqual(8);
    expect(placement.left + 296).toBeLessThanOrEqual(312);
    expect(placement.top + 360).toBeLessThanOrEqual(632);
  });
});

describe('the delegated contextual help controller', () => {
  it('rounds fractional placement toward the viewport-safe edge', () => {
    const viewport = {
      innerWidth: 360, innerHeight: 640,
      addEventListener() {}, removeEventListener() {},
    };
    const { controller, popover, first } = setup({
      popoverRect: rect(0, 0, 340, 360.09375), suppliedWindow: viewport,
    });
    pointerActivate(first);

    expect(Number.parseFloat(popover.style.top) + 360.09375).toBeLessThanOrEqual(632);
    expect(Number.parseFloat(popover.style.left) + 340).toBeLessThanOrEqual(352);
    controller.destroy();
  });

  it('opens by pointer without blurring the active editor and dismisses outside without cancelling it', () => {
    const { controller, popover, first, editor, outside } = setup();
    editor.focus();

    const openingDown = pointerActivate(first);
    expect(openingDown.defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(editor);
    expect(popover.hidden).toBe(false);
    expect(first.getAttribute('aria-expanded')).toBe('true');

    const outsideDown = pointerDown(outside);
    expect(outsideDown.defaultPrevented).toBe(false);
    expect(popover.hidden).toBe(true);
    expect(document.activeElement).toBe(editor);
    expect(first.getAttribute('aria-expanded')).toBe('false');
    controller.destroy();
  });

  it('keeps only one trigger open and replaces title and content as text', () => {
    const { controller, popover, first, second } = setup();
    pointerActivate(first);
    pointerActivate(second);

    expect(first.getAttribute('aria-expanded')).toBe('false');
    expect(second.getAttribute('aria-expanded')).toBe('true');
    expect(popover.querySelector('[data-contextual-help-heading]').textContent).toBe('Edge routing');
    expect(popover.querySelector('[data-contextual-help-body]').textContent).toBe('Stable routing guidance');
    controller.destroy();
  });

  it('moves keyboard-owned focus into help and Escape returns it predictably to the opener', () => {
    const { controller, popover, first } = setup();
    first.focus();
    first.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, detail: 0 }));

    expect(document.activeElement).toBe(popover);
    const escape = new KeyboardEvent('keydown', { bubbles: true, cancelable: true, key: 'Escape' });
    popover.dispatchEvent(escape);
    expect(escape.defaultPrevented).toBe(true);
    expect(popover.hidden).toBe(true);
    expect(document.activeElement).toBe(first);
    controller.destroy();
  });

  it('the visible Close button dismisses and returns focus to the opener', () => {
    const { controller, popover, first } = setup();
    first.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, detail: 0 }));
    popover.querySelector('[data-contextual-help-close]').click();
    expect(popover.hidden).toBe(true);
    expect(document.activeElement).toBe(first);
    controller.destroy();
  });
});
