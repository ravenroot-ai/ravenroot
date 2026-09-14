const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const TERMINAL = new Set(['COMPLETED', 'CANCELLED', 'DEADLINE_EXCEEDED']);

/** Tenant-generation-safe runner workbench. Untrusted instructions and artifacts are text only. */
export function createRunnerWindow({ dialog }) {
  const doc = dialog.ownerDocument;
  let client = null, generation = 0;
  let auditOffset = 0, healthCursor = null;
  const element = (name, text = '') => { const node = doc.createElement(name); node.textContent = text; return node; };
  const heading = element('h2', 'Governed agents and runners'); heading.id = 'runner-title';
  const status = element('p', 'Connect to a service to inspect governed agents and runners.');
  status.setAttribute('role', 'status'); status.setAttribute('aria-live', 'polite');
  const catalog = element('div');
  const editorLabel = element('label', 'Versioned definition or runner profile (JSON)');
  const editor = element('textarea'); editor.id = 'runner-resource-editor'; editor.rows = 14; editor.maxLength = 1_048_576;
  editorLabel.htmlFor = editor.id;
  const processLabel = element('label', 'Process instance ID');
  const process = element('input'); process.id = 'runner-process-id'; processLabel.htmlFor = process.id;
  const jobs = element('div'); const evidence = element('pre');
  const operations = element('pre'); operations.setAttribute('aria-label', 'Runner health and audit page');
  evidence.setAttribute('aria-label', 'Bounded artifact preview');
  const button = (text, action) => {
    const node = element('button', text); node.type = 'button'; node.className = 'btn';
    node.addEventListener('click', action); return node;
  };
  const request = async action => {
    const owner = client, version = generation;
    if (!owner) { status.textContent = 'Connect to a service before using the runner plane.'; return; }
    status.textContent = 'Loading…';
    try {
      const render = await action(owner);
      if (version !== generation || owner !== client) return;
      render?.(); status.textContent = 'Updated from the authenticated tenant control plane.';
    } catch (error) {
      if (version === generation && owner === client) status.textContent = error.message || 'Runner request failed.';
    }
  };
  const refresh = () => request(async owner => {
    const result = await owner.runnerCatalog();
    if (!Array.isArray(result?.items) || result.items.length > 256) throw new Error('Unsupported runner catalogue');
    return () => {
      catalog.replaceChildren();
      for (const resource of result.items) {
        const row = element('p');
        row.append(button(resource.kind + ' · ' + resource.name + ' v' + resource.version
          + ' · ' + (resource.approved ? 'Approved' : 'Draft / retired'), () => request(async selectedClient => {
          const selected = await selectedClient.runnerResource(resource.kind + ':' + resource.name + ':' + resource.version);
          return () => { editor.value = JSON.stringify({ ...selected, expectedRevision: selected.revision }, null, 2); };
        })));
        row.append(element('span', ' Revision ' + resource.revision + ' · ' + resource.actor + ' · ' + resource.updatedAt));
        catalog.append(row);
      }
    };
  });
  const inspect = () => request(async owner => {
    const id = process.value.trim();
    if (!UUID.test(id)) throw new Error('Enter an exact process instance UUID.');
    const workspace = await owner.runnerWorkspace(id);
    if (!Array.isArray(workspace?.jobs) || workspace.jobs.length > 256) throw new Error('Unsupported runner workspace');
    return () => {
      jobs.replaceChildren(element('h3', 'Workspace ' + workspace.workspaceId + ' · runner ' + workspace.runnerId));
      for (const job of workspace.jobs) {
        const section = element('section');
        section.append(element('h4', job.command + ' · ' + job.state + ' · fence ' + job.fence),
          element('p', job.definition + ' v' + job.definitionVersion + ' · ' + (job.readOnly ? 'Read-only' : 'Governed write authority')),
          element('pre', JSON.stringify({ processInstanceId: id, traversalId: job.traversalId,
            invocationId: job.invocationId, attemptId: job.attemptId, runnerJobId: job.runnerJobId,
            deadline: job.deadline, leaseUntil: job.leaseUntil, authority: job.authority, outcome: job.outcome }, null, 2)));
        if (job.state === 'UNKNOWN') section.append(element('p',
          'Effect unknown. The workspace remains owned. Reconciliation is report-only; this action does not retry the agent.'));
        if (job.continuationUncertain) section.append(element('p',
          'Successor delivery is uncertain. Automatic runner replay and new workspace admissions are blocked; inspect the audit and use operator graph recovery.'));
        if (!TERMINAL.has(job.state)) for (const operation of ['cancel', 'reconcile']) {
          section.append(button(operation === 'cancel' ? 'Request cancellation' : 'Reconcile liveness', () => request(async current => {
            await current.runnerOperation(id, job.runnerJobId, operation);
            return () => { void inspect(); };
          })));
        }
        for (const artifact of job.artifacts || []) section.append(button(
          artifact.kind + ' · ' + artifact.sizeBytes + ' bytes · SHA-256 ' + artifact.sha256,
          () => request(async current => {
            const preview = await current.runnerArtifact(id, job.runnerJobId, artifact.artifactId);
            return () => { evidence.textContent = preview.content + (preview.truncated ? '\n[Preview truncated]' : ''); };
          })));
        jobs.append(section);
      }
    };
  });
  const save = () => request(async owner => {
    const value = JSON.parse(editor.value);
    if (typeof value.approved !== 'boolean' || !Number.isSafeInteger(value.expectedRevision)) {
      throw new Error('Approval and expectedRevision must be explicit. New resources use expectedRevision 0.');
    }
    const resource = await owner.saveRunnerResource(value);
    return () => { editor.value = JSON.stringify({ ...resource, expectedRevision: resource.revision }, null, 2); void refresh(); };
  });
  const health = () => request(async owner => {
    const result = await owner.runnerHealth(healthCursor);
    return () => { healthCursor = result.nextCursor; operations.textContent = JSON.stringify(result, null, 2); };
  });
  const audit = () => request(async owner => {
    const result = await owner.runnerAudit(auditOffset);
    return () => { auditOffset = result.nextOffset; operations.textContent = JSON.stringify(result, null, 2); };
  });
  dialog.replaceChildren(heading, button('Close', () => dialog.close()), status,
    element('p', 'The bounded Agent node is unchanged. Workspace agents reference approved versions; graph instructions cannot grant authority.'),
    button('Refresh catalogue', refresh), catalog, editorLabel, editor,
    element('p', 'Changing a body requires a new version. Set approved explicitly to approve or retire; an operator scope is required.'),
    button('Save version / approval', save), processLabel, process, button('Inspect workspace', inspect), jobs, evidence,
    element('p', 'Health and metrics are cursor-paged observations, not global totals. Idle runner health is unknown.'),
    button('Next health / metrics page', health), button('Next audit page', audit), operations);
  return {
    open() { dialog.showModal(); if (client) void refresh(); },
    close() { dialog.close(); },
    setClient(value) {
      generation++; client = value; editor.value = ''; process.value = '';
      auditOffset = 0; healthCursor = null; operations.textContent = '';
      catalog.replaceChildren(); jobs.replaceChildren(); evidence.textContent = '';
      status.textContent = client ? 'Refresh the catalogue or inspect an exact process ID.'
        : 'Connect to a service to inspect governed agents and runners.';
    },
  };
}
