// The Credentials window is the ONE place in this product where a credential
// value is typed.
//
// ── WHY A MODAL WINDOW AND NOT A PANEL ───────────────────────────────────────────────────────────
//
// "This is the only place" is a claim about the WHOLE interface, and a claim like
// that is only checkable if the place is a single, named, bounded thing. A panel is none of those
// from the reader's side: it sits in a column beside the Inspector and the Assistant, it can be
// moved to the dock, it can be short, and the runtime bar stays visible above it the entire time.
// That adjacency is precisely what the modal boundary prevents.
//
// A native `<dialog>` opened modally takes the screen, dims what is behind it, contains focus, and
// closes on Escape — all of it from the platform, none of it reimplemented here. It is also what the
// product already reaches for when a decision must not be made half-attentively
// (`#unsaved-document-dialog`). And it costs no layout: adding an eighth panel would have rewritten
// the stored layout inventory, the panel splitter geometry and every test that pins them, in
// exchange for a worse boundary.
//
// ── THE RULES ────────────────────────────────────────────────────────────────────────────────────
//
// 1. THE VALUE FIELD IS CLEARED THE MOMENT THE CALL RETURNS, success or failure — see `save`. It is
// never repopulated, never read back into a variable that outlives the call, and never put in a
// message. A failed save keeps every OTHER typed field, because retyping a label is a nuisance
// and retyping a secret is the correct cost of a failed attempt.
// 2. NO VALUE IS EVER RENDERED, not even masked. There is nothing to mask: the service does not
// answer with one, `normalizeCredential` throws if it ever starts to, and this file has no code
// path that would draw one. A masked value in a list is an interface promising that the value is
// here somewhere, which would be a lie about where it is held.
// 3. NOTHING HERE IS PRINTED, and nothing writes to the developer log at all —
// `credential-panel.test.js` reads this source for that token, comments included, for the reason
// `credential-client.js`'s header gives.
// 4. THE REFERENCE IS OPAQUE AND COPYABLE. It is what a node's property stores, so an author needs
// to be able to take it; it says nothing about the value, so showing it costs nothing.

import {
  API_KEY_SCHEME,
  CREDENTIAL_SCHEMES,
  credentialSchemeLabel,
  schemeCarriesUsername,
} from './credential-client.js';

export const LABEL_MAX_LENGTH = 120;

// One sentence, written once, shown in every state. It explains this window's purpose and how its
// stored node credentials differ from the service token in the command bar.
export const CREDENTIAL_SCOPE_TEXT = 'A credential stored here is held by your Ravenroot service and '
  + 'never comes back to this browser. Nodes name it by its reference. This is not the token in the '
  + 'command bar: that one signs this editor in to your own service.';

/**
 * Pure, DOM-free, and exported so the rules can be read and tested without a document.
 * Returns `{ok, errors}` — never a partially repaired draft.
 */
export function validateCredentialDraft(draft) {
  const errors = {};
  const label = String(draft?.label ?? '').trim();
  const scheme = String(draft?.scheme ?? '');
  const username = String(draft?.username ?? '').trim();
  const value = String(draft?.value ?? '');

  if (!label) errors.label = 'Give this credential a name you will recognise in a node.';
  else if (label.length > LABEL_MAX_LENGTH) {
    errors.label = `A name may be at most ${LABEL_MAX_LENGTH} characters; this one is ${label.length}.`;
  }
  if (!CREDENTIAL_SCHEMES.includes(scheme)) errors.scheme = 'Choose what kind of credential this is.';
  // Both directions, because the service refuses both: a blank username for `basic`, and any
  // username at all for the other two. The second is unreachable through the form — the field is
  // hidden and cleared when the kind changes — and is checked anyway, because "unreachable through
  // the form" is a statement about today's form.
  if (schemeCarriesUsername(scheme) && !username) {
    errors.username = 'A username and password credential needs the username too.';
  }
  if (!schemeCarriesUsername(scheme) && username) {
    errors.username = `A ${credentialSchemeLabel(scheme).toLowerCase()} credential carries no username.`;
  }
  if (!value) errors.value = 'Paste the credential value. It is sent once and never shown again.';

  return { ok: Object.keys(errors).length === 0, errors };
}

/** A creation date the service wrote, shown as a date rather than as a timestamp nobody reads. */
export function credentialCreatedText(createdAt) {
  const raw = String(createdAt || '');
  if (!raw) return '';
  const parsed = new Date(raw);
  return Number.isNaN(parsed.getTime()) ? raw : parsed.toLocaleDateString();
}

/**
 * @param dialog the `<dialog id="credentials-dialog">` shipped in `index.html`
 * @param client a `RavenrootCredentialClient`, or null while the page holds no service
 * @param onCredentials called after every listing attempt with `{loaded, credentials}` — this is
 * what the node inspector's SECRET_REFERENCE control offers
 */
export function createCredentialsWindow({ dialog, client = null, onCredentials = () => {} } = {}) {
  if (!dialog) {
    return {
      open: () => {}, close: () => {}, refresh: async () => {},
      setClient: async () => {}, destroy: () => {},
    };
  }
  const doc = dialog.ownerDocument;
  const element = id => dialog.querySelector(`#${id}`);

  const form = element('credential-form');
  const status = element('credential-status');
  const list = element('credential-list');
  const empty = element('credential-empty');
  const scope = element('credential-scope');
  // No `closeButton` handle: Close is reached through the dialog's own delegated click listener
  // below, alongside the per-item Copy buttons, which are created and destroyed on every listing.
  const saveButton = element('credential-save');
  const usernameField = element('credential-username-field');

  const controls = {
    label: element('credential-label'),
    scheme: element('credential-scheme'),
    username: element('credential-username'),
    value: element('credential-value'),
  };

  let listing = { loaded: false, surface: 'none', credentials: [] };
  let busy = false;
  let disposed = false;

  if (scope) scope.textContent = CREDENTIAL_SCOPE_TEXT;
  if (controls.label) controls.label.maxLength = LABEL_MAX_LENGTH;
  if (controls.scheme && controls.scheme.options.length === 0) {
    controls.scheme.replaceChildren(...CREDENTIAL_SCHEMES.map(scheme => {
      const option = doc.createElement('option');
      option.value = scheme;
      option.textContent = credentialSchemeLabel(scheme);
      return option;
    }));
  }

  function draft() {
    return {
      label: controls.label?.value ?? '',
      scheme: controls.scheme?.value ?? API_KEY_SCHEME,
      // Read from the control only while the control is the one the chosen kind uses. A hidden
      // field's leftover text must not become part of the request, and clearing it on change is not
      // enough on its own — a build that forgets the clear would still be correct here.
      username: schemeCarriesUsername(controls.scheme?.value) ? (controls.username?.value ?? '') : '',
      value: controls.value?.value ?? '',
    };
  }

  // ── RENDERING ────────────────────────────────────────────────────────────────────────────────

  function renderScheme() {
    const carries = schemeCarriesUsername(controls.scheme?.value);
    if (usernameField) usernameField.hidden = !carries;
    if (!carries && controls.username) controls.username.value = '';
  }

  function renderList() {
    const credentials = listing.credentials || [];
    if (empty) {
      // THREE STATES, NOT TWO, and the third one is why this is not `loaded ? … : …`. Measured in
      // Chromium against this tree: a deployment with no credential route showed "This deployment
      // does not offer credential storage" in the status AND "Connect to your Ravenroot service" a
      // few lines below it — two different explanations of one situation, one of them wrong, and the
      // reader has no way to tell which. So an ABSENT surface renders no empty line at all: the
      // status has already said it, once, in the right words. Saying it twice is the same defect the
      // model provider panel's `onModeChange` documents.
      empty.hidden = credentials.length > 0 || listing.surface === 'absent';
      empty.textContent = listing.loaded
        ? 'You hold no credentials yet. The first one you store appears here.'
        : 'Connect to your Ravenroot service to see the credentials you hold.';
    }
    list.hidden = credentials.length === 0;
    // textContent throughout. A label is text a person typed and this page renders author-authored
    // graph content a few pixels away; nothing here builds markup from a server string.
    list.replaceChildren(...credentials.map(credential => {
      const item = doc.createElement('li');
      item.className = 'credential-item';
      item.dataset.credentialReference = credential.reference;

      const name = doc.createElement('b');
      name.textContent = credential.label;

      const detail = doc.createElement('small');
      const created = credentialCreatedText(credential.createdAt);
      detail.textContent = [
        credentialSchemeLabel(credential.scheme),
        credential.username ? `user ${credential.username}` : '',
        created ? `stored ${created}` : '',
      ].filter(Boolean).join(' · ');

      // The reference, and nothing else about the credential, is what a node's property stores — so
      // it is shown verbatim and can be taken. There is no value beside it to be mistaken for one.
      const reference = doc.createElement('code');
      reference.className = 'credential-reference';
      reference.textContent = credential.reference;
      reference.tabIndex = 0;

      const copy = doc.createElement('button');
      copy.type = 'button';
      copy.className = 'btn credential-copy';
      copy.textContent = 'Copy reference';
      copy.dataset.copyReference = credential.reference;

      const row = doc.createElement('div');
      row.className = 'credential-item-reference';
      row.append(reference, copy);

      item.append(name, detail, row);
      return item;
    }));
  }

  function clearFieldErrors() {
    for (const name of Object.keys(controls)) {
      const control = controls[name];
      const slot = element(`credential-${name}-error`);
      if (!control || !slot) continue;
      control.removeAttribute('aria-invalid');
      control.removeAttribute('aria-errormessage');
      slot.textContent = '';
      slot.hidden = true;
    }
  }

  function showFieldErrors(errors) {
    clearFieldErrors();
    let first = null;
    for (const [name, message] of Object.entries(errors)) {
      const control = controls[name];
      const slot = element(`credential-${name}-error`);
      if (!control || !slot) continue;
      slot.textContent = message;
      slot.hidden = false;
      control.setAttribute('aria-invalid', 'true');
      control.setAttribute('aria-errormessage', slot.id);
      first = first || control;
    }
    first?.focus();
  }

  function say(message, kind = 'info') {
    status.textContent = message;
    status.dataset.state = kind;
  }

  function setBusy(next) {
    busy = next;
    // `aria-disabled` rather than `disabled`: app.js already gates activation of anything marked
    // this way, and the control stays focusable so a keyboard user can reach it.
    saveButton?.setAttribute('aria-disabled', String(Boolean(next)));
  }

  // ── ACTIONS ──────────────────────────────────────────────────────────────────────────────────

  function publish() {
    onCredentials({ loaded: listing.loaded, credentials: listing.credentials });
  }

  async function refresh() {
    if (!client) {
      listing = { loaded: false, surface: 'none', credentials: [] };
      renderList();
      publish();
      say('Connect to your Ravenroot service to see and store credentials.');
      return;
    }
    try {
      const answer = await client.list();
      if (disposed) return;
      listing = answer.surface === 'absent'
        ? { loaded: false, surface: 'absent', credentials: [] }
        : { loaded: true, surface: 'present', credentials: answer.credentials };
      renderList();
      publish();
      if (answer.surface === 'absent') {
        say('This deployment does not offer credential storage.', 'blocked');
      }
    } catch (error) {
      if (disposed) return;
      listing = { loaded: false, surface: 'none', credentials: [] };
      renderList();
      publish();
      say(`The credentials you hold could not be read: ${error?.message || error}`, 'error');
    }
  }

  async function save() {
    const current = draft();
    const check = validateCredentialDraft(current);
    if (!check.ok) {
      showFieldErrors(check.errors);
      say('Some fields need correcting before this credential can be stored. Each one says what is '
        + 'wrong next to the field itself.', 'error');
      return;
    }
    clearFieldErrors();
    if (!client) {
      // Cleared even here. The value reached an input on a page with no service to send it to, and
      // leaving it there so the author can "try again" is leaving it on screen for as long as the
      // tab is open.
      if (controls.value) controls.value.value = '';
      say('There is no connection to your service, so nothing was stored. The value was cleared; '
        + 'connect and paste it again.', 'error');
      return;
    }
    const label = String(current.label).trim();
    setBusy(true);
    say(`Storing “${label}”…`);
    try {
      await client.create(current);
      if (disposed) return;
      // RULE 1, and the ordering is the rule: cleared before anything else is done with the answer,
      // so no later failure can leave it on screen.
      if (controls.value) controls.value.value = '';
      if (controls.label) controls.label.value = '';
      if (controls.username) controls.username.value = '';
      say(`Stored “${label}”. The value is held by your service and is not shown here again — nodes `
        + 'name this credential by its reference below.', 'ok');
      await refresh();
    } catch (error) {
      if (disposed) return;
      // RULE 1 again, on the failure path, and the message is the SERVICE's prose about the request
      // — never the request's contents.
      if (controls.value) controls.value.value = '';
      say(`“${label}” was not stored: ${error?.message || error} The value was cleared; paste it `
        + 'again to retry.', 'error');
    } finally {
      if (!disposed) setBusy(false);
    }
  }

  function open() {
    if (!dialog.open) {
      // `showModal` rather than `show`: focus containment and the inert backdrop are the reason a
      // dialog was chosen over a panel, and `show` provides neither.
      if (typeof dialog.showModal === 'function') dialog.showModal();
      else dialog.setAttribute('open', '');
    }
    say('');
    clearFieldErrors();
    renderScheme();
    controls.label?.focus();
    void refresh();
  }

  function close() {
    // Whatever was typed and not stored goes with the window. A value left in a closed dialog is
    // still a value in the document.
    if (controls.value) controls.value.value = '';
    if (typeof dialog.close === 'function') dialog.close();
    else dialog.removeAttribute('open');
  }

  // ── WIRING ───────────────────────────────────────────────────────────────────────────────────

  const onSubmit = event => {
    event.preventDefault();
    if (busy) return;
    void save();
  };
  const onSchemeChange = event => {
    if (event.target !== controls.scheme) return;
    renderScheme();
    clearFieldErrors();
    say('');
  };
  const onDialogClick = event => {
    const copy = event.target.closest?.('[data-copy-reference]');
    if (copy) {
      // Best effort and silent about the mechanism: a reference is short enough to select and read,
      // so a browser without clipboard permission loses a convenience, not the information.
      void doc.defaultView?.navigator?.clipboard?.writeText?.(copy.dataset.copyReference)
        ?.then(() => { if (!disposed) say('Reference copied.'); })
        ?.catch(() => {});
      return;
    }
    if (event.target.closest?.('#credential-close')) close();
  };
  // Escape reaches the dialog's own `cancel`, which must clear the value exactly as the button does.
  const onCancel = () => { if (controls.value) controls.value.value = ''; };

  form.addEventListener('submit', onSubmit);
  form.addEventListener('change', onSchemeChange);
  dialog.addEventListener('click', onDialogClick);
  dialog.addEventListener('cancel', onCancel);

  renderScheme();
  renderList();

  return {
    open,
    close,
    refresh,
    save,
    references: () => ({ loaded: listing.loaded, credentials: listing.credentials }),
    setClient(next) {
      client = next;
      if (!next) {
        listing = { loaded: false, surface: 'none', credentials: [] };
        renderList();
        publish();
        return Promise.resolve();
      }
      return refresh();
    },
    destroy() {
      disposed = true;
      form.removeEventListener('submit', onSubmit);
      form.removeEventListener('change', onSchemeChange);
      dialog.removeEventListener('click', onDialogClick);
      dialog.removeEventListener('cancel', onCancel);
    },
  };
}
