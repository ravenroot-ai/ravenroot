import { describe, expect, it } from 'vitest';
import { readFileSync, readdirSync } from 'node:fs';
import { extname, join } from 'node:path';

import { getRendererPalette } from '../src/theme-palette.js';
import { createViewerStylesheet } from '../src/viewer-presentation.js';

const APP_SOURCE = readFileSync('src/app.js', 'utf8');
const FORBIDDEN_EDGE_WORD = String.fromCharCode(101, 115, 105, 116, 111);
const TEXT_EXTENSIONS = new Set(['.css', '.graphml', '.html', '.js', '.json', '.mjs']);

function textFiles(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) return textFiles(path);
    return TEXT_EXTENSIONS.has(extname(entry.name)) ? [path] : [];
  });
}

describe('canonical outcome edge vocabulary', () => {
  it('keeps renderer style, legend and bypass semantics on the canonical edge type', () => {
    const palette = getRendererPalette('dark');
    const outcome = createViewerStylesheet(palette)
      .find(rule => rule.selector === 'edge[edgeType="outcome"]');
    expect(outcome?.style).toMatchObject({
      'line-color': palette.edgeType.outcome,
      'target-arrow-color': palette.edgeType.outcome,
      width: 2.5,
      color: palette.edgeType.outcome,
    });
    expect(APP_SOURCE).toContain(`{ type: 'outcome', label: '*_OUTCOME' }`);
    expect(APP_SOURCE).toContain(`if (state === 'bypassed') return rendererPalette.edgeType.outcome;`);

    expect(getRendererPalette('light').edgeType.outcome).toBe('#8250df');
    expect(getRendererPalette('dark').edgeType.outcome).toBe('#d2a8ff');
  });

  it('rejects the removed non-English edge word throughout frontend source and test scope', () => {
    const matches = ['src', 'test', 'e2e'].flatMap(textFiles).filter(path =>
      readFileSync(path, 'utf8').toLocaleLowerCase('en-US').includes(FORBIDDEN_EDGE_WORD),
    );

    expect(matches).toEqual([]);
  });
});
