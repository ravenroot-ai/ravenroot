import { describe, expect, it } from 'vitest';
import {
  ENGLISH_MESSAGES,
  UI_CATALOG_SCHEMA,
  createUiLocalization,
  createUiText,
  formatUiDuration,
  uiErrorReason,
  uiLocaleDirection,
  validateUiCatalog,
} from '../src/ui-text.js';
import { productError } from '../src/product-error.js';

describe('UI text catalogs', () => {
  it('translates structured product validation reasons while preserving unknown diagnostics', () => {
    expect(uiErrorReason(productError('humanTasks.validation.failed', 'developer detail')))
      .toBe('The Human Task response failed client validation.');
    expect(uiErrorReason(new Error('service diagnostic'))).toBe('service diagnostic');
  });
  it('prefers an exact locale, then its base language, then English', () => {
    const catalogs = {
      fr: { 'commands.file.new.label': 'Nouveau document' },
      'fr-CA': { 'commands.file.open.label': 'Ouvrir…' },
    };
    const text = createUiText({ locale: 'fr-CA', catalogs });

    expect(text('commands.file.open.label')).toBe('Ouvrir…');
    expect(text('commands.file.new.label')).toBe('Nouveau document');
    expect(text('commands.file.save.label')).toBe('Save GraphML');
  });

  it('canonicalizes locale casing and underscore separators', () => {
    const catalogs = {
      'fr-CA': { 'commands.file.open.label': 'Ouvrir au Canada…' },
    };

    expect(createUiText({ locale: 'fr-ca', catalogs })('commands.file.open.label'))
      .toBe('Ouvrir au Canada…');
    expect(createUiText({ locale: 'fr_CA', catalogs })('commands.file.open.label'))
      .toBe('Ouvrir au Canada…');
  });

  it('uses English for an unsupported locale', () => {
    const text = createUiText({ locale: 'zz-ZZ' });
    expect(text('commands.view.keyboardShortcuts.label')).toBe('Keyboard Shortcuts');
  });

  it('interpolates named values and reports safe fallbacks for invalid lookups', () => {
    const diagnostics = [];
    const text = createUiText({
      locale: 'en', onDiagnostic: detail => diagnostics.push(detail),
    });

    expect(text('closeAll.sessions.item.many', { name: 'Orders', count: 3 }))
      .toBe('Orders — 3 inbound source nodes · scope LOCAL_PROCESS');
    expect(text('closeAll.sessions.item.many')).toBe('Unavailable');
    expect(text('not.registered')).toBe('Unavailable');
    expect(diagnostics.map(item => item.code)).toEqual([
      'INVALID_MESSAGE_PARAMETERS', 'MISSING_SOURCE_MESSAGE',
    ]);
  });

  it('validates keys, directions and placeholder parity at the catalog boundary', () => {
    const input = { schema: UI_CATALOG_SCHEMA, locale: 'ar', direction: 'rtl', messages: {
      'commands.file.open.label': 'افتح…',
      'closeAll.sessions.item.many': '{name} — {count} عناصر',
    } };
    expect(validateUiCatalog(input)).toMatchObject({ locale: 'ar', direction: 'rtl' });
    expect(() => validateUiCatalog({ ...input, messages: { unknown: 'No' } }))
      .toThrow(/Invalid UI text key|Unknown UI text key/);
    expect(() => validateUiCatalog({ ...input, messages: {
      'closeAll.sessions.item.many': '{name}',
    } })).toThrow(/incompatible named parameters/);
    expect(() => validateUiCatalog({ ...input, direction: 'sideways' })).toThrow(/direction/);
  });

  it('supports plurals and locale-aware number, date, direction and duration formatting', () => {
    const sourceMessages = { ...ENGLISH_MESSAGES,
      'test.items': { one: '{count} item', other: '{count} items' } };
    const catalog = validateUiCatalog({ schema: UI_CATALOG_SCHEMA, locale: 'fr', messages: {
      'test.items': { one: '{count} élément', other: '{count} éléments' },
    } }, { sourceMessages });
    const text = createUiText({ locale: 'de-DE' });
    expect(catalog.messages['test.items'].other).toBe('{count} éléments');
    expect(text.number(1234.5)).toMatch(/1[.\s]234,5/);
    expect(text.dateTime(new Date('2026-10-17T00:00:00Z'), { timeZone: 'UTC', year: 'numeric' }))
      .toContain('2026');
    expect(formatUiDuration(65_000, 'en')).toBe('1 min 5 sec');
    expect(uiLocaleDirection('ar')).toBe('rtl');
  });

  it('switches a live localization context without replacing application state', () => {
    const localization = createUiLocalization({ locale: 'en', catalogs: { fr: {
      'commands.file.open.label': 'Ouvrir…',
    } } });
    const state = { selectedNode: 'n1' };
    let notices = 0;
    localization.subscribe(() => { notices += 1; });

    expect(localization.setLocale('fr-CA')).toBe(true);
    expect(localization.text('commands.file.open.label')).toBe('Ouvrir…');
    expect(localization.text('commands.file.save.label')).toBe('Save GraphML');
    expect(state).toEqual({ selectedNode: 'n1' });
    expect(notices).toBe(1);
  });
});
