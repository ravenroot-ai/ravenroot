import { JSDOM } from 'jsdom';
import { describe, expect, it, vi } from 'vitest';
import { createLanguageDialog, readUiCatalogFile } from '../src/language-dialog.js';
import { MAX_UI_CATALOG_BYTES } from '../src/ui-localization.js';
import { initializeUiLocalization } from '../src/ui-localization.js';
import { UI_CATALOG_SCHEMA, UI_LOCALES_SCHEMA } from '../src/ui-text.js';

describe('language dialog catalog import', () => {
  it('rejects an oversized catalog before reading the file body', async () => {
    const text = vi.fn();

    await expect(readUiCatalogFile({ size: MAX_UI_CATALOG_BYTES + 1, text }))
      .rejects.toThrow(/too large/i);
    expect(text).not.toHaveBeenCalled();
  });

  it('reads and parses a catalog within the import bound', async () => {
    const catalog = { schema: 'ravenroot.ui-catalog/v1', locale: 'en-XA', messages: {} };
    const text = vi.fn(async () => JSON.stringify(catalog));

    await expect(readUiCatalogFile({ size: 128, text })).resolves.toEqual(catalog);
    expect(text).toHaveBeenCalledOnce();
  });
});

describe('language dialog preference', () => {
  it('represents an explicit regional preference backed by a base-language catalog', async () => {
    const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'https://example.test/' });
    dom.window.localStorage.setItem('ravenroot.ui.locale.v1', 'fr-CA');
    const controller = await initializeUiLocalization({ document: dom.window.document,
      storage: dom.window.localStorage, indexedDB: null, navigator: { languages: ['en'] },
      onDiagnostic: vi.fn(), fetch: async () => new Response(JSON.stringify({
        schema: UI_LOCALES_SCHEMA, defaultLocale: 'en',
        catalogs: [{ schema: UI_CATALOG_SCHEMA, locale: 'fr', messages: {} }],
      })) });
    const dialog = createLanguageDialog({ document: dom.window.document, controller });
    const select = dom.window.document.querySelector('#language-selection');
    try {
      expect(controller.localization.locale).toBe('fr-CA');
      expect(select.value).toBe('fr-CA');
      expect(select.selectedIndex).toBeGreaterThan(-1);
      expect([...select.options].map(option => option.value)).toEqual(['', 'en', 'fr', 'fr-CA']);
    } finally { dialog.destroy(); controller.destroy(); }
  });

  it('keeps Automatic selected while reporting the separately resolved configured locale', async () => {
    const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'https://example.test/' });
    const catalog = { schema: UI_CATALOG_SCHEMA, locale: 'fr', messages: {} };
    const controller = await initializeUiLocalization({ document: dom.window.document,
      storage: dom.window.localStorage, indexedDB: null, navigator: { languages: ['en'] },
      onDiagnostic: vi.fn(), fetch: async () => new Response(JSON.stringify({
        schema: UI_LOCALES_SCHEMA, defaultLocale: 'fr-CA', catalogs: [catalog],
      })) });
    const dialog = createLanguageDialog({ document: dom.window.document, controller });
    const select = dom.window.document.querySelector('#language-selection');
    const source = dom.window.document.querySelector('.language-current-source');
    try {
      expect(select.value).toBe('');
      expect(source.textContent).toContain('Resolved language: fr-CA');
      expect(source.textContent).toContain('Configured by this deployment');

      select.value = 'fr';
      select.dispatchEvent(new dom.window.Event('change'));
      expect(controller.preferenceLocale).toBe('fr');
      expect(select.value).toBe('fr');
      expect(dom.window.localStorage.getItem('ravenroot.ui.locale.v1')).toBe('fr');

      select.value = '';
      select.dispatchEvent(new dom.window.Event('change'));
      expect(controller.preferenceLocale).toBeNull();
      expect(select.value).toBe('');
      expect(controller.localization.locale).toBe('fr-CA');
      expect(dom.window.localStorage.getItem('ravenroot.ui.locale.v1')).toBeNull();
    } finally { dialog.destroy(); controller.destroy(); }
  });
});
