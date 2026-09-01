import { readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const REMOVED_MODE = '(?:dagre|cose|elastic|elk|n8n|n8n2|n8n3|n8n4|cyto|preset)';

function specFiles(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = resolve(directory, entry.name);
    if (entry.isDirectory()) return specFiles(path);
    return entry.isFile() && entry.name.endsWith('.spec.js') ? [path] : [];
  });
}

describe('public render-mode surface', () => {
  it('contains no removed technical toolbar, command, or numeric-shortcut invocation', () => {
    const publicSources = ['index.html', 'src/app-commands.js', 'src/ui-text.js']
      .map(path => [path, readFileSync(path, 'utf8')]);
    const publicPattern = new RegExp(
      `(?:btn-${REMOVED_MODE}\\b|layout\\.${REMOVED_MODE}\\b|commands\\.layout\\.${REMOVED_MODE}\\b)`,
      'i',
    );
    expect(publicSources.flatMap(([path, source]) =>
      publicPattern.test(source) ? [path] : [])).toEqual([]);

    const obsoleteTestFlow = new RegExp(
      `(?:#btn-${REMOVED_MODE}\\b|keyboard\\.press\\(\\s*['\"][0-9]['\"]\\s*\\)|shortcut\\s*:\\s*['\"][0-9]['\"])`,
      'i',
    );
    expect(specFiles(resolve('e2e')).flatMap(path => {
      const source = readFileSync(path, 'utf8');
      return obsoleteTestFlow.test(source) ? [path] : [];
    })).toEqual([]);
  });

  it('keeps the hidden algorithm implementation available only inside the renderer', () => {
    const source = readFileSync('src/app.js', 'utf8');
    expect(source).toContain("const ELK_LAYOUT_MODES = new Set(['elk', 'hierarchical', 'n8n', 'n8n2', 'n8n3', 'n8n4', 'cyto'])");
    expect(source).toContain('function setLayout(name, options = {})');
    expect(source).toContain("else if (mode === 'n8n4') applyN8n4EdgeCurves(target)");
  });
});
