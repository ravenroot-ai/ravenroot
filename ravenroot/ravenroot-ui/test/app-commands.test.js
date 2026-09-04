import { readFileSync } from 'node:fs';
import { describe, expect, it, vi } from 'vitest';
import {
  createAppCommands,
  createNodeActionCatalog,
  NODE_ACTION_DEFINITIONS,
} from '../src/app-commands.js';

const actions = new Proxy({}, { get: () => vi.fn() });

describe('application command catalog', () => {
  const commands = createAppCommands(actions);

  it('contains the five textual menus without reserved or future commands', () => {
    for (const menu of ['file', 'edit', 'view', 'layout', 'run']) {
      expect(commands.some(command => command.placements?.includes(`menu.${menu}`))).toBe(true);
    }
    expect(commands.some(command => /save all|recent|rename|preferences/i.test(command.label))).toBe(false);
    const reserved = commands.flatMap(command => command.shortcuts || [])
      .filter(shortcut => shortcut.primary).map(shortcut => shortcut.key.toLowerCase());
    expect(reserved).not.toContain('n');
    expect(reserved).not.toContain('o');
    expect(reserved).not.toContain('w');
  });

  it('expresses document, editing, layout, runtime and selection state without DOM access', () => {
    const byId = Object.fromEntries(commands.map(command => [command.id, command]));
    const context = {
      hasDocument: true, editable: true, canModify: true, modifyEnabled: true,
      layoutBusy: false,
      connectArmed: true, hasSelection: true, layoutMode: 'cyto', renderMode: 'design', running: false,
      canUndo: true, canRedo: false, hasToken: true, leftCollapsed: false, rightCollapsed: true,
      workspaceLayoutMode: 'grid', workspaceLayoutDefault: false,
      applicationTheme: 'dark',
      canDuplicateSelectedNode: true,
    };
    expect(byId['file.replaceActive'].isEnabled(context)).toBe(true);
    expect(byId['edit.undo'].isEnabled(context)).toBe(true);
    expect(byId['edit.redo'].isEnabled(context)).toBe(false);
    expect(byId['edit.connect'].isChecked(context)).toBe(true);
    expect(byId['edit.duplicateNode'].isEnabled(context)).toBe(true);
    expect(byId['edit.duplicateNode'].isEnabled({ ...context, canDuplicateSelectedNode: false })).toBe(false);
    expect(byId['layout.design'].isChecked(context)).toBe(true);
    expect(byId['layout.design'].isChecked({ ...context, hasDocument: false })).toBe(false);
    expect(byId['workspace.grid'].isChecked(context)).toBe(true);
    expect(byId['workspace.reset'].isEnabled(context)).toBe(true);
    expect(byId['run.play'].isEnabled(context)).toBe(true);
    expect(byId['run.play'].isEnabled({ ...context, running: true })).toBe(false);
    expect(byId['run.play'].isEnabled({ ...context, running: true, executionUnknown: true })).toBe(true);
    expect(byId['run.start'].isEnabled({ ...context, running: true, executionUnknown: true })).toBe(true);
    expect(byId['run.play'].label).toBe('Test');
    expect(byId['run.play'].help).toMatch(/pass-through/);
    expect(byId['canvas.cancel'].isEnabled({ ...context, edgeGestureActive: true })).toBe(true);
    expect(byId['canvas.cancel'].isEnabled({ ...context, nodeMoveActive: true })).toBe(true);
    expect(byId['canvas.cancel'].isEnabled(context)).toBe(false);
    for (const id of ['view.fit', 'view.zoomIn', 'view.zoomOut']) {
      expect(byId[id].isEnabled(context)).toBe(true);
      expect(byId[id].isEnabled({ ...context, edgeGestureActive: true })).toBe(false);
    }
    expect(byId['run.start'].isEnabled(context)).toBe(true);
    for (const id of ['run.pause', 'run.stop', 'run.forceStop']) {
      expect(byId[id].isEnabled(context)).toBe(false);
      expect(byId[id].placements).toContain('menu.run');
      expect(byId[id].placements).toContain('toolbar.primary');
    }
    expect(byId['run.stop'].isEnabled({ ...context, sourceSessionActive: true })).toBe(true);
    expect(byId['run.pause'].isEnabled({ ...context, sourceSessionActive: true })).toBe(false);
    expect(byId['run.forceStop'].isEnabled({ ...context, sourceSessionActive: true })).toBe(false);
    expect(byId['run.pause'].isEnabled({ ...context, transientRunning: true })).toBe(true);
    expect(byId['run.forceStop'].isEnabled({ ...context, transientRunning: true })).toBe(true);
    expect(byId['view.rightInspector'].isChecked(context)).toBe(false);
    expect(byId['view.themeDark'].isChecked(context)).toBe(true);
    expect(byId['view.themeLight'].isChecked(context)).toBe(false);
    // Offered while the document is editable and has not already declared join semantics;
    // withdrawn once it has, since migrating an already-declared document is a defined no-op.
    expect(byId['edit.migrateJoinSemantics'].placements).toContain('menu.edit');
    expect(byId['edit.migrateJoinSemantics'].isEnabled(context)).toBe(true);
    expect(byId['edit.migrateJoinSemantics'].isEnabled({ ...context, hasJoinSemanticsMarker: true }))
      .toBe(false);
    expect(byId['edit.migrateJoinSemantics'].isEnabled({ ...context, modifyEnabled: false })).toBe(false);
    for (const id of ['file.save', 'edit.connect', 'edit.addNode', 'edit.addEdge',
      'edit.duplicateNode', 'edit.deleteSelection', 'edit.migrateJoinSemantics',
      'canvas.startEdge', 'canvas.reconnect']) {
      expect(byId[id].isEnabled({ ...context, layoutBusy: true, canModify: false })).toBe(false);
    }
  });

  it('gives every presented shortcut a help-owned command', () => {
    expect(commands.filter(command => command.shortcuts?.length)
      .every(command => command.placements.includes('help'))).toBe(true);
  });

  it('localizes labels and help without changing command behavior or shortcut semantics', () => {
    const stableActions = { save: vi.fn() };
    const translated = createAppCommands(stableActions, { t: key => `translated:${key}` });
    const original = Object.fromEntries(
      createAppCommands(stableActions).map(command => [command.id, command]));
    const localized = Object.fromEntries(translated.map(command => [command.id, command]));

    expect(localized['file.save']).toMatchObject({
      label: 'translated:commands.file.save.label',
      help: 'translated:commands.file.save.help',
      placements: original['file.save'].placements,
      shortcuts: original['file.save'].shortcuts,
      execute: original['file.save'].execute,
    });
    expect(localized['view.keyboardShortcuts'].shortcuts)
      .toEqual(original['view.keyboardShortcuts'].shortcuts);
  });

  it('rejects inline command labels and help in the command definition source', () => {
    const source = readFileSync('src/app-commands.js', 'utf8');
    const inlineCommandText = /\b(?:label|help)\s*:\s*(['"`])/;

    expect(inlineCommandText.test("{ id: 'file.example', label:\n  'Inline text' }")).toBe(true);
    expect(inlineCommandText.test("{ id: 'file.example', help: `Inline help` }")).toBe(true);
    expect(source.match(inlineCommandText)).toBeNull();
  });

  it('offers exactly Design and Monitoring as one exclusive group without technical shortcuts', () => {
    const byId = Object.fromEntries(commands.map(command => [command.id, command]));
    const renderCommands = commands.filter(command => command.group === 'render-mode');
    expect(renderCommands.map(command => command.id)).toEqual(['layout.design', 'layout.monitoring']);
    expect(renderCommands.map(command => command.label)).toEqual(['Design', 'Monitoring']);
    expect(renderCommands.every(command => command.kind === 'radio'
      && command.placements.includes('menu.layout')
      && command.placements.includes('toolbar.layout')
      && !command.shortcuts)).toBe(true);
    expect(renderCommands.filter(command => command.isChecked({
      hasDocument: true, renderMode: 'design',
    })).map(command => command.id)).toEqual(['layout.design']);
    expect(byId['layout.monitoring'].isChecked({ hasDocument: false, renderMode: 'monitoring' }))
      .toBe(false);
    expect(byId['layout.design'].help).toMatch(/Arrange every node/);

    const setRenderMode = vi.fn();
    const spied = Object.fromEntries(createAppCommands({ setRenderMode }).map(command => [command.id, command]));
    spied['layout.design'].execute();
    spied['layout.monitoring'].execute();
    expect(setRenderMode.mock.calls).toEqual([['design'], ['monitoring']]);
  });

  it('adds the layered arrangements as a sibling group after the established four', () => {
    const commands = createAppCommands({});
    const established = commands.filter(command => command.group === 'design-arrange');
    const layered = commands.filter(command => command.group === 'design-arrange-layered');
    expect(layered.map(command => command.id)).toEqual([
      'layout.arrange.hierarchical-new',
      'layout.arrange.flow-new',
    ]);
    expect(layered.map(command => command.label)).toEqual([
      'Arrange — Hierarchical (new)',
      'Arrange — Flow (new)',
    ]);
    expect(Math.min(...layered.map(command => command.order)))
      .toBeGreaterThan(Math.max(...established.map(command => command.order)));
    expect(layered.every(command => command.kind == null
      && command.placements.includes('menu.layout') && command.placements.includes('help'))).toBe(true);
    expect(layered.every(command => command.isEnabled({ hasDocument: true, renderMode: 'design' }))).toBe(true);
    expect(layered.some(command => command.isEnabled({ hasDocument: true, renderMode: 'monitoring' }))).toBe(false);
    const arrange = vi.fn();
    const spied = Object.fromEntries(createAppCommands({ arrange }).map(command => [command.id, command]));
    spied['layout.arrange.hierarchical-new'].execute();
    spied['layout.arrange.flow-new'].execute();
    expect(arrange.mock.calls).toEqual([['hierarchical-new'], ['flow-new']]);
  });

  it('offers semantic Design arrangements as actions after the render modes', () => {
    const byId = Object.fromEntries(commands.map(command => [command.id, command]));
    const arrangeCommands = commands.filter(command => command.group === 'design-arrange');
    expect(arrangeCommands.map(command => command.id)).toEqual([
      'layout.arrange.hierarchical',
      'layout.arrange.flow',
      'layout.arrange.organic',
      'layout.arrange.keep',
    ]);
    expect(arrangeCommands.map(command => command.label)).toEqual([
      'Arrange — Hierarchical', 'Arrange — Flow', 'Arrange — Organic', 'Keep positions',
    ]);
    expect(arrangeCommands.every(command => command.kind == null
      && command.placements.includes('menu.layout')
      && command.placements.includes('help'))).toBe(true);
    expect(arrangeCommands.every(command => command.isEnabled({
      hasDocument: true, renderMode: 'design',
    }))).toBe(true);
    expect(arrangeCommands.some(command => command.isEnabled({
      hasDocument: true, renderMode: 'monitoring',
    }))).toBe(false);
    expect(arrangeCommands.some(command => command.isEnabled({
      hasDocument: false, renderMode: 'design',
    }))).toBe(false);
    expect(byId['layout.arrange.keep'].help).toMatch(/fit the graph/i);

    const arrange = vi.fn();
    const spied = Object.fromEntries(createAppCommands({ arrange }).map(command => [command.id, command]));
    spied['layout.arrange.hierarchical'].execute();
    spied['layout.arrange.flow'].execute();
    spied['layout.arrange.organic'].execute();
    spied['layout.arrange.keep'].execute();
    expect(arrange.mock.calls).toEqual([
      ['hierarchical'], ['flow'], ['organic'], ['keep'],
    ]);
  });

  // "single" is a fourth workspace layout choice, offered the same way as the existing three.
  it('offers Single pane as a fourth workspace layout, alongside horizontal/vertical/grid', () => {
    const byId = Object.fromEntries(commands.map(command => [command.id, command]));
    expect(byId['workspace.single']).toMatchObject({
      label: 'Single pane', kind: 'radio', group: 'workspace-layout',
    });
    expect(byId['workspace.single'].placements).toContain('menu.layout');
    expect(byId['workspace.single'].isChecked({ workspaceLayoutMode: 'single' })).toBe(true);
    expect(byId['workspace.single'].isChecked({ workspaceLayoutMode: 'grid' })).toBe(false);

    // A stable spy, not the shared `actions` Proxy above (which mints a fresh vi.fn() on every
    // property read, so a read taken here could never equal the one `execute` closed over).
    const setWorkspaceLayout = vi.fn();
    const byIdSpied = Object.fromEntries(
      createAppCommands({ setWorkspaceLayout }).map(command => [command.id, command]));
    byIdSpied['workspace.single'].execute({ hasDocument: true });
    expect(setWorkspaceLayout).toHaveBeenCalledWith('single');
  });

  // The menu entry lists open documents and lets the user switch
  // between them. That mechanism already exists as the toolbar document-switcher popover
  // (`openDocumentSwitcher` in app.js) — this command must make it reachable from the menu bar
  // rather than build a second list that could drift from the first.
  it('exposes the existing document switcher from the View menu instead of a second mechanism', () => {
    const byId = Object.fromEntries(commands.map(command => [command.id, command]));
    const command = byId['view.graphs'];
    expect(command).toBeTruthy();
    expect(command.placements).toContain('menu.view');
    expect(command.isEnabled({ hasDocument: true })).toBe(true);

    const openDocumentSwitcher = vi.fn();
    const byIdSpied = Object.fromEntries(
      createAppCommands({ openDocumentSwitcher }).map(cmd => [cmd.id, cmd]));
    byIdSpied['view.graphs'].execute({ hasDocument: true });
    expect(openDocumentSwitcher).toHaveBeenCalledOnce();
  });
});

describe('canonical node action catalog', () => {
  it('adapts one ordered definition set to hovered and selected targets', () => {
    const handlers = { trace: vi.fn(), duplicate: vi.fn(), delete: vi.fn() };
    const hovered = createNodeActionCatalog({
      targetLabel: 'Hovered', capabilities: { trace: true, duplicate: false, delete: true }, handlers,
    });
    expect(hovered.map(action => action.id)).toEqual(NODE_ACTION_DEFINITIONS.map(action => action.id));
    expect(hovered.map(action => action.label)).toEqual([
      'Trace full path from Hovered', 'Duplicate Hovered', 'Delete Hovered',
    ]);
    expect(hovered.map(action => action.enabled)).toEqual([true, false, true]);
    hovered[2].run();
    expect(handlers.delete).toHaveBeenCalledOnce();
  });

  it('derives the selected-node application command from the duplicate action definition', () => {
    const definition = NODE_ACTION_DEFINITIONS.find(action => action.id === 'duplicate');
    const duplicateNode = vi.fn();
    const command = createAppCommands({ duplicateNode })
      .find(candidate => candidate.id === definition.commandId);
    expect(command).toMatchObject({ order: definition.commandOrder, execute: duplicateNode });
    expect(command.isEnabled({ modifyEnabled: true, canModify: true, canDuplicateSelectedNode: true })).toBe(true);
    expect(command.isEnabled({ modifyEnabled: true, canModify: true, canDuplicateSelectedNode: false })).toBe(false);
    expect(command.isEnabled({ modifyEnabled: false, canModify: true, canDuplicateSelectedNode: true })).toBe(false);
  });
});
