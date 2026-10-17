import { JSDOM } from 'jsdom';
import { describe, expect, it, vi } from 'vitest';
import { UI_CATALOG_SCHEMA, UI_LOCALES_SCHEMA, createUiLocalization } from '../src/ui-text.js';
import {
  initializeUiLocalization,
  localizeDocument,
  validateUiLocalesConfiguration,
} from '../src/ui-localization.js';

const french = Object.freeze({
  schema: UI_CATALOG_SCHEMA,
  locale: 'fr',
  messages: Object.freeze({
    'commands.file.open.label': 'Ouvrir…',
    'shell.application-menubar.aria-label': 'Menu principal',
  }),
});

describe('UI localization controller', () => {
  it('validates deployment configuration and requires a catalog for its default', () => {
    expect(validateUiLocalesConfiguration({
      schema: UI_LOCALES_SCHEMA, defaultLocale: 'fr-CA', catalogs: [french],
    })).toMatchObject({ defaultLocale: 'fr-CA' });
    expect(() => validateUiLocalesConfiguration({
      schema: UI_LOCALES_SCHEMA, defaultLocale: 'it', catalogs: [french],
    })).toThrow(/has no catalog/);
  });

  it('localizes static text and attributes while updating lang and dir', () => {
    const dom = new JSDOM('<!doctype html><html><body><button data-i18n="commands.file.open.label" data-i18n-aria-label="shell.application-menubar.aria-label" data-i18n-aria-description="commands.file.open.label" data-i18n-aria-valuetext="commands.file.open.label">Open…</button></body></html>');
    const localization = createUiLocalization({ locale: 'fr', catalogs: { fr: french } });
    localizeDocument(dom.window.document, localization);

    expect(dom.window.document.documentElement.lang).toBe('fr');
    expect(dom.window.document.documentElement.dir).toBe('ltr');
    expect(dom.window.document.querySelector('button').textContent).toBe('Ouvrir…');
    expect(dom.window.document.querySelector('button').getAttribute('aria-label')).toBe('Menu principal');
    expect(dom.window.document.querySelector('button').getAttribute('aria-description')).toBe('Ouvrir…');
    expect(dom.window.document.querySelector('button').getAttribute('aria-valuetext')).toBe('Ouvrir…');
  });

  it('honors explicit, configured, browser and English precedence', async () => {
    const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'https://example.test/' });
    const storage = { getItem: vi.fn(() => 'fr-CA'), setItem: vi.fn(), removeItem: vi.fn() };
    const fetch = vi.fn(async () => new Response(JSON.stringify({
      schema: UI_LOCALES_SCHEMA, defaultLocale: 'fr', catalogs: [french],
    }), { status: 200, headers: { 'content-type': 'application/json' } }));
    const controller = await initializeUiLocalization({ document: dom.window.document, fetch, storage,
      indexedDB: null, navigator: { languages: ['de-DE'] }, onDiagnostic: vi.fn() });

    expect(controller.localization.locale).toBe('fr-CA');
    expect(controller.localization.text('commands.file.open.label')).toBe('Ouvrir…');
    controller.chooseLocale('fr');
    expect(storage.setItem).toHaveBeenCalled();
    controller.useAutomaticLocale();
    expect(controller.localization.locale).toBe('fr');
    controller.destroy();
  });

  it('refuses configuration redirects and a final cross-origin response URL', async () => {
    const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'https://example.test/' });
    const diagnostics = vi.fn();
    const fetch = vi.fn(async (_url, options) => {
      expect(options.redirect).toBe('error');
      return {
        status: 200,
        ok: true,
        url: 'https://untrusted.example/ui-locales.json',
        headers: new Headers(),
        text: vi.fn(() => Promise.resolve('{}')),
      };
    });
    const controller = await initializeUiLocalization({
      document: dom.window.document, fetch, storage: null, indexedDB: null,
      navigator: { languages: ['en'] }, onDiagnostic: diagnostics,
    });
    expect(controller.localization.locale).toBe('en');
    expect(diagnostics).toHaveBeenCalledWith(expect.objectContaining({
      code: 'CONFIG_ORIGIN_REFUSED',
    }));
    controller.destroy();
  });

  it.each([
    ['response headers', () => new Promise(() => {})],
    ['response body', () => Promise.resolve({
      status: 200,
      ok: true,
      url: 'https://example.test/ui-locales.json',
      headers: new Headers(),
      text: () => new Promise(() => {}),
    })],
  ])('falls back to English when configuration %s never arrives', async (_name, fetchResult) => {
    const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'https://example.test/' });
    const diagnostics = vi.fn();
    let requestSignal;
    const fetch = vi.fn((_url, options) => {
      requestSignal = options.signal;
      return fetchResult();
    });

    const controller = await initializeUiLocalization({
      document: dom.window.document, fetch, storage: null, indexedDB: null,
      navigator: { languages: ['en'] }, onDiagnostic: diagnostics,
      configurationTimeoutMs: 5,
    });

    expect(controller.localization.locale).toBe('en');
    expect(requestSignal.aborted).toBe(true);
    expect(diagnostics).toHaveBeenCalledWith(expect.objectContaining({
      code: 'CONFIG_UNAVAILABLE', reason: expect.stringMatching(/timed out/i),
    }));
    controller.destroy();
  });
});
