import { describe, expect, it, vi } from 'vitest';
import {
  INSPECTOR_AUTOSAVE_STORAGE_KEY,
  nodePatchChanged,
  readInspectorAutosavePreference,
  writeInspectorAutosavePreference,
} from '../src/inspector-autosave.js';

describe('Inspector autosave preference', () => {
  it('defaults ON for missing, corrupt, wrong-shaped, and unavailable storage', () => {
    expect(readInspectorAutosavePreference({ getItem: () => null })).toBe(true);
    expect(readInspectorAutosavePreference({ getItem: () => '{broken' })).toBe(true);
    expect(readInspectorAutosavePreference({ getItem: () => '"false"' })).toBe(true);
    expect(readInspectorAutosavePreference({ getItem: () => { throw new Error('denied'); } })).toBe(true);
  });

  it('round-trips booleans through the versioned key without throwing on write failure', () => {
    const storage = { getItem: vi.fn(() => 'false'), setItem: vi.fn() };
    expect(readInspectorAutosavePreference(storage)).toBe(false);
    expect(writeInspectorAutosavePreference(true, storage)).toBe(true);
    expect(storage.setItem).toHaveBeenCalledWith(INSPECTOR_AUTOSAVE_STORAGE_KEY, 'true');
    expect(writeInspectorAutosavePreference(false, { setItem: () => { throw new Error('full'); } })).toBe(false);
  });
});

describe('nodePatchChanged', () => {
  const node = { name: 'Before', properties: { alpha: '1' }, propertyTypes: { alpha: 'string' } };

  it('compares scalar and whole-replacement property maps without depending on key order', () => {
    expect(nodePatchChanged(node, {
      name: 'Before', properties: { alpha: '1' }, propertyTypes: { alpha: 'string' },
    })).toBe(false);
    expect(nodePatchChanged(node, { name: 'After' })).toBe(true);
    expect(nodePatchChanged(node, { properties: {} })).toBe(true);
  });
});
