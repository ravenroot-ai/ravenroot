import { productError } from './product-error.js';
import {
  createUiLocalization, normalizeUiLocale, validateUiCatalog,
} from './ui-text.js';
import { localizeDocument } from './ui-localization.js';

export function createEmbedLocalization({ locale = 'en', catalog = null,
  document: document_ = document } = {}) {
  const catalogs = {};
  if (catalog !== null) {
    const validated = validateUiCatalog(catalog);
    catalogs[validated.locale] = validated;
  }
  const localization = createUiLocalization({ locale, catalogs });
  const apply = () => localizeDocument(document_, localization);
  localization.subscribe(apply);
  apply();
  return localization;
}

export function validateEmbedLocaleChange(locale, catalog) {
  const normalized = normalizeUiLocale(locale);
  const validated = catalog === null ? null : validateUiCatalog(catalog);
  const base = new Intl.Locale(normalized).language;
  if (validated !== null && validated.locale !== normalized && validated.locale !== base) {
    throw productError('workspace.error.embed.viewer.entry.embedded.catalog.locale.must.match.the.requested.locale', new TypeError('Embedded catalog locale must match the requested locale.'));
  }
  return Object.freeze({ locale: normalized, catalog: validated });
}
