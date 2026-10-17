import {
  UI_CATALOG_SCHEMA,
  UI_LOCALES_SCHEMA,
  UI_LOCALE_STORAGE_KEY,
  createUiLocalization,
  normalizeUiLocale,
  validateUiCatalog,
} from './ui-text.js';

const CATALOG_DATABASE = 'ravenroot-ui-catalogs-v1';
const CATALOG_STORE = 'catalogs';
export const MAX_UI_CATALOG_BYTES = 512 * 1024;
const CONFIGURATION_TIMEOUT_MS = 3_000;

function plainObject(value) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return false;
  const prototype = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
}

function report(onDiagnostic, code, detail = {}) {
  onDiagnostic(Object.freeze({ code, ...detail }));
}

function openCatalogDatabase(indexedDB) {
  if (!indexedDB) return Promise.resolve(null);
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(CATALOG_DATABASE, 1);
    request.onupgradeneeded = () => request.result.createObjectStore(CATALOG_STORE,
      { keyPath: 'locale' });
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error || new Error('Catalog storage is unavailable.'));
    request.onblocked = () => reject(new Error('Catalog storage upgrade is blocked.'));
  });
}

async function storedCatalogs(indexedDB, onDiagnostic) {
  let database;
  try { database = await openCatalogDatabase(indexedDB); }
  catch (failure) {
    report(onDiagnostic, 'CATALOG_STORAGE_UNAVAILABLE', { reason: failure.message });
    return [];
  }
  if (!database) return [];
  return new Promise(resolve => {
    const transaction = database.transaction(CATALOG_STORE, 'readonly');
    const request = transaction.objectStore(CATALOG_STORE).getAll();
    request.onsuccess = () => {
      const catalogs = [];
      for (const value of request.result || []) {
        try { catalogs.push(validateUiCatalog(value)); }
        catch (failure) {
          report(onDiagnostic, 'STORED_CATALOG_INVALID', {
            locale: String(value?.locale || ''), reason: failure.message,
          });
        }
      }
      resolve(catalogs);
    };
    request.onerror = () => {
      report(onDiagnostic, 'CATALOG_STORAGE_UNAVAILABLE', {
        reason: request.error?.message || 'Catalog read failed.',
      });
      resolve([]);
    };
    transaction.oncomplete = () => database.close();
  });
}

async function writeStoredCatalog(indexedDB, catalog) {
  const database = await openCatalogDatabase(indexedDB);
  if (!database) throw new Error('Catalog storage is unavailable.');
  return new Promise((resolve, reject) => {
    const transaction = database.transaction(CATALOG_STORE, 'readwrite');
    transaction.objectStore(CATALOG_STORE).put(catalog);
    transaction.oncomplete = () => { database.close(); resolve(); };
    transaction.onerror = () => { database.close(); reject(transaction.error); };
    transaction.onabort = transaction.onerror;
  });
}

async function deleteStoredCatalog(indexedDB, locale) {
  const database = await openCatalogDatabase(indexedDB);
  if (!database) return;
  return new Promise((resolve, reject) => {
    const transaction = database.transaction(CATALOG_STORE, 'readwrite');
    transaction.objectStore(CATALOG_STORE).delete(locale);
    transaction.oncomplete = () => { database.close(); resolve(); };
    transaction.onerror = () => { database.close(); reject(transaction.error); };
    transaction.onabort = transaction.onerror;
  });
}

export function validateUiLocalesConfiguration(value) {
  if (!plainObject(value) || value.schema !== UI_LOCALES_SCHEMA) {
    throw new TypeError(`UI locale configuration schema must be "${UI_LOCALES_SCHEMA}".`);
  }
  const defaultLocale = value.defaultLocale === undefined ? null
    : normalizeUiLocale(value.defaultLocale);
  if (!Array.isArray(value.catalogs)) throw new TypeError('UI locale configuration catalogs must be an array.');
  const catalogs = value.catalogs.map(validateUiCatalog);
  const locales = new Set();
  for (const catalog of catalogs) {
    if (locales.has(catalog.locale)) throw new TypeError(`Duplicate UI catalog "${catalog.locale}".`);
    locales.add(catalog.locale);
  }
  if (defaultLocale !== null && defaultLocale !== 'en' && !locales.has(defaultLocale)
      && !locales.has(new Intl.Locale(defaultLocale).language)) {
    throw new TypeError(`Configured default locale "${defaultLocale}" has no catalog.`);
  }
  return Object.freeze({ schema: UI_LOCALES_SCHEMA, defaultLocale,
    catalogs: Object.freeze(catalogs) });
}

function configurationDeadline(timeoutMs, controller) {
  let timer;
  const promise = new Promise((_resolve, reject) => {
    timer = globalThis.setTimeout(() => {
      reject(new Error('UI locale configuration request timed out.'));
      controller.abort();
    }, timeoutMs);
  });
  return Object.freeze({ promise, clear: () => globalThis.clearTimeout(timer) });
}

async function readConfiguration(fetch_, document_, onDiagnostic, timeoutMs) {
  if (!fetch_ || !document_) return { defaultLocale: null, catalogs: [] };
  const url = new URL('./ui-locales.json', document_.baseURI);
  if (url.origin !== document_.location.origin) {
    report(onDiagnostic, 'CONFIG_ORIGIN_REFUSED');
    return { defaultLocale: null, catalogs: [] };
  }
  const controller = new AbortController();
  const deadline = configurationDeadline(timeoutMs, controller);
  let response;
  let text;
  try {
    try {
      response = await Promise.race([fetch_(url, {
        credentials: 'same-origin', cache: 'no-store', redirect: 'error',
        signal: controller.signal,
      }), deadline.promise]);
    } catch (failure) {
      report(onDiagnostic, 'CONFIG_UNAVAILABLE', { reason: failure.message });
      return { defaultLocale: null, catalogs: [] };
    }
    if (response.status === 404) return { defaultLocale: null, catalogs: [] };
    if (response.url && new URL(response.url).origin !== document_.location.origin) {
      report(onDiagnostic, 'CONFIG_ORIGIN_REFUSED');
      return { defaultLocale: null, catalogs: [] };
    }
    if (!response.ok) {
      report(onDiagnostic, 'CONFIG_UNAVAILABLE', { status: response.status });
      return { defaultLocale: null, catalogs: [] };
    }
    const length = Number(response.headers.get('content-length'));
    if (Number.isFinite(length) && length > MAX_UI_CATALOG_BYTES) {
      report(onDiagnostic, 'CONFIG_TOO_LARGE');
      return { defaultLocale: null, catalogs: [] };
    }
    try {
      text = await Promise.race([response.text(), deadline.promise]);
    } catch (failure) {
      report(onDiagnostic, 'CONFIG_UNAVAILABLE', { reason: failure.message });
      return { defaultLocale: null, catalogs: [] };
    }
  } finally {
    deadline.clear();
  }
  try {
    if (new TextEncoder().encode(text).byteLength > MAX_UI_CATALOG_BYTES) throw new RangeError('Configuration is too large.');
    return validateUiLocalesConfiguration(JSON.parse(text));
  } catch (failure) {
    report(onDiagnostic, 'CONFIG_INVALID', { reason: failure.message });
    return { defaultLocale: null, catalogs: [] };
  }
}

function matchingLocale(preferences, available) {
  for (const preference of preferences) {
    let normalized;
    try { normalized = normalizeUiLocale(preference); } catch { continue; }
    if (available.has(normalized)) return normalized;
    const base = new Intl.Locale(normalized).language;
    if (available.has(base)) return normalized;
  }
  return null;
}

function safeStorageValue(storage, key) {
  try { return storage?.getItem(key); } catch { return null; }
}

function setStorageValue(storage, key, value) {
  try {
    if (value === null) storage?.removeItem(key);
    else storage?.setItem(key, value);
  } catch { /* Preference persistence is optional; active locale still changes. */ }
}

function setLocalizedAttribute(element, attribute, key, localization) {
  if (key) element.setAttribute(attribute, localization.text(key));
}

export function localizeDocument(document_, localization) {
  if (!document_) return;
  document_.documentElement.lang = localization.locale;
  document_.documentElement.dir = localization.direction;
  for (const element of document_.querySelectorAll('[data-i18n]')) {
    const prefix = element.dataset.i18nPrefix;
    element.textContent = `${prefix ? `${prefix} ` : ''}${localization.text(element.dataset.i18n)}`;
  }
  for (const element of document_.querySelectorAll('[data-i18n-html]')) {
    // Catalogs never provide markup. This hook is named for elements whose fixed child markup is
    // preserved while a single text span is localized.
    const target = element.querySelector('[data-i18n-text]');
    if (target) target.textContent = localization.text(element.dataset.i18nHtml);
  }
  for (const element of document_.querySelectorAll('[data-i18n-aria-label]')) {
    setLocalizedAttribute(element, 'aria-label', element.dataset.i18nAriaLabel, localization);
  }
  for (const element of document_.querySelectorAll('[data-i18n-aria-description]')) {
    setLocalizedAttribute(element, 'aria-description', element.dataset.i18nAriaDescription, localization);
  }
  for (const element of document_.querySelectorAll('[data-i18n-aria-valuetext]')) {
    setLocalizedAttribute(element, 'aria-valuetext', element.dataset.i18nAriaValuetext, localization);
  }
  for (const element of document_.querySelectorAll('[data-i18n-title]')) {
    setLocalizedAttribute(element, 'title', element.dataset.i18nTitle, localization);
  }
  for (const element of document_.querySelectorAll('[data-i18n-placeholder]')) {
    setLocalizedAttribute(element, 'placeholder', element.dataset.i18nPlaceholder, localization);
  }
}

export async function initializeUiLocalization({
  document: document_ = globalThis.document,
  fetch: fetch_ = globalThis.fetch?.bind(globalThis),
  storage = globalThis.localStorage,
  indexedDB = globalThis.indexedDB,
  navigator: navigator_ = globalThis.navigator,
  onDiagnostic = detail => globalThis.console?.warn?.('Ravenroot UI localization:', detail),
  configurationTimeoutMs = CONFIGURATION_TIMEOUT_MS,
} = {}) {
  const timeoutMs = Number.isFinite(configurationTimeoutMs) && configurationTimeoutMs > 0
    ? configurationTimeoutMs : CONFIGURATION_TIMEOUT_MS;
  const [configuration, imported] = await Promise.all([
    readConfiguration(fetch_, document_, onDiagnostic, timeoutMs),
    storedCatalogs(indexedDB, onDiagnostic),
  ]);
  const catalogs = new Map();
  for (const catalog of [...configuration.catalogs, ...imported]) catalogs.set(catalog.locale, catalog);
  const available = new Set(['en', ...catalogs.keys()]);
  const explicitValue = safeStorageValue(storage, UI_LOCALE_STORAGE_KEY);
  let explicit = null;
  if (explicitValue) {
    try { explicit = normalizeUiLocale(explicitValue); }
    catch (failure) { report(onDiagnostic, 'PREFERENCE_INVALID', { reason: failure.message }); }
  }
  const locale = matchingLocale(explicit ? [explicit] : [], available)
    || matchingLocale(configuration.defaultLocale ? [configuration.defaultLocale] : [], available)
    || matchingLocale(navigator_?.languages || [navigator_?.language].filter(Boolean), available)
    || 'en';
  const localization = createUiLocalization({ locale,
    catalogs: Object.fromEntries([...catalogs].map(([key, catalog]) => [key, catalog])), onDiagnostic });
  const sources = new Map(configuration.catalogs.map(catalog => [catalog.locale, 'configured']));
  for (const catalog of imported) sources.set(catalog.locale, 'imported');
  sources.set('en', 'built-in');
  const apply = () => localizeDocument(document_, localization);
  const unsubscribe = localization.subscribe(apply);
  apply();
  const languageChange = () => {
    if (safeStorageValue(storage, UI_LOCALE_STORAGE_KEY)) return;
    const next = matchingLocale(navigator_?.languages || [navigator_?.language].filter(Boolean), available) || 'en';
    localization.setLocale(next);
  };
  globalThis.addEventListener?.('languagechange', languageChange);

  return Object.freeze({
    localization,
    reportDiagnostic(code, failure) {
      report(onDiagnostic, code, { reason: failure?.message || String(failure || '') });
    },
    sourceFor: locale_ => sources.get(normalizeUiLocale(locale_)) || 'fallback',
    chooseLocale(locale_) {
      const normalized = normalizeUiLocale(locale_);
      setStorageValue(storage, UI_LOCALE_STORAGE_KEY, normalized);
      localization.setLocale(normalized);
    },
    useAutomaticLocale() {
      setStorageValue(storage, UI_LOCALE_STORAGE_KEY, null);
      languageChange();
    },
    async importCatalog(value) {
      const catalog = validateUiCatalog(value);
      if (catalog.locale === 'en') throw new TypeError('The built-in English source catalog cannot be replaced by an imported catalog.');
      await writeStoredCatalog(indexedDB, catalog);
      sources.set(catalog.locale, 'imported');
      available.add(catalog.locale);
      localization.registerCatalog(catalog);
      return catalog.locale;
    },
    async removeImportedCatalog(locale_) {
      const normalized = normalizeUiLocale(locale_);
      if (sources.get(normalized) !== 'imported') throw new TypeError('Only imported UI catalogs can be removed.');
      await deleteStoredCatalog(indexedDB, normalized);
      sources.delete(normalized);
      available.delete(normalized);
      const wasActive = localization.locale === normalized;
      localization.removeCatalog(normalized);
      if (wasActive) {
        setStorageValue(storage, UI_LOCALE_STORAGE_KEY, null);
        languageChange();
      }
    },
    destroy() {
      unsubscribe();
      globalThis.removeEventListener?.('languagechange', languageChange);
    },
  });
}

export { UI_CATALOG_SCHEMA, UI_LOCALES_SCHEMA };
