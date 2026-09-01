// The Deployments window lets a graph with no effective SOURCE be "also registered and controlled as a local
// deployment" for a graph with no effective SOURCE, and the tenant's own view of every deployment
// registered this way -- from this editor or from the CLI, since both reach the identical
// `/v1/deployments` contract.
//
// ── WHY THIS EXISTS AS ITS OWN WINDOW, NOT ON THE RUN BUTTON ────────────────────────────────────────
//
// Making Run itself register and start a deployment for every graph, including one with no
// effective SOURCE, would leave the editor with no way to
// submit a real, effect-producing one-shot execution at all, because Run no longer reached
// `POST /v1/executions?mode=run` for ANY graph. A source-less graph must be "also" registrable and
// controllable, but doing so does not
// has to happen on the button that used to mean something else. This window is where that capability
// actually lives, decoupled from Run, Stop, and from any particular open document: a deployment
// registered here is tenant-scoped, addressable by its own id, and outlives the document that
// registered it exactly as a CLI-registered one would.
//
// ── WHY A MODAL WINDOW AND NOT A PANEL ────────────────────────────────────────────────────────────
//
// The same reasoning as `credential-panel.js`'s own header: a native `<dialog>` opened modally takes
// the screen, dims what is behind it, contains focus, and closes on Escape, all from the platform.
// Adding an eighth panel would have rewritten the stored layout inventory and every test that pins
// it, in exchange for a worse boundary -- this window is opened rarely (register, occasionally
// inspect or undeploy) rather than watched continuously the way a panel is.
//
// ── THE RULES ────────────────────────────────────────────────────────────────────────────────────
//
// 1. STATES SHOWN ARE THE SERVER'S OWN WORDS: REGISTERED, STARTING, READY, DEGRADED, STOPPING,
// STOPPED, FAILED. Never translated into a source-session word (`LISTENING`, etc.) -- that
// translation existed only to let Run's now-reverted generalization reuse source-session chrome,
// and reintroducing it here would create exactly that UI-invented approximation.
// 2. EVERY ROW NAMES ITS SCOPE. `scope` is always `LOCAL_PROCESS` on the wire; this window never
// implies durability, a lease, failover or cluster ownership by omitting it.
// 3. UNDEPLOY IS A DISTINCT, CONFIRMED ACTION. It stops a deployment and then removes its
// registration -- the operation that turns "registered and controlled as a local deployment" back
// into nothing. Closing a document never reaches it (see `proceedToCloseDocument` in app.js); it
// exists only as this window's own button, with its own confirmation, because it is the one
// irreversible action this window offers.
// 4. ONE IN-FLIGHT ACTION PER ROW. A row mid-Start/Stop/Restart/Undeploy disables its own buttons
// until that call resolves and a refresh has run, so a second click cannot race the first.

export const DEPLOYMENT_SCOPE_TEXT = 'A deployment registered here runs inside your Ravenroot '
  + 'service process (scope LOCAL_PROCESS): no durability, failover or cluster ownership is claimed. '
  + 'It is addressed by the id below, in this window and in the CLI (`ravenroot deployments`), and '
  + 'keeps running after you close this window or the document that registered it -- Undeploy is the '
  + 'only action that removes it.';

/** Which buttons a row offers for a given truthful server state. Transient states (STARTING,
 * STOPPING) offer nothing -- the row is mid-flight and this window's own per-row busy flag already
 * covers a locally-initiated transition; this function only decides steady states, and falls back to
 * offering nothing at all for any state string it does not recognise. */
export function deploymentRowActions(state) {
  switch (state) {
    case 'REGISTERED':
    case 'STOPPED':
      return ['start', 'undeploy'];
    case 'READY':
    case 'DEGRADED':
      return ['stop', 'restart', 'undeploy'];
    case 'FAILED':
      return ['restart', 'undeploy'];
    case 'STARTING':
    case 'STOPPING':
    default:
      return [];
  }
}

const ACTION_LABEL = Object.freeze({ start: 'Start', stop: 'Stop', restart: 'Restart', undeploy: 'Undeploy' });

function validateDeploymentId(value) {
  const id = String(value ?? '').trim();
  if (!id) return { ok: false, error: 'Choose an id to register this deployment under.' };
  if (id.length > 200) return { ok: false, error: `An id may be at most 200 characters; this one is ${id.length}.` };
  return { ok: true, id };
}

/**
 * @param dialog the `<dialog id="deployments-dialog">` shipped in `index.html`
 * @param client a `RavenrootRuntimeClient`, or null while the page holds no service
 * @param currentDocument called fresh on every register attempt; returns `{ displayName, graphMl }`
 * for the active document's CURRENT graph, or null with no document open. The
 * caller (app.js) owns capturing this -- serialization, position sync -- the
 * same way `playGraph` captures its own snapshot before the first await.
 * @param pollMs how often an open window re-lists while idle, so a STARTING/STOPPING row
 * reaches its steady state without a manual refresh. 0 disables polling
 * (used by tests).
 */
export function createDeploymentsWindow({
  dialog, client = null, currentDocument = () => null, pollMs = 2000,
} = {}) {
  if (!dialog) {
    return {
      open: () => {}, close: () => {}, refresh: async () => {},
      setClient: async () => {}, destroy: () => {},
    };
  }
  const doc = dialog.ownerDocument;
  const element = id => dialog.querySelector(`#${id}`);

  const form = element('deployment-register-form');
  const status = element('deployment-status');
  const list = element('deployment-list');
  const empty = element('deployment-empty');
  const scope = element('deployment-scope');
  const idInput = element('deployment-id-input');
  const idError = element('deployment-id-error');
  const registerButton = element('deployment-register');

  let listing = { loaded: false, deployments: [] };
  let registering = false;
  let disposed = false;
  const rowBusy = new Set();
  let pollHandle = null;

  if (scope) scope.textContent = DEPLOYMENT_SCOPE_TEXT;

  function say(message, kind = 'info') {
    status.textContent = message;
    status.dataset.state = kind;
  }

  function clearIdError() {
    idInput?.removeAttribute('aria-invalid');
    idInput?.removeAttribute('aria-errormessage');
    if (idError) {
      idError.textContent = '';
      idError.hidden = true;
    }
  }

  function showIdError(message) {
    if (!idError || !idInput) return;
    idError.textContent = message;
    idError.hidden = false;
    idInput.setAttribute('aria-invalid', 'true');
    idInput.setAttribute('aria-errormessage', idError.id);
    idInput.focus();
  }

  function setRegistering(next) {
    registering = next;
    registerButton?.setAttribute('aria-disabled', String(Boolean(next)));
  }

  function stateLabel(state) {
    return state.charAt(0) + state.slice(1).toLowerCase();
  }

  // ── RENDERING ────────────────────────────────────────────────────────────────────────────────

  function renderList() {
    const deployments = listing.deployments || [];
    if (empty) {
      empty.hidden = deployments.length > 0;
      empty.textContent = listing.loaded
        ? 'You hold no deployments yet. Register one from the form above.'
        : 'Connect to your Ravenroot service to see and register deployments.';
    }
    list.hidden = deployments.length === 0;
    list.replaceChildren(...deployments.map(entry => {
      const item = doc.createElement('li');
      item.className = 'credential-item deployment-item';
      item.dataset.deploymentId = entry.deploymentId;

      const head = doc.createElement('div');
      head.className = 'deployment-item-head';
      const name = doc.createElement('b');
      name.textContent = entry.deploymentId;
      const state = doc.createElement('span');
      state.className = `deployment-state deployment-state-${entry.state.toLowerCase()}`;
      state.textContent = stateLabel(entry.state);
      head.append(name, state);

      const detail = doc.createElement('small');
      const sourceText = entry.sourceCount > 0
        ? `${entry.sourceCount} inbound source node${entry.sourceCount === 1 ? '' : 's'}`
        : 'no inbound source';
      detail.textContent = `${sourceText} · scope ${entry.scope}`;

      item.append(head, detail);

      if (entry.diagnostic) {
        const diagnostic = doc.createElement('small');
        diagnostic.className = 'deployment-diagnostic';
        diagnostic.textContent = entry.diagnostic;
        item.append(diagnostic);
      }

      const busy = rowBusy.has(entry.deploymentId);
      const actions = deploymentRowActions(entry.state);
      const actionsRow = doc.createElement('div');
      actionsRow.className = 'deployment-item-actions';
      if (busy) {
        const pending = doc.createElement('small');
        pending.textContent = 'Working…';
        actionsRow.append(pending);
      } else if (actions.length === 0) {
        const pending = doc.createElement('small');
        pending.textContent = entry.state === 'STARTING' || entry.state === 'STOPPING'
          ? 'In progress…' : '';
        if (pending.textContent) actionsRow.append(pending);
      } else {
        for (const action of actions) {
          const button = doc.createElement('button');
          button.type = 'button';
          button.className = `btn deployment-action${action === 'undeploy' ? ' danger' : ''}`;
          button.textContent = ACTION_LABEL[action];
          button.dataset.deploymentAction = action;
          button.dataset.deploymentId = entry.deploymentId;
          actionsRow.append(button);
        }
      }
      item.append(actionsRow);
      return item;
    }));
  }

  function publish() {
    // No `onDeployments` callback exists yet -- unlike credentials, nothing else in this editor reads
    // this listing today. Kept as a named function anyway so a future caller (e.g. the node inspector
    // offering a deployment id) has one place to hook in, matching credential-panel.js's own shape.
  }

  // ── ACTIONS ──────────────────────────────────────────────────────────────────────────────────

  async function refresh() {
    if (!client) {
      listing = { loaded: false, deployments: [] };
      renderList();
      publish();
      say('Connect to your Ravenroot service to see and register deployments.');
      return;
    }
    try {
      const deployments = await client.deployments();
      if (disposed) return;
      listing = { loaded: true, deployments };
      renderList();
      publish();
    } catch (error) {
      if (disposed) return;
      listing = { loaded: false, deployments: [] };
      renderList();
      publish();
      say(`The deployments you hold could not be read: ${error?.message || error}`, 'error');
    }
  }

  async function register() {
    const check = validateDeploymentId(idInput?.value);
    if (!check.ok) {
      showIdError(check.error);
      return;
    }
    clearIdError();
    if (!client) {
      say('There is no connection to your service, so nothing was registered.', 'error');
      return;
    }
    const source = currentDocument();
    if (!source) {
      say('Open or create a graph first -- Register and start uses the active document’s current graph.',
        'error');
      return;
    }
    setRegistering(true);
    say(`Registering “${check.id}” from “${source.displayName}”…`);
    try {
      await client.registerDeployment(check.id, source.graphMl);
      if (disposed) return;
      await client.startDeployment(check.id);
      if (disposed) return;
      say(`“${check.id}” is registered and starting. It appears below once the server answers.`, 'ok');
      if (idInput) idInput.value = '';
      await refresh();
    } catch (error) {
      if (disposed) return;
      say(`“${check.id}” was not registered: ${error?.message || error}`, 'error');
    } finally {
      if (!disposed) setRegistering(false);
    }
  }

  async function runRowAction(deploymentId, action) {
    if (!client || rowBusy.has(deploymentId)) return;
    if (action === 'undeploy') {
      const confirmed = doc.defaultView?.confirm?.(
        `Undeploy “${deploymentId}”? This stops it and removes its registration. It can be `
        + 'registered again later under the same id, but this run stops now and cannot be resumed.');
      if (!confirmed) return;
    }
    rowBusy.add(deploymentId);
    renderList();
    try {
      if (action === 'start') await client.startDeployment(deploymentId);
      else if (action === 'stop') await client.stopDeployment(deploymentId);
      else if (action === 'restart') await client.restartDeployment(deploymentId);
      else if (action === 'undeploy') await client.undeployDeployment(deploymentId);
    } catch (error) {
      if (!disposed) say(`${ACTION_LABEL[action]} on “${deploymentId}” failed: ${error?.message || error}`,
        'error');
    } finally {
      rowBusy.delete(deploymentId);
      if (!disposed) await refresh();
    }
  }

  function startPolling() {
    stopPolling();
    if (pollMs > 0) pollHandle = doc.defaultView?.setInterval(() => { void refresh(); }, pollMs) ?? null;
  }

  function stopPolling() {
    if (pollHandle != null) doc.defaultView?.clearInterval(pollHandle);
    pollHandle = null;
  }

  function open() {
    if (!dialog.open) {
      if (typeof dialog.showModal === 'function') dialog.showModal();
      else dialog.setAttribute('open', '');
    }
    say('');
    clearIdError();
    if (idInput && !idInput.value) idInput.value = doc.defaultView?.crypto?.randomUUID?.()
      || `deployment-${Date.now().toString(36)}`;
    idInput?.focus();
    void refresh();
    startPolling();
  }

  function close() {
    stopPolling();
    if (typeof dialog.close === 'function') dialog.close();
    else dialog.removeAttribute('open');
  }

  // ── WIRING ───────────────────────────────────────────────────────────────────────────────────

  const onSubmit = event => {
    event.preventDefault();
    if (registering) return;
    void register();
  };
  const onDialogClick = event => {
    const actionButton = event.target.closest?.('[data-deployment-action]');
    if (actionButton) {
      void runRowAction(actionButton.dataset.deploymentId, actionButton.dataset.deploymentAction);
      return;
    }
    if (event.target.closest?.('#deployment-close')) close();
  };
  const onCancel = () => {};
  // The dialog can close by a route our own `close()` never sees -- Escape reaches `cancel` and,
  // since nothing here calls `preventDefault`, the platform closes the dialog on its own right after.
  // `close` fires for every dismissal (our own `close()`'s `dialog.close()` included), so it -- not
  // the button click -- is the one place that reliably stops the poll no matter how the dialog left.
  const onDialogClose = () => stopPolling();

  form.addEventListener('submit', onSubmit);
  dialog.addEventListener('click', onDialogClick);
  dialog.addEventListener('cancel', onCancel);
  dialog.addEventListener('close', onDialogClose);

  renderList();

  return {
    open,
    close,
    refresh,
    register,
    listing: () => ({ loaded: listing.loaded, deployments: listing.deployments }),
    setClient(next) {
      client = next;
      if (!next) {
        listing = { loaded: false, deployments: [] };
        renderList();
        publish();
        return Promise.resolve();
      }
      return dialog.open ? refresh() : Promise.resolve();
    },
    destroy() {
      disposed = true;
      stopPolling();
      form.removeEventListener('submit', onSubmit);
      dialog.removeEventListener('click', onDialogClick);
      dialog.removeEventListener('cancel', onCancel);
      dialog.removeEventListener('close', onDialogClose);
    },
  };
}
