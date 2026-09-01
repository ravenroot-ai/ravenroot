export const THEME_STORAGE_KEY = 'ravenroot.ui.theme';
export const PRODUCT_DEFAULT_THEME = 'dark';
export const APPLICATION_THEMES = Object.freeze(['dark', 'light']);

export function normalizeTheme(value) {
  return APPLICATION_THEMES.includes(value) ? value : null;
}

export function requireEmbedTheme(value) {
  const normalized = normalizeTheme(value);
  if (normalized === null) throw new TypeError('Unsupported embed theme.');
  return normalized;
}

/** Pure initial precedence shared by the application and embedded viewer contracts. */
export function resolveInitialTheme({
  embedTheme = null,
  persistedTheme = null,
  systemPrefersDark = null,
  productDefault = PRODUCT_DEFAULT_THEME,
} = {}) {
  const explicit = embedTheme == null ? null : requireEmbedTheme(embedTheme);
  return explicit
    || normalizeTheme(persistedTheme)
    || (systemPrefersDark == null ? null : (systemPrefersDark ? 'dark' : 'light'))
    || normalizeTheme(productDefault)
    || PRODUCT_DEFAULT_THEME;
}
