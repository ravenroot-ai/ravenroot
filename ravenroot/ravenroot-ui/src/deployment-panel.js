import { uiErrorReason, uiText } from './ui-text.js';
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

export const deploymentScopeText = () => uiText("workspace.deployment.scope");

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

const actionLabel = action => uiText(`workspace.deployment.action.${action}`);

const UNAVAILABLE_REASON = Object.freeze({
  NOT_AUTHORIZED: "workspace.deployment.reason.not.authorized",
  TERMINAL_TARGET: "workspace.deployment.reason.terminal.target",
  INCOMPATIBLE_STATE: "workspace.deployment.reason.incompatible.state",
  DURABLE_AUTHORITY_UNAVAILABLE: "workspace.deployment.reason.durable.authority.unavailable",
});

function advertisedCommands(target, scope) {
  const contract = target?.lifecycleCapabilities;
  if (!contract || contract.contractVersion !== 1 || contract.scope !== scope
      || !Array.isArray(contract.commands)) return [];
  return contract.commands.map(command => ({ ...command, action: command.command.toLowerCase() }));
}

function boundedReason(doc, label, action) {
  const value = doc.defaultView?.prompt?.(uiText("workspace.deployment.panel.why.should", { label: label, action: actionLabel(action) }));
  if (value === null) return null;
  const reason = String(value).trim();
  return reason && reason.length <= 256 ? reason : undefined;
}

function unavailableExplanation(capability) {
  return (UNAVAILABLE_REASON[capability.unavailableReason]
    ? uiText(UNAVAILABLE_REASON[capability.unavailableReason]) : capability.unavailableReason)
    || uiText("workspace.deployment.panel.the.server.did.not.provide.a.reason");
}

function reasonId(scope, targetId, action) {
  return `${scope}-${targetId}-${action}-unavailable`.replace(/[^a-zA-Z0-9_-]/g, '-');
}

function appendUnavailableReason(doc, row, button, capability, scope, targetId) {
  const explanation = doc.createElement('small');
  explanation.id = reasonId(scope, targetId, capability.action);
  explanation.className = 'lifecycle-unavailable-reason';
  explanation.textContent = uiText("workspace.deployment.panel.unavailable", { action: actionLabel(capability.action), capability: unavailableExplanation(capability) });
  button.setAttribute('aria-disabled', 'true');
  button.setAttribute('aria-describedby', explanation.id);
  row.append(button, explanation);
}

function drainExplanation(bound) {
  return bound === 'UNTIL_ACCEPTED_WORK_SETTLES'
    ? uiText("workspace.deployment.panel.drain.closes.new.admission.immediately.work.already.accepted.continues")
    : uiText("workspace.deployment.panel.drain.closes.new.admission.immediately.work.already.accepted.may", { bound: bound });
}

function validateDeploymentId(value) {
  const id = String(value ?? '').trim();
  if (!id) return { ok: false, error: uiText("workspace.deployment.idRequired") };
  if (id.length > 200) return { ok: false, error: uiText("workspace.deployment.idTooLong", { length: id.length }) };
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
  // Keep message recipes, not translated snapshots, for mounted asynchronous status text.
  const localizedText = new Map();
  function paintText(node, value) {
    if (!node) return;
    if (typeof value === 'function') localizedText.set(node, value);
    else localizedText.delete(node);
    node.textContent = typeof value === 'function' ? value() : value;
  }
  function appendText(node, render) {
    const previous = localizedText.get(node) || (() => '');
    paintText(node, () => previous() + render());
  }
  function refreshText() {
    for (const [node, render] of localizedText) {
      if (dialog.contains(node)) node.textContent = render();
      else localizedText.delete(node);
    }
  }

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
  const derivedForm = element('derived-execution-form');
  const derivedSource = element('derived-source-id');
  const derivedBoundaryChoice = element('derived-boundary-choice');
  const derivedDiscoverButton = element('derived-discover');
  const derivedKey = element('derived-idempotency-key');
  const derivedReason = element('derived-reason');
  const derivedDecision = element('derived-repeatability');
  const derivedEffects = element('derived-authorize-effects');
  const derivedPreviewButton = element('derived-preview');
  const derivedStartButton = element('derived-start');
  const derivedStatus = element('derived-execution-status');
  const derivedOutput = element('derived-execution-preview');

  let listing = { loaded: false, deployments: [] };
  let registering = false;
  let disposed = false;
  const rowBusy = new Set();
  let pollHandle = null;
  let selectedDeploymentId = null;
  let selectedProcessId = null;
  let processes = [];
  const processBusy = new Set();
  let derivedPreviewFingerprint = null;
  let derivedGeneration = 0;
  let derivedBusy = false;
  let derivedBoundaryOptions = [];

  function freshDerivedKey() {
    if (!derivedKey || derivedKey.value.trim()) return;
    derivedKey.value = globalThis.crypto?.randomUUID?.()
      || `derived-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  }

  function derivedRequest() {
    const selectedBoundary = derivedBoundaryChoice?.value ?? '';
    const choice = selectedBoundary === '' ? null : derivedBoundaryOptions[Number(selectedBoundary)];
    const request = {
      boundaries: [{ nodeId: choice?.nodeId || '',
        predecessorInvocationIds: choice ? [choice.predecessorInvocationId] : [] }],
      idempotencyKey: derivedKey?.value.trim(), reason: derivedReason?.value.trim(),
      repeatabilityDecision: derivedDecision?.value.trim() || '',
      authorizeExternalEffects: Boolean(derivedEffects?.checked),
    };
    if (!derivedSource?.value.trim() || !request.boundaries[0].nodeId
        || !request.boundaries[0].predecessorInvocationIds[0]
        || !request.idempotencyKey || !request.reason) {
      throw new Error('Source, boundary, predecessor, idempotency key, and reason are required.');
    }
    return request;
  }

  function invalidateDerivedPreview(event) {
    derivedGeneration += 1;
    derivedPreviewFingerprint = null;
    if (derivedStartButton) derivedStartButton.disabled = true;
    if (event?.target === derivedSource) {
      derivedBoundaryOptions = [];
      derivedBoundaryChoice.replaceChildren();
      const option = doc.createElement('option');
      option.value = '';
      paintText(option, () => uiText("workspace.deployment.panel.discover.retained.boundaries.for.this.source"));
      derivedBoundaryChoice.append(option);
    }
  }

  async function discoverDerivedBoundaries() {
    if (derivedBusy) return;
    const source = derivedSource?.value.trim();
    if (!source) {
      paintText(derivedStatus, () => uiText("workspace.deployment.panel.select.or.enter.a.source.process.before"));
      return;
    }
    const generation = ++derivedGeneration;
    derivedBusy = true;
    derivedDiscoverButton.disabled = true;
    derivedPreviewButton.disabled = true;
    derivedStartButton.disabled = true;
    paintText(derivedStatus, () => uiText("workspace.deployment.panel.reading.retained.positively.settled.boundary.evidence"));
    try {
      const boundaries = await client.derivedExecutionBoundaries(source);
      if (disposed || generation !== derivedGeneration) return;
      derivedBoundaryOptions = boundaries;
      derivedBoundaryChoice.replaceChildren();
      const placeholder = doc.createElement('option');
      placeholder.value = '';
      paintText(placeholder, () => boundaries.length
        ? uiText("workspace.deployment.panel.choose.a.retained.boundary") : uiText("workspace.deployment.panel.no.retained.downstream.boundaries.are.available"));
      derivedBoundaryChoice.append(placeholder);
      boundaries.forEach((boundary, index) => {
        const option = doc.createElement('option');
        option.value = String(index);
        paintText(option, () => uiText("workspace.deployment.panel.text", { predecessorNodeId: boundary.predecessorNodeId, nodeId: boundary.nodeId, outcome: boundary.outcome }));
        derivedBoundaryChoice.append(option);
      });
      paintText(derivedStatus, () => boundaries.length
        ? uiText("workspace.deployment.panel.value1.retained.boundary.choice.value2.available", { count: boundaries.length })
        : uiText("workspace.deployment.panel.no.retained.downstream.boundary.can.be.selected"));
    } catch (error) {
      if (generation !== derivedGeneration) return;
      derivedBoundaryOptions = [];
      paintText(derivedStatus, () => uiText("workspace.deployment.panel.boundary.discovery.failed", { reason: uiErrorReason(error) }));
    } finally {
      derivedBusy = false;
      if (generation === derivedGeneration) {
        derivedDiscoverButton.disabled = false;
        derivedPreviewButton.disabled = false;
      } else {
        derivedDiscoverButton.disabled = false;
        derivedPreviewButton.disabled = false;
      }
    }
  }

  function renderDerivedBoundaryChoices() {
    if (!derivedBoundaryChoice) return;
    const selected = derivedBoundaryChoice.value;
    derivedBoundaryChoice.replaceChildren();
    const placeholder = doc.createElement('option');
    placeholder.value = '';
    paintText(placeholder, () => derivedBoundaryOptions.length
      ? uiText('workspace.deployment.panel.choose.a.retained.boundary')
      : uiText('workspace.deployment.panel.no.retained.downstream.boundaries.are.available'));
    derivedBoundaryChoice.append(placeholder);
    derivedBoundaryOptions.forEach((boundary, index) => {
      const option = doc.createElement('option');
      option.value = String(index);
      paintText(option, () => uiText('workspace.deployment.panel.text', {
        predecessorNodeId: boundary.predecessorNodeId, nodeId: boundary.nodeId, outcome: boundary.outcome,
      }));
      derivedBoundaryChoice.append(option);
    });
    if ([...derivedBoundaryChoice.options].some(option => option.value === selected)) {
      derivedBoundaryChoice.value = selected;
    }
  }

  async function previewDerived() {
    if (derivedBusy) return;
    try {
      freshDerivedKey();
      const request = derivedRequest();
      const source = derivedSource.value.trim();
      const fingerprint = JSON.stringify([source, request]);
      const generation = derivedGeneration;
      derivedBusy = true;
      derivedPreviewButton.disabled = true;
      derivedDiscoverButton.disabled = true;
      paintText(derivedStatus, () => uiText("workspace.deployment.panel.checking.retained.evidence.pins.causal.closure.and"));
      const preview = await client.previewDerivedExecution(source, request);
      if (generation !== derivedGeneration || fingerprint !== JSON.stringify([source, derivedRequest()])) return;
      const lines = () => [preview.admissible ? uiText("workspace.deployment.panel.ready.to.submit") : uiText("workspace.deployment.panel.submission.is.refused"),
        uiText("workspace.deployment.refusals", { codes: preview.refusalCodes.join(', ') || uiText("workspace.common.none") }),
        uiText("workspace.deployment.panel.inherited.invocation.evidence", { value: preview.inheritedInvocationIds.join(', ') || uiText("workspace.common.none") }),
        uiText("workspace.deployment.panel.possible.downstream.scope", { value: preview.possibleScopeNodeIds.join(', ') || uiText("workspace.common.none") }),
        uiText("workspace.deployment.panel.missing.inputs", { value: preview.missingInputs.join(', ') || uiText("workspace.common.none") }),
        uiText("workspace.deployment.panel.external.effect.nodes", { value: preview.externalEffectNodes.join(', ') || uiText("workspace.common.none") }),
        uiText("workspace.deployment.panel.source.outcome.ambiguous", { value: preview.sourceOutcomeAmbiguous ? uiText("workspace.common.yes") : uiText("workspace.common.no") }),
        uiText("workspace.deployment.panel.graph.pin", { value: preview.graphContentId || uiText("workspace.common.unavailable") }),
        uiText("workspace.deployment.panel.manifest.pin", { value: preview.manifestDigest || uiText("workspace.common.unavailable") })];
      paintText(derivedOutput, () => lines().join('\n'));
      derivedOutput.hidden = false;
      paintText(derivedStatus, () => preview.admissible
        ? uiText("workspace.deployment.panel.preview.is.admissible.review.the.possible.scope")
        : uiText("workspace.deployment.panel.preview.refused.correct.the.named.evidence.or"));
      derivedPreviewFingerprint = preview.admissible ? fingerprint : null;
      derivedStartButton.disabled = !preview.admissible;
    } catch (error) {
      invalidateDerivedPreview();
      paintText(derivedStatus, () => uiText("workspace.deployment.panel.preview.failed", { reason: uiErrorReason(error) }));
    } finally {
      derivedBusy = false;
      if (derivedPreviewButton) derivedPreviewButton.disabled = false;
      if (derivedDiscoverButton) derivedDiscoverButton.disabled = false;
    }
  }

  async function startDerived(event) {
    event.preventDefault();
    if (derivedBusy) return;
    try {
      const request = derivedRequest();
      const source = derivedSource.value.trim();
      if (derivedPreviewFingerprint !== JSON.stringify([source, request])) {
        throw new Error('Preview this exact request again before starting.');
      }
      derivedStartButton.disabled = true;
      derivedBusy = true;
      derivedPreviewButton.disabled = true;
      derivedDiscoverButton.disabled = true;
      paintText(derivedStatus, () => uiText("workspace.deployment.panel.admitting.the.fresh.derived.execution"));
      const started = await client.startDerivedExecution(source, request);
      paintText(derivedStatus, () => uiText("workspace.deployment.panel.derived.process.started.at.traversal", { processInstanceId: started.processInstanceId, traversalId: started.traversalId }));
      invalidateDerivedPreview();
      await refreshProcesses();
    } catch (error) {
      paintText(derivedStatus, () => uiText("workspace.deployment.panel.derived.execution.was.not.started", { reason: uiErrorReason(error) }));
      invalidateDerivedPreview();
    } finally {
      derivedBusy = false;
      derivedPreviewButton.disabled = false;
      derivedDiscoverButton.disabled = false;
    }
  }

  if (scope) paintText(scope, deploymentScopeText());

  function say(message, kind = 'info') {
    paintText(status, message);
    status.dataset.state = kind;
  }

  function clearIdError() {
    idInput?.removeAttribute('aria-invalid');
    idInput?.removeAttribute('aria-errormessage');
    if (idError) {
      paintText(idError, '');
      idError.hidden = true;
    }
  }

  function showIdError(message) {
    if (!idError || !idInput) return;
    paintText(idError, message);
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
    refreshText();
    const deployments = listing.deployments || [];
    if (empty) {
      empty.hidden = deployments.length > 0;
      paintText(empty, () => listing.loaded
        ? uiText("workspace.deployment.panel.you.hold.no.deployments.yet.register.one")
        : uiText("workspace.deployment.panel.connect.to.your.ravenroot.service.to.see"));
    }
    list.hidden = deployments.length === 0;
    list.replaceChildren(...deployments.map(entry => {
      const item = doc.createElement('li');
      item.className = 'credential-item deployment-item';
      item.dataset.deploymentId = entry.deploymentId;

      const head = doc.createElement('div');
      head.className = 'deployment-item-head';
      const name = doc.createElement('b');
      paintText(name, entry.deploymentId);
      const state = doc.createElement('span');
      state.className = `deployment-state deployment-state-${entry.state.toLowerCase()}`;
      paintText(state, stateLabel(entry.state));
      head.append(name, state);

      const detail = doc.createElement('small');
      const sourceText = entry.sourceCount > 0
        ? uiText('workspace.deployment.inboundSources', { count: entry.sourceCount })
        : uiText('workspace.deployment.noInboundSources');
      paintText(detail, () => uiText("workspace.deployment.panel.tenant.value1.graph.value2", { tenantId: entry.tenantId, unavailable: entry.graphVersion || uiText('common.unavailable') })
        + uiText("workspace.deployment.panel.value1.scope.value2.continuity.value3", { sourceText: sourceText, scope: entry.scope, continuity: entry.continuity }));
      if (entry.deploymentGeneration !== undefined && entry.deploymentGeneration !== null) {
        appendText(detail, () => uiText("workspace.deployment.panel.generation", { deploymentGeneration: entry.deploymentGeneration }));
      }
      if (entry.deploymentRevision !== null) appendText(detail, () => uiText("workspace.deployment.panel.registry.revision", { deploymentRevision: entry.deploymentRevision }));
      if (entry.desiredState !== null || entry.observedState !== null) {
        appendText(detail, () => uiText("workspace.deployment.panel.reconciliation.desired.observed", { unknown: entry.desiredState || uiText('common.unknown'), unknown2: entry.observedState || uiText('common.unknown') }));
      }
      if (entry.recoveryFailure) appendText(detail, () => uiText("workspace.deployment.panel.recovery.failure", { recoveryFailure: entry.recoveryFailure }));

      item.append(head, detail);

      if (entry.diagnostic) {
        const diagnostic = doc.createElement('small');
        diagnostic.className = 'deployment-diagnostic';
        paintText(diagnostic, entry.diagnostic);
        item.append(diagnostic);
      }

      const busy = rowBusy.has(entry.deploymentId);
      const capabilities = advertisedCommands(entry, 'DEPLOYMENT');
      const actionsRow = doc.createElement('div');
      actionsRow.className = 'deployment-item-actions';
      const view = doc.createElement('button');
      view.type = 'button';
      view.className = 'btn deployment-view';
      paintText(view, () => uiText("workspace.deployment.panel.open.read.only.view"));
      view.dataset.deploymentView = entry.deploymentId;
      view.disabled = busy;
      actionsRow.append(view);
      if (busy) {
        const pending = doc.createElement('small');
        paintText(pending, () => uiText("workspace.deployment.panel.working"));
        actionsRow.append(pending);
      } else if (capabilities.length === 0) {
        const pending = doc.createElement('small');
        paintText(pending, () => uiText("workspace.deployment.panel.no.server.advertised.lifecycle.operations"));
        if (pending.textContent) actionsRow.append(pending);
      } else {
        for (const capability of capabilities) {
          const action = capability.action;
          const button = doc.createElement('button');
          button.type = 'button';
          button.className = `btn deployment-action${action === 'undeploy' ? ' danger' : ''}`;
          paintText(button, actionLabel(action));
          button.dataset.deploymentAction = action;
          button.dataset.deploymentId = entry.deploymentId;
          if (capability.available) actionsRow.append(button);
          else appendUnavailableReason(doc, actionsRow, button, capability, 'deployment', entry.deploymentId);
        }
      }
      if (entry.lifecycleCapabilities?.drainBound) {
        const drain = doc.createElement('small');
        drain.className = 'lifecycle-drain-bound';
        paintText(drain, drainExplanation(entry.lifecycleCapabilities.drainBound));
        actionsRow.append(drain);
      }
      item.append(actionsRow);
      const use = doc.createElement('button');
      use.type = 'button';
      use.className = 'btn deployment-context-action';
      use.dataset.deploymentContext = entry.deploymentId;
      paintText(use, () => uiText("workspace.deployment.panel.use.for.active.graph.tasks"));
      use.disabled = !entry.graphVersion;
      if (!entry.graphVersion) use.title = uiText("workspace.deployment.panel.this.service.did.not.provide.the.deployment");
      item.append(use);
      const operate = doc.createElement('button');
      operate.type = 'button';
      operate.className = 'btn deployment-operational-target';
      operate.dataset.deploymentOperational = entry.deploymentId;
      paintText(operate, () => selectedDeploymentId === entry.deploymentId
        ? uiText("workspace.deployment.panel.selected.operational.target") : uiText("workspace.deployment.panel.select.deployment.and.processes"));
      operate.setAttribute('aria-pressed', String(selectedDeploymentId === entry.deploymentId));
      item.append(operate);
      return item;
    }));
  }

  function renderProcesses() {
    refreshText();
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
      paintText(identity, entry.processInstanceId);
      const state = doc.createElement('span');
      state.className = 'deployment-state';
      paintText(state, () => uiText("workspace.deployment.panel.status", { status: entry.status }));
      head.append(identity, state);
      const detail = doc.createElement('small');
      paintText(detail, () => uiText("workspace.deployment.panel.tenant.value1.deployment.value2.graph.value3", { tenantId: entry.tenantId, transient: entry.deploymentId || uiText('common.transient'), graphVersion: entry.graphVersion })
        + uiText("workspace.deployment.panel.revision.value1.lifecycle.generation.value2", { revision: entry.revision, lifecycleGeneration: entry.lifecycleGeneration })
        + uiText("workspace.deployment.panel.fence.value1.recovery.value2", { fencingToken: entry.fencingToken, disposition: entry.disposition })
        + uiText("workspace.deployment.panel.control.value1", { unavailable: entry.controlState || uiText('common.unavailable') }));
      if (entry.terminationReason) appendText(detail, () => uiText("workspace.deployment.panel.terminal.reason", { reason: entry.terminationReason }));
      if (entry.cancelled) appendText(detail, () => uiText("workspace.deployment.panel.cancellation.recorded"));
      const select = doc.createElement('button');
      select.type = 'button';
      select.className = 'btn process-select';
      select.dataset.processSelect = entry.processInstanceId;
      paintText(select, () => selected ? uiText("workspace.deployment.panel.selected.process") : uiText("workspace.deployment.panel.select.process"));
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
          paintText(button, actionLabel(capability.action));
          button.disabled = processBusy.has(entry.processInstanceId);
          if (capability.available) actions.append(button);
          else appendUnavailableReason(doc, actions, button, capability, 'process', entry.processInstanceId);
        }
        if (entry.lifecycleCapabilities?.drainBound) {
          const drain = doc.createElement('small');
          drain.className = 'lifecycle-drain-bound';
          paintText(drain, drainExplanation(entry.lifecycleCapabilities.drainBound));
          actions.append(drain);
        }
        item.append(actions);
        if (entry.sagaError) {
          const diagnostic = doc.createElement('small');
          diagnostic.className = 'deployment-diagnostic';
          paintText(diagnostic, () => uiText("workspace.deployment.panel.saga.status.unavailable", { reason: entry.sagaError }));
          item.append(diagnostic);
        } else if (Array.isArray(entry.sagas)) {
          const sagas = doc.createElement('ul');
          sagas.className = 'lifecycle-saga-list';
          if (entry.sagas.length === 0) {
            const none = doc.createElement('li');
            paintText(none, () => uiText("workspace.deployment.panel.no.saga.scope.has.entered.this.process"));
            sagas.append(none);
          }
          for (const saga of entry.sagas) {
            const row = doc.createElement('li');
            const headline = doc.createElement('b');
            paintText(headline, () => uiText("workspace.deployment.panel.text.2", { scope: saga.scope, disposition: saga.disposition }));
            const detail = doc.createElement('small');
            const steps = Array.isArray(saga.steps)
              ? saga.steps.map(step => `${step.stepId}: ${step.status}`).join(' · ') : '';
            paintText(detail, () => uiText("workspace.deployment.panel.revision.value1", { revision: saga.revision })
              + `${saga.actionableReason ? ` · ${saga.actionableReason}` : ''}`
              + `${steps ? ` · ${steps}` : ''}`);
            row.append(headline, detail);
            sagas.append(row);
          }
          item.append(sagas);
          if (Array.isArray(entry.sagaOutbox) && entry.sagaOutbox.length > 0) {
            const outbox = doc.createElement('ul');
            outbox.className = 'lifecycle-saga-outbox';
            for (const command of entry.sagaOutbox) {
              const row = doc.createElement('li');
              paintText(row, () => `${command.commandType} · ${command.status}`
                + uiText("workspace.deployment.panel.attempts.value1.value2", { attempts: command.attempts, maxAttempts: command.maxAttempts })
                + `${command.lastFailure ? ` · ${command.lastFailure}` : ''}`);
              outbox.append(row);
            }
            item.append(outbox);
          }
        }
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
      say(() => uiText("workspace.deployment.panel.connect.to.your.ravenroot.service.to.see.and.register"));
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
      say(() => uiText("workspace.deployment.panel.the.deployments.you.hold.could.not.be.read", { reason: uiErrorReason(error) }), 'error');
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
      const previous = new Map(processes.map(entry => [entry.processInstanceId, entry]));
      processes = rows.map(entry => ({ ...entry,
        ...(previous.get(entry.processInstanceId)?.sagas
          ? { sagas: previous.get(entry.processInstanceId).sagas,
            sagaOutbox: previous.get(entry.processInstanceId).sagaOutbox } : {}) }));
      if (selectedProcessId && !rows.some(item => item.processInstanceId === selectedProcessId)) {
        selectedProcessId = null;
      }
      renderProcesses();
      paintText(processStatus, () => rows.length
        ? uiText("workspace.deployment.panel.value1.authoritative.process.instance.value2.for.value3", { count: rows.length, selectedDeploymentId })
        : uiText("workspace.deployment.panel.no.durable.process.instances.are.recorded.for", { selectedDeploymentId: selectedDeploymentId }));
      if (selectedProcessId) void refreshSagas(selectedProcessId);
    } catch (error) {
      processes = [];
      renderProcesses();
      paintText(processStatus, () => uiText("workspace.deployment.panel.process.inventory.could.not.be.reconciled", { reason: uiErrorReason(error) }));
    }
  }

  async function refreshSagas(processInstanceId) {
    if (!client || typeof client.processInstanceSagas !== 'function') return;
    try {
      const result = await client.processInstanceSagas(processInstanceId);
      if (disposed || selectedProcessId !== processInstanceId) return;
      processes = processes.map(entry => entry.processInstanceId === processInstanceId
        ? { ...entry, sagas: result.sagas, sagaOutbox: result.outbox, sagaError: null } : entry);
    } catch (error) {
      if (disposed || selectedProcessId !== processInstanceId) return;
      processes = processes.map(entry => entry.processInstanceId === processInstanceId
        ? { ...entry, sagaError: error?.message || String(error) } : entry);
    }
    renderProcesses();
  }

  async function register() {
    const check = validateDeploymentId(idInput?.value);
    if (!check.ok) {
      showIdError(check.error);
      return;
    }
    clearIdError();
    if (!client) {
      say(() => uiText("workspace.deployment.panel.there.is.no.connection.to.your.service.so.nothing"), 'error');
      return;
    }
    const source = currentDocument();
    if (!source) {
      say(() => uiText("workspace.deployment.panel.open.or.create.a.graph.first.register.and.start"),
        'error');
      return;
    }
    setRegistering(true);
    say(() => uiText("workspace.deployment.panel.registering.from", { id: check.id, displayName: source.displayName }));
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
      say(() => uiText("workspace.deployment.panel.is.registered.and.starting.it.appears.below.once.the", { id: check.id }), 'ok');
      if (idInput) idInput.value = '';
      await refresh();
    } catch (error) {
      if (disposed) return;
      say(() => uiText("workspace.deployment.panel.was.not.registered", { id: check.id, reason: uiErrorReason(error) }), 'error');
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
      const reason = boundedReason(doc, uiText("workspace.deployment.panel.deployment", { deploymentId: deploymentId }), action);
      if (reason === null) return;
      if (!reason) {
        say(() => uiText("workspace.deployment.panel.was.not.sent.a.bounded.reason.is", {
          action: actionLabel(action),
        }), 'error');
        return;
      }
      command.reason = reason;
    }
    if (action === 'undeploy') {
      const warning = uiText(durable
        ? "workspace.deployment.undeployDurable.confirm"
        : "workspace.deployment.undeployLocal.confirm", { deploymentId });
      const confirmed = doc.defaultView?.confirm?.(warning);
      if (!confirmed) return;
      if (durable) {
        const disposition = doc.defaultView?.prompt?.(
          uiText("workspace.deployment.panel.choose.exactly.one.undeploy.disposition.drain.first.cancel.in"));
        if (disposition === null) return;
        const normalized = String(disposition).trim().toUpperCase();
        if (!['DRAIN_FIRST', 'CANCEL_IN_FLIGHT', 'REFUSE_IF_BUSY'].includes(normalized)) {
          say(() => uiText("workspace.deployment.panel.undeploy.was.not.sent.choose.a.supported.disposition.explicitly"), 'error');
          return;
        }
        const reason = doc.defaultView?.prompt?.(uiText("workspace.deployment.panel.why.should.be.undeployed", { deploymentId: deploymentId }));
        if (reason === null) return;
        if (!String(reason).trim()) {
          say(() => uiText("workspace.deployment.panel.undeploy.was.not.sent.a.durable.undeploy.requires.a"), 'error');
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
          ? uiText("workspace.deployment.panel.replayed", { outcome: result.outcome.original.outcome }) : result.outcome.outcome;
        say(() => uiText("workspace.deployment.panel.on", { value: actionLabel(action), deploymentId: deploymentId, outcome: outcome }),
          ['REFUSED', 'FAILED', 'STALE_GENERATION', 'SUPERSEDED', 'IDEMPOTENCY_CONFLICT']
            .includes(result.outcome.outcome) ? 'error' : 'ok');
      } else if (result?.reconciliation?.delivery === 'AMBIGUOUS') {
        const evidence = result.reconciliation.authoritative === 'NOT_FOUND'
          ? uiText("workspace.deployment.panel.the.deployment.is.no.longer.registered")
          : uiText("workspace.deployment.panel.the.deployment.is.now", { value: String(result.status?.state || 'present').toLowerCase() });
        say(() => uiText("workspace.deployment.panel.on.lost.both.command.responses.authoritative.reconciliation.shows.but", { value: actionLabel(action), deploymentId: deploymentId, evidence: evidence }), 'error');
      }
    } catch (error) {
      if (!disposed) say(() => uiText("workspace.deployment.panel.on.failed", { value: actionLabel(action), deploymentId: deploymentId, reason: uiErrorReason(error) }),
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
      const supplied = boundedReason(doc, uiText("workspace.deployment.panel.process", { processInstanceId: processInstanceId }), action);
      if (supplied === null) return;
      if (!supplied) {
        paintText(processStatus, () => uiText("workspace.deployment.panel.was.not.sent.a.bounded.reason.is", { action: actionLabel(action) }));
        return;
      }
      reason = supplied;
    }
    const idempotencyKey = doc.defaultView?.crypto?.randomUUID?.();
    if (!idempotencyKey) {
      paintText(processStatus, () => uiText("workspace.deployment.panel.a.secure.process.command.identity.could.not"));
      return;
    }
    processBusy.add(processInstanceId);
    renderProcesses();
    paintText(processStatus, () => uiText("workspace.deployment.panel.is.being.reconciled.for.process", { action: actionLabel(action), processInstanceId: processInstanceId }));
    try {
      const outcome = await client.controlProcess(processInstanceId, action, entry.revision,
        { idempotencyKey, reason });
      paintText(processStatus, () => uiText("workspace.deployment.panel.authoritative.state.will.be.read.again", { action: actionLabel(action), outcome: outcome.outcome }));
    } catch (error) {
      paintText(processStatus, () => uiText("workspace.deployment.panel.response.was.refused.or.ambiguous.re.reading", { action: actionLabel(action), reason: uiErrorReason(error) }));
    } finally {
      processBusy.delete(processInstanceId);
      await refreshProcesses();
    }
  }

  async function openDeployment(deploymentId) {
    if (!client || rowBusy.has(deploymentId)) return;
    rowBusy.add(deploymentId);
    renderList();
    say(() => uiText("workspace.deployment.panel.opening.a.read.only.live.view.of", { deploymentId: deploymentId }));
    try {
      await onOpenDeployment(deploymentId, client);
      if (!disposed) {
        say(() => uiText("workspace.deployment.panel.is.open.in.a.read.only.deployment.canvas", { deploymentId: deploymentId }), 'ok');
        close();
      }
    } catch (error) {
      if (!disposed) say(() => uiText("workspace.deployment.panel.could.not.be.opened", { deploymentId: deploymentId, reason: uiErrorReason(error) }), 'error');
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
        say(() => uiText("workspace.deployment.panel.is.the.active.graph.s.human.task.context", { deploymentId: selected.deploymentId }), 'ok');
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
      if (processScope) paintText(processScope, () => uiText("workspace.deployment.panel.deployment.is.selected.its.process.instances.are", { selectedDeploymentId: selectedDeploymentId }));
      void refreshProcesses();
      return;
    }
    const selectProcess = event.target.closest?.('[data-process-select]');
    if (selectProcess) {
      selectedProcessId = selectProcess.dataset.processSelect;
      if (derivedSource) derivedSource.value = selectedProcessId;
      if (derivedKey) derivedKey.value = '';
      freshDerivedKey();
      invalidateDerivedPreview({ target: derivedSource });
      renderProcesses();
      void refreshSagas(selectedProcessId);
      void discoverDerivedBoundaries();
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
  derivedForm?.addEventListener('submit', startDerived);
  derivedPreviewButton?.addEventListener('click', previewDerived);
  derivedDiscoverButton?.addEventListener('click', discoverDerivedBoundaries);
  derivedForm?.addEventListener('input', invalidateDerivedPreview);
  dialog.addEventListener('click', onDialogClick);
  dialog.addEventListener('cancel', onCancel);
  dialog.addEventListener('close', onDialogClose);

  renderList();
  renderProcesses();
  freshDerivedKey();

  return {
    open,
    close,
    refresh,
    refreshProcesses,
    register,
    openDeployment,
    listing: () => ({ loaded: listing.loaded, deployments: listing.deployments }),
    refreshLocale() { refreshText(); renderList(); renderProcesses(); renderDerivedBoundaryChoices();
      if (idInput?.getAttribute('aria-invalid') === 'true') {
        paintText(idError, validateDeploymentId(idInput.value).error || '');
      }
    },
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
      localizedText.clear();
      disposed = true;
      stopPolling();
      form.removeEventListener('submit', onSubmit);
      derivedForm?.removeEventListener('submit', startDerived);
      derivedPreviewButton?.removeEventListener('click', previewDerived);
      derivedDiscoverButton?.removeEventListener('click', discoverDerivedBoundaries);
      derivedForm?.removeEventListener('input', invalidateDerivedPreview);
      dialog.removeEventListener('click', onDialogClick);
      dialog.removeEventListener('cancel', onCancel);
      dialog.removeEventListener('close', onDialogClose);
    },
  };
}
