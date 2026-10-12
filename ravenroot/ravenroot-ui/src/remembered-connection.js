import { memoryTokenProvider } from './runtime-client.js';

export const CONNECTION_STORAGE_KEY = 'ravenroot.ui.connection';
const MAX_RECORD_LENGTH = 24 * 1024;
const STORAGE_FAILURE = 'Browser storage is unavailable. Authentication is memory only; clear this site’s browser data if a previously saved token could not be removed.';

/** Public browser authority, including API prefix; never the proxy's private upstream identity. */
export function connectionService(value, origin = globalThis.location?.origin) {
  const raw = String(value || '').trim();
  if (raw.length > 2048 || /[\\\u0000-\u001f\u007f?#]/u.test(raw)) throw new Error('Enter a valid HTTP(S) service URL without credentials, query or fragment');
  const url = new URL(raw || origin, origin);
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password
      || /%(?:2f|5c|2e|25)|\/\//i.test(url.pathname)) {
    throw new Error('Enter a valid HTTP(S) service URL without credentials, query or fragment');
  }
  return url.origin + url.pathname.replace(/\/+$/, '');
}

/** Exactly one opted-in credential. A token-free record retains the service after forgetting. */
export function createRememberedConnection({ origin, storage = () => globalThis.localStorage,
  onChange = () => {}, onNotice = () => {}, onRejection = () => {} } = {}) {
  const memory = memoryTokenProvider();
  let saved = null;
  let service = null;
  let pending = null;
  let rejectedSnapshot = null;
  let asked = false;
  let notice = '';
  const report = message => { notice = message; onNotice(message); };
  const publish = () => onChange({ hasToken: Boolean(memory.getAccessToken()),
    remembered: Boolean(saved?.service === service && saved.token === memory.getAccessToken() && saved.token), notice });
  const write = record => {
    // If a write fails, also try removing the previous token-bearing record. Never claim removal
    // succeeded when the browser denied both operations.
    try { storage().setItem(CONNECTION_STORAGE_KEY, JSON.stringify(record)); saved = record; return true; }
    catch {
      try { storage().removeItem(CONNECTION_STORAGE_KEY); } catch { /* notice explains uncertainty */ }
      saved = null;
      report(STORAGE_FAILURE);
      return false;
    }
  };
  try {
    const raw = storage().getItem(CONNECTION_STORAGE_KEY);
    if (raw) {
      if (raw.length > MAX_RECORD_LENGTH) throw new Error();
      const record = JSON.parse(raw);
      if (!record || Array.isArray(record) || record.version !== 1
          || Object.keys(record).sort().join(',') !== 'service,token,version'
          || typeof record.service !== 'string' || connectionService(record.service, origin) !== record.service
          || !(record.token === null || (typeof record.token === 'string' && record.token.length > 0
            && record.token.length <= 16 * 1024 && !/[\u0000-\u0020\u007f]/u.test(record.token)))) throw new Error();
      saved = record;
    }
  } catch {
    try { storage().removeItem(CONNECTION_STORAGE_KEY); } catch { /* optional storage */ }
    report('The saved connection could not be read. Authenticate explicitly; browser storage may be unavailable or the record unsupported.');
  }
  const forget = () => {
    pending = null;
    rejectedSnapshot = null;
    memory.clearAccessToken();
    if (saved?.service === service) write({ version: 1, service, token: null });
    publish();
  };
  return {
    startup(defaultService = '', legacyService = '') {
      const selected = saved?.service || connectionService(legacyService || defaultService, origin);
      this.select(selected);
      return selected;
    },
    select(value) {
      const next = connectionService(value, origin);
      if (next !== service) {
        service = next;
        pending = null;
        rejectedSnapshot = null;
        memory.setAccessToken(saved?.service === service ? saved.token || '' : '');
        publish();
      }
      return service;
    },
    authenticate(value, token) {
      this.select(value);
      rejectedSnapshot = null;
      memory.setAccessToken(token);
      pending = memory.getAccessTokenSnapshot();
      publish();
    },
    provider(value) {
      const bound = connectionService(value, origin);
      // A client from an old service can never read a credential belonging to the current one.
      const empty = Object.freeze({ accessToken: '' });
      const assertUsable = () => {
        if (bound === service && rejectedSnapshot === memory.getAccessTokenSnapshot()) {
          throw new Error('Authenticate with a replacement service token');
        }
      };
      return {
        getAccessToken: () => { assertUsable(); return bound === service ? memory.getAccessToken() : ''; },
        getAccessTokenSnapshot: () => { assertUsable(); return bound === service ? memory.getAccessTokenSnapshot() : empty; },
        clearAccessTokenIfCurrent(snapshot) {
          if (bound !== service || snapshot !== memory.getAccessTokenSnapshot() || !snapshot.accessToken) return false;
          forget();
          rejectedSnapshot = memory.getAccessTokenSnapshot();
          onRejection();
          return true;
        },
      };
    },
    async authenticated(confirmRemember) {
      const snapshot = pending;
      if (!snapshot?.accessToken || snapshot !== memory.getAccessTokenSnapshot()) return;
      pending = null;
      // An existing opt-in applies only to this connection, and only after replacement succeeds.
      if (saved?.service === service && saved.token) {
        write({ version: 1, service, token: snapshot.accessToken }); publish(); return;
      }
      if (asked) return;
      asked = true;
      const bound = service;
      const accepted = await confirmRemember(bound);
      if (accepted && service === bound && snapshot === memory.getAccessTokenSnapshot()) {
        write({ version: 1, service, token: snapshot.accessToken }); publish();
      }
    },
    forget,
    get notice() { return notice; },
    get hasSavedService() { return Boolean(saved); },
  };
}
