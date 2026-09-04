const ENGLISH_MESSAGES = Object.freeze({
  'commands.file.new.label': 'New Document',
  'commands.file.open.label': 'Open…',
  'commands.file.replaceActive.label': 'Replace Active…',
  'commands.file.save.label': 'Save GraphML',
  'commands.file.save.help': 'Save the workflow as GraphML',
  'commands.file.close.label': 'Close Document',
  'commands.edit.undo.label': 'Undo',
  'commands.edit.undo.help': 'Undo the last change',
  'commands.edit.redo.label': 'Redo',
  'commands.edit.redo.help': 'Redo the last undone change',
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
  'commands.layout.design.help': 'Arrange every node for workflow design and editing',
  'commands.layout.monitoring.label': 'Monitoring',
  'commands.layout.monitoring.help': 'Continuously visualize live workflow activity',
  'commands.layout.arrange.hierarchical.label': 'Arrange — Hierarchical',
  'commands.layout.arrange.hierarchical.help': 'Arrange left to right by workflow dependency',
  'commands.layout.arrange.flow.label': 'Arrange — Flow',
  'commands.layout.arrange.flow.help': 'Arrange as a compact directional flow',
  'commands.layout.arrange.organic.label': 'Arrange — Organic',
  'commands.layout.arrange.organic.help': 'Arrange related nodes with balanced organic spacing',
  'commands.layout.arrange.keep.label': 'Keep positions',
  'commands.layout.arrange.keep.help': 'Keep every node where it is and fit the graph in view',
  'commands.run.play.label': 'Test',
  'commands.run.play.help': 'Test this graph with pass-through execution; node side effects are bypassed',
  'commands.run.start.label': 'Run',
  // Run always starts this graph as a process-local deployment now (register + start) --
  // listening for inbound sources when the graph names one, otherwise simply becoming an addressable,
  // stoppable deployment. Use Stop to end it either way. Test (above) remains the one-shot,
  // pass-through way to submit a payload and see a result.
  'commands.run.start.help': 'Start this graph as a process-local deployment (listening for inbound sources when it declares one); use Stop to end it',
  'commands.run.pause.label': 'Pause',
  'commands.run.pause.help': 'Pause the current graph at a safe node boundary',
  'commands.run.stop.label': 'Stop',
  'commands.run.stop.help': 'Cooperatively stop the current graph deployment only',
  'commands.run.forceStop.label': 'Force stop',
  'commands.run.forceStop.help': 'Force-kill isolated work for the current graph deployment only',
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
});

export const UI_TEXT_CATALOGS = Object.freeze({ en: ENGLISH_MESSAGES });

export function hasUiText(key) {
  return Object.hasOwn(ENGLISH_MESSAGES, key);
}

function localeCandidates(locale) {
  const input = String(locale || 'en').replaceAll('_', '-');
  let normalized;
  try {
    [normalized] = Intl.getCanonicalLocales(input);
  } catch {
    normalized = input;
  }
  const language = normalized.split('-')[0];
  return [...new Set([normalized, language, 'en'])];
}

function interpolate(template, params, key) {
  return template.replace(/\{([A-Za-z][A-Za-z0-9_]*)\}/g, (_, name) => {
    if (!Object.hasOwn(params, name)) throw new Error(`Missing UI text parameter "${name}" for "${key}"`);
    return String(params[name]);
  });
}

export function createUiText({ locale = 'en', catalogs = {} } = {}) {
  const supplied = Object.fromEntries(Object.entries(catalogs)
    .map(([catalogLocale, messages]) => [localeCandidates(catalogLocale)[0], messages]));
  const available = { ...UI_TEXT_CATALOGS, ...supplied };
  available.en = { ...ENGLISH_MESSAGES, ...(supplied.en || {}) };
  const candidates = localeCandidates(locale);

  return (key, params = {}) => {
    const catalog = candidates.map(candidate => available[candidate]).find(item => item && Object.hasOwn(item, key));
    if (!catalog) throw new Error(`Missing English UI text for "${key}"`);
    return interpolate(catalog[key], params, key);
  };
}

export const uiText = createUiText();
