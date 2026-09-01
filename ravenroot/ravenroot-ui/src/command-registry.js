const EDITABLE_TAGS = new Set(['INPUT', 'TEXTAREA', 'SELECT']);
const DIALOG_SELECTOR = 'dialog[open], [role="dialog"][aria-modal="true"]';

export function isEditableTarget(target) {
  if (!target) return false;
  const element = target.nodeType === 3 ? target.parentElement : target;
  if (!element) return false;
  const tagName = String(element.tagName || '').toUpperCase();
  if (EDITABLE_TAGS.has(tagName) || element.isContentEditable) return true;
  const editableAncestor = element.closest?.('[contenteditable]');
  if (editableAncestor && editableAncestor.getAttribute?.('contenteditable') !== 'false') return true;
  // A modal can stop bubbling after it handles its own keyboard contract. Capture-phase graph
  // shortcuts therefore need an explicit dialog fence, including the rare case where focus is
  // temporarily restored outside the top layer while a dialog is still open.
  if (element.closest?.(DIALOG_SELECTOR)) return true;
  return Boolean(element.ownerDocument?.querySelector?.(DIALOG_SELECTOR));
}

export function detectShortcutPlatform(navigator_ = globalThis.navigator) {
  const platform = navigator_?.userAgentData?.platform || navigator_?.platform || '';
  return /mac|iphone|ipad|ipod/i.test(platform) ? 'mac' : 'other';
}

export function createCommandRegistry(definitions, options = {}) {
  const platform = options.platform || detectShortcutPlatform();
  const commands = new Map();
  for (const definition of definitions) {
    validateCommand(definition);
    if (commands.has(definition.id)) throw new Error(`Duplicate command id '${definition.id}'`);
    commands.set(definition.id, Object.freeze({ ...definition }));
  }

  function get(id) {
    const command = commands.get(id);
    if (!command) throw new Error(`Unknown command '${id}'`);
    return command;
  }

  function state(id, context) {
    const command = get(id);
    return {
      enabled: command.isEnabled ? Boolean(command.isEnabled(context)) : true,
      checked: command.isChecked ? Boolean(command.isChecked(context)) : undefined,
    };
  }

  function execute(id, context, invocation = {}) {
    const command = get(id);
    if (!state(id, context).enabled) return false;
    command.execute(context, invocation);
    return true;
  }

  function listPlacement(placement) {
    return [...commands.values()]
      .filter(command => command.placements?.includes(placement))
      .sort((left, right) => (left.order || 0) - (right.order || 0));
  }

  function matchShortcut(event, context, scope = 'global') {
    if (isEditableTarget(event.target)) return null;
    for (const command of commands.values()) {
      for (const shortcut of command.shortcuts || []) {
        if ((shortcut.scope || 'global') !== scope || !matchesShortcut(event, shortcut, platform)) continue;
        if (!state(command.id, context).enabled) return null;
        return command.id;
      }
    }
    return null;
  }

  return Object.freeze({
    platform,
    get,
    state,
    execute,
    listPlacement,
    matchShortcut,
    shortcutLabel: shortcut => formatShortcut(shortcut, platform),
    ariaShortcut: shortcut => formatAriaShortcut(shortcut, platform),
    all: () => [...commands.values()],
  });
}

function validateCommand(command) {
  if (!command || typeof command.id !== 'string' || !command.id.includes('.')) {
    throw new Error('Every command needs a namespaced id');
  }
  if (typeof command.label !== 'string' || !command.label.trim()) {
    throw new Error(`Command '${command.id}' needs a label`);
  }
  if (typeof command.execute !== 'function') {
    throw new Error(`Command '${command.id}' needs an execute function`);
  }
}

function matchesShortcut(event, shortcut, platform) {
  const key = String(event.key || '');
  const acceptedKeys = [shortcut.key, ...(shortcut.keyAliases || [])].map(normalizeKey);
  if (!acceptedKeys.includes(normalizeKey(key))) return false;
  const primary = platform === 'mac' ? event.metaKey : event.ctrlKey;
  const secondaryPrimary = platform === 'mac' ? event.ctrlKey : event.metaKey;
  if (Boolean(shortcut.primary) !== Boolean(primary)) return false;
  if (!shortcut.primary && (event.metaKey || event.ctrlKey) && !shortcut.ctrl) return false;
  if (shortcut.primary && secondaryPrimary) return false;
  if (Boolean(shortcut.ctrl) !== Boolean(event.ctrlKey && !shortcut.primary)) return false;
  if (Boolean(shortcut.shift) !== Boolean(event.shiftKey)) return false;
  if (Boolean(shortcut.alt) !== Boolean(event.altKey)) return false;
  return true;
}

function normalizeKey(key) {
  return key.length === 1 ? key.toLowerCase() : key;
}

export function formatShortcut(shortcut, platform = 'other') {
  const parts = [];
  if (shortcut.primary) parts.push(platform === 'mac' ? '⌘' : 'Ctrl');
  if (shortcut.ctrl) parts.push('Ctrl');
  if (shortcut.alt) parts.push(platform === 'mac' ? '⌥' : 'Alt');
  if (shortcut.shift) parts.push(platform === 'mac' ? '⇧' : 'Shift');
  parts.push(displayKey(shortcut.key));
  return platform === 'mac' ? parts.join('') : parts.join('+');
}

export function formatAriaShortcut(shortcut, platform = 'other') {
  const parts = [];
  if (shortcut.primary) parts.push(platform === 'mac' ? 'Meta' : 'Control');
  if (shortcut.ctrl) parts.push('Control');
  if (shortcut.alt) parts.push('Alt');
  if (shortcut.shift) parts.push('Shift');
  parts.push(ariaKey(shortcut.key));
  return parts.join('+');
}

function displayKey(key) {
  return ({ ArrowUp: '↑', ArrowDown: '↓', ArrowLeft: '←', ArrowRight: '→',
    Escape: 'Esc', Enter: 'Enter', Delete: 'Delete', Backspace: 'Backspace', ' ': 'Space' })[key] || key.toUpperCase();
}

function ariaKey(key) {
  return ({ ' ': 'Space', '+': 'Plus', '-': 'Minus', '=': 'Equals', '/': 'Slash', '?': 'QuestionMark' })[key] || key;
}
