import { describe, expect, it, vi } from 'vitest';
import {
  NODE_ACTION_SCALE_DEFAULT,
  NODE_ACTION_SCALE_STORAGE_KEY,
  applyNodeActionGeometry,
  bindNodeActionScaleControl,
  nodeActionGeometry,
  normalizeNodeActionScale,
  readNodeActionScalePreference,
  resolveNodeActionScalePreference,
  writeNodeActionScalePreference,
} from '../src/node-action-scale.js';

describe('node action scale preference', () => {
  it('defaults missing, corrupt, non-finite, wrong-version and out-of-range values to 115%', () => {
    for (const value of [null, '{broken', 'NaN', 'Infinity', JSON.stringify({ version: 1, percent: NaN }),
      JSON.stringify({ version: 1, percent: 'Infinity' }), JSON.stringify({ version: 2, percent: 120 }),
      JSON.stringify({ version: 1, percent: 70 }), JSON.stringify({ version: 1, percent: 180 })]) {
      expect(resolveNodeActionScalePreference(value).percent).toBe(NODE_ACTION_SCALE_DEFAULT);
    }
    expect(readNodeActionScalePreference({ getItem: () => { throw new Error('denied'); } })).toBe(115);
  });

  it('clamps and snaps live input while rejecting out-of-range persisted state', () => {
    expect(normalizeNodeActionScale(74, { clamp: true })).toBe(75);
    expect(normalizeNodeActionScale(178, { clamp: true })).toBe(175);
    expect(normalizeNodeActionScale(123, { clamp: true })).toBe(125);
    expect(normalizeNodeActionScale(178)).toBe(115);
  });

  it('migrates a valid legacy numeric value to the versioned envelope', () => {
    const storage = { getItem: vi.fn(() => '130'), setItem: vi.fn() };
    expect(readNodeActionScalePreference(storage)).toBe(130);
    expect(storage.setItem).toHaveBeenCalledWith(NODE_ACTION_SCALE_STORAGE_KEY,
      JSON.stringify({ version: 1, percent: 130 }));
  });

  it('round-trips safely when storage is available or denied', () => {
    const storage = { setItem: vi.fn() };
    expect(writeNodeActionScalePreference(140, storage)).toBe(true);
    expect(storage.setItem).toHaveBeenCalledWith(NODE_ACTION_SCALE_STORAGE_KEY,
      JSON.stringify({ version: 1, percent: 140 }));
    expect(writeNodeActionScalePreference(140, { setItem: () => { throw new Error('full'); } })).toBe(false);
  });
});

describe('node action geometry', () => {
  it('derives every fine-pointer measure from the exact 115% baseline factor', () => {
    expect(nodeActionGeometry(115)).toMatchObject({
      percent: 115, factor: 1.15, buttonWidth: 20.7, buttonHeight: 19.55,
      glyphSize: 13.8, gap: 1.15, padding: 1.15, border: 1.15,
      barInset: 2.3, coarseBarInset: 3.45,
      barRadius: 5.75, buttonRadius: 3.45, bridgeGap: 9.2, edgeInset: 4.6,
      menuMinWidth: 218.5, menuInset: 5.75, menuItemMinHeight: 36.8,
    });
  });

  it('publishes real CSS dimensions and retains coarse-pointer minimum targets', () => {
    const root = document.createElement('div');
    const geometry = applyNodeActionGeometry(root, 75);
    expect(root.dataset.nodeActionScale).toBe('75');
    expect(root.style.getPropertyValue('--node-action-button-w')).toBe('13.5px');
    expect(root.style.getPropertyValue('--node-action-coarse-button-w')).toBe('44px');
    expect(geometry.coarseMenuItemMinHeight).toBe(44);
    expect(nodeActionGeometry(115).coarseButtonWidth).toBeCloseTo(50.6);
  });

  it('owns one input listener and removes it on destroy', () => {
    const input = document.createElement('input');
    input.type = 'range';
    input.min = '75';
    input.max = '175';
    input.step = '5';
    input.value = '115';
    const output = document.createElement('span');
    const storage = { getItem: () => null, setItem: vi.fn() };
    const onChange = vi.fn();
    const controller = bindNodeActionScaleControl({ input, output, storage, root: document.documentElement, onChange });
    input.value = '135';
    input.dispatchEvent(new Event('input'));
    expect(controller.percent).toBe(135);
    expect(output.textContent).toBe('135');
    expect(onChange).toHaveBeenCalledTimes(2);
    controller.destroy();
    input.value = '150';
    input.dispatchEvent(new Event('input'));
    expect(controller.percent).toBe(135);
    expect(onChange).toHaveBeenCalledTimes(2);
  });
});
