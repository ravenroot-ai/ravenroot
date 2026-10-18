import { JSDOM } from 'jsdom';
import { describe, expect, it } from 'vitest';

import { viewerProjectionElements } from '../src/viewer-presentation.js';
import { runtimeNodeTooltipText } from '../src/viewer-elastic-renderer.js';
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
    expect(validateEmbedLocaleChange('it-IT', catalog('it', 'Caricamento')))
      .toMatchObject({ locale: 'it-IT', catalog: { locale: 'it' } });
    expect(() => validateEmbedLocaleChange('it-IT', catalog('en', 'Loading')))
      .toThrow(/must match/);
  });

  it('keeps the requested regional locale while applying base text and declared direction', () => {
    const dom = new JSDOM('<html><body><p data-i18n="embed.state.loading"></p></body></html>');
    const base = { ...catalog('az', 'Yüklənir'), direction: 'rtl' };
    const localization = createEmbedLocalization({ locale: 'az-IR', catalog: base,
      document: dom.window.document });
    expect(dom.window.document.documentElement.lang).toBe('az-IR');
    expect(dom.window.document.documentElement.dir).toBe('rtl');
    expect(dom.window.document.querySelector('p').textContent).toBe('Yüklənir');
    expect(localization.locale).toBe('az-IR');

    const exact = validateEmbedLocaleChange('az-IR', { ...catalog('az-IR', 'Dəqiq'),
      direction: 'ltr' });
    localization.registerCatalog(exact.catalog);
    localization.setLocale(exact.locale);
    expect(localization.direction).toBe('ltr');
    expect(localization.text('embed.state.loading')).toBe('Dəqiq');
  });
});


it('isolates shared bypass labels and Monitoring tooltips from workspace defaults', () => {
  const make = (locale, marker) => createEmbedLocalization({ locale, document: new JSDOM('<html><body></body></html>').window.document,
    catalog: { schema: UI_CATALOG_SCHEMA, locale, messages: {
      'workspace.node.bypassed.suffix': marker + ' bypass', 'embed.tooltip.id': marker + ' ID',
    } },
  });
  const first = make('it', 'IT'), second = make('fr', 'FR');
  const node = { id: 'authored', kind: 'BEHAVIOR', bypassed: true, label: 'Authored name' };
  const label = context => viewerProjectionElements([node], [], context.text)[0].data.cardLabel;
  expect(label(first)).toBe('Authored nameIT bypass');
  expect(label(second)).toBe('Authored nameFR bypass');
  expect(runtimeNodeTooltipText(node, first.text)).toContain('IT ID: authored');
  expect(runtimeNodeTooltipText(node, second.text)).toContain('FR ID: authored');
  first.setLocale('en');
  expect(label(first)).toBe('Authored name · bypassed');
  expect(label(second)).toBe('Authored nameFR bypass');
  expect(viewerProjectionElements([node], [])[0].data.cardLabel).toBe('Authored name · bypassed');
});
