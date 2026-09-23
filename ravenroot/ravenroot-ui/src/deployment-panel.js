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
// 2. EVERY ROW NAMES ITS SCOPE. `scope` is always `LOCAL_PROCESS` on the wire. A generation means
// the command intent is durable; it still does not claim that this browser owns the runtime.
// 3. UNDEPLOY IS A DISTINCT, CONFIRMED ACTION. It stops a deployment and then removes its
// registration -- the operation that turns "registered and controlled as a local deployment" back
// into nothing. Closing a document never reaches it (see `proceedToCloseDocument` in app.js); it
// exists only as this window's own button, with its own confirmation, because it is the one
// irreversible action this window offers.
// 4. ONE IN-FLIGHT ACTION PER ROW. A row mid-Start/Stop/Restart/Undeploy disables its own buttons
// until that call resolves and a refresh has run, so a second click cannot race the first.

export const DEPLOYMENT_SCOPE_TEXT = 'A deployment registered here runs inside your Ravenroot '
  + 'service process (scope LOCAL_PROCESS). When a generation is shown, lifecycle intent is durable '
  + 'and reconciled by the service; the runtime itself remains process-local and no cluster ownership '
  + 'is claimed. '
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

const ACTION_LABEL = Object.freeze({
  start: 'Start', pause: 'Pause', resume: 'Resume', cancel: 'Cancel admitted work',
  drain: 'Drain', stop: 'Stop', restart: 'Restart', undeploy: 'Undeploy',
});

function advertisedCommands(target, scope) {
  const contract = target?.lifecycleCapabilities;
  if (!contract || contract.contractVersion !== 1 || contract.scope !== scope
      || !Array.isArray(contract.commands)) return [];
  return contract.commands.map(command => ({ ...command, action: command.command.toLowerCase() }));
}

function boundedReason(doc, label, action) {
  const value = doc.defaultView?.prompt?.(`Why should ${label} ${action}?`);
  if (value === null) return null;
  const reason = String(value).trim();
  return reason && reason.length <= 256 ? reason : undefined;
}

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
  dialog, client = null, currentDocument = () => null, onOpenDeployment = async () => {}, pollMs = 2000,
  onDeployments = () => {}, onRegistered = () => {}, onDeploymentSelected = () => {},
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
  const processList = element('lifecycle-process-list');
  const processStatus = element('lifecycle-process-status');
  const processScope = element('lifecycle-process-scope');

  let listing = { loaded: false, deployments: [] };
  let registering = false;
  let disposed = false;
  const rowBusy = new Set();
  let pollHandle = null;
  let selectedDeploymentId = null;
  let selectedProcessId = null;
  let processes = [];
  const processBusy = new Set();

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
      if (entry.deploymentGeneration !== undefined && entry.deploymentGeneration !== null) {
        detail.textContent += ` · generation ${entry.deploymentGeneration}`;
      }

      item.append(head, detail);

      if (entry.diagnostic) {
        const diagnostic = doc.createElement('small');
        diagnostic.className = 'deployment-diagnostic';
        diagnostic.textContent = entry.diagnostic;
        item.append(diagnostic);
      }

      const busy = rowBusy.has(entry.deploymentId);
      const capabilities = advertisedCommands(entry, 'DEPLOYMENT');
      const actionsRow = doc.createElement('div');
      actionsRow.className = 'deployment-item-actions';
      const view = doc.createElement('button');
      view.type = 'button';
      view.className = 'btn deployment-view';
      view.textContent = 'Open read-only view';
      view.dataset.deploymentView = entry.deploymentId;
      view.disabled = busy;
      actionsRow.append(view);
      if (busy) {
        const pending = doc.createElement('small');
        pending.textContent = 'Working…';
        actionsRow.append(pending);
      } else if (capabilities.length === 0) {
        const pending = doc.createElement('small');
        pending.textContent = 'No server-advertised lifecycle operations.';
        if (pending.textContent) actionsRow.append(pending);
      } else {
        for (const capability of capabilities) {
          const action = capability.action;
          const button = doc.createElement('button');
          button.type = 'button';
          button.className = `btn deployment-action${action === 'undeploy' ? ' danger' : ''}`;
          button.textContent = ACTION_LABEL[action];
          button.dataset.deploymentAction = action;
          button.dataset.deploymentId = entry.deploymentId;
          button.disabled = !capability.available;
          if (!capability.available) button.title = capability.unavailableReason;
          actionsRow.append(button);
        }
      }
      item.append(actionsRow);
      const use = doc.createElement('button');
      use.type = 'button';
      use.className = 'btn deployment-context-action';
      use.dataset.deploymentContext = entry.deploymentId;
      use.textContent = 'Use for active graph tasks';
      use.disabled = !entry.graphVersion;
      if (!entry.graphVersion) use.title = 'This service did not provide the deployment graph version';
      item.append(use);
      const operate = doc.createElement('button');
      operate.type = 'button';
      operate.className = 'btn deployment-operational-target';
      operate.dataset.deploymentOperational = entry.deploymentId;
      operate.textContent = selectedDeploymentId === entry.deploymentId
        ? 'Selected operational target' : 'Select deployment and processes';
      operate.setAttribute('aria-pressed', String(selectedDeploymentId === entry.deploymentId));
      item.append(operate);
      return item;
    }));
  }

  function renderProcesses() {
    if (!processList) return;
    processList.hidden = processes.length === 0;
    processList.replaceChildren(...processes.map(entry => {
      const item = doc.createElement('li');
      item.className = 'credential-item lifecycle-process-item';
      item.dataset.processId = entry.processInstanceId;
      const selected = entry.processInstanceId === selectedProcessId;
      const head = doc.createElement('div');
      head.className = 'deployment-item-head';
      const identity = doc.createElement('b');
      identity.textContent = entry.processInstanceId;
      const state = doc.createElement('span');
      state.className = 'deployment-state';
      state.textContent = entry.controlState || entry.status;
      head.append(identity, state);
      const detail = doc.createElement('small');
      detail.textContent = `tenant ${entry.tenantId} · deployment ${entry.deploymentId || 'transient'} · graph ${entry.graphVersion}`
        + ` · revision ${entry.revision} · lifecycle generation ${entry.lifecycleGeneration}`
        + ` · fence ${entry.fencingToken} · recovery ${entry.disposition}`;
      const select = doc.createElement('button');
      select.type = 'button';
      select.className = 'btn process-select';
      select.dataset.processSelect = entry.processInstanceId;
      select.textContent = selected ? 'Selected process' : 'Select process';
      select.setAttribute('aria-pressed', String(selected));
      item.append(head, detail, select);
      if (selected) {
        const actions = doc.createElement('div');
        actions.className = 'deployment-item-actions process-item-actions';
        const capabilities = advertisedCommands(entry, 'PROCESS');
        for (const capability of capabilities) {
          const button = doc.createElement('button');
          button.type = 'button';
          button.className = `btn process-action${['cancel', 'stop'].includes(capability.action) ? ' danger' : ''}`;
          button.dataset.processAction = capability.action;
          button.dataset.processId = entry.processInstanceId;
          button.textContent = ACTION_LABEL[capability.action];
          button.disabled = !capability.available || processBusy.has(entry.processInstanceId);
          if (!capability.available) button.title = capability.unavailableReason;
          actions.append(button);
        }
        if (entry.lifecycleCapabilities?.drainBound) {
          const drain = doc.createElement('small');
          drain.textContent = `Drain closes admission; accepted work settles within ${entry.lifecycleCapabilities.drainBound}.`;
          actions.append(drain);
        }
        item.append(actions);
      }
      return item;
    }));
  }

  function publish() {
    onDeployments({ loaded: listing.loaded, deployments: [...listing.deployments] });
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
      if (selectedDeploymentId && !deployments.some(entry => entry.deploymentId === selectedDeploymentId)) {
        selectedDeploymentId = null;
        selectedProcessId = null;
        processes = [];
        renderProcesses();
      }
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

  async function refreshProcesses() {
    if (!client || !selectedDeploymentId || typeof client.processInventory !== 'function') {
      processes = [];
      renderProcesses();
      return;
    }
    try {
      const rows = [];
      let cursor = null;
      do {
        const page = await client.processInventory({ deploymentId: selectedDeploymentId,
          includeTerminal: true, ...(cursor ? { cursor } : {}) });
        rows.push(...page.items);
        cursor = page.nextCursor;
      } while (cursor);
      processes = rows;
      if (selectedProcessId && !rows.some(item => item.processInstanceId === selectedProcessId)) {
        selectedProcessId = null;
      }
      renderProcesses();
      processStatus.textContent = rows.length
        ? `${rows.length} authoritative process instance${rows.length === 1 ? '' : 's'} for “${selectedDeploymentId}”.`
        : `No durable process instances are recorded for “${selectedDeploymentId}”.`;
    } catch (error) {
      processes = [];
      renderProcesses();
      processStatus.textContent = `Process inventory could not be reconciled: ${error?.message || error}`;
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
      const registered = await client.registerDeployment(check.id, source.graphMl);
      onRegistered(registered, source);
      if (disposed) return;
      if (registered.deploymentGeneration === undefined || registered.deploymentGeneration === null) {
        await client.startDeployment(check.id);
      } else {
        await client.startDeployment(check.id, { expectedGeneration: registered.deploymentGeneration });
      }
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
    const entry = listing.deployments.find(candidate => candidate.deploymentId === deploymentId);
    const durable = entry?.deploymentGeneration !== undefined && entry?.deploymentGeneration !== null;
    const command = { expectedGeneration: entry?.deploymentGeneration };
    const capability = advertisedCommands(entry, 'DEPLOYMENT')
      .find(candidate => candidate.action === action);
    if (!capability?.available) return;
    if (durable && capability.reasonRequired && action !== 'undeploy') {
      const reason = boundedReason(doc, `deployment “${deploymentId}”`, action);
      if (reason === null) return;
      if (!reason) {
        say(`${ACTION_LABEL[action]} was not sent: a bounded reason is required.`, 'error');
        return;
      }
      command.reason = reason;
    }
    if (action === 'undeploy') {
      const warning = durable
        ? `Undeploy “${deploymentId}”? This stops it and permanently retires its durable identity. `
          + 'Its tombstone remains authoritative and the same durable id cannot be reused.'
        : `Undeploy “${deploymentId}”? This stops and removes this process-local registration. `
          + 'The same local id can be registered again later.';
      const confirmed = doc.defaultView?.confirm?.(warning);
      if (!confirmed) return;
      if (durable) {
        const disposition = doc.defaultView?.prompt?.(
          'Choose exactly one undeploy disposition: DRAIN_FIRST, CANCEL_IN_FLIGHT, or REFUSE_IF_BUSY');
        if (disposition === null) return;
        const normalized = String(disposition).trim().toUpperCase();
        if (!['DRAIN_FIRST', 'CANCEL_IN_FLIGHT', 'REFUSE_IF_BUSY'].includes(normalized)) {
          say('Undeploy was not sent: choose a supported disposition explicitly.', 'error');
          return;
        }
        const reason = doc.defaultView?.prompt?.(`Why should “${deploymentId}” be undeployed?`);
        if (reason === null) return;
        if (!String(reason).trim()) {
          say('Undeploy was not sent: a durable undeploy requires a reason.', 'error');
          return;
        }
        command.disposition = normalized;
        command.reason = String(reason).trim();
      }
    }
    rowBusy.add(deploymentId);
    renderList();
    try {
      let result;
      if (action === 'start') result = durable
        ? await client.startDeployment(deploymentId, command) : await client.startDeployment(deploymentId);
      else if (action === 'pause') result = await client.pauseDeployment(deploymentId, command);
      else if (action === 'resume') result = await client.resumeDeployment(deploymentId, command);
      else if (action === 'cancel') result = await client.cancelDeployment(deploymentId, command);
      else if (action === 'drain') result = await client.drainDeployment(deploymentId, command);
      else if (action === 'stop') result = durable
        ? await client.stopDeployment(deploymentId, command) : await client.stopDeployment(deploymentId);
      else if (action === 'restart') result = durable
        ? await client.restartDeployment(deploymentId, command) : await client.restartDeployment(deploymentId);
      else if (action === 'undeploy') result = durable
        ? await client.undeployDeployment(deploymentId, command) : await client.undeployDeployment(deploymentId);
      if (result?.outcome) {
        const outcome = result.outcome.outcome === 'REPLAYED'
          ? `REPLAYED ${result.outcome.original.outcome}` : result.outcome.outcome;
        say(`${ACTION_LABEL[action]} on “${deploymentId}”: ${outcome}.`,
          ['REFUSED', 'FAILED', 'STALE_GENERATION', 'SUPERSEDED', 'IDEMPOTENCY_CONFLICT']
            .includes(result.outcome.outcome) ? 'error' : 'ok');
      } else if (result?.reconciliation?.delivery === 'AMBIGUOUS') {
        const evidence = result.reconciliation.authoritative === 'NOT_FOUND'
          ? 'the deployment is no longer registered'
          : `the deployment is now ${String(result.status?.state || 'present').toLowerCase()}`;
        say(`${ACTION_LABEL[action]} on “${deploymentId}” lost both command responses; `
          + `authoritative reconciliation shows ${evidence}, but the command outcome is unknown.`, 'error');
      }
    } catch (error) {
      if (!disposed) say(`${ACTION_LABEL[action]} on “${deploymentId}” failed: ${error?.message || error}`,
        'error');
    } finally {
      rowBusy.delete(deploymentId);
      if (!disposed) await refresh();
    }
  }

  async function runProcessAction(processInstanceId, action) {
    if (!client || processBusy.has(processInstanceId)) return;
    const entry = processes.find(candidate => candidate.processInstanceId === processInstanceId);
    const capability = advertisedCommands(entry, 'PROCESS').find(candidate => candidate.action === action);
    if (!entry || !capability?.available) return;
    let reason = '';
    if (capability.reasonRequired) {
      const supplied = boundedReason(doc, `process “${processInstanceId}”`, action);
      if (supplied === null) return;
      if (!supplied) {
        processStatus.textContent = `${ACTION_LABEL[action]} was not sent: a bounded reason is required.`;
        return;
      }
      reason = supplied;
    }
    const idempotencyKey = doc.defaultView?.crypto?.randomUUID?.();
    if (!idempotencyKey) {
      processStatus.textContent = 'A secure process command identity could not be created.';
      return;
    }
    processBusy.add(processInstanceId);
    renderProcesses();
    processStatus.textContent = `${ACTION_LABEL[action]} is being reconciled for process “${processInstanceId}”…`;
    try {
      const outcome = await client.controlProcess(processInstanceId, action, entry.revision,
        { idempotencyKey, reason });
      processStatus.textContent = `${ACTION_LABEL[action]}: ${outcome.outcome}. Authoritative state will be read again.`;
    } catch (error) {
      processStatus.textContent = `${ACTION_LABEL[action]} response was refused or ambiguous: ${error?.message || error}. Re-reading authoritative state.`;
    } finally {
      processBusy.delete(processInstanceId);
      await refreshProcesses();
    }
  }

  async function openDeployment(deploymentId) {
    if (!client || rowBusy.has(deploymentId)) return;
    rowBusy.add(deploymentId);
    renderList();
    say(`Opening a read-only live view of “${deploymentId}”…`);
    try {
      await onOpenDeployment(deploymentId, client);
      if (!disposed) {
        say(`“${deploymentId}” is open in a read-only deployment canvas.`, 'ok');
        close();
      }
    } catch (error) {
      if (!disposed) say(`“${deploymentId}” could not be opened: ${error?.message || error}`, 'error');
    } finally {
      rowBusy.delete(deploymentId);
      if (!disposed) renderList();
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
    const viewButton = event.target.closest?.('[data-deployment-view]');
    if (viewButton) {
      void openDeployment(viewButton.dataset.deploymentView);
      return;
    }
    const actionButton = event.target.closest?.('[data-deployment-action]');
    if (actionButton) {
      void runRowAction(actionButton.dataset.deploymentId, actionButton.dataset.deploymentAction);
      return;
    }
    const contextButton = event.target.closest?.('[data-deployment-context]');
    if (contextButton) {
      const selected = listing.deployments.find(entry =>
        entry.deploymentId === contextButton.dataset.deploymentContext);
      if (selected?.graphVersion) {
        onDeploymentSelected(selected);
        say(`“${selected.deploymentId}” is the active graph’s Human Task context.`, 'ok');
        close();
      }
      return;
    }
    const operational = event.target.closest?.('[data-deployment-operational]');
    if (operational) {
      selectedDeploymentId = operational.dataset.deploymentOperational;
      selectedProcessId = null;
      processes = [];
      renderList();
      renderProcesses();
      if (processScope) processScope.textContent = `Deployment “${selectedDeploymentId}” is selected. Its process instances are read from the durable server inventory.`;
      void refreshProcesses();
      return;
    }
    const selectProcess = event.target.closest?.('[data-process-select]');
    if (selectProcess) {
      selectedProcessId = selectProcess.dataset.processSelect;
      renderProcesses();
      return;
    }
    const processAction = event.target.closest?.('[data-process-action]');
    if (processAction) {
      void runProcessAction(processAction.dataset.processId, processAction.dataset.processAction);
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
  renderProcesses();

  return {
    open,
    close,
    refresh,
    refreshProcesses,
    register,
    openDeployment,
    listing: () => ({ loaded: listing.loaded, deployments: listing.deployments }),
    setClient(next) {
      client = next;
      if (!next) {
        listing = { loaded: false, deployments: [] };
        processes = [];
        selectedDeploymentId = null;
        selectedProcessId = null;
        renderList();
        renderProcesses();
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
