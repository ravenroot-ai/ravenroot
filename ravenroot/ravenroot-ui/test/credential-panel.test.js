import { readFile } from 'node:fs/promises';

import { JSDOM } from 'jsdom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { API_KEY_SCHEME, BASIC_SCHEME, OAUTH_TOKEN_SCHEME } from '../src/credential-client.js';
import {
  CREDENTIAL_SCOPE_TEXT,
  createCredentialsWindow,
  validateCredentialDraft,
} from '../src/credential-panel.js';

// The window's own rules, against the REAL markup shipped in `index.html`
// rather than a fixture: the panel looks every element up by id, so a fixture would let the two
// drift apart silently and this file would keep passing while the shipped window stopped working.

const VALUE = 'sk-the-actual-secret-nobody-may-see-again';
const REFERENCE = 'rrc_0123456789abcdef0123456789abcdef';

const STORED = {
  reference: REFERENCE, label: 'Weather API', scheme: API_KEY_SCHEME,
  username: '', createdAt: '2026-08-28T09:15:00Z',
};

let dialog;

beforeEach(async () => {
  const html = await readFile('index.html', 'utf8');
  const source = new JSDOM(html).window.document.getElementById('credentials-dialog');
  document.body.innerHTML = '';
  dialog = document.importNode(source, true);
  document.body.appendChild(dialog);
  // jsdom implements `<dialog>` but not always `showModal` in every version; the window uses it
  // through a capability check, and this keeps the check honest rather than stubbed away.
  if (typeof dialog.showModal !== 'function') dialog.showModal = function open() { this.open = true; };
});

afterEach(() => { document.body.innerHTML = ''; });

const field = id => document.getElementById(id);

function stubClient({ credentials = [], create = async () => STORED } = {}) {
  return {
    list: vi.fn(async () => ({ surface: 'present', credentials })),
    create: vi.fn(create),
  };
}

function fill({ label = 'Weather API', scheme = API_KEY_SCHEME, username = '', value = VALUE } = {}) {
  field('credential-label').value = label;
  field('credential-scheme').value = scheme;
  field('credential-scheme').dispatchEvent(new window.Event('change', { bubbles: true }));
  field('credential-username').value = username;
  field('credential-value').value = value;
}

describe('the draft rules, without a DOM', () => {
  it('requires a label, a value, and a recognised kind', () => {
    const { ok, errors } = validateCredentialDraft({ label: '', scheme: 'nonsense', value: '' });
    expect(ok).toBe(false);
    expect(Object.keys(errors).sort()).toEqual(['label', 'scheme', 'value']);
  });

  it('requires a username for basic and refuses one for the other two kinds', () => {
    expect(validateCredentialDraft({ label: 'R', scheme: BASIC_SCHEME, value: VALUE }).errors.username)
      .toBeTruthy();
    expect(validateCredentialDraft({
      label: 'R', scheme: BASIC_SCHEME, username: 'ada', value: VALUE,
    }).ok).toBe(true);
    for (const scheme of [API_KEY_SCHEME, OAUTH_TOKEN_SCHEME]) {
      expect(validateCredentialDraft({ label: 'R', scheme, username: 'ada', value: VALUE })
        .errors.username).toBeTruthy();
    }
  });

  it('refuses a label longer than the service will take, and says what the limit is', () => {
    const errors = validateCredentialDraft({
      label: 'x'.repeat(121), scheme: API_KEY_SCHEME, value: VALUE,
    }).errors;
    expect(errors.label).toContain('120');
    expect(validateCredentialDraft({ label: 'x'.repeat(120), scheme: API_KEY_SCHEME, value: VALUE }).ok)
      .toBe(true);
  });

  it('no message it produces quotes the value it was given', () => {
    const { errors } = validateCredentialDraft({ label: '', scheme: BASIC_SCHEME, value: VALUE });
    expect(JSON.stringify(errors)).not.toContain(VALUE);
  });
});

describe('the window is the only place a value is typed, and does not keep it', () => {
  it('clears the value the moment a successful store returns', async () => {
    const client = stubClient();
    const window_ = createCredentialsWindow({ dialog, client });
    fill();

    await window_.save();

    expect(client.create).toHaveBeenCalledTimes(1);
    expect(client.create.mock.calls[0][0].value).toBe(VALUE);
    expect(field('credential-value').value).toBe('');
    expect(field('credential-status').textContent).not.toContain(VALUE);
  });

  it('clears it on a FAILED store too — a failed attempt is exactly when it must not be left on screen',
    async () => {
      const client = stubClient({
        create: async () => { throw new Error('The service refused this credential.'); },
      });
      const window_ = createCredentialsWindow({ dialog, client });
      fill();

      await window_.save();

      expect(field('credential-value').value).toBe('');
      expect(field('credential-status').textContent).toContain('The service refused this credential.');
      expect(field('credential-status').textContent).not.toContain(VALUE);
    });

  it('clears it when there is no service to send it to, rather than leaving it to be retried later',
    async () => {
      const window_ = createCredentialsWindow({ dialog, client: null });
      fill();

      await window_.save();

      expect(field('credential-value').value).toBe('');
    });

  it('clears it when the window is closed with the form half filled in', () => {
    const window_ = createCredentialsWindow({ dialog, client: stubClient() });
    window_.open();
    fill();

    window_.close();

    expect(field('credential-value').value).toBe('');
  });

  it('sends nothing at all when the draft is invalid, and says which field is wrong', async () => {
    const client = stubClient();
    const window_ = createCredentialsWindow({ dialog, client });
    fill({ label: '' });

    await window_.save();

    expect(client.create).not.toHaveBeenCalled();
    expect(field('credential-label-error').hidden).toBe(false);
    expect(field('credential-label').getAttribute('aria-invalid')).toBe('true');
    // The value is NOT cleared here: nothing was attempted, and clearing it would punish a typo in
    // the label by making someone paste the secret again.
    expect(field('credential-value').value).toBe(VALUE);
  });
});

describe('what the window renders', () => {
  it('lists a stored credential by label, kind and reference — and by nothing else', async () => {
    const window_ = createCredentialsWindow({ dialog, client: stubClient({ credentials: [STORED] }) });

    await window_.refresh();

    const item = field('credential-list').querySelector('.credential-item');
    expect(item.querySelector('b').textContent).toBe('Weather API');
    expect(item.querySelector('small').textContent).toContain('API key');
    expect(item.querySelector('.credential-reference').textContent).toBe(REFERENCE);
    expect(item.dataset.credentialReference).toBe(REFERENCE);
  });

  it('renders a stored credential with NO value anywhere in the window, not even masked', async () => {
    const client = stubClient({ credentials: [STORED] });
    const window_ = createCredentialsWindow({ dialog, client });
    fill();

    await window_.save();

    // The whole window, markup and all: the strongest form of "no value is rendered".
    expect(dialog.innerHTML).not.toContain(VALUE);
    expect(dialog.textContent).not.toContain(VALUE);
    // And nothing stood in for one either — a mask would be an interface claiming the value is here.
    expect(dialog.textContent).not.toContain('••••');
    expect(dialog.textContent).not.toContain('****');
  });

  it('shows the username field only for the kind that has one, and clears it when the kind changes',
    () => {
      const window_ = createCredentialsWindow({ dialog, client: stubClient() });
      expect(field('credential-username-field').hidden).toBe(true);

      fill({ scheme: BASIC_SCHEME, username: 'ada' });
      expect(field('credential-username-field').hidden).toBe(false);

      field('credential-scheme').value = OAUTH_TOKEN_SCHEME;
      field('credential-scheme').dispatchEvent(new window.Event('change', { bubbles: true }));

      expect(field('credential-username-field').hidden).toBe(true);
      // Cleared, not merely hidden: a leftover username would be refused by the service, and the
      // author would have no field on screen to correct it in.
      expect(field('credential-username').value).toBe('');
      void window_;
    });

  it('says once, in every state, what this window is and how it differs from the command bar', () => {
    createCredentialsWindow({ dialog, client: null });
    expect(field('credential-scope').textContent).toBe(CREDENTIAL_SCOPE_TEXT);
    expect(CREDENTIAL_SCOPE_TEXT).toMatch(/command bar/i);
  });

  it('distinguishes "you hold none" from "nobody has asked yet"', async () => {
    const connected = createCredentialsWindow({ dialog, client: stubClient({ credentials: [] }) });
    await connected.refresh();
    expect(field('credential-empty').hidden).toBe(false);
    expect(field('credential-empty').textContent).toContain('no credentials yet');

    const offline = createCredentialsWindow({ dialog, client: null });
    await offline.refresh();
    expect(field('credential-empty').textContent).toContain('Connect');
  });

  it('says nothing under the list when the STATUS has already explained an absent surface', async () => {
    // Observed in Chromium against a fixture with no credential route: the status said "This
    // deployment does not offer credential storage" and the line below it said "Connect to your
    // Ravenroot service" — two explanations of one situation, one of them wrong, with no way for a
    // reader to tell which. The status keeps it; the empty line stands down.
    const absent = createCredentialsWindow({
      dialog, client: { list: async () => ({ surface: 'absent', credentials: [] }), create: vi.fn() },
    });

    await absent.refresh();

    expect(field('credential-status').textContent).toContain('does not offer credential storage');
    expect(field('credential-empty').hidden).toBe(true);
  });

  it('publishes the list to whoever renders the node inspector, with its loaded-ness attached',
    async () => {
      const onCredentials = vi.fn();
      const window_ = createCredentialsWindow({
        dialog, client: stubClient({ credentials: [STORED] }), onCredentials,
      });

      await window_.refresh();
      expect(onCredentials).toHaveBeenLastCalledWith({ loaded: true, credentials: [STORED] });

      // Withdrawing the client is what `revokeRuntimeAccess` does, and the inspector has to hear it:
      // a list read under an authentication that has just been dropped is not a list any more.
      await window_.setClient(null);
      expect(onCredentials).toHaveBeenLastCalledWith({ loaded: false, credentials: [] });
    });
});

describe('the file\'s own rules', () => {
  it('contains no logging of any kind', async () => {
    const source = await readFile('src/credential-panel.js', 'utf8');
    expect(source).not.toContain('console');
    expect(source).not.toContain('debugger');
  });
});
