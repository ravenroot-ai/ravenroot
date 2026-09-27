import { readFile } from 'node:fs/promises';
import { dirname, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../src');
const ENTRY = resolve(ROOT, 'embed-viewer-entry.js');
const BOOTSTRAP = resolve(ROOT, '../public/embed-bootstrap.js');
const FORBIDDEN = new Set([
  'app.js',
  'app-commands.js',
  'runtime-client.js',
  'graph-editing.js',
  'graph-parsers.js',
  'graph-trace.js',
  'edge-gestures.js',
  'workspace.js',
  'assistant-client.js',
]);

async function localImportGraph(entry) {
  const visited = new Set();
  async function visit(file) {
    if (visited.has(file)) return;
    visited.add(file);
    const source = await readFile(file, 'utf8');
    const imports = source.matchAll(/(?:import|export)\s+(?:[^'";]*?\s+from\s+)?['"](\.\.?\/[^'"]+)['"]/g);
    for (const match of imports) {
      const imported = resolve(dirname(file), match[1]);
      await visit(imported.endsWith('.js') ? imported : `${imported}.js`);
    }
  }
  await visit(entry);
  return [...visited].map(file => relative(ROOT, file));
}

describe('embed viewer import boundary', () => {
  it('cannot reach editor, runtime, import/export, execution, or monitoring modules', async () => {
    const graph = await localImportGraph(ENTRY);

    expect(graph).toContain('viewer-core.js');
    expect(graph).toContain('viewer-renderer-adapter.js');
    expect(graph).toContain('viewer-presentation.js');
    expect(graph.filter(file => FORBIDDEN.has(file))).toEqual([]);
  });

  it('delegates graph styling to the same presentation factory as the editor', async () => {
    const [embed, app] = await Promise.all([
      readFile(ENTRY, 'utf8'),
      readFile(resolve(ROOT, 'app.js'), 'utf8'),
    ]);
    expect(embed).toContain('createViewerStylesheet(requireEmbedTheme(theme), mode)');
    expect(app).toContain("style: createViewerStylesheet(rendererPalette, 'cyto', { includeNodeSelection: false })");
  });

  it('keeps theme selection out of viewer storage and the closed parent protocol', async () => {
    const source = await readFile(BOOTSTRAP, 'utf8');
    expect(source).not.toMatch(/localStorage|sessionStorage|indexedDB|serviceWorker|caches\s*[.(]/);
    expect(source).not.toMatch(/THEME(?:_CHANGED)?['"]/);
    expect(source).toContain("const THEMES = Object.freeze(['dark', 'light'])");
  });

  it('keeps deployment observation inside the viewer realm and authenticates fetch by header', async () => {
    const source = await readFile(BOOTSTRAP, 'utf8');
    expect(source).toContain("const OBSERVATION_PATH = '/v1/embed/observation'");
    expect(source).toContain("Accept: 'text/event-stream'");
    expect(source).toContain('Authorization: `Bearer ${bearer}`');
    expect(source).toContain('const MAX_OBSERVATION_RETRIES = 5');
    expect(source).toContain('Math.min(5_000, 500 * (attempt + 1))');
    expect(source).toContain('if (result.terminal || observation.signal.aborted) return');
    expect(source).toContain("if (failure?.kind === 'expired' || attempt === MAX_OBSERVATION_RETRIES)");
    expect(source).not.toContain('new EventSource');
    expect(source).not.toMatch(/OBSERVATION_PATH\s*\+\s*['"`?]/u);
    expect(source).toContain('viewerInstance.observe(');
    expect(source).toContain('fallback: Boolean(payload.event?.fallback ?? payload.fallback)');
    expect(source).not.toMatch(/sendToParent\(['"](?:EVENT|TOPOLOGY|RUNTIME|OBSERVATION)/u);
  });
});
