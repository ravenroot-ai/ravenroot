const LIVE_STATUSES = new Set(['WAITING', 'ESCALATED']);
const ALL_STATUSES = new Set([...LIVE_STATUSES, 'RESOLVED', 'DENIED', 'EXPIRED', 'CANCELLED']);
const ACTIONS = new Set(['RESOLVE', 'DENY', 'CANCEL']);
const COMMENT_MODES = new Set(['DISALLOWED', 'OPTIONAL', 'REQUIRED']);

function object(value, message) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(message);
  return value;
}

function text(value, name, { optional = false } = {}) {
  if (optional && (value === null || value === undefined)) return null;
  if (typeof value !== 'string' || !value) throw new Error(`Human Task ${name} is missing`);
  return value;
}

function displayText(value, name, { required = false } = {}) {
  if (typeof value !== 'string' || (required && !value.trim())) {
    throw new Error(`Human Task ${name} is missing`);
  }
  if ((typeof value.isWellFormed === 'function' && !value.isWellFormed())
      || /[\u0000-\u001f\u007f-\u009f]/u.test(value)) {
    throw new Error(`Human Task ${name} contains invalid display text`);
  }
  return value;
}

function integer(value, name, minimum = 0) {
  if (!Number.isSafeInteger(value) || value < minimum) {
    throw new Error(`Human Task ${name} is not a valid integer`);
  }
  return value;
}

function timestamp(value, name, { optional = false } = {}) {
  const string = text(value, name, { optional });
  if (string === null) return null;
  if (!Number.isFinite(Date.parse(string))) throw new Error(`Human Task ${name} is not a valid timestamp`);
  return string;
}

export function utf8Length(value) {
  return new TextEncoder().encode(String(value ?? '')).length;
}

export function humanTaskActionLabelKey(value) {
  return String(value).normalize('NFKC')
    .replace(/[\p{White_Space}\p{Zs}]+/gu, ' ')
    .replace(/^ +| +$/g, '')
    .toLowerCase();
}

export function validateHumanTaskCapability(value) {
  const capability = object(value, 'Human Task capability is missing');
  const versions = capability.confirmationPresentationVersions;
  if (capability.schemaVersion !== 1 || !Array.isArray(versions) || versions.length !== 1
      || versions[0] !== 1) {
    throw new Error('Human Task capability is not a valid schema version 1 document');
  }
  const attentionPollMillis = integer(capability.attentionPollMillis, 'poll interval', 1);
  const attentionBackoffMaxMillis = integer(capability.attentionBackoffMaxMillis,
    'poll backoff maximum', attentionPollMillis);
  const attentionPageSize = integer(capability.attentionPageSize, 'page size', 1);
  const attentionPageSizeMax = integer(capability.attentionPageSizeMax, 'page-size maximum', attentionPageSize);
  const confirmationPromptMaxUtf8Bytes = integer(capability.confirmationPromptMaxUtf8Bytes,
    'prompt maximum', 1);
  const confirmationActionLabelMaxUtf8Bytes = integer(capability.confirmationActionLabelMaxUtf8Bytes,
    'action-label maximum', 1);
  const commentMaxUtf8Bytes = integer(capability.commentMaxUtf8Bytes, 'comment maximum', 1);
  return Object.freeze({
    schemaVersion: 1,
    confirmationPresentationVersions: Object.freeze([...new Set(versions)]),
    attentionPollMillis,
    attentionBackoffMaxMillis,
    attentionPageSize,
    attentionPageSizeMax,
    confirmationPromptMaxUtf8Bytes,
    confirmationActionLabelMaxUtf8Bytes,
    commentMaxUtf8Bytes,
  });
}

export function validateHumanTaskPresentation(value, capability, pinnedLimits) {
  const presentation = object(value, 'Human Task presentation is missing');
  const version = integer(presentation.version, 'presentation version', 1);
  if (!capability.confirmationPresentationVersions.includes(version)) {
    throw new Error(`Human Task presentation version ${version} is not supported`);
  }
  // Current capability maxima admit new definitions. Reopened tasks keep the presentation pinned
  // when they were created, so a tighter current policy must not make their safe text unreadable.
  const prompt = displayText(presentation.prompt, 'prompt', { required: true });
  if (utf8Length(prompt) > pinnedLimits.promptMaxUtf8Bytes) {
    throw new Error('Human Task prompt exceeds its pinned maximum');
  }
  if (!COMMENT_MODES.has(presentation.commentRequirement)) {
    throw new Error('Human Task comment requirement is invalid');
  }
  if (!Array.isArray(presentation.actions) || presentation.actions.length < 1) {
    throw new Error('Human Task presentation has no permitted actions');
  }
  const seen = new Set();
  const actions = presentation.actions.map((entry, index) => {
    if (!ACTIONS.has(entry) || seen.has(entry)) {
      throw new Error(`Human Task action ${index + 1} is invalid`);
    }
    seen.add(entry);
    return entry;
  });
  const labels = Object.freeze({ RESOLVE: displayText(presentation.resolveLabel, 'resolve label'),
    DENY: displayText(presentation.denyLabel, 'deny label'),
    CANCEL: displayText(presentation.cancelLabel, 'cancel label') });
  const activeLabelKeys = new Set();
  for (const action of actions) {
    if (!labels[action].trim()) throw new Error(`Human Task ${action.toLowerCase()} label is missing`);
    if (utf8Length(labels[action]) > pinnedLimits.actionLabelMaxUtf8Bytes) {
      throw new Error('Human Task action label exceeds its pinned maximum');
    }
    const key = humanTaskActionLabelKey(labels[action]);
    if (activeLabelKeys.has(key)) throw new Error('Human Task active action labels are ambiguous');
    activeLabelKeys.add(key);
  }
  return Object.freeze({ version, prompt, commentRequirement: presentation.commentRequirement,
    actions: Object.freeze(actions), labels });
}

export function validateHumanTaskRow(candidate, capability, { actionable = false, index = 0 } = {}) {
  const item = object(candidate, `Human Task row ${index + 1} is invalid`);
  if (!ALL_STATUSES.has(item.status) || (actionable && !LIVE_STATUSES.has(item.status))) {
    throw new Error(`Human Task row ${index + 1} is not ${actionable ? 'actionable' : 'valid'}`);
  }
  const generation = integer(item.generation, 'generation', 1);
  const pinnedLimits = Object.freeze({
    promptMaxUtf8Bytes: integer(item.promptMaxUtf8Bytes, 'pinned prompt maximum', 1),
    actionLabelMaxUtf8Bytes: integer(item.actionLabelMaxUtf8Bytes, 'pinned action-label maximum', 1),
    commentMaxUtf8Bytes: integer(item.commentMaxUtf8Bytes, 'pinned comment maximum', 1),
  });
  if (!Array.isArray(item.availableActions) || item.availableActions.some(action => !ACTIONS.has(action))
      || new Set(item.availableActions).size !== item.availableActions.length) {
    throw new Error('Human Task available actions are invalid');
  }
  if (actionable && item.availableActions.length < 1) {
    throw new Error(`Human Task row ${index + 1} has no authorized action`);
  }
  const presentation = validateHumanTaskPresentation(item.presentation, capability, pinnedLimits);
  if (item.availableActions.some(action => !presentation.actions.includes(action))) {
    throw new Error('Human Task available action is outside the pinned presentation');
  }
  return Object.freeze({
    taskId: text(item.taskId, 'task id'), generation, status: item.status,
    graphVersion: text(item.graphVersion, 'graph version'),
    deploymentId: text(item.deploymentId, 'deployment id', { optional: true }),
    processInstanceId: text(item.processInstanceId, 'process id'),
    traversalId: text(item.traversalId, 'traversal id'),
    nodeId: text(item.nodeId, 'node id'),
    createdAt: timestamp(item.createdAt, 'creation time'),
    expiresAt: timestamp(item.expiresAt, 'expiry time'),
    escalateAt: timestamp(item.escalateAt, 'escalation time', { optional: true }),
    presentation, availableActions: Object.freeze([...new Set(item.availableActions)]),
    ...pinnedLimits,
  });
}

export function validateHumanTaskAttention(value, capability, expected = {}) {
  const page = object(value, 'Human Task attention response is invalid');
  if (page.schemaVersion !== 1 || !Array.isArray(page.items)) {
    throw new Error('Human Task attention response is not a schema version 1 page');
  }
  if (page.items.length > capability.attentionPageSizeMax) {
    throw new Error('Human Task attention response exceeds the advertised page maximum');
  }
  if (expected.limit != null && page.items.length > Number(expected.limit)) {
    throw new Error('Human Task attention response exceeds the requested page size');
  }
  const counts = object(page.counts, 'Human Task attention counts are missing');
  const pending = integer(counts.pending, 'pending count');
  const escalated = integer(counts.escalated, 'escalated count');
  if (escalated > pending) throw new Error('Human Task escalated count exceeds pending count');
  const items = page.items.map((candidate, index) =>
    validateHumanTaskRow(candidate, capability, { actionable: true, index }));
  if (pending < items.length
      || escalated < items.filter(item => item.status === 'ESCALATED').length) {
    throw new Error('Human Task attention counts are smaller than the returned page');
  }
  const rowKeys = items.map(item => `${item.taskId}\u0000${item.generation}`);
  if (new Set(rowKeys).size !== rowKeys.length) {
    throw new Error('Human Task attention page contains a duplicate task generation');
  }
  for (const item of items) {
    for (const field of ['graphVersion', 'deploymentId', 'processInstanceId', 'traversalId', 'nodeId', 'taskId']) {
      if (expected[field] != null && String(item[field]) !== String(expected[field])) {
        throw new Error(`Human Task row ${field} does not match the requested context`);
      }
    }
    if (expected.generation != null && item.generation !== Number(expected.generation)) {
      throw new Error('Human Task row generation does not match the requested context');
    }
  }
  const nextCursor = page.nextCursor == null ? null : text(page.nextCursor, 'next cursor');
  if (!Array.isArray(page.nodeCounts)) throw new Error('Human Task node counts are missing');
  const seenNodeIds = new Set();
  const nodeCounts = page.nodeCounts.map((candidate, index) => {
    const count = object(candidate, `Human Task node count ${index + 1} is invalid`);
    const nodePending = integer(count.pending, 'node pending count');
    const nodeEscalated = integer(count.escalated, 'node escalated count');
    if (nodeEscalated > nodePending) throw new Error('Human Task node escalated count exceeds pending count');
    const nodeId = text(count.nodeId, 'node id');
    if (seenNodeIds.has(nodeId)) throw new Error('Human Task node counts contain a duplicate node');
    seenNodeIds.add(nodeId);
    return Object.freeze({ nodeId, pending: nodePending,
      escalated: nodeEscalated });
  });
  const aggregate = expected.graphVersion != null && expected.nodeId == null && expected.taskId == null;
  if (aggregate) {
    const totals = nodeCounts.reduce((sum, count) => ({ pending: sum.pending + count.pending,
      escalated: sum.escalated + count.escalated }), { pending: 0, escalated: 0 });
    if (totals.pending !== pending || totals.escalated !== escalated) {
      throw new Error('Human Task node counts do not match aggregate counts');
    }
  } else if (nodeCounts.length) {
    throw new Error('Human Task node counts are not valid for this selected task or node query');
  }
  return Object.freeze({ schemaVersion: 1, items: Object.freeze(items), nextCursor,
    counts: Object.freeze({ pending, escalated }), nodeCounts: Object.freeze(nodeCounts) });
}

export function humanTaskContext(documentRecord) {
  const execution = documentRecord?.execution;
  if (documentRecord?.humanTasks?.deploymentId) {
    if (!documentRecord.humanTasks.graphVersion) return null;
    return { graphVersion: String(documentRecord.humanTasks.graphVersion),
      deploymentId: String(documentRecord.humanTasks.deploymentId) };
  }
  if (!execution?.graphVersion || !execution.processInstanceId) return null;
  return { graphVersion: String(execution.graphVersion),
    processInstanceId: String(execution.processInstanceId) };
}

export function humanTaskServiceOrigin(clientBaseUrl, pageOrigin) {
  return String(clientBaseUrl || pageOrigin || '');
}

export function nodeAttention(items) {
  const result = new Map();
  for (const item of items || []) {
    const current = result.get(item.nodeId) || { pending: 0, escalated: 0 };
    current.pending += 1;
    if (item.status === 'ESCALATED') current.escalated += 1;
    result.set(item.nodeId, current);
  }
  return result;
}

export function validateDecisionComment(comment, mode, maximumUtf8Bytes) {
  const raw = String(comment ?? '');
  if ((typeof raw.isWellFormed === 'function' && !raw.isWellFormed())
      || /[\u0000-\u0008\u000b\u000c\u000d-\u001f\u007f]/u.test(raw)) {
    return { ok: false, error: 'Comment contains a control character or malformed Unicode.' };
  }
  const value = raw.trim();
  if (mode === 'DISALLOWED' && value.length) return { ok: false, error: 'This task does not accept a comment.' };
  if (mode === 'REQUIRED' && !value.trim()) return { ok: false, error: 'Enter the required comment.' };
  const bytes = utf8Length(value);
  if (bytes > maximumUtf8Bytes) {
    return { ok: false, error: `Comment uses ${bytes} UTF-8 bytes; the service permits ${maximumUtf8Bytes}.` };
  }
  return { ok: true, value, bytes };
}

export function nextHumanTaskBackoff(current, capability) {
  if (!current) return capability.attentionPollMillis;
  return Math.min(capability.attentionBackoffMaxMillis,
    Math.max(capability.attentionPollMillis, current * 2));
}
