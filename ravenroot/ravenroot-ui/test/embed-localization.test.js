import { JSDOM } from 'jsdom';
import { describe, expect, it } from 'vitest';

import { UI_CATALOG_SCHEMA } from '../src/ui-text.js';
import {
  createEmbedLocalization,
  validateEmbedLocaleChange,
} from '../src/embed-viewer-entry.js';

const catalog = (locale, loading) => ({
  schema: UI_CATALOG_SCHEMA,
  locale,
  messages: { 'embed.state.loading': loading },
});

describe('embedded viewer localization', () => {
  it('keeps independent viewer documents and locale state isolated', () => {
    const left = new JSDOM('<html><body><p data-i18n="embed.state.loading"></p></body></html>');
    const right = new JSDOM('<html><body><p data-i18n="embed.state.loading"></p></body></html>');
    const first = createEmbedLocalization({ locale: 'it', catalog: catalog('it', 'Caricamento'),
      document: left.window.document });
    const second = createEmbedLocalization({ locale: 'fr', catalog: catalog('fr', 'Chargement'),
      document: right.window.document });

    expect(left.window.document.querySelector('p').textContent).toBe('Caricamento');
    expect(right.window.document.querySelector('p').textContent).toBe('Chargement');
    first.setLocale('en');
    expect(right.window.document.documentElement.lang).toBe('fr');
    expect(second.text('embed.state.loading')).toBe('Chargement');
  });

  it('validates the complete SET_LOCALE mutation before either value can be applied', () => {
    expect(() => validateEmbedLocaleChange('not a locale', catalog('it', 'Caricamento')))
      .toThrow();
    expect(() => validateEmbedLocaleChange('it', catalog('fr', 'Chargement')))
      .toThrow(/must match/);
    expect(validateEmbedLocaleChange('it', catalog('it', 'Caricamento')))
      .toMatchObject({ locale: 'it', catalog: { locale: 'it' } });
  });
});
