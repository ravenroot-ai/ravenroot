import { STATIC_ENGLISH_MESSAGES } from './ui-static-text.js';
import { DYNAMIC_ENGLISH_MESSAGES } from './ui-dynamic-text.js';

export const ENGLISH_MESSAGES = Object.freeze({
  ...STATIC_ENGLISH_MESSAGES,
  ...DYNAMIC_ENGLISH_MESSAGES,
  'commands.edit.groupSelection.label': 'Group selection',
  'commands.edit.groupSelection.help': 'Create a named visual group from two or more selected real nodes',
  'commands.view.toggleGroup.label': 'Expand / collapse group',
  'commands.view.toggleGroup.help': 'Toggle the selected visual group without changing execution or zoom',
  'commands.edit.renameGroup.label': 'Rename group',
  'commands.edit.replaceGroupMembers.label': 'Replace members with selection',
  'commands.edit.ungroup.label': 'Ungroup',
  'commands.edit.removeGroupMetadata.label': 'Remove unsupported visual group metadata',
  'commands.file.new.label': 'New Document',
  'commands.file.open.label': 'Open…',
  'commands.file.localOpen.label': 'Open local file…',
  'commands.file.localOpen.help': 'Open a GraphML or Graphify file from this device',
  'commands.file.replaceActive.label': 'Replace Active…',
  'commands.file.fork.label': 'Fork as Draft',
  'commands.file.fork.help': 'Create a new editable draft from this immutable graph snapshot.',
  'commands.file.save.label': 'Save GraphML',
  'commands.file.save.help': 'Save the workflow as GraphML',
  'commands.file.localSave.label': 'Download GraphML',
  'commands.file.localSave.help': 'Download this workflow as a local GraphML file',
  'commands.file.repositoryOpen.label': 'Open from repository…',
  'commands.file.repositoryOpen.help': 'Open a tenant graph from the configured Git repository',
  'commands.file.repositorySave.label': 'Save draft to repository',
  'commands.file.repositorySave.help': 'Save this GraphML draft with conflict protection',
  'commands.file.repositoryHistory.label': 'Repository history…',
  'commands.file.repositoryHistory.help': 'View, compare, or restore prior source revisions',
  'commands.file.repositoryDiscard.label': 'Discard draft changes',
  'commands.file.repositoryDiscard.help': 'Restore the reviewed release into the draft',
  'commands.file.repositoryDelete.label': 'Delete repository draft',
  'commands.file.repositoryDelete.help': 'Delete the draft while retaining Git history',
  'commands.file.repositoryRelease.label': 'Propose release review',
  'commands.file.repositoryRelease.help': 'Open a review pull request in the graph repository',
  'commands.file.publishedArtifacts.label': 'Published versions…',
  'commands.file.publishedArtifacts.help': 'Deploy or roll back using verified immutable publications',
  'commands.file.close.label': 'Close Document',
  'commands.edit.undo.label': 'Undo',
  'commands.edit.undo.help': 'Undo the last change',
  'commands.edit.undo.named': 'Undo {name}',
  'commands.edit.undo.empty': 'Nothing to undo',
  'commands.edit.redo.label': 'Redo',
  'commands.edit.redo.help': 'Redo the last undone change',
  'commands.edit.redo.named': 'Redo {name}',
  'commands.edit.redo.empty': 'Nothing to redo',
  'commands.edit.modify.label': 'Modify',
  'commands.edit.autosave.label': 'Autosave',
  'commands.edit.autosave.help': 'Save valid Inspector changes automatically',
  'commands.view.navigation.label': 'Navigation',
  'commands.view.navigation.help': 'Pan the graph with a hand cursor',
  'commands.edit.connect.label': 'Connect',
  'commands.edit.addNode.label': 'Add Node',
  'commands.edit.addEdge.label': 'Add Edge',
  'commands.edit.duplicateNode.label': 'Duplicate Node',
  'commands.edit.deleteSelection.label': 'Delete Selection',
  'commands.edit.deleteSelection.help': 'Delete selected elements in Modify mode',
  'nodeActions.trace': 'Trace full path from {name}',
  'nodeActions.duplicate': 'Duplicate {name}',
  'nodeActions.delete': 'Delete {name}',
  'commands.edit.migrateJoinSemantics.label': 'Migrate Join Semantics…',
  'commands.edit.migrateJoinSemantics.help':
    'Declare join.semantics on this document and materialise its currently-inferred join policies',
  'commands.view.fit.label': 'Fit Graph',
  'commands.view.fit.help': 'Fit graph to view',
  'commands.view.zoomIn.label': 'Zoom In',
  'commands.view.zoomIn.help': 'Zoom in',
  'commands.view.zoomOut.label': 'Zoom Out',
  'commands.view.zoomOut.help': 'Zoom out',
  'controls.nodeActionScale.label': 'Node minibar size',
  'controls.nodeActionScale.help': 'Resize node action minibars',
  'commands.view.graphs.label': 'Graphs…',
  'commands.view.graphs.help': 'List open documents and switch between them',
  'commands.view.closeAllDocuments.label': 'Close All Documents',
  'commands.view.closeAllDocuments.help': 'Close every document that is currently open',
  'commands.view.panels.label': 'Panels…',
  'commands.view.leftPanels.label': 'Left Panels',
  'commands.view.rightInspector.label': 'Right Inspector',
  'commands.view.themeDark.label': 'Dark theme',
  'commands.view.themeLight.label': 'Light theme',
  'commands.view.keyboardShortcuts.label': 'Keyboard Shortcuts',
  'commands.view.keyboardShortcuts.help': 'Toggle this help',
  'commands.file.open.repositoryLabel': 'Open from repository…',
  'commands.file.open.repositoryHelp': 'Open a tenant graph from the configured Git repository',
  'commands.file.save.repositoryLabel': 'Save draft to repository',
  'commands.file.save.repositoryHelp': 'Save this GraphML draft to the configured Git repository with conflict protection',
  'commands.file.fork.repositoryLabel': 'Open repository draft',
  'commands.workspace.horizontal.label': 'Horizontal panes',
  'commands.workspace.horizontal.help': 'Horizontal panes workspace panes',
  'commands.workspace.vertical.label': 'Vertical panes',
  'commands.workspace.vertical.help': 'Vertical panes workspace panes',
  'commands.workspace.grid.label': 'Grid panes',
  'commands.workspace.grid.help': 'Grid panes workspace panes',
  'commands.workspace.single.label': 'Single pane',
  'commands.workspace.single.help': 'Single pane workspace panes',
  'commands.workspace.reset.label': 'Reset workspace layout',
  'commands.workspace.reset.help': 'Reset workspace layout and pane sizes',
  'commands.layout.design.label': 'Design',
  'commands.layout.design.help': 'Restore the saved Design view without arranging it',
  'commands.layout.monitoring.label': 'Monitoring',
  'commands.layout.monitoring.help': 'Restore the saved Monitoring view without reheating it',
  'commands.layout.render.label': 'Render',
  'commands.layout.render.help': 'Recompute only the active graph view',
  'commands.layout.arrange.hierarchical.label': 'Arrange — Hierarchical',
  'commands.layout.arrange.hierarchical.help': 'Arrange left to right by workflow dependency',
  'commands.layout.arrange.flow.label': 'Arrange — Flow',
  'commands.layout.arrange.flow.help': 'Arrange as a compact directional flow',
  'commands.layout.arrange.organic.label': 'Arrange — Organic',
  'commands.layout.arrange.organic.help': 'Arrange related nodes with balanced organic spacing',
  'commands.layout.arrange.keep.label': 'Keep positions',
  'commands.layout.arrange.keep.help': 'Keep every node where it is and fit the graph in view',
  'commands.layout.arrange.hierarchical-new.label': 'Arrange — Hierarchical (new)',
  'commands.layout.arrange.hierarchical-new.help': 'Arrange on layers with orthogonal, label-aware edge channels',
  'commands.layout.arrange.layered-down.label': 'Arrange — Layered (top-down)',
  'commands.layout.arrange.layered-down.help': 'Arrange on layers from top to bottom, with the node name beside the card',
  'commands.run.play.label': 'Test',
  'commands.run.play.help': 'Test this graph with pass-through execution; node side effects are bypassed',
  'commands.run.start.label': 'Run',
  'commands.run.start.help': 'Execute real node behavior once, or start a process-local source session when this graph declares an inbound source',
  'commands.run.pause.label': 'Pause',
  'commands.run.pause.help': 'Pause this execution after its current node reaches a safe boundary',
  'commands.run.resume.label': 'Resume',
  'commands.run.resume.help': 'Resume this paused execution',
  'commands.run.cancel.label': 'Cancel',
  'commands.run.cancel.help': 'Cooperatively cancel this execution',
  'commands.run.stop.label': 'Stop',
  'commands.run.stop.help': 'Stop this document’s process-local source session; closing the document does not stop it',
  'commands.run.stopDeployment.label': 'Stop Deployment',
  'commands.run.stopDeployment.help': 'Unavailable: this runtime does not advertise the versioned deployment Stop API',
  'commands.run.shutdown.label': 'Shutdown Service',
  'commands.run.shutdown.help': 'Unavailable: this runtime does not advertise the authorized global Shutdown API',
  // These two act on THE COMMAND BAR'S SERVICE TOKEN — the sign-in to the
  // author's own Ravenroot service — and never on a credential a node uses. Their verbs were already
  // accurate and are deliberately unchanged: what was ambiguous was the FIELD's label ("Access
  // token", next to a Service URL, reads as a provider key box), and that label lives in
  // `index.html`, not here. It now reads "Service token".
  'commands.run.authenticate.label': 'Authenticate',
  'commands.run.forgetToken.label': 'Forget Token',
  // The ellipsis is the product's existing spelling for "this opens something", as in `Open…`,
  // `Replace Active…`, `Graphs…` and `Panels…`.
  'commands.run.credentials.label': 'Credentials…',
  'commands.run.runners.label': 'Agents and runners…',
  'commands.run.deployments.label': 'Deployments…',
  'commands.canvas.cursor.label': 'Move graph cursor',
  'commands.canvas.cursor.help': 'Move the graph cursor between nodes',
  'commands.canvas.edges.label': 'Step through connected edges',
  'commands.canvas.edges.help': "Step through the cursor node's edges",
  'commands.canvas.confirm.label': 'Confirm graph action',
  'commands.canvas.confirm.help': 'Select a node or confirm an edge',
  'commands.canvas.startEdge.label': 'Start edge',
  'commands.canvas.startEdge.help': 'Start an edge from the cursor node',
  'commands.canvas.reconnect.label': 'Reconnect edge',
  'commands.canvas.reconnect.help': "Reconnect the selected edge's target or source",
  'commands.canvas.cancel.label': 'Cancel graph gesture',
  'commands.canvas.cancel.help': 'Cancel the current graph gesture',
  'commands.ui.dismiss.label': 'Dismiss transient interface',
  'commands.ui.dismiss.help': 'Dismiss a transient menu, drag, filter, or trace',
  'inspector.unsaved.title': 'Save node changes?',
  'inspector.unsaved.description': 'This node has Inspector changes that have not been saved.',
  'inspector.unsaved.invalidDescription':
    'This node has invalid Inspector changes. Correct them, or discard them before changing selection.',
  'inspector.unsaved.save': 'Save',
  'inspector.unsaved.discard': 'Discard',
  'inspector.unsaved.cancel': 'Cancel',
  'inspector.history.pending': 'Save or discard the current Inspector changes before using Undo or Redo.',
  'closeAll.dirty.title': 'Save changes before closing all documents?',
  'closeAll.dirty.description.one':
    'One modified document has changes that have not been downloaded.',
  'closeAll.dirty.description.many':
    '{count} modified documents have changes that have not been downloaded.',
  'closeAll.dirty.save': 'Save All and Continue',
  'closeAll.dirty.discard': 'Discard All and Continue',
  'closeAll.sessions.title': 'Close documents with active local sessions?',
  'closeAll.sessions.description.one':
    'One document has an active local source session. Keeping it running detaches this browser only.',
  'closeAll.sessions.description.many':
    '{count} documents have active local source sessions. Keeping them running detaches this browser only.',
  'closeAll.sessions.item.one': '{name} — one inbound source node · scope LOCAL_PROCESS',
  'closeAll.sessions.item.many': '{name} — {count} inbound source nodes · scope LOCAL_PROCESS',
  'closeAll.sessions.keep': 'Keep Running and Close',
  'closeAll.sessions.stop': 'Stop and Close All',
  'closeAll.working.title': 'Closing documents…',
  'closeAll.working.description': 'Ravenroot is completing the selected save and stop actions before closing documents.',
  'closeAll.failure.title': 'Could not close all documents',
  'closeAll.failure.description':
    'The targeted documents remain open. Review the problem below, then retry or cancel.',
  'closeAll.failure.save': 'One or more modified documents could not be prepared or downloaded.',
  'closeAll.failure.stop': 'One or more local source sessions did not reach the stopped state.',
  'closeAll.retry': 'Retry',
  'closeAll.cancel': 'Cancel',
  'commands.view.language.label': 'Language…',
  'commands.view.language.help': 'Choose the interface language or import a custom catalog',
  'language.title': 'Interface language',
  'language.description': 'Choose a configured or imported language. Ravenroot applies the change immediately.',
  'language.selection.label': 'Preferred language',
  'language.automatic': 'Automatic (configured, browser, then English)',
  'language.source.built-in': 'Built in',
  'language.source.configured': 'Configured by this deployment',
  'language.source.imported': 'Imported in this browser',
  'language.source.fallback': 'English fallback',
  'language.import': 'Import catalog…',
  'language.remove': 'Remove imported catalog',
  'language.close': 'Close',
  'language.import.success': 'Imported {locale}.',
  'language.import.failure': 'The catalog could not be imported. Check the file and try again.',
  'language.remove.failure': 'The catalog could not be removed. Try again.',
  'language.catalogFile.label': 'UI catalog JSON file',
  'language.currentSource': 'Source: {source}',
  'embed.document.title': 'Ravenroot embedded graph',
  'embed.state.idle': '',
  'embed.state.loading': 'Loading graph…',
  'embed.state.ready': 'Graph ready.',
  'embed.state.empty': 'This graph is empty.',
  'embed.state.error': 'The graph could not be displayed.',
  'embed.state.expired': 'This viewing session has expired.',
  'embed.state.offline': 'The graph service is unavailable.',
  'embed.state.incompatible': 'This graph requires a newer viewer.',
  'embed.state.destroyed': 'Viewer closed.',
  'embed.kind.START': 'Start',
  'embed.kind.PASSTHROUGH': 'Step',
  'embed.kind.BEHAVIOR': 'Behavior',
  'embed.kind.END': 'End',
  'embed.kind.ERROR': 'Error',
  'embed.alternative.node': '{kind} node {id}',
  'embed.alternative.empty': 'No nodes in this graph.',
  'embed.alternative.connects': 'Connects to {id}',
  'embed.alternative.receives': 'Receives from {id}',
  'embed.alternative.relationships': 'Relationships for {id}',
  'embed.boundary.start': 'Start of embedded graph',
  'embed.boundary.end': 'End of embedded graph',
  'embed.skip': 'Skip to graph',
  'embed.title': 'Graph',
  'embed.controls.label': 'Graph view controls',
  'embed.controls.view': 'View',
  'embed.mode.design': 'Design',
  'embed.mode.monitoring': 'Monitoring',
  'embed.mode.cyto': 'Cyto',
  'embed.mode.n8n': 'N8N',
  'embed.mode.elastic': 'Elastic',
  'embed.controls.render': 'Render',
  'embed.controls.render.title': 'Render layout',
  'embed.controls.run': 'Run',
  'embed.controls.liveRun': 'Live run',
  'embed.runs.none': 'No runs',
  'embed.runs.noneAuthorized': 'No authorized runs.',
  'embed.runs.choose': 'Choose a run',
  'embed.runs.connecting': 'Connecting to selected run.',
  'embed.runs.unavailable': 'Run list is temporarily unavailable.',
  'embed.runs.noLongerAuthorized': 'The selected run is no longer authorized.',
  'embed.controls.startExecution': 'Start execution',
  'embed.controls.zoom': 'Zoom controls',
  'embed.controls.zoomOut': 'Zoom out',
  'embed.controls.fit': 'Fit',
  'embed.controls.fit.title': 'Fit graph',
  'embed.controls.zoomIn': 'Zoom in',
  'embed.controls.maximize': 'Maximize embedded graph',
  'embed.controls.restore': 'Restore embedded graph',
  'embed.canvas.label': 'Read-only graph. Arrow keys pan, plus and minus zoom, and zero fits the graph.',
  'embed.minimap.label': 'Graph overview. Arrow keys pan, Home fits, Escape returns to graph.',
  'embed.minimap.fallback': 'Graph overview',
  'embed.contents.label': 'Graph contents',
  'embed.view.rendered': '{mode} view rendered.',
  'embed.view.ready': '{mode} view ready.',
  'embed.view.elasticUnavailable': 'Elastic view is unavailable for large graphs.',
  'embed.metadata.version': 'Version {version}',
  'embed.observation.gap': 'Live observation has a gap. Reattach to reconcile the deployment.',
  'embed.observation.versionMismatch': 'The deployment version changed. This view is no longer live.',
  'embed.observation.authorizationEnded': 'Live observation authorization ended.',
  'embed.observation.deployment': 'Deployment {state}.',
  'embed.selection': { one: 'One node selected.', other: '{count} nodes selected.' },
  'embed.inspect.group': {
    one: '{action} visual group {name}, one member',
    other: '{action} visual group {name}, {count} members',
  },
  'embed.inspect.expand': 'Expand',
  'embed.inspect.collapse': 'Collapse',
  'embed.inspect.node': 'Inspect node {name}',
});

export const UI_TEXT_CATALOGS = Object.freeze({ en: ENGLISH_MESSAGES });

export const UI_CATALOG_SCHEMA = 'ravenroot.ui-catalog/v1';
export const UI_LOCALES_SCHEMA = 'ravenroot.ui-locales/v1';
export const UI_LOCALE_STORAGE_KEY = 'ravenroot.ui.locale.v1';

const MAX_CATALOG_KEYS = 4_096;
const MAX_MESSAGE_LENGTH = 8_192;
const MAX_LOCALE_LENGTH = 64;
const PLURAL_CATEGORIES = new Set(['zero', 'one', 'two', 'few', 'many', 'other']);
const RTL_SCRIPTS = new Set(['Arab', 'Hebr', 'Nkoo', 'Rohg', 'Syrc', 'Thaa']);
const RTL_LANGUAGES = new Set(['ar', 'dv', 'fa', 'he', 'ku', 'nqo', 'ps', 'sd', 'ug', 'ur', 'yi']);

function plainObject(value) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return false;
  const prototype = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
}

export function normalizeUiLocale(locale, fallback = 'en') {
  const input = String(locale || fallback).replaceAll('_', '-');
  if (input.length > MAX_LOCALE_LENGTH) throw new TypeError('UI locale is too long.');
  try {
    const [normalized] = Intl.getCanonicalLocales(input);
    if (!normalized) throw new TypeError('UI locale is empty.');
    return normalized;
  } catch (failure) {
    throw new TypeError(`Invalid UI locale "${input}".`, { cause: failure });
  }
}

export function uiLocaleDirection(locale, declaredDirection) {
  if (declaredDirection !== undefined && declaredDirection !== 'ltr' && declaredDirection !== 'rtl') {
    throw new TypeError('UI catalog direction must be "ltr" or "rtl".');
  }
  if (declaredDirection) return declaredDirection;
  const parsed = new Intl.Locale(normalizeUiLocale(locale));
  return RTL_SCRIPTS.has(parsed.script) || RTL_LANGUAGES.has(parsed.language) ? 'rtl' : 'ltr';
}

export function hasUiText(key) {
  return Object.hasOwn(ENGLISH_MESSAGES, key);
}

export function uiLocaleCandidates(locale) {
  const normalized = normalizeUiLocale(locale);
  const language = new Intl.Locale(normalized).language;
  return [...new Set([normalized, language, 'en'])];
}

function placeholders(template) {
  return [...String(template).matchAll(/\{([A-Za-z][A-Za-z0-9_]*)\}/g)].map(match => match[1]);
}

function messageVariants(message, key) {
  if (typeof message === 'string') return { other: message };
  if (!plainObject(message)) throw new TypeError(`UI text "${key}" must be a string or plural object.`);
  const entries = Object.entries(message);
  if (!entries.length || !Object.hasOwn(message, 'other')) {
    throw new TypeError(`Plural UI text "${key}" must define "other".`);
  }
  for (const [category, value] of entries) {
    if (!PLURAL_CATEGORIES.has(category) || typeof value !== 'string') {
      throw new TypeError(`Invalid plural category "${category}" for UI text "${key}".`);
    }
  }
  return message;
}

function validateMessage(key, message, sourceMessage) {
  const variants = messageVariants(message, key);
  const sourceVariants = sourceMessage === undefined ? null : messageVariants(sourceMessage, key);
  for (const [category, template] of Object.entries(variants)) {
    if (!template.length || template.length > MAX_MESSAGE_LENGTH) {
      throw new TypeError(`UI text "${key}" has an invalid length.`);
    }
    const sourceTemplate = sourceVariants?.[category] ?? sourceVariants?.other;
    if (sourceTemplate !== undefined) {
      const actual = [...new Set(placeholders(template))].sort();
      const expected = [...new Set(placeholders(sourceTemplate))].sort();
      if (actual.join('\0') !== expected.join('\0')) {
        throw new TypeError(`UI text "${key}" has incompatible named parameters.`);
      }
    }
  }
}

export function validateUiCatalog(input, { sourceMessages = ENGLISH_MESSAGES } = {}) {
  if (!plainObject(input) || input.schema !== UI_CATALOG_SCHEMA) {
    throw new TypeError(`UI catalog schema must be "${UI_CATALOG_SCHEMA}".`);
  }
  const locale = normalizeUiLocale(input.locale);
  const direction = uiLocaleDirection(locale, input.direction);
  if (!plainObject(input.messages)) throw new TypeError('UI catalog messages must be an object.');
  const entries = Object.entries(input.messages);
  if (entries.length > MAX_CATALOG_KEYS) throw new RangeError('UI catalog has too many messages.');
  const messages = Object.create(null);
  for (const [key, message] of entries) {
    if (!/^[A-Za-z][A-Za-z0-9]*(?:[.-][A-Za-z0-9]+)+$/.test(key)) {
      throw new TypeError(`Invalid UI text key "${key}".`);
    }
    if (!Object.hasOwn(sourceMessages, key)) throw new TypeError(`Unknown UI text key "${key}".`);
    validateMessage(key, message, sourceMessages[key]);
    messages[key] = typeof message === 'string' ? message : Object.freeze({ ...message });
  }
  return Object.freeze({ schema: UI_CATALOG_SCHEMA, locale, direction,
    messages: Object.freeze(messages) });
}

function interpolate(template, params, key, numberFormat) {
  return template.replace(/\{([A-Za-z][A-Za-z0-9_]*)\}/g, (_, name) => {
    if (!Object.hasOwn(params, name)) throw new Error(`Missing UI text parameter "${name}" for "${key}"`);
    return typeof params[name] === 'number' ? numberFormat.format(params[name]) : String(params[name]);
  });
}

function catalogMessages(value, locale) {
  if (value?.schema === UI_CATALOG_SCHEMA) return validateUiCatalog(value).messages;
  if (!plainObject(value)) throw new TypeError(`UI catalog for "${locale}" must be an object.`);
  const validated = validateUiCatalog({ schema: UI_CATALOG_SCHEMA, locale, messages: value });
  return validated.messages;
}

export function createUiText({ locale = 'en', catalogs = {}, onDiagnostic = () => {} } = {}) {
  const normalizedLocale = normalizeUiLocale(locale);
  const supplied = Object.fromEntries(Object.entries(catalogs)
    .map(([catalogLocale, messages]) => {
      const normalized = normalizeUiLocale(catalogLocale);
      return [normalized, catalogMessages(messages, normalized)];
    }));
  const available = { ...UI_TEXT_CATALOGS, ...supplied };
  available.en = { ...ENGLISH_MESSAGES, ...(supplied.en || {}) };
  const candidates = uiLocaleCandidates(normalizedLocale);
  const numberFormat = new Intl.NumberFormat(normalizedLocale);
  const pluralRules = new Intl.PluralRules(normalizedLocale);

  const text = (key, params = {}) => {
    const catalog = candidates.map(candidate => available[candidate])
      .find(item => item && Object.hasOwn(item, key));
    if (!catalog) {
      onDiagnostic(Object.freeze({ code: 'MISSING_SOURCE_MESSAGE', key, locale: normalizedLocale }));
      return 'Unavailable';
    }
    try {
      const variants = messageVariants(catalog[key], key);
      const count = Number(params.count);
      const category = Number.isFinite(count) ? pluralRules.select(count) : 'other';
      return interpolate(variants[category] ?? variants.other, params, key, numberFormat);
    } catch (failure) {
      onDiagnostic(Object.freeze({ code: 'INVALID_MESSAGE_PARAMETERS', key,
        locale: normalizedLocale, reason: failure.message }));
      const fallback = messageVariants(ENGLISH_MESSAGES[key] ?? 'Unavailable', key).other;
      try { return interpolate(fallback, params, key, new Intl.NumberFormat('en')); }
      catch { return 'Unavailable'; }
    }
  };
  Object.defineProperties(text, {
    locale: { value: normalizedLocale },
    direction: { value: uiLocaleDirection(normalizedLocale) },
    number: { value: (value, options) => new Intl.NumberFormat(normalizedLocale, options).format(value) },
    dateTime: { value: (value, options) => new Intl.DateTimeFormat(normalizedLocale, options).format(value) },
    duration: { value: (milliseconds) => formatUiDuration(milliseconds, normalizedLocale) },
  });
  return text;
}

let sharedUiText = createUiText();
let sharedUiLocalization = null;
/** Stable delegate used by workspace modules; the main application swaps its backing locale live. */
export const uiText = (key, params) => sharedUiText(key, params);
export function useUiLocalization(localization) {
  if (!localization || typeof localization.text !== 'function') {
    throw new TypeError('UI localization context is required.');
  }
  sharedUiText = (key, params) => localization.text(key, params);
  sharedUiLocalization = localization;
}
export const uiNumber = (value, options) => sharedUiLocalization?.number(value, options)
  ?? new Intl.NumberFormat('en', options).format(value);
export const uiDateTime = (value, options) => sharedUiLocalization?.dateTime(value, options)
  ?? new Intl.DateTimeFormat('en', options).format(value);
export const uiDuration = value => sharedUiLocalization?.duration(value) ?? formatUiDuration(value, 'en');

export function formatUiDuration(milliseconds, locale = 'en') {
  const value = Number(milliseconds);
  if (!Number.isFinite(value)) return 'Unavailable';
  const absoluteSeconds = Math.max(0, Math.round(Math.abs(value) / 1_000));
  const units = absoluteSeconds >= 3_600
    ? [['hour', Math.floor(absoluteSeconds / 3_600)], ['minute', Math.floor(absoluteSeconds % 3_600 / 60)]]
    : absoluteSeconds >= 60
      ? [['minute', Math.floor(absoluteSeconds / 60)], ['second', absoluteSeconds % 60]]
      : [['second', absoluteSeconds]];
  return units.filter(([, amount], index) => amount > 0 || index === 0).map(([unit, amount]) =>
    new Intl.NumberFormat(normalizeUiLocale(locale), { style: 'unit', unit, unitDisplay: 'short' }).format(amount))
    .join(' ');
}

export function createUiLocalization({ locale = 'en', catalogs = {}, onDiagnostic = () => {} } = {}) {
  let activeLocale = normalizeUiLocale(locale);
  const available = new Map();
  const listeners = new Set();
  for (const [catalogLocale, messages] of Object.entries(catalogs)) {
    const normalized = normalizeUiLocale(catalogLocale);
    const catalog = messages?.schema === UI_CATALOG_SCHEMA ? validateUiCatalog(messages)
      : Object.freeze({ schema: UI_CATALOG_SCHEMA, locale: normalized,
        direction: uiLocaleDirection(normalized), messages: catalogMessages(messages, normalized) });
    available.set(normalized, catalog);
  }
  const textCatalogs = () => Object.fromEntries([...available]
    .map(([catalogLocale, catalog]) => [catalogLocale, catalog.messages]));
  let text = createUiText({ locale: activeLocale, catalogs: textCatalogs(), onDiagnostic });
  const notify = () => listeners.forEach(listener => listener(context));
  const rebuild = () => {
    text = createUiText({ locale: activeLocale, catalogs: textCatalogs(), onDiagnostic });
  };
  const context = Object.freeze({
    get locale() { return activeLocale; },
    get direction() { return uiLocaleDirection(activeLocale,
      available.get(activeLocale)?.direction); },
    text: (key, params) => text(key, params),
    number: (value, options) => text.number(value, options),
    dateTime: (value, options) => text.dateTime(value, options),
    duration: value => text.duration(value),
    setLocale(nextLocale) {
      const normalized = normalizeUiLocale(nextLocale);
      if (normalized === activeLocale) return false;
      activeLocale = normalized;
      rebuild();
      notify();
      return true;
    },
    registerCatalog(catalog) {
      const validated = validateUiCatalog(catalog);
      available.set(validated.locale, validated);
      rebuild();
      notify();
      return validated.locale;
    },
    removeCatalog(locale_) {
      const normalized = normalizeUiLocale(locale_);
      if (normalized === 'en') return false;
      const removed = available.delete(normalized);
      if (!removed) return false;
      if (activeLocale === normalized) activeLocale = 'en';
      rebuild();
      notify();
      return true;
    },
    availableLocales: () => Object.freeze(['en', ...[...available.keys()].filter(item => item !== 'en')]),
    subscribe(listener) {
      if (typeof listener !== 'function') throw new TypeError('UI locale listener must be a function.');
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  });
  return context;
}
