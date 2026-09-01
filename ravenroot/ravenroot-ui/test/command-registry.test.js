import { describe, expect, it, vi } from 'vitest';
import {
  createCommandRegistry,
  formatAriaShortcut,
  formatShortcut,
  isEditableTarget,
} from '../src/command-registry.js';

const command = (overrides = {}) => ({
  id: 'test.run', label: 'Run', execute: vi.fn(), placements: ['menu.run'], ...overrides,
});

describe('command registry', () => {
  it('validates ids and rejects duplicates', () => {
    expect(() => createCommandRegistry([{ label: 'No id', execute() {} }])).toThrow(/namespaced id/);
    expect(() => createCommandRegistry([command(), command()])).toThrow(/Duplicate command id/);
  });

  it('orders placements and centrally gates disabled execution', () => {
    const execute = vi.fn();
    const registry = createCommandRegistry([
      command({ id: 'test.later', order: 20 }),
      command({ id: 'test.first', order: 10, execute, isEnabled: context => context.ready }),
    ]);
    expect(registry.listPlacement('menu.run').map(item => item.id)).toEqual(['test.first', 'test.later']);
    expect(registry.execute('test.first', { ready: false })).toBe(false);
    expect(execute).not.toHaveBeenCalled();
    expect(registry.execute('test.first', { ready: true })).toBe(true);
  });

  it('matches platform primary modifiers and declines editable targets', () => {
    const registry = createCommandRegistry([
      command({ shortcuts: [{ key: 'Enter', primary: true }] }),
    ], { platform: 'mac' });
    expect(registry.matchShortcut({ key: 'Enter', metaKey: true, target: { tagName: 'DIV' } }, {}, 'global')).toBe('test.run');
    expect(registry.matchShortcut({ key: 'Enter', ctrlKey: true, target: { tagName: 'DIV' } }, {}, 'global')).toBeNull();
    expect(registry.matchShortcut({ key: 'Enter', metaKey: true, target: { tagName: 'INPUT' } }, {}, 'global')).toBeNull();
  });

  it('fences nested contenteditable and open-dialog editing from global shortcuts', () => {
    const editableAncestor = { getAttribute: () => 'true' };
    const nestedEditable = {
      tagName: 'SPAN',
      closest: selector => selector === '[contenteditable]' ? editableAncestor : null,
    };
    const dialogDocument = {
      querySelector: selector => selector.includes('dialog[open]') ? {} : null,
    };
    const dialogTarget = {
      tagName: 'BUTTON',
      closest: () => null,
      ownerDocument: dialogDocument,
    };
    const ordinaryTarget = {
      tagName: 'BUTTON',
      closest: () => null,
      ownerDocument: { querySelector: () => null },
    };

    expect(isEditableTarget(nestedEditable)).toBe(true);
    expect(isEditableTarget(dialogTarget)).toBe(true);
    expect(isEditableTarget(ordinaryTarget)).toBe(false);

    const registry = createCommandRegistry([command({ shortcuts: [{ key: 'Delete' }] })]);
    expect(registry.matchShortcut({ key: 'Delete', target: nestedEditable }, {}, 'global')).toBeNull();
    expect(registry.matchShortcut({ key: 'Delete', target: dialogTarget }, {}, 'global')).toBeNull();
    expect(registry.matchShortcut({ key: 'Delete', target: ordinaryTarget }, {}, 'global')).toBe('test.run');
  });

  it('separates canvas shortcuts from global shortcuts', () => {
    const registry = createCommandRegistry([
      command({ shortcuts: [{ key: 'ArrowRight', scope: 'canvas' }] }),
    ]);
    const event = { key: 'ArrowRight', target: { tagName: 'DIV' } };
    expect(registry.matchShortcut(event, {}, 'global')).toBeNull();
    expect(registry.matchShortcut(event, {}, 'canvas')).toBe('test.run');
  });

  it('matches a shifted physical key and its browser-normalized alias only', () => {
    const registry = createCommandRegistry([
      command({ shortcuts: [{ key: '/', keyAliases: ['?'], shift: true }] }),
    ]);
    const target = { tagName: 'DIV' };
    expect(registry.matchShortcut({ key: '/', shiftKey: true, target }, {}, 'global')).toBe('test.run');
    expect(registry.matchShortcut({ key: '?', shiftKey: true, target }, {}, 'global')).toBe('test.run');
    expect(registry.matchShortcut({ key: '/', shiftKey: false, target }, {}, 'global')).toBeNull();
    expect(registry.matchShortcut({ key: '?', shiftKey: true, target: { tagName: 'INPUT' } }, {}, 'global')).toBeNull();
    expect(registry.ariaShortcut({ key: '/', shift: true })).toBe('Shift+Slash');
  });

  it('formats visible and ARIA shortcut labels for each platform', () => {
    expect(formatShortcut({ key: 's', primary: true }, 'mac')).toBe('⌘S');
    expect(formatShortcut({ key: 's', primary: true }, 'other')).toBe('Ctrl+S');
    expect(formatAriaShortcut({ key: 'z', primary: true, shift: true }, 'mac')).toBe('Meta+Shift+z');
  });
});
