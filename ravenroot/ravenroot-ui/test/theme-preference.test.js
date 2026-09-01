import { describe, expect, it, vi } from 'vitest';

import {
  PRODUCT_DEFAULT_THEME,
  THEME_STORAGE_KEY,
  createThemePreferenceController,
  resolveApplicationTheme,
} from '../src/theme-preference.js';
import { resolveInitialTheme } from '../src/theme-resolution.js';
import { getRendererPalette } from '../src/theme-palette.js';
import { viewerStylesheet } from '../src/embed-viewer-entry.js';

function media(matches = false) {
  const listeners = new Set();
  return {
    matches,
    addEventListener: (_name, listener) => listeners.add(listener),
    removeEventListener: (_name, listener) => listeners.delete(listener),
    change(next) { this.matches = next; listeners.forEach(listener => listener({ matches: next })); },
    listenerCount: () => listeners.size,
  };
}

describe('application theme preference', () => {
  it('resolves explicit embed, saved preference, system, then product default', () => {
    expect(resolveInitialTheme({
      embedTheme: 'light', persistedTheme: 'dark', systemPrefersDark: true,
    })).toBe('light');
    expect(resolveInitialTheme({ persistedTheme: 'dark', systemPrefersDark: false })).toBe('dark');
    expect(resolveInitialTheme({ systemPrefersDark: true, productDefault: 'light' })).toBe('dark');
    expect(resolveInitialTheme({ systemPrefersDark: false, productDefault: 'dark' })).toBe('light');
    expect(resolveInitialTheme({ productDefault: 'light' })).toBe('light');
  });

  it('rejects an invalid explicit embed enum instead of treating it as CSS or a fallback', () => {
    for (const value of ['auto', 'Dark', 'dark;body{}', 'https://theme.example']) {
      expect(() => resolveInitialTheme({ embedTheme: value })).toThrow('Unsupported embed theme.');
    }
  });

  it('resolves persisted choice, then system, then the dark product default', () => {
    expect(resolveApplicationTheme({ persistedTheme: 'light', systemPrefersDark: true })).toBe('light');
    expect(resolveApplicationTheme({ persistedTheme: 'dark', systemPrefersDark: false })).toBe('dark');
    expect(resolveApplicationTheme({ persistedTheme: 'invalid', systemPrefersDark: true })).toBe('dark');
    expect(resolveApplicationTheme({ persistedTheme: null, systemPrefersDark: false })).toBe('light');
    expect(resolveApplicationTheme({ persistedTheme: null, systemPrefersDark: null })).toBe(PRODUCT_DEFAULT_THEME);
  });

  it('persists an explicit choice and stops following system changes', () => {
    const values = new Map();
    const storage = {
      getItem: key => values.get(key) ?? null,
      setItem: (key, value) => values.set(key, value),
    };
    const query = media(true);
    const root = { dataset: {}, style: {} };
    const onChange = vi.fn();
    const controller = createThemePreferenceController({ storage, media: query, root, onChange });

    expect(controller.theme).toBe('dark');
    controller.select('light');
    expect(values.get(THEME_STORAGE_KEY)).toBe('light');
    expect(root).toMatchObject({ dataset: { theme: 'light' }, style: { colorScheme: 'light' } });
    query.change(true);
    expect(controller.theme).toBe('light');
    expect(onChange).toHaveBeenCalledTimes(1);
    controller.destroy();
    expect(query.listenerCount()).toBe(0);
  });

  it('follows system changes without a saved choice and survives unavailable storage', () => {
    const query = media(false);
    const root = { dataset: {}, style: {} };
    const controller = createThemePreferenceController({
      root,
      media: query,
      storage: { getItem: () => { throw new Error('blocked'); }, setItem: () => { throw new Error('blocked'); } },
    });
    expect(controller.theme).toBe('light');
    query.change(true);
    expect(controller.theme).toBe('dark');
    expect(() => controller.select('light')).not.toThrow();
  });

  it('provides complete, different renderer palettes for both themes', () => {
    const dark = getRendererPalette('dark');
    const light = getRendererPalette('light');
    expect(Object.keys(dark.nodeType)).toEqual(Object.keys(light.nodeType));
    expect(Object.keys(dark.edgeType)).toEqual(Object.keys(light.edgeType));
    expect(dark.canvas).not.toBe(light.canvas);
    expect(dark.nodeSurface).not.toBe(light.nodeSurface);
    expect(dark.nodeText).not.toBe(light.nodeText);
    expect(light.edgeType.outcome).toBe('#8250df');
    expect(dark.edgeType.outcome).toBe('#d2a8ff');
  });

  it('applies the same semantic palette to the isolated read-only renderer', () => {
    for (const theme of ['dark', 'light']) {
      const palette = getRendererPalette(theme);
      const node = viewerStylesheet('cyto', theme).find(rule => rule.selector === 'node').style;
      const edge = viewerStylesheet('cyto', theme).find(rule => rule.selector === 'edge').style;
      expect(node).toMatchObject({
        color: palette.nodeText,
        'background-color': palette.nodeSurface,
        'border-color': palette.nodeBorder,
      });
      expect(edge['line-color']).toBe(palette.edgeType.default);
      expect(edge['target-arrow-color']).toBe(palette.edgeType.default);
    }
    expect(() => viewerStylesheet('cyto', 'auto')).toThrow('Unsupported embed theme.');
  });
});
