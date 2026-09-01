export const INSPECTOR_AUTOSAVE_STORAGE_KEY = 'ravenroot.inspector.autosave.v1';

export function readInspectorAutosavePreference(storage) {
  try {
    const target = storage === undefined ? globalThis.localStorage : storage;
    const value = target?.getItem(INSPECTOR_AUTOSAVE_STORAGE_KEY);
    if (value == null) return true;
    const parsed = JSON.parse(value);
    return typeof parsed === 'boolean' ? parsed : true;
  } catch {
    return true;
  }
}

export function writeInspectorAutosavePreference(enabled, storage) {
  try {
    const target = storage === undefined ? globalThis.localStorage : storage;
    target?.setItem(INSPECTOR_AUTOSAVE_STORAGE_KEY, JSON.stringify(Boolean(enabled)));
    return true;
  } catch {
    return false;
  }
}

export function nodePatchChanged(node, patch) {
  if (!node || !patch) return false;
  return Object.entries(patch).some(([key, value]) => {
    if (key === 'properties' || key === 'propertyTypes') return !sameRecord(node[key], value);
    return node[key] !== value;
  });
}

function sameRecord(left = {}, right = {}) {
  const leftKeys = Object.keys(left);
  const rightKeys = Object.keys(right);
  return leftKeys.length === rightKeys.length
    && leftKeys.every(key => Object.hasOwn(right, key) && left[key] === right[key]);
}
