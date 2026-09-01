import { describe, expect, it } from 'vitest';
import { createUiText } from '../src/ui-text.js';

describe('UI text catalogs', () => {
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

  it('interpolates named values and fails when a key or parameter is absent', () => {
    const text = createUiText({
      locale: 'en',
      catalogs: { en: { example: 'Open {count} documents' } },
    });

    expect(text('example', { count: 3 })).toBe('Open 3 documents');
    expect(() => text('example')).toThrow(/Missing UI text parameter "count"/);
    expect(() => text('not.registered')).toThrow(/Missing English UI text/);
  });
});
