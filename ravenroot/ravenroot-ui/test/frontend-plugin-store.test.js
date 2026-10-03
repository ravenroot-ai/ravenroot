import { describe, expect, it } from 'vitest';
import { packageFromFiles } from '../src/frontend-plugin-store.js';

function file(name, text, relative = name) {
  const bytes = new TextEncoder().encode(text);
  return { name, webkitRelativePath: relative, text: async () => text,
    arrayBuffer: async () => bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) };
}

const manifest = JSON.stringify({
  schema: 'ravenroot.frontend-plugin/v1', id: 'example.package', name: 'Example', version: '1.0.0',
  apiVersion: '1.0', permissions: [],
  layouts: [{ id: 'layout', name: 'Layout', entry: 'plugin.js' }],
  renderers: [{ id: 'renderer', name: 'Renderer', entry: 'plugin.js' }],
  drawingModels: [{ id: 'model', name: 'Model', layout: 'layout', renderer: 'renderer' }], integrity: {},
});

describe('browser-installed frontend package', () => {
  it('loads a directory bundle without a frontend rebuild', async () => {
    const package_ = await packageFromFiles([
      file('ravenroot-frontend-plugin.json', manifest, 'example/ravenroot-frontend-plugin.json'),
      file('plugin.js', 'export default {};', 'example/plugin.js'),
    ]);
    expect(package_).toMatchObject({ id: 'example.package', enabled: true });
    expect(package_.files['plugin.js']).toBe('export default {};');
  });

  it('refuses packages missing their declared entry', async () => {
    await expect(packageFromFiles([
      file('ravenroot-frontend-plugin.json', manifest, 'example/ravenroot-frontend-plugin.json'),
    ])).rejects.toThrow(/entry 'plugin.js' is missing/);
  });
});
