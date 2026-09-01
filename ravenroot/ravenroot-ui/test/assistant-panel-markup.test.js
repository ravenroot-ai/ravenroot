import { readFile } from 'node:fs/promises';

import { describe, expect, it } from 'vitest';

import { PANELS, defaultLayout, panelsInZone } from '../src/panel-layout.js';

// The panel's accessibility guarantees are properties of the SHIPPED MARKUP, so they are asserted
// against `index.html` itself rather than against a fixture that agrees with the test by
// construction. This is the pattern `visual-tooltip.test.js` already uses for the panel chrome.
const page = async () => {
  const html = await readFile('index.html', 'utf8');
  return new DOMParser().parseFromString(html, 'text/html');
};

describe('the assistant panel exists as a panel, not as a bolted-on box', () => {
  it('is registered in the descriptor set, in the right column, under the Inspector', () => {
    expect(panelsInZone(defaultLayout(), 'right').map(entry => entry.id))
      .toEqual(['inspector', 'assistant']);
    expect(PANELS.find(panel => panel.id === 'assistant'))
      .toEqual({
        id: 'assistant', title: 'Assistant', zone: 'right', kind: 'unbounded',
        // Measured, not stylistic — see the descriptor comment in `panel-layout.js`.
        defaultClosed: true,
      });
  });

  it('carries the same header chrome every other panel carries', async () => {
    const document_ = await page();
    const panel = document_.querySelector('.panel[data-panel-id="assistant"]');
    expect(panel).not.toBeNull();
    expect(panel.getAttribute('data-panel-zone')).toBe('right');
    expect(panel.querySelector('[data-action="panel-menu"][data-panel="assistant"]')).not.toBeNull();
    expect(panel.querySelector('[data-action="panel-close"][data-panel="assistant"]')).not.toBeNull();
    // Labelled by its own heading, so the region is named for a screen reader exactly as the
    // Inspector and Runtime activity are.
    expect(panel.getAttribute('aria-labelledby')).toBe('assistant-title');
    expect(document_.getElementById('assistant-title').textContent).toBe('Assistant');
  });

  it('has a rail mark, which is the only thing naming it while the column is collapsed', async () => {
    const document_ = await page();
    expect(document_.getElementById('i-p-assistant')).not.toBeNull();
  });
});

// ── THE LIVE-REGION RULES ────────────────────────────────────────────────────────────────────────
describe('every announcement this panel makes is polite', () => {
  it('announces the transcript and the state politely, never assertively', async () => {
    const document_ = await page();
    const transcript = document_.getElementById('assistant-transcript');
    const state = document_.getElementById('assistant-state');

    expect(transcript.getAttribute('role')).toBe('log');
    expect(transcript.getAttribute('aria-live')).toBe('polite');
    expect(state.getAttribute('role')).toBe('status');
    expect(state.getAttribute('aria-live')).toBe('polite');
  });

  // `role="alert"` is implicitly assertive. An arriving answer, a lost context source and a refused
  // empty draft are all things that must WAIT for the user, not interrupt them mid-sentence.
  it('uses no assertive region and no alert role anywhere in the panel', async () => {
    const document_ = await page();
    const panel = document_.querySelector('.panel[data-panel-id="assistant"]');
    expect(panel.querySelectorAll('[aria-live="assertive"]')).toHaveLength(0);
    expect(panel.querySelectorAll('[role="alert"]')).toHaveLength(0);
    // Every live region inside the panel is accounted for, so a new one cannot arrive unpoliced.
    // The fourth is the connection region: it announces a code the author has to transcribe,
    // and a code that appeared silently would be invisible to the reader who most needs it read.
    const live = [...panel.querySelectorAll('[aria-live]')].map(node => node.id);
    expect(live.sort()).toEqual([
      'assistant-connection', 'assistant-error', 'assistant-state', 'assistant-transcript',
    ]);
    expect([...panel.querySelectorAll('[aria-live]')]
      .every(node => node.getAttribute('aria-live') === 'polite')).toBe(true);
  });

  // A live region whose STRUCTURE moves as content arrives destroys focus stability
  // and over-announces. The transcript therefore ships EMPTY — no placeholder that would have to be
  // removed when the first turn lands, and no group container that could later hold one.
  it('ships an empty transcript, so no element has to be removed when content arrives', async () => {
    const document_ = await page();
    const transcript = document_.getElementById('assistant-transcript');
    expect(transcript.children).toHaveLength(0);
    expect(transcript.textContent.trim()).toBe('');
  });
});

// ── THE ERROR IS BOUND TO THE CONTROL, NOT MERELY NEAR IT ────────────────────────────────────────
describe('the composer', () => {
  it('has a real label and a described-by hint rather than a placeholder standing in for one', async () => {
    const document_ = await page();
    const draft = document_.getElementById('assistant-draft');
    const label = document_.querySelector('label[for="assistant-draft"]');
    expect(label).not.toBeNull();
    expect(label.textContent.trim().length).toBeGreaterThan(0);
    expect(draft.getAttribute('aria-describedby')).toBe('assistant-composer-help');
    expect(document_.getElementById('assistant-composer-help')).not.toBeNull();
  });

  it('ships the error element it will bind to, hidden and empty', async () => {
    const document_ = await page();
    const error = document_.getElementById('assistant-error');
    expect(error.hasAttribute('hidden')).toBe(true);
    expect(error.textContent.trim()).toBe('');
    // Not yet bound: there is no error, so asserting `aria-invalid` at rest would be a lie.
    expect(document_.getElementById('assistant-draft').hasAttribute('aria-invalid')).toBe(false);
  });

  it('says in the panel itself that graph proposals need confirmation and runs remain unavailable', async () => {
    const document_ = await page();
    expect(document_.getElementById('assistant-composer-help').textContent)
      .toMatch(/proposals require your explicit confirmation; the assistant cannot start a run/i);
  });
});

describe('the disclosure surface', () => {
  it('ships the chip list and the payload inspector, with the toggle bound to what it reveals', async () => {
    const document_ = await page();
    const toggle = document_.getElementById('assistant-payload-toggle');
    expect(document_.getElementById('assistant-chips')).not.toBeNull();
    expect(toggle.getAttribute('aria-controls')).toBe('assistant-payload');
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(document_.getElementById('assistant-payload').hasAttribute('hidden')).toBe(true);
  });

  it('names the chip list, so it is not an unlabelled list of fragments', async () => {
    const document_ = await page();
    expect(document_.getElementById('assistant-chips').getAttribute('aria-labelledby'))
      .toBe('assistant-context-label');
    expect(document_.getElementById('assistant-context-label').textContent.trim().length)
      .toBeGreaterThan(0);
  });
});

// ── NO EFFECTORS IN THE MARKUP EITHER ────────────────────────────────────────────────────────────
describe('the read-only increment, checked against the shipped panel', () => {
  // The inventory includes two connection actions, and it is worth being exact about why that does
  // not widen the read-only surface. An EFFECTOR is a control through which something the model
  // suggested could act on the graph or on a run; `data-command-id` is how every such control in
  // this product is wired, and the assertion below still finds none. The two new entries connect
  // the author's own account and stop that attempt — panel-local, model-unreachable, and nothing a
  // reply could route through. The list stays written out so that a control which IS an effector
  // cannot arrive quietly beside them.
  it('offers no command, confirmation card or action control inside the panel', async () => {
    const document_ = await page();
    const panel = document_.querySelector('.panel[data-panel-id="assistant"]');
    expect(panel.querySelectorAll('[data-command-id]')).toHaveLength(0);
    const actions = [...panel.querySelectorAll('[data-action]')].map(node => node.dataset.action);
    expect(actions.sort()).toEqual([
      'cancel-assistant-connection', 'clear-assistant', 'connect-assistant',
      'panel-close', 'panel-menu',
    ]);
  });
});
