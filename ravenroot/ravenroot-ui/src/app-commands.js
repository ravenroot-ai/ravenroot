import { hasUiText, uiText } from './ui-text.js';

const global = shortcut => ({ scope: 'global', ...shortcut });
const canvas = shortcut => ({ scope: 'canvas', ...shortcut });

// One catalog owns the identity, order and adapter keys of every node action. A graph overlay
// supplies a hovered node while the application menu supplies the selected node; neither surface
// gets to redefine availability or execution semantics.
export const NODE_ACTION_DEFINITIONS = Object.freeze([
  Object.freeze({ id: 'trace', glyph: '⇢', capability: 'trace', handler: 'trace' }),
  Object.freeze({
    id: 'duplicate', glyph: '⧉', capability: 'duplicate', handler: 'duplicate',
    commandId: 'edit.duplicateNode', commandOrder: 70,
  }),
  Object.freeze({ id: 'delete', glyph: '⌫', capability: 'delete', handler: 'delete' }),
]);

export function createNodeActionCatalog({ targetLabel, capabilities, handlers }) {
  return NODE_ACTION_DEFINITIONS.map(definition => ({
    ...definition,
    label: definition.id === 'trace' ? `Trace full path from ${targetLabel}`
      : definition.id === 'duplicate' ? `Duplicate ${targetLabel}` : `Delete ${targetLabel}`,
    enabled: Boolean(capabilities?.[definition.capability]),
    run: handlers?.[definition.handler] || (() => false),
  }));
}

function localizeCommand(command, t) {
  const labelKey = `commands.${command.id}.label`;
  const helpKey = `commands.${command.id}.help`;
  return {
    ...command,
    label: t(labelKey),
    ...(hasUiText(helpKey) ? { help: t(helpKey) } : {}),
  };
}

export function createAppCommands(actions, { t = uiText } = {}) {
  const active = context => context.hasDocument;
  const editable = context => context.editable;
  const modifiable = context => context.canModify;
  const modifying = context => context.modifyEnabled;
  const authoring = context => context.modifyEnabled && context.canModify;
  const viewportIdle = context => context.hasDocument && !context.edgeGestureActive;
  const renderMode = (id, order) => ({
    id: `layout.${id}`, group: 'render-mode', order: order + 100,
    placements: ['menu.layout', 'toolbar.layout', 'help'],
    execute: () => actions.setRenderMode(id), isEnabled: active,
    isChecked: context => context.hasDocument && context.renderMode === id,
    kind: 'radio',
  });
  const arrangement = (id, order) => ({
    id: `layout.arrange.${id}`, group: 'design-arrange', order: order + 200,
    placements: ['menu.layout', 'help'], execute: () => actions.arrange(id),
    isEnabled: context => active(context) && context.renderMode === 'design',
  });
  const workspaceLayout = (id, mode, order) => ({
    id: `workspace.${id}`, group: 'workspace-layout', order,
    placements: ['menu.layout', 'help'], execute: () => actions.setWorkspaceLayout(mode),
    isEnabled: active, isChecked: context => context.workspaceLayoutMode === mode,
    kind: 'radio',
  });
  const duplicateNode = NODE_ACTION_DEFINITIONS.find(action => action.id === 'duplicate');

  return [
    { id: 'file.new', group: 'document', order: 10,
      placements: ['menu.file', 'toolbar.editor'], execute: actions.newDocument },
    { id: 'file.open', group: 'document', order: 20,
      placements: ['menu.file', 'toolbar.file'], execute: actions.openFile },
    { id: 'file.replaceActive', group: 'document', order: 30,
      placements: ['menu.file'], execute: actions.replaceActive, isEnabled: active },
    { id: 'file.save', group: 'save', order: 40,
      placements: ['menu.file', 'toolbar.editor', 'help'], execute: actions.save,
      isEnabled: context => editable(context) && !context.layoutBusy,
      shortcuts: [global({ key: 's', primary: true })],
    },
    { id: 'file.close', group: 'save', order: 50,
      placements: ['menu.file'], execute: actions.closeDocument, isEnabled: active },

    { id: 'edit.undo', group: 'history', order: 10,
      placements: ['menu.edit', 'toolbar.editor', 'help'], execute: actions.undo,
      isEnabled: context => context.canUndo, shortcuts: [global({ key: 'z', primary: true })],
    },
    { id: 'edit.redo', group: 'history', order: 20,
      placements: ['menu.edit', 'toolbar.editor', 'help'], execute: actions.redo,
      isEnabled: context => context.canRedo,
      shortcuts: [global({ key: 'z', primary: true, shift: true }), global({ key: 'y', ctrl: true })],
    },
    { id: 'edit.modify', group: 'mode', order: 30, kind: 'checkbox',
      placements: ['menu.edit', 'toolbar.editor'], execute: actions.toggleModify,
      isEnabled: modifiable, isChecked: modifying },
    { id: 'edit.autosave', group: 'mode', order: 35, kind: 'checkbox',
      placements: ['menu.edit', 'toolbar.editor'], execute: actions.toggleAutosave,
      isChecked: context => context.inspectorAutosave },
    { id: 'view.navigation', group: 'graph-view', order: 5, kind: 'checkbox',
      placements: ['menu.view', 'toolbar.editor', 'help'], execute: actions.toggleNavigation,
      isEnabled: active, isChecked: context => context.navigationEnabled,
      shortcuts: [global({ key: 'h' })] },
    { id: 'edit.connect', group: 'mode', order: 40, kind: 'checkbox',
      placements: ['menu.edit', 'toolbar.editor'], execute: actions.toggleConnect,
      isEnabled: authoring, isChecked: context => context.connectArmed },
    { id: 'edit.addNode', group: 'author', order: 50,
      placements: ['menu.edit', 'toolbar.editor'], execute: actions.addNode, isEnabled: authoring },
    { id: 'edit.addEdge', group: 'author', order: 60,
      placements: ['menu.edit', 'toolbar.editor'], execute: actions.addEdge, isEnabled: authoring },
    { id: duplicateNode.commandId, group: 'author', order: duplicateNode.commandOrder,
      placements: ['menu.edit'], execute: actions[duplicateNode.handler + 'Node'],
      isEnabled: context => authoring(context) && context.canDuplicateSelectedNode },
    { id: 'edit.deleteSelection', group: 'author', order: 80,
      placements: ['menu.edit', 'help'], execute: actions.deleteSelection,
      isEnabled: context => authoring(context) && context.hasSelection,
      shortcuts: [global({ key: 'Delete' }), global({ key: 'Backspace' })],
    },
    // Only offered while the document has not already declared join semantics -- migrating a
    // document that already has the marker is a defined no-op (JoinSemantics.migrate is idempotent),
    // but a command an author can invoke for no visible effect is worse than one that is simply
    // absent once there is nothing left for it to do.
    { id: 'edit.migrateJoinSemantics', group: 'author', order: 90,
      placements: ['menu.edit'], execute: actions.migrateJoinSemantics,
      isEnabled: context => authoring(context) && context.editable && !context.hasJoinSemanticsMarker,
    },

    { id: 'view.fit', group: 'graph-view', order: 10,
      placements: ['menu.view', 'toolbar.view', 'toolbar.canvas', 'help'], execute: actions.fit,
      isEnabled: viewportIdle, shortcuts: [global({ key: 'f' })] },
    { id: 'view.zoomIn', group: 'graph-view', order: 20,
      placements: ['menu.view', 'toolbar.canvas', 'help'], execute: actions.zoomIn,
      isEnabled: viewportIdle, shortcuts: [global({ key: '+', shift: true }), global({ key: '=' })] },
    { id: 'view.zoomOut', group: 'graph-view', order: 30,
      placements: ['menu.view', 'toolbar.canvas', 'help'], execute: actions.zoomOut,
      isEnabled: viewportIdle, shortcuts: [global({ key: '-' })] },
    // Lists every open document and switches between them — the same popover the toolbar
    // document-switcher button already opens (`openDocumentSwitcher` in app.js). This command makes
    // that existing mechanism reachable from the menu bar; it does not build a second list of open
    // documents that could drift from the first.
    { id: 'view.graphs', group: 'panels', order: 35,
      placements: ['menu.view'], execute: actions.openDocumentSwitcher, isEnabled: active,
    },
    { id: 'view.closeAllDocuments', group: 'panels', order: 36,
      placements: ['menu.view'], execute: actions.closeAllDocuments,
      isEnabled: context => context.hasOpenDocuments,
    },
    { id: 'view.panels', group: 'panels', order: 40,
      placements: ['menu.view'], execute: actions.openPanels },
    { id: 'view.leftPanels', group: 'panels', order: 50, kind: 'checkbox',
      placements: ['menu.view'], execute: actions.toggleLeftPanels,
      isChecked: context => !context.leftCollapsed },
    { id: 'view.rightInspector', group: 'panels', order: 60, kind: 'checkbox',
      placements: ['menu.view'], execute: actions.toggleRightInspector,
      isChecked: context => !context.rightCollapsed },
    { id: 'view.themeDark', group: 'application-theme', order: 65, kind: 'radio',
      placements: ['menu.view'], execute: () => actions.setTheme('dark'),
      isChecked: context => context.applicationTheme === 'dark' },
    { id: 'view.themeLight', group: 'application-theme', order: 66, kind: 'radio',
      placements: ['menu.view'], execute: () => actions.setTheme('light'),
      isChecked: context => context.applicationTheme === 'light' },
    { id: 'view.keyboardShortcuts', group: 'help', order: 70,
      placements: ['menu.view', 'toolbar.view', 'help'], execute: actions.toggleHelp,
      shortcuts: [global({ key: '/', keyAliases: ['?'], shift: true })] },

    workspaceLayout('horizontal', 'horizontal', 10),
    workspaceLayout('vertical', 'vertical', 20),
    workspaceLayout('grid', 'grid', 30),
    // Always shows exactly the active document, regardless of how many are open — a deliberate
    // choice, distinct from the `active-only` state horizontal/vertical/grid already fall back to
    // when the viewport is too narrow for their floor (`planWorkspaceLayout`'s `single-mode` reason
    // keeps the two apart in the stored plan).
    workspaceLayout('single', 'single', 40),
    { id: 'workspace.reset', group: 'workspace-reset', order: 50,
      placements: ['menu.layout', 'help'], execute: actions.resetWorkspaceLayout,
      isEnabled: context => context.hasDocument && !context.workspaceLayoutDefault,
    },

    renderMode('design', 10),
    renderMode('monitoring', 20),
    arrangement('hierarchical', 10),
    arrangement('flow', 20),
    arrangement('organic', 30),
    arrangement('keep', 40),

    { id: 'run.play', group: 'execution', order: 10,
      placements: ['menu.run', 'toolbar.primary', 'help'], execute: actions.play,
      isEnabled: context => context.editable && (!context.running || context.executionUnknown === true),
      shortcuts: [global({ key: 'Enter', primary: true })],
    },
    { id: 'run.start', group: 'execution', order: 20,
      placements: ['menu.run', 'toolbar.primary', 'help'], execute: actions.run,
      isEnabled: context => context.editable && (!context.running || context.executionUnknown === true),
    },
    { id: 'run.pause', group: 'execution', order: 30,
      placements: ['menu.run', 'toolbar.primary', 'help'], execute: actions.pause,
      isEnabled: context => Boolean(context.transientRunning && !context.sourceSessionActive) },
    { id: 'run.stop', group: 'execution', order: 40,
      placements: ['menu.run', 'toolbar.primary', 'help'], execute: actions.stop,
      isEnabled: context => Boolean(context.transientRunning || context.sourceSessionActive) },
    { id: 'run.forceStop', group: 'execution', order: 50,
      placements: ['menu.run', 'toolbar.primary', 'help'], execute: actions.forceStop,
      isEnabled: context => Boolean(context.transientRunning && !context.sourceSessionActive) },
    { id: 'run.authenticate', group: 'connection', order: 60,
      placements: ['menu.run', 'toolbar.runtime'], execute: actions.authenticate },
    { id: 'run.forgetToken', group: 'connection', order: 70,
      placements: ['menu.run', 'toolbar.runtime'], execute: actions.forgetToken,
      isEnabled: context => context.hasToken },
    // Its own group, so the menu separator puts a line between "sign this editor in to my
    // service" and "store a credential a node will use". They are next to each other in the menu
    // because both are about reaching something outside the graph; they are separated because a
    // service-authentication token and a node credential have different scope and custody.
    //
    // ALWAYS ENABLED, including with no connection. The window is where an author is TOLD that a
    // credential needs a service to be stored in, and a menu entry that is greyed out until they
    // guess why teaches them nothing. Its own status line says it in a sentence instead.
    { id: 'run.credentials', group: 'credentials', order: 80,
      placements: ['menu.run'], execute: actions.openCredentials },

    // Its own group, same reasoning as `run.credentials` just above: a line separates
    // "sign this editor in" and "store a credential" from "manage a registered deployment", because
    // they are three different relationships with the service. ALWAYS ENABLED for the identical
    // reason credentials is -- the window itself is where an author learns a service connection is
    // needed, rather than a greyed-out entry teaching them nothing about why.
    { id: 'run.deployments', group: 'deployments', order: 90,
      placements: ['menu.run'], execute: actions.openDeployments },

    { id: 'canvas.cursor', group: 'canvas', order: 100,
      placements: ['help'], execute: actions.graphKey, isEnabled: active,
      shortcuts: ['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].map(key => canvas({ key })),
    },
    { id: 'canvas.edges', group: 'canvas', order: 110,
      placements: ['help'], execute: actions.graphKey, isEnabled: active,
      shortcuts: ['ArrowLeft', 'ArrowRight'].map(key => canvas({ key, shift: true })),
    },
    { id: 'canvas.confirm', group: 'canvas', order: 120,
      placements: ['help'], execute: actions.graphKey, isEnabled: active,
      shortcuts: [canvas({ key: 'Enter' })] },
    { id: 'canvas.startEdge', group: 'canvas', order: 130,
      placements: ['help'], execute: actions.graphKey, isEnabled: authoring,
      shortcuts: [canvas({ key: 'e' })] },
    { id: 'canvas.reconnect', group: 'canvas', order: 140,
      placements: ['help'], execute: actions.graphKey, isEnabled: authoring,
      shortcuts: [canvas({ key: 'r' }), canvas({ key: 'r', shift: true })],
    },
    { id: 'canvas.cancel', group: 'canvas', order: 150,
      placements: ['help'], execute: actions.graphKey,
      shortcuts: [canvas({ key: 'Escape' })],
      isEnabled: context => Boolean(context.hasDocument
        && (context.edgeGestureActive || context.nodeMoveActive)),
    },
    { id: 'ui.dismiss', group: 'canvas', order: 160,
      placements: ['help'], execute: actions.dismiss,
      shortcuts: [global({ key: 'Escape' })] },
  ].map(command => localizeCommand(command, t));
}
