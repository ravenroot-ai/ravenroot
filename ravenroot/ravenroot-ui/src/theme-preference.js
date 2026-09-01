import {
  PRODUCT_DEFAULT_THEME,
  THEME_STORAGE_KEY,
  normalizeTheme,
  resolveInitialTheme,
} from './theme-resolution.js';

export {
  APPLICATION_THEMES,
  PRODUCT_DEFAULT_THEME,
  THEME_STORAGE_KEY,
  normalizeTheme,
} from './theme-resolution.js';

export function resolveApplicationTheme({ persistedTheme, systemPrefersDark = null,
  productDefault = PRODUCT_DEFAULT_THEME } = {}) {
  return resolveInitialTheme({ persistedTheme, systemPrefersDark, productDefault });
}

function readPersistedTheme(storage) {
  try { return normalizeTheme(storage?.getItem(THEME_STORAGE_KEY)); }
  catch { return null; }
}

function persistTheme(storage, theme) {
  try { storage?.setItem(THEME_STORAGE_KEY, theme); }
  catch { /* Browser storage is optional; the in-page choice still applies. */ }
}

export function applyThemeToRoot(root, theme) {
  const normalized = normalizeTheme(theme) || PRODUCT_DEFAULT_THEME;
  if (root) {
    root.dataset.theme = normalized;
    root.style.colorScheme = normalized;
  }
  return normalized;
}

export function createThemePreferenceController({
  root = globalThis.document?.documentElement,
  storage = globalThis.localStorage,
  media = globalThis.matchMedia?.('(prefers-color-scheme: dark)'),
  onChange = () => {},
} = {}) {
  let persistedTheme = readPersistedTheme(storage);
  let theme = applyThemeToRoot(root, resolveApplicationTheme({
    persistedTheme,
    systemPrefersDark: media ? Boolean(media.matches) : null,
  }));

  const publish = (nextTheme, source) => {
    const previousTheme = theme;
    theme = applyThemeToRoot(root, nextTheme);
    if (theme !== previousTheme) onChange(theme, { source, previousTheme });
    return theme;
  };

  const onSystemChange = event => {
    if (persistedTheme) return;
    publish(resolveApplicationTheme({ systemPrefersDark: Boolean(event.matches) }), 'system');
  };
  media?.addEventListener?.('change', onSystemChange);

  return Object.freeze({
    get theme() { return theme; },
    get hasUserChoice() { return Boolean(persistedTheme); },
    select(nextTheme) {
      const normalized = normalizeTheme(nextTheme);
      if (!normalized) throw new TypeError(`Unsupported application theme: ${String(nextTheme)}`);
      persistedTheme = normalized;
      persistTheme(storage, normalized);
      return publish(normalized, 'user');
    },
    destroy() { media?.removeEventListener?.('change', onSystemChange); },
  });
}
