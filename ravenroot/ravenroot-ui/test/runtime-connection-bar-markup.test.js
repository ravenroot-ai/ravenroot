import { readFile } from 'node:fs/promises';

import { JSDOM } from 'jsdom';
import { beforeAll, describe, expect, it } from 'vitest';

// ── THE COMMAND BAR SAYS WHAT IT CONNECTS, AND KEEPS EVERY CONTROL ───────────────────────────
//
// The bar was labelled "Access token", next to a field labelled "Service", above a page that now has
// a window which really does take a provider credential. Two boxes that look alike and mean
// different things creates confusion, and the remedy is words — NOT a removal.
//
// SO THIS FILE IS TWO ASSERTIONS PULLING AGAINST EACH OTHER, ON PURPOSE. The first is that the
// wording no longer reads as a model credential. The second, and the one that would actually break
// the product, is that NOTHING WAS TAKEN AWAY: this bar is what calls `runtimeClient.connect(...)`
// and loads the node catalog, so an id that disappears here disconnects the application. A rename
// that quietly dropped a field would satisfy the first assertion perfectly.

let html;
let document;

beforeAll(async () => {
  html = await readFile('index.html', 'utf8');
  document = new JSDOM(html).window.document;
});

describe('the runtime connection bar keeps every control it had', () => {
  // Named individually rather than counted: a count passes when one control is replaced by another.
  it.each([
    '#service-url', '#access-token', '#btn-authenticate', '#btn-revoke',
    '#runtime-connection', '#execution-payload',
  ])('still ships %s with that exact id', selector => {
    expect(document.querySelectorAll(selector)).toHaveLength(1);
  });

  it('keeps the two commands bound to the same ids, so authenticate and forget still work', () => {
    expect(document.querySelector('#btn-authenticate').dataset.commandId).toBe('run.authenticate');
    expect(document.querySelector('#btn-revoke').dataset.commandId).toBe('run.forgetToken');
  });

  it('keeps the token field a password field that browsers are told not to keep', () => {
    const token = document.querySelector('#access-token');
    expect(token.getAttribute('type')).toBe('password');
    expect(token.getAttribute('autocomplete')).toBe('off');
    // Never a `value` attribute: there must be nothing for a restored form state to repopulate.
    expect(token.hasAttribute('value')).toBe(true);
    expect(token.getAttribute('value')).toBe('');
  });

  it('announces process-local source lifecycle state without relying on colour', () => {
    const status = document.getElementById('source-session-status');
    expect(status).not.toBeNull();
    expect(status.getAttribute('role')).toBe('status');
    expect(status.getAttribute('aria-live')).toBe('polite');
    expect(status.hasAttribute('hidden')).toBe(true);
  });
});

describe('the runtime connection bar no longer reads as a model credential', () => {
  it('the visible label names the SERVICE rather than a bare access token', () => {
    const label = document.querySelector('#access-token').closest('label');
    const visible = label.querySelector('span').textContent.trim();

    expect(visible).toBe('Service token');
    // The exact old wording, which is what a reader mistook for a provider key box.
    expect(visible).not.toBe('Access token');
    expect(visible.toLowerCase()).toContain('service');
  });

  it('the accessible name says which service it is, and says what it is not', () => {
    const name = document.querySelector('#access-token').getAttribute('aria-label');

    expect(name).toMatch(/ravenroot service/i);
    expect(name).toMatch(/not.*model provider/i);
    // The former name was ambiguous to a reader on assistive technology, with none of the
    // surrounding layout to disambiguate it.
    expect(name).not.toBe('Bearer access token');
  });

  it('the fields are one named group rather than four adjacent controls', () => {
    const group = document.querySelector('.runtime-controls');
    expect(group.getAttribute('role')).toBe('group');

    const caption = document.getElementById(group.getAttribute('aria-labelledby'));
    expect(caption.textContent).toMatch(/ravenroot service/i);

    // The distinction itself, written where somebody meeting the group first will hear it: this is
    // the sign-in to your own service, and node credentials live somewhere else.
    const scope = document.getElementById(group.getAttribute('aria-describedby'));
    expect(scope.textContent).toMatch(/credentials window/i);
    // Every one of the six controls is inside the group it is named by.
    for (const selector of ['#service-url', '#access-token', '#btn-authenticate', '#btn-revoke',
      '#runtime-connection', '#execution-payload']) {
      expect(group.querySelector(selector), selector).not.toBeNull();
    }
  });

  it('says the same thing to a pointer, which never reaches an aria-describedby', () => {
    expect(document.querySelector('.runtime-controls').getAttribute('title'))
      .toMatch(/credentials window/i);
  });
});

describe('the credentials window is a separate place, not part of the bar', () => {
  it('is a native modal dialog outside #workflowbar entirely', () => {
    const dialog = document.getElementById('credentials-dialog');
    expect(dialog.tagName).toBe('DIALOG');
    expect(document.getElementById('workflowbar').contains(dialog)).toBe(false);
  });

  it('is the only place in the shipped markup with a second password field', () => {
    const passwords = [...document.querySelectorAll('input[type="password"]')].map(input => input.id);
    // Exactly two, with the credential field inside the dialog. Checking the whole document prevents
    // another password field from appearing outside either approved surface.
    expect(passwords).toEqual(['access-token', 'credential-value']);
    expect(document.getElementById('credentials-dialog').contains(
      document.getElementById('credential-value'))).toBe(true);
  });

  it('asks for no reference of its own — the service mints it', () => {
    const dialog = document.getElementById('credentials-dialog');
    const named = [...dialog.querySelectorAll('input, select, textarea')].map(field => field.id);
    expect(named).toEqual([
      'credential-label', 'credential-scheme', 'credential-username', 'credential-value',
    ]);
  });

  it('marks the value field as not for keeping, exactly as the service token is marked', () => {
    const value = document.getElementById('credential-value');
    expect(value.getAttribute('type')).toBe('password');
    expect(value.getAttribute('autocomplete')).toBe('off');
    expect(value.getAttribute('spellcheck')).toBe('false');
    expect(value.hasAttribute('value')).toBe(false);
  });

  it('adds no inline event handler anywhere, the boundary ui-security-boundary already pins', () => {
    expect(html).not.toMatch(/\bon(?:click|change|input|submit)=/);
  });
});
