import { describe, expect, it } from 'vitest';

import { viewerDesignLayoutOptions } from '../src/viewer-design-layout.js';

describe('embedded Design Render arrangement', () => {
  it.each([
    ['keep', 'preset'],
    ['flow', 'dagre'],
    ['organic', 'cose'],
    ['hierarchical', 'elk'],
    ['hierarchical-new', 'rr-layered'],
    ['layered-down', 'rr-layered'],
  ])('maps %s to the native Design engine %s without fitting', (arrangement, engine) => {
    expect(viewerDesignLayoutOptions(arrangement)).toMatchObject({ name: engine, fit: false });
  });

  it('fails closed for renderer and unknown algorithm names', () => {
    expect(() => viewerDesignLayoutOptions('elastic')).toThrow(/Unsupported Design arrangement/);
    expect(() => viewerDesignLayoutOptions('cose')).toThrow(/Unsupported Design arrangement/);
  });
});
