/** Browser sessions are opt-in and confined to the page's own HTTP(S) origin. */
export function browserSessionCredentials(baseUrl, enabled) {
  const pageOrigin = globalThis.location?.origin;
  if (!enabled || !pageOrigin || pageOrigin === 'null') return 'omit';
  try {
    const destination = new URL(baseUrl || pageOrigin, pageOrigin);
    return ['http:', 'https:'].includes(destination.protocol) && destination.origin === pageOrigin
      ? 'same-origin' : 'omit';
  } catch {
    return 'omit';
  }
}
