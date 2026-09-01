// The credential routes, as this page reaches them (the UI half).
//
// ── WHY THIS IS A CLIENT OF ITS OWN AND NOT METHODS ON AN EXISTING ONE ───────────────────────────
//
// Every other client in this page — `runtime-client.js`, `assistant-client.js` — is built around a
// promise this one cannot make: NOTHING SECRET GOES ON THE WIRE FROM HERE. THIS client is the one
// place in the product that deliberately puts a secret on it, once, in one direction. Adding that to
// a file built around the opposite promise would have made both promises unreadable and obscured
// which method obeys which.
//
// So the files state opposite intentions and each states it once.
//
// This boundary does not depend on another client's shape. A projection that drops `apiKey` and a
// form that asks for a NAME illustrate the contrast, but the reason for this separate client is the
// wire-secrecy promise above.
//
// This file is the sole transport for the user-facing credential write path. Keeping that boundary
// explicit makes the route's write-only secret contract auditable.
//
// ── THE RULES, EACH ONE STRUCTURAL RATHER THAN REVIEWED ──────────────────────────────────────────
//
// 1. A CREDENTIAL VALUE TRAVELS IN A BODY, NEVER IN A PATH OR A QUERY. `runtime-client.js` puts its
// execution payload in the query string and that is fine for a payload; a value in a URL lands in
// access logs, in `Referer` and in browser history, and no amount of transport security removes
// it from any of the three. `#send` therefore takes `body`, and this client interpolates NOTHING
// into a path at all — the destination set below is two literal strings, not a pattern.
// 2. NOTHING IN THIS FILE LOGS. Not on success, not in a `catch`, not "temporarily". There is no
// logger, no debug flag and no such reference anywhere in the file, and
// `credential-client.test.js` reads this source to keep it that way. The rule it enforces is
// deliberately coarse — the file may not contain that token AT ALL, comments included — because
// a search that has to tell code from prose is a search that can be talked around.
// 3. NOTHING IS STORED ON THE CLIENT. No field on this class holds a value, and no method returns
// one. The draft is passed in, projected field by field into a request body, and dropped when the
// call returns. The caller clears its own input; this file never gives it anything to put back.
// 4. NOTHING VALUE-SHAPED IS READ BACK. The service is built never to answer with a value, so a
// listing or a creation answer that carries one is a SERVER defect — and `normalizeCredential`
// throws `CredentialLeakError` rather than handing the panel something it could render. The
// projection alone would already drop it; the throw is what makes the defect visible instead of
// silently absorbed.
// 5. THE DESTINATION SET IS CLOSED. `credentialUrl` validates the path against a frozen set and
// throws otherwise, so no response field can become an endpoint. Same control as `assistantUrl`
// in `assistant-client.js`.
//
// The HOST is not validated here, exactly as it is not validated there: `baseUrl` is a
// constructor argument and there is one construction site, in `app.js`, alongside the runtime client
// and from the same `baseUrl`. That is what keeps "this value goes to Ravenroot and nowhere else"
// true by construction rather than by inspection.

export const CREDENTIAL_PATHS = Object.freeze({
  credentials: '/v1/credentials',
});

const PERMITTED_PATHS = new Set(Object.values(CREDENTIAL_PATHS));

export function credentialUrl(baseUrl, path) {
  if (!PERMITTED_PATHS.has(path)) {
    throw new Error(`credential destination is not permitted: ${path}`);
  }
  return `${baseUrl}${path}`;
}

// The service's vocabulary, not a display vocabulary. `credentialSchemeLabel` below is the only
// place a scheme becomes a sentence, so a reader never meets two spellings of the same thing.
export const API_KEY_SCHEME = 'api-key';
export const BASIC_SCHEME = 'basic';
export const OAUTH_TOKEN_SCHEME = 'oauth-token';

export const CREDENTIAL_SCHEMES = Object.freeze([API_KEY_SCHEME, BASIC_SCHEME, OAUTH_TOKEN_SCHEME]);

const SCHEME_LABELS = Object.freeze({
  [API_KEY_SCHEME]: 'API key',
  [BASIC_SCHEME]: 'Username and password',
  [OAUTH_TOKEN_SCHEME]: 'OAuth token',
});

/** A scheme this build does not recognise is shown as itself rather than as a guess. */
export function credentialSchemeLabel(scheme) {
  return SCHEME_LABELS[scheme] || String(scheme || '');
}

/** The one scheme that carries a username. The server refuses the pairing in both directions. */
export function schemeCarriesUsername(scheme) {
  return scheme === BASIC_SCHEME;
}

export class CredentialError extends Error {
  constructor(message, { status = 0, reason = null, correlationId = null, code = null } = {}) {
    super(message);
    this.name = 'CredentialError';
    this.status = status;
    // Carried whether or not this build recognises it: an unmapped token is exactly what a
    // maintainer needs to see, and hiding it makes vocabulary drift invisible.
    this.reason = reason;
    this.correlationId = correlationId;
    this.code = code;
  }
}

export class CredentialLeakError extends Error {
  constructor(key) {
    super('The service answered with a field that could carry a credential value, '
      + `so nothing was rendered: “${key}”.`);
    this.name = 'CredentialLeakError';
    this.key = key;
  }
}

// Rule 4's actual test. Deliberately wider than the five fields the route documents, because the
// point is to catch a field NOBODY here anticipated — the anticipated ones are already dropped by
// the projection below.
const VALUE_BEARING_KEY = /secret|password|passphrase|api[_-]?key|access[_-]?token|refresh[_-]?token|bearer|private[_-]?key/i;
const EXACT_VALUE_KEYS = new Set(['value', 'key', 'token', 'secret', 'auth', 'credential']);

export function normalizeCredential(source) {
  const record = source && typeof source === 'object' && !Array.isArray(source) ? source : {};
  for (const key of Object.keys(record)) {
    if (VALUE_BEARING_KEY.test(key) || EXACT_VALUE_KEYS.has(key.toLowerCase())) {
      throw new CredentialLeakError(key);
    }
  }
  return {
    reference: String(record.reference ?? ''),
    label: String(record.label ?? ''),
    scheme: String(record.scheme ?? ''),
    username: String(record.username ?? ''),
    createdAt: String(record.createdAt ?? ''),
  };
}

/**
 * `{credentials: [...]}` is the documented envelope; a bare array is accepted for the same reason
 * `assistant-client.js` accepts two spellings of its error field — a build that emits only one shape
 * still renders, instead of showing an empty list that looks like "you have none".
 */
export function normalizeCredentialListing(body) {
  const records = Array.isArray(body) ? body
    : Array.isArray(body?.credentials) ? body.credentials : [];
  return records.map(normalizeCredential).filter(entry => entry.reference !== '');
}

export const ABSENT_LISTING = Object.freeze({ surface: 'absent', credentials: Object.freeze([]) });

// The server's standard error envelope (API-01): `{contract, code, message, error, correlationId}`.
// Not this route's own shape — every route answers a failure in it, which is why this reader makes
// no assumption particular to `/v1/credentials`.
function readEnvelope(status, body) {
  const envelope = body && typeof body === 'object' && !Array.isArray(body) ? body : {};
  const prose = typeof envelope.message === 'string' && envelope.message
    ? envelope.message
    : (typeof envelope.error === 'string' ? envelope.error : '');
  return {
    status,
    reason: typeof envelope.reason === 'string' && envelope.reason ? envelope.reason : null,
    detail: prose || '',
    correlationId: typeof envelope.correlationId === 'string' && envelope.correlationId
      ? envelope.correlationId : null,
    code: typeof envelope.code === 'string' && envelope.code ? envelope.code : null,
  };
}

export class RavenrootCredentialClient {
  constructor(baseUrl, options = {}) {
    this.baseUrl = normalizeBaseUrl(baseUrl);
    this.fetchImpl = options.fetchImpl || globalThis.fetch?.bind(globalThis);
    // The Ravenroot session token — the author's authentication to THIS product, the same one the
    // runtime client and the assistant use. It is not one of the credentials this route manages, and
    // that distinction is essential to keeping the two credential classes separate.
    this.tokenProvider = options.tokenProvider || { getAccessToken: () => '' };
  }

  /**
   * The author's OWN credentials, and never a value. The service scopes the listing to the caller;
   * this side does not filter, because a client-side filter over somebody else's records would mean
   * the records had already been sent.
   *
   * A 404 or 501 is not an error: it is what a build without this route answers. The path has no id
   * segment, so there is nothing else a 404 could be about.
   */
  async list() {
    const response = await this.#send(CREDENTIAL_PATHS.credentials, {
      method: 'GET',
      headers: { Accept: 'application/json' },
    });
    if (response.status === 404 || response.status === 501) return ABSENT_LISTING;
    const body = await readJson(response);
    if (!response.ok) throw failure(readEnvelope(response.status, body));
    return { surface: 'present', credentials: normalizeCredentialListing(body) };
  }

  /**
   * Store one credential. THIS IS THE ONE REQUEST IN THE PAGE THAT CARRIES A VALUE.
   *
   * The body is projected field by field rather than spread, and that projection is load-bearing
   * twice over. It is what keeps a stray field left on a draft object by a later edit off the wire —
   * the service refuses any unknown member with a 400 — and it is what enforces the `username`
   * pairing this route requires in BOTH directions: present and non-blank for `basic`, absent
   * entirely for the other two. Sending an empty `username` for an api-key would be refused, so it
   * is not sent at all.
   *
   * NO REFERENCE IS PROPOSED. The service mints it; a body carrying `reference`, `id`,
   * `credentialRef`, `credentialReference` or `name` is refused with 400. That is the server's rule,
   * and the projection here is the reason this client cannot break it by accident.
   */
  async create(draft) {
    const source = draft && typeof draft === 'object' ? draft : {};
    const scheme = String(source.scheme ?? '');
    const body = {
      label: String(source.label ?? ''),
      scheme,
      ...(schemeCarriesUsername(scheme) ? { username: String(source.username ?? '') } : {}),
      value: String(source.value ?? ''),
    };
    const response = await this.#send(CREDENTIAL_PATHS.credentials, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json; charset=utf-8', Accept: 'application/json' },
      body: JSON.stringify(body),
    });
    if (response.status === 404 || response.status === 501) {
      throw new CredentialError(
        'This deployment does not offer credential storage, so nothing was stored.',
        { status: response.status, reason: 'SURFACE_ABSENT' });
    }
    const answer = await readJson(response);
    if (!response.ok) throw failure(readEnvelope(response.status, answer));
    // Normalized, not returned raw: rule 4 applies to a creation answer exactly as it applies to a
    // listing, and this is the answer most likely to echo what was just sent.
    return normalizeCredential(answer);
  }

  // A REJECTED FETCH IS NOT "THE REQUEST NEVER ARRIVED", AND THIS IS WHERE THAT IS DECIDED.
  //
  // The browser hands the page a rejection with no status and no cause. That covers a request that
  // never departed AND a request that arrived and was refused: `BrowserOriginPolicy` answers an
  // origin outside its allowlist with a 403 carrying no `Access-Control-Allow-Origin`, and the
  // browser then refuses to let this page read the answer it did receive. So the transport failure
  // gets its own reason and a sentence that enumerates the causes rather than choosing one. The
  // browser's own wording is not a diagnosis and is never passed through as if it were.
  async #send(path, options) {
    if (!this.fetchImpl) throw new Error('Fetch API is not supported by this browser');
    const token = String(this.tokenProvider?.getAccessToken?.() || '');
    // OUTSIDE the try on purpose: a refused destination is a defect in this build, not a transport
    // failure, and dressing it up as one would hide the only bug this control exists to catch.
    const url = credentialUrl(this.baseUrl, path);
    try {
      return await this.fetchImpl(url, {
        ...options,
        credentials: 'omit',
        cache: 'no-store',
        headers: token ? { ...options.headers, Authorization: `Bearer ${token}` } : { ...options.headers },
      });
    } catch {
      throw new CredentialError(
        'The service could not be reached, and the browser does not say why: the request may never '
        + 'have left this page, or it may have arrived and been refused by an origin rule. Nothing '
        + 'was stored.',
        { status: 0, reason: 'NO_HTTP_STATUS' });
    }
  }
}

function failure(envelope) {
  const message = envelope.detail || `The service answered with HTTP ${envelope.status}.`;
  return new CredentialError(message, envelope);
}

async function readJson(response) {
  try {
    return await response.json();
  } catch {
    return null;
  }
}

function normalizeBaseUrl(value) {
  const baseUrl = String(value || '').trim().replace(/\/$/, '');
  if (!baseUrl) return '';
  const parsed = new URL(baseUrl, globalThis.location?.origin || 'http://localhost');
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw new Error('Service URL must use HTTP(S)');
  }
  return parsed.origin + parsed.pathname.replace(/\/$/, '');
}
