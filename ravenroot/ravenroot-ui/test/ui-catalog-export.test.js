// @vitest-environment node
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { ENGLISH_MESSAGES, createUiLocalization, validateUiCatalog } from '../src/ui-text.js';
import { readUiCatalogFile } from '../src/language-dialog.js';
import { MAX_UI_CATALOG_BYTES } from '../src/ui-localization.js';

const testDirectory = dirname(fileURLToPath(import.meta.url));
const exportCatalog = () => execFileSync(process.execPath,
  [resolve(testDirectory, '../scripts/export-ui-catalog.mjs'), 'it'], { encoding: 'utf8' });

describe('the shipped catalog export and documented viewer subset', () => {
  it('round-trips the actual CLI output through the bounded file reader and catalog runtime', async () => {
    const output = exportCatalog();
    const file = new File([output], 'it.json', { type: 'application/json' });
    expect(file.size).toBeLessThanOrEqual(MAX_UI_CATALOG_BYTES);
    const catalog = validateUiCatalog(await readUiCatalogFile(file));
    expect(catalog.messages).toEqual(ENGLISH_MESSAGES);
    const localization = createUiLocalization({ locale: 'it', catalogs: { it: catalog } });
    expect(localization.text('embed.state.idle')).toBe('');
    expect(localization.text('shell.menu-file.text')).toBe('File');
    await expect(readUiCatalogFile(new File([' '.repeat(MAX_UI_CATALOG_BYTES + 1)], 'oversize.json')))
      .rejects.toThrow(/too large/);
  });

  it('executes the actual documented compact filter and validates its complete viewer catalog', () => {
    const guide = readFileSync(resolve(testDirectory, '../../../docs/architecture/ui-internationalization.md'), 'utf8');
    const snippet = guide.match(/```js\n(const viewerMessages[\s\S]*?)\n```/)[1];
    const serialized = new Function('workspaceCatalog', `${snippet}\nreturn catalog;`)(JSON.parse(exportCatalog()));
    expect(serialized.length).toBeLessThanOrEqual(49_152);
    const catalog = validateUiCatalog(JSON.parse(serialized));
    expect(catalog.messages['embed.state.idle']).toBe('');
    for (const key of ['embed.state.loading', 'common.yes', 'visualGroup.header', 'workspace.node.bypassed.suffix']) {
      expect(catalog.messages[key]).toEqual(ENGLISH_MESSAGES[key]);
    }
    expect(catalog.messages['shell.menu-file.text']).toBeUndefined();
  });

  it('permits only canonical empty scalar messages and keeps labels and plural variants nonempty', () => {
    const exported = JSON.parse(exportCatalog());
    expect(Object.entries(ENGLISH_MESSAGES).filter(([, value]) => value === '').map(([key]) => key))
      .toEqual(['embed.state.idle']);
    for (const key of ['shell.menu-file.text', 'shell.application-menubar.aria-label']) {
      expect(() => validateUiCatalog({ ...exported, messages: { [key]: '' } })).toThrow(/invalid length/);
    }
    expect(() => validateUiCatalog({ ...exported, messages: { 'embed.state.idle': { other: '' } } }))
      .toThrow(/invalid length/);
  });
});
