import { productError } from './product-error.js';
import { uiErrorReason, uiText } from './ui-text.js';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const TERMINAL = new Set(['COMPLETED', 'CANCELLED', 'DEADLINE_EXCEEDED']);

/** Tenant-generation-safe runner workbench. Untrusted instructions and artifacts are text only. */
export function createRunnerWindow({ dialog }) {
  const doc = dialog.ownerDocument;
  // Keep message recipes, not translated snapshots, for mounted asynchronous status text.
  const localizedText = new Map();
  function paintText(node, value) {
    if (!node) return;
    if (typeof value === 'function') localizedText.set(node, value);
    else localizedText.delete(node);
    node.textContent = typeof value === 'function' ? value() : value;
  }
  function refreshText() {
    for (const [node, render] of localizedText) {
      if (dialog.contains(node)) node.textContent = render();
      else localizedText.delete(node);
    }
  }

  let client = null, generation = 0;
  let auditOffset = 0, healthCursor = null;
  const element = (name, text = '') => { const node = doc.createElement(name); paintText(node, text); return node; };
  const heading = element('h2', () => uiText("workspace.runner.panel.workspaces.named.agents.and.runners")); heading.id = 'runner-title';
  const status = element('p', () => uiText("workspace.runner.panel.connect.to.a.service.to.inspect.governed"));
  status.setAttribute('role', 'status'); status.setAttribute('aria-live', 'polite');
  const catalog = element('div');
  const editorLabel = element('label', () => uiText("workspace.runner.panel.versioned.definition.or.runner.profile.json"));
  const editor = element('textarea'); editor.id = 'runner-resource-editor'; editor.rows = 14; editor.maxLength = 1_048_576;
  editorLabel.htmlFor = editor.id;
  const processLabel = element('label', () => uiText("workspace.runner.panel.process.instance.id"));
  const process = element('input'); process.id = 'runner-process-id'; processLabel.htmlFor = process.id;
  const jobs = element('div'); const evidence = element('pre');
  const operations = element('pre'); operations.setAttribute('aria-label', uiText("workspace.runner.panel.runner.health.and.audit.page"));
  evidence.setAttribute('aria-label', uiText("workspace.runner.panel.bounded.artifact.preview"));
  const button = (text, action) => {
    const node = element('button', text); node.type = 'button'; node.className = 'btn';
    node.addEventListener('click', action); return node;
  };
  const request = async action => {
    const owner = client, version = generation;
    if (!owner) { paintText(status, () => uiText("workspace.runner.panel.connect.to.a.service.before.using.the")); return; }
    paintText(status, () => uiText("workspace.runner.panel.loading"));
    try {
      const render = await action(owner);
      if (version !== generation || owner !== client) return;
      render?.(); paintText(status, () => uiText("workspace.runner.panel.updated.from.the.authenticated.tenant.control.plane"));
    } catch (error) {
      if (version === generation && owner === client) paintText(status, () => uiErrorReason(error) || uiText("workspace.runner.panel.runner.request.failed"));
    }
  };
  const refresh = () => request(async owner => {
    const result = await owner.runnerCatalog();
    if (!Array.isArray(result?.items) || result.items.length > 256) throw productError("workspace.error.runner.panel.unsupported.runner.catalogue", new Error('Unsupported runner catalogue'));
    return () => {
      catalog.replaceChildren(); refreshText();
      for (const resource of result.items) {
        const row = element('p');
        row.append(button(() => resource.kind + ' · ' + resource.name + ' v' + resource.version
          + ' · ' + (resource.approved ? uiText("workspace.runner.panel.approved") : uiText("workspace.runner.panel.draft.retired")), () => request(async selectedClient => {
          const selected = await selectedClient.runnerResource(resource.kind + ':' + resource.name + ':' + resource.version);
          return () => { editor.value = JSON.stringify({ ...selected, expectedRevision: selected.revision }, null, 2); };
        })));
        row.append(element('span', () => uiText("workspace.runner.panel.revision") + resource.revision + ' · ' + resource.actor + ' · ' + resource.updatedAt));
        catalog.append(row);
      }
    };
  });
  const inspect = () => request(async owner => {
    const id = process.value.trim();
    if (!UUID.test(id)) throw productError('workspace.runner.validation.process', new Error("Enter an exact process instance UUID."));
    const workspace = await owner.runnerWorkspace(id);
    if (!Array.isArray(workspace?.jobs)) throw productError("workspace.error.runner.panel.unsupported.runner.workspace", new Error('Unsupported runner workspace'));
    return () => {
      refreshText();
      jobs.replaceChildren(element('h3', () => uiText("workspace.runner.panel.process.workspaces")));
      for (const resource of workspace.workspaces || []) {
        const section = element('section');
        section.append(element('h4', resource.nodeId + ' · ' + resource.state),
          element('pre', JSON.stringify({ workspaceId: resource.workspaceId, runtimeId: resource.runtimeId,
            workspaceScope: resource.profile?.workspaceScope, runtimeLifecycle: resource.profile?.runtimeLifecycle,
            runnerPool: resource.profile?.runnerPool, runnerId: resource.runnerId, driver: resource.driver,
            kubernetes: resource.kubernetes,
            ownershipGeneration: resource.ownershipGeneration, checkpoint: resource.checkpoint,
            capacity: resource.profile?.capacity, fleetLimits: resource.profile?.fleetLimits,
            cpuMillicores: resource.profile?.cpuMillicores, stopRequested: resource.stopRequested }, null, 2)));
        if (!resource.stopRequested && !['CLOSED', 'ABORTED', 'FAILED', 'RELEASING', 'RELEASED'].includes(resource.state)) {
          section.append(button(() => uiText("workspace.runner.panel.stop.this.workspace"), () => request(async current => {
            await current.stopRunnerWorkspace(id, resource.nodeId, workspace.revision);
            return () => { void inspect(); };
          })));
        }
        jobs.append(section);
      }
      for (const job of workspace.jobs) {
        const section = element('section');
        section.append(element('h4', () => job.command + ' · ' + job.state + uiText("workspace.runner.panel.fence") + job.fence),
          element('p', () => job.definition + ' v' + job.definitionVersion + ' · ' + (job.readOnly ? uiText("workspace.runner.panel.read.only") : uiText("workspace.runner.panel.governed.write.authority"))));
        if (job.kubernetes) section.append(element('p', () => uiText("workspace.runner.panel.kubernetes") + job.kubernetes.cluster + ' / '
          + job.kubernetes.namespace + ' · ' + job.kubernetes.phase + ' · ' + job.kubernetes.reason),
          element('p', () => uiText("workspace.runner.panel.pod") + (job.kubernetes.podName || uiText("workspace.runner.panel.creating")) + uiText("workspace.runner.panel.pvc") + job.kubernetes.claimName),
          element('p', () => uiText("workspace.runner.panel.model.turns") + job.kubernetes.modelTurns + uiText("workspace.runner.panel.tool.calls") + job.kubernetes.toolCalls
            + uiText("workspace.runner.panel.observed.model.tokens") + job.kubernetes.modelTokens));
        if (job.result != null) section.append(element('h5', () => uiText("workspace.runner.panel.agent.result")), element('pre', JSON.stringify(job.result, null, 2)));
        const technical = element('details');
        technical.append(element('summary', () => uiText("workspace.runner.panel.technical.job.identity.and.authority")),
          element('pre', JSON.stringify({ processInstanceId: id, traversalId: job.traversalId,
            invocationId: job.invocationId, attemptId: job.attemptId, runnerJobId: job.runnerJobId,
            workspaceRef: job.workspaceRef, deadline: job.deadline, leaseUntil: job.leaseUntil,
            authority: job.authority, outcome: job.outcome, driver: job.driver, kubernetes: job.kubernetes }, null, 2)));
        section.append(technical);
        if (job.state === 'UNKNOWN') section.append(element('p',
          () => uiText("workspace.runner.panel.effect.unknown.the.workspace.remains.owned.reconciliation")));
        if (job.continuationUncertain) {
          section.append(element('p', () => uiText("workspace.runner.panel.successor.delivery.is.uncertain.inspect.graph.state")
            + workspace.revision + uiText("workspace.runner.panel.resume.requires.zero.recorded.successors.acknowledge.requires")));
          const confirmation = element('input'); confirmation.type = 'checkbox';
          const confirmationLabel = element('label', () => uiText("workspace.runner.panel.i.reviewed.graph.state.and.understand.the"));
          confirmationLabel.prepend(confirmation); section.append(confirmationLabel);
          for (const [resolution, label] of [['RESUME', () => uiText("workspace.runner.resume.successors")],
            ['ACKNOWLEDGE', () => uiText("workspace.runner.acknowledge.successors")], ['ABANDON', () => uiText("workspace.runner.abandon.traversal")]]) {
            const action = button(label, () => request(async current => {
              if (!confirmation.checked) throw productError('workspace.runner.validation.review', new Error("Confirm graph-state review before resolving."));
              await current.resolveRunnerContinuation(id, job.runnerJobId, workspace.revision, resolution);
              return () => { void inspect(); };
            }));
            action.disabled = !Number.isSafeInteger(workspace.revision); section.append(action);
          }
        }
        if (!TERMINAL.has(job.state)) for (const operation of ['cancel', 'reconcile']) {
          section.append(button(() => operation === 'cancel' ? uiText("workspace.runner.panel.request.cancellation") : uiText("workspace.runner.panel.reconcile.liveness"), () => request(async current => {
            await current.runnerOperation(id, job.runnerJobId, operation);
            return () => { void inspect(); };
          })));
        }
        for (const artifact of job.artifacts || []) section.append(button(
          () => uiText("workspace.runner.panel.artifact.summary", {
            kind: artifact.kind, size: artifact.sizeBytes, sha256: artifact.sha256,
          }),
          () => request(async current => {
            const preview = await current.runnerArtifact(id, job.runnerJobId, artifact.artifactId);
            return () => { paintText(evidence, () => preview.content
              + (preview.truncated ? uiText("workspace.runner.panel.preview.truncated") : '')); };
          })));
        jobs.append(section);
      }
    };
  });
  const save = () => request(async owner => {
    const value = JSON.parse(editor.value);
    if (typeof value.approved !== 'boolean' || !Number.isSafeInteger(value.expectedRevision)) {
      throw productError('workspace.runner.validation.approval', new Error("Approval and expectedRevision must be explicit. New resources use expectedRevision 0."));
    }
    const resource = await owner.saveRunnerResource(value);
    return () => { editor.value = JSON.stringify({ ...resource, expectedRevision: resource.revision }, null, 2); void refresh(); };
  });
  const health = () => request(async owner => {
    const result = await owner.runnerHealth(healthCursor);
    return () => { healthCursor = result.nextCursor; paintText(operations, JSON.stringify(result, null, 2)); };
  });
  const audit = () => request(async owner => {
    const result = await owner.runnerAudit(auditOffset);
    return () => { auditOffset = result.nextOffset; paintText(operations, JSON.stringify(result, null, 2)); };
  });
  const availability = () => request(async owner => {
    const result = await owner.runnerAvailability();
    return () => { paintText(operations, JSON.stringify(result, null, 2)); };
  });
  dialog.replaceChildren(heading, button(() => uiText("workspace.runner.panel.close"), () => dialog.close()), status,
    element('p', () => uiText("workspace.runner.panel.an.agent.without.workspaceref.remains.unchanged.named")),
    element('p', () => uiText("workspace.runner.panel.the.approved.profile.pins.docker.or.kubernetes")),
    button(() => uiText("workspace.runner.panel.refresh.catalogue"), refresh), catalog, editorLabel, editor,
    element('p', () => uiText("workspace.runner.panel.changing.a.body.requires.a.new.version")),
    button(() => uiText("workspace.runner.panel.save.version.approval"), save), processLabel, process, button(() => uiText("workspace.runner.panel.inspect.workspace"), inspect), jobs, evidence,
    element('p', () => uiText("workspace.runner.panel.health.and.metrics.are.cursor.paged.observations")),
    button(() => uiText("workspace.runner.panel.worker.capacity.availability"), availability), button(() => uiText("workspace.runner.panel.next.health.metrics.page"), health), button(() => uiText("workspace.runner.panel.next.audit.page"), audit), operations);
  return {
    refreshLocale() { refreshText();
      operations.setAttribute('aria-label', uiText("workspace.runner.panel.runner.health.and.audit.page"));
      evidence.setAttribute('aria-label', uiText("workspace.runner.panel.bounded.artifact.preview"));
    },
    open() { dialog.showModal(); if (client) void refresh(); },
    close() { dialog.close(); },
    setClient(value) {
      generation++; client = value; editor.value = ''; process.value = '';
      auditOffset = 0; healthCursor = null; paintText(operations, '');
      catalog.replaceChildren(); refreshText(); jobs.replaceChildren(); paintText(evidence, '');
      paintText(status, () => client ? uiText("workspace.runner.panel.refresh.the.catalogue.or.inspect.an.exact")
        : uiText("workspace.runner.panel.connect.to.a.service.to.inspect.governed"));
    },
  };
}
