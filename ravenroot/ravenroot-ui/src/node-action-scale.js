export const NODE_ACTION_SCALE_STORAGE_KEY = 'ravenroot.node-actions.scale.v1';
export const NODE_ACTION_SCALE_VERSION = 1;
export const NODE_ACTION_SCALE_MIN = 75;
export const NODE_ACTION_SCALE_MAX = 175;
export const NODE_ACTION_SCALE_STEP = 5;
export const NODE_ACTION_SCALE_DEFAULT = 115;

const BASELINE = Object.freeze({
  buttonWidth: 18,
  buttonHeight: 17,
  glyphSize: 12,
  gap: 1,
  padding: 1,
  border: 1,
  barRadius: 5,
  buttonRadius: 3,
  bridgeGap: 8,
  edgeInset: 4,
  menuMinWidth: 190,
  menuPadding: 4,
  menuRadius: 5,
  menuItemMinHeight: 32,
  menuItemPaddingBlock: 5,
  menuItemPaddingInline: 8,
  menuItemRadius: 3,
});

function finiteNumber(value) {
  if (typeof value === 'string' && value.trim() === '') return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

export function normalizeNodeActionScale(value, { clamp = false } = {}) {
  const number = finiteNumber(value);
  if (number == null) return NODE_ACTION_SCALE_DEFAULT;
  if (!clamp && (number < NODE_ACTION_SCALE_MIN || number > NODE_ACTION_SCALE_MAX)) {
    return NODE_ACTION_SCALE_DEFAULT;
  }
  const bounded = Math.min(NODE_ACTION_SCALE_MAX, Math.max(NODE_ACTION_SCALE_MIN, number));
  return Math.min(NODE_ACTION_SCALE_MAX, Math.max(NODE_ACTION_SCALE_MIN,
    Math.round(bounded / NODE_ACTION_SCALE_STEP) * NODE_ACTION_SCALE_STEP));
}

export function resolveNodeActionScalePreference(raw) {
  if (raw == null) return { percent: NODE_ACTION_SCALE_DEFAULT, migrated: false };
  try {
    const parsed = JSON.parse(raw);
    if (typeof parsed === 'number') {
      const percent = normalizeNodeActionScale(parsed);
      return {
        percent,
        migrated: Number.isFinite(parsed)
          && parsed >= NODE_ACTION_SCALE_MIN && parsed <= NODE_ACTION_SCALE_MAX,
      };
    }
    if (!parsed || parsed.version !== NODE_ACTION_SCALE_VERSION) {
      return { percent: NODE_ACTION_SCALE_DEFAULT, migrated: false };
    }
    const percent = normalizeNodeActionScale(parsed.percent);
    const valid = Number.isFinite(parsed.percent)
      && parsed.percent >= NODE_ACTION_SCALE_MIN && parsed.percent <= NODE_ACTION_SCALE_MAX;
    return { percent: valid ? percent : NODE_ACTION_SCALE_DEFAULT, migrated: false };
  } catch {
    return { percent: NODE_ACTION_SCALE_DEFAULT, migrated: false };
  }
}

export function writeNodeActionScalePreference(percent, storage) {
  const normalized = normalizeNodeActionScale(percent, { clamp: true });
  try {
    const target = storage === undefined ? globalThis.localStorage : storage;
    target?.setItem(NODE_ACTION_SCALE_STORAGE_KEY, JSON.stringify({
      version: NODE_ACTION_SCALE_VERSION,
      percent: normalized,
    }));
    return true;
  } catch {
    return false;
  }
}

export function readNodeActionScalePreference(storage) {
  try {
    const target = storage === undefined ? globalThis.localStorage : storage;
    const resolved = resolveNodeActionScalePreference(target?.getItem(NODE_ACTION_SCALE_STORAGE_KEY));
    if (resolved.migrated) writeNodeActionScalePreference(resolved.percent, target);
    return resolved.percent;
  } catch {
    return NODE_ACTION_SCALE_DEFAULT;
  }
}

export function nodeActionGeometry(percent) {
  const normalized = normalizeNodeActionScale(percent, { clamp: true });
  const factor = normalized / 100;
  // Stable decimal CSS values make the percentage contract inspectable and avoid exposing binary
  // floating-point tails (for example 17 * 1.15) through computed custom properties.
  const scaled = value => Number((value * factor).toFixed(4));
  const buttonWidth = scaled(BASELINE.buttonWidth);
  const buttonHeight = scaled(BASELINE.buttonHeight);
  const menuItemMinHeight = scaled(BASELINE.menuItemMinHeight);
  return Object.freeze({
    percent: normalized,
    factor,
    buttonWidth,
    buttonHeight,
    glyphSize: scaled(BASELINE.glyphSize),
    gap: scaled(BASELINE.gap),
    padding: scaled(BASELINE.padding),
    border: scaled(BASELINE.border),
    barInset: scaled(BASELINE.padding + BASELINE.border),
    coarseBarInset: scaled(2 + BASELINE.border),
    barRadius: scaled(BASELINE.barRadius),
    buttonRadius: scaled(BASELINE.buttonRadius),
    bridgeGap: scaled(BASELINE.bridgeGap),
    edgeInset: scaled(BASELINE.edgeInset),
    menuMinWidth: scaled(BASELINE.menuMinWidth),
    menuPadding: scaled(BASELINE.menuPadding),
    menuInset: scaled(BASELINE.menuPadding + BASELINE.border),
    menuRadius: scaled(BASELINE.menuRadius),
    menuItemMinHeight,
    menuItemPaddingBlock: scaled(BASELINE.menuItemPaddingBlock),
    menuItemPaddingInline: scaled(BASELINE.menuItemPaddingInline),
    menuItemRadius: scaled(BASELINE.menuItemRadius),
    coarseButtonWidth: Math.max(44, scaled(44)),
    coarseButtonHeight: Math.max(44, scaled(44)),
    coarseMenuMinWidth: Math.max(BASELINE.menuMinWidth, scaled(BASELINE.menuMinWidth)),
    coarseMenuItemMinHeight: Math.max(44, scaled(44)),
  });
}

const CSS_VARIABLES = Object.freeze({
  buttonWidth: '--node-action-button-w',
  buttonHeight: '--node-action-button-h',
  glyphSize: '--node-action-glyph-size',
  gap: '--node-action-gap',
  padding: '--node-action-padding',
  border: '--node-action-border',
  barInset: '--node-action-bar-inset',
  coarseBarInset: '--node-action-coarse-bar-inset',
  barRadius: '--node-action-bar-radius',
  buttonRadius: '--node-action-button-radius',
  menuMinWidth: '--node-action-menu-min-w',
  menuPadding: '--node-action-menu-padding',
  menuInset: '--node-action-menu-inset',
  menuRadius: '--node-action-menu-radius',
  menuItemMinHeight: '--node-action-menu-item-min-h',
  menuItemPaddingBlock: '--node-action-menu-item-padding-block',
  menuItemPaddingInline: '--node-action-menu-item-padding-inline',
  menuItemRadius: '--node-action-menu-item-radius',
  coarseButtonWidth: '--node-action-coarse-button-w',
  coarseButtonHeight: '--node-action-coarse-button-h',
  coarseMenuMinWidth: '--node-action-coarse-menu-min-w',
  coarseMenuItemMinHeight: '--node-action-coarse-menu-item-min-h',
});

export function applyNodeActionGeometry(root, percent) {
  const geometry = nodeActionGeometry(percent);
  if (!root?.style) return geometry;
  root.dataset.nodeActionScale = String(geometry.percent);
  Object.entries(CSS_VARIABLES).forEach(([key, property]) => {
    root.style.setProperty(property, `${geometry[key]}px`);
  });
  return geometry;
}

export function bindNodeActionScaleControl({ input, output, storage, root, onChange = () => {} }) {
  let percent = readNodeActionScalePreference(storage);
  const publish = (source, persist) => {
    const geometry = applyNodeActionGeometry(root, percent);
    if (input) input.value = String(percent);
    if (output) output.textContent = String(percent);
    if (persist) writeNodeActionScalePreference(percent, storage);
    onChange(geometry, { source });
    return geometry;
  };
  const onInput = event => {
    percent = normalizeNodeActionScale(event.currentTarget?.value, { clamp: true });
    publish('user', true);
  };
  input?.addEventListener('input', onInput);
  publish('initial', false);
  return Object.freeze({
    get percent() { return percent; },
    select(value) {
      percent = normalizeNodeActionScale(value, { clamp: true });
      return publish('programmatic', true);
    },
    destroy() { input?.removeEventListener('input', onInput); },
  });
}
