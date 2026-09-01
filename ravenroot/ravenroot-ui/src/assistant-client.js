// The panel's only way out of the browser — and it goes to this product's own service, never to a
// model provider (the per-author credential contract and the design record's §1).
//
// ── THE PROHIBITION, ENFORCED STRUCTURALLY ──────────────────────────────────────────────────────
//
// No provider call may originate in the browser. Two reasons, and neither is a preference:
// browser-originated egress is invisible to the JVM-wide guard, so the product's only
// egress control would not see the product's most data-laden outbound call; and a provider token
// reachable from page script is one injection away from subscription theft, in a UI that renders
// USER-AUTHORED GRAPH CONTENT.
//
// A comment saying "do not call a provider from here" is not a control — it cannot fail a test.
// So the control is split, and it is worth being exact about which half each part does:
//
// * `assistantUrl` validates THE PATH against a frozen two-element set and throws on anything
// else. No response field, no reply text and no graph label can become a destination, because
// none of them is ever passed as a path.
// * THE HOST IS NOT VALIDATED HERE. `baseUrl` is a constructor argument and this class passes it
// through untouched, so the guarantee "no provider call originates in the browser" does not
// rest on this file alone — it rests on `baseUrl` only ever being the Ravenroot service the
// rest of the UI is already talking to. There is exactly one construction site (`app.js`,
// alongside `new RavenrootRuntimeClient`, from the same `baseUrl`), and
// `assistant-client.test.js` pins that site by reading the source, because an ADR headline
// decision resting on one unpinned call site is resting on a habit.
//
// Between them: a change that lets a status response name its own endpoint reds a test, and a
// change that gives the assistant its own host reds a different one.
//
// ── WHAT THIS CLIENT TALKS TO TODAY: NOTHING ─────────────────────────────────────────────────────
//
// The server-side assistant service is not implemented. This client is written against the
// interface ADR 0025 defines rather than against a running endpoint, and when the endpoint is
// absent it says so — `reachable: false`, surfaced as the panel's `service-unavailable` inert
// reason. IT NEVER SYNTHESIZES A REPLY. A panel that appeared to work by echoing the prompt is
// false, so `send()` has no local fallback path:
// with no service, it throws.

import { ATTACHED, CONTEXT_CLASS_IDS } from './assistant-context.js';
import { assistantFailureText } from './assistant-session.js';

// The complete destination set. Adding one is a deliberate edit here, visible in a diff.
export const ASSISTANT_PATHS = Object.freeze({
  status: '/v1/assistant',
  messages: '/v1/assistant/messages',
  // The device grant's two moments — ask for a code, then ask whether it has been used — on
  // one path distinguished by method, because they are two views of ONE pending connection rather
  // than two resources. POST starts it, GET reports on it, DELETE abandons it.
  //
  // IT IS A RAVENROOT PATH, like the other two, and that is the whole point of it existing. The
  // provider's own endpoints are never named here and never reach the browser: this client asks
  // Ravenroot to conduct the exchange, exactly as it asks Ravenroot to conduct a model call. The
  // header's prohibition is unchanged by this addition — if anything it is the reason the addition
  // takes this shape instead of the browser talking to an authorization server directly.
  connection: '/v1/assistant/connection',
});

const PERMITTED_PATHS = new Set(Object.values(ASSISTANT_PATHS));

// THE PATH CONTROL, EXPORTED SO IT CAN BE EXERCISED DIRECTLY.
//
// It was a private method first. That was a mistake: a control reachable only through the two
// callers that already obey it is a control whose failure mode — someone widening it — no test can
// reach. As a named function a test hands it `https://provider.attacker.example` and asserts it
// throws, so removing the check reds a test instead of passing review.
//
// It validates the PATH ONLY. `baseUrl` is passed through as given; see the header for where that
// half of the guarantee actually lives and what pins it.
export function assistantUrl(baseUrl, path) {
  if (!PERMITTED_PATHS.has(path)) {
    throw new Error(`assistant destination is not permitted: ${path}`);
  }
  return `${baseUrl}${path}`;
}

export class AssistantUnavailableError extends Error {
  constructor(reason, message) {
    super(message);
    this.name = 'AssistantUnavailableError';
    // One of `assistant-session.js`'s inert reasons, so the panel states its cause rather than
    // pooling every failure into "error".
    this.reason = reason;
  }
}

// ── READING THE SERVER'S STANDARD ERROR ENVELOPE (API-01) ────────────────────────────────────────
//
// The server answers a failed turn with `{contract, code, message, error, correlationId}` — the
// same envelope every Ravenroot surface returns. Three things about it decide this function.
//
// 1. `code` IS THE MACHINE-READABLE FIELD; `message` and `error` are prose. The earlier client read
// only `error`. That happened to work — `error` is a compatibility alias carrying the same text
// as `message` — but it read the human sentence and ignored the token, so every failure arrived
// as one undifferentiated string and the panel rendered its own generic fallback. Reading `code`
// is what lets a failure keep a NAME on this side of the wire.
//
// 2. `correlationId` IS NOT DECORATION, IT IS THE BRIDGE. The envelope's stated trade is that the
// response says little while the server keeps the cause. The server records the specific
// assistant reason against this id (`recordAssistantFailure`), so quoting it is the only way a
// user gets from the sentence they can see to the reason that actually occurred. Dropping it
// would strand the diagnosis the server deliberately kept.
//
// 3. THE HTTP CODE COLLAPSES NINE REASONS INTO FOUR CLASSES; `assistantReason` PRESERVES THE EXACT
// ONE. `AssistantOutcome.Reason` distinguishes provider refusal, unreadable/rejected/unavailable
// responses, egress refusal, tool-loop exhaustion, invalid author input, an invalid model graph
// proposal and an adapter defect. `assistantFailureCode` maps those onto four `ErrorCode`s, while
// the optional token keeps the distinction on the wire. This function prefers a recognised token
// and falls back to the envelope prose when it is absent or unknown; it never guesses a reason
// from the coarser HTTP code.
const ENVELOPE_REASONS = Object.freeze({
  // The route is not registered: this deployment does not offer the assistant at all.
  UNKNOWN_RESOURCE: 'service-unavailable',
  // The caller is not authenticated to RAVENROOT. Not a provider sign-in; see `assistant-session.js`.
  AUTHENTICATION_REQUIRED: 'not-signed-in',
  ACCESS_DENIED: 'not-signed-in',
});

export function failureFromEnvelope(status, body) {
  const envelope = body && typeof body === 'object' && !Array.isArray(body) ? body : {};
  const code = typeof envelope.code === 'string' ? envelope.code : '';
  const correlationId = typeof envelope.correlationId === 'string' ? envelope.correlationId : '';
  // `message` first — it is the contract's own field. `error` is accepted as its documented alias
  // so a build that emits only the alias still renders prose rather than a bare status.
  const prose = typeof envelope.message === 'string' && envelope.message
    ? envelope.message
    : (typeof envelope.error === 'string' && envelope.error ? envelope.error : '');

  // THE NAMED REASON, WHEN THE SERVER CARRIES ONE. `assistantReason` is optional and additive: a
  // response without it must render exactly as it did before this field existed, which is why the
  // fallback chain below still ends at the envelope prose and then at the status.
  //
  // An UNRECOGNISED token contributes nothing. It is not prefix-matched, not fuzzy-matched and not
  // mapped to a neighbour — the vocabulary can grow on the server before this build learns the
  // word, and guessing a nearby reason is the same failure as echoing a reply we did not receive.
  // The token is still carried on the error so it reaches a log and a test even when unmapped.
  const assistantReason = typeof envelope.assistantReason === 'string' ? envelope.assistantReason : '';
  const named = assistantFailureText(assistantReason);
  const sentence = named || prose || `The assistant request failed with HTTP ${status}.`;
  // Quotable, and marked as quotable. A hex handle with no explanation is noise to the person
  // holding it and gold to the person reading the server log.
  const message = correlationId
    ? `${sentence} Quote reference ${correlationId} when reporting this.`
    : sentence;

  const error = new AssistantUnavailableError(ENVELOPE_REASONS[code] ?? null, message);
  // Carried so the panel and its tests can assert on the token rather than on prose, and so a
  // future server field can be threaded through without reshaping this type.
  error.code = code || null;
  error.correlationId = correlationId || null;
  // Carried whether or not it was recognised: an unmapped token is exactly the thing a maintainer
  // needs to see, and hiding it would make the vocabulary drift invisible.
  error.assistantReason = assistantReason || null;
  error.assistantReasonKnown = Boolean(named);
  return error;
}

export class RavenrootAssistantClient {
  constructor(baseUrl, options = {}) {
    this.baseUrl = normalizeBaseUrl(baseUrl);
    this.fetchImpl = options.fetchImpl || globalThis.fetch?.bind(globalThis);
    // The Ravenroot session token — the user's authentication to THIS product, which is what makes
    // "a denial to the user is a denial to the panel" true. It is never a provider credential:
    // the provider token lives server-side and has no browser-readable representation in any mode.
    this.tokenProvider = options.tokenProvider || { getAccessToken: () => '' };
  }

  // What the deployment says about itself. Every failure maps to a NAMED reason rather than to a
  // generic error, because the panel's whole promise is that it says which thing is missing.
  async status() {
    let response;
    try {
      response = await this.#fetch(ASSISTANT_PATHS.status, {
        method: 'GET',
        headers: { Accept: 'application/json' },
      });
    } catch (error) {
      // Network-level failure is indistinguishable from an absent deployment from here, and both
      // are honestly described by the same sentence: nothing is configured to answer.
      return unreachable(error?.message || 'the assistant service could not be reached');
    }

    // A deployment may intentionally omit or disable the service.
    if (response.status === 404 || response.status === 501) {
      return unreachable('this deployment does not provide the assistant service');
    }
    if (response.status === 401 || response.status === 403) {
      return { reachable: true, configured: true, allowlisted: true, signedIn: false, provider: null };
    }
    if (!response.ok) {
      return unreachable(`the assistant service responded with HTTP ${response.status}`);
    }

    let body;
    try {
      body = await response.json();
    } catch {
      return unreachable('the assistant service returned a response this build cannot read');
    }
    return normalizeStatus(body);
  }

  // Sends the author's prompt and the composed context payload to the SERVER, which is the only
  // component that ever holds a provider credential or opens a provider connection.
  async send({ prompt, context, document, signal }) {
    const payload = {
      prompt: String(prompt ?? ''),
      // Only the classes this build knows about, and only those actually attached. A payload key
      // the panel did not show a chip for would be an undisclosed egress of exactly the kind the
      // chips exist to prevent.
      context: Object.fromEntries(Object.entries(context?.payload || {})
        .filter(([id]) => CONTEXT_CLASS_IDS.includes(id))),
      // The constant, not the literal `'attached'`: this is the one place where the wire format
      // and the chip vocabulary have to agree, so it reads the same symbol the chips do rather
      // than a copy of its current value.
      attached: (context?.classes || [])
        .filter(entry => entry.state === ATTACHED)
        .map(entry => entry.id),
    };
    if (document) {
      payload.document = {
        incarnation: String(document.incarnation),
        revision: Number(document.revision),
        catalogDigest: String(document.catalogDigest),
      };
    }

    let response;
    try {
      response = await this.#fetch(ASSISTANT_PATHS.messages, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json; charset=utf-8', Accept: 'application/json' },
        body: JSON.stringify(payload),
        signal,
      });
    } catch (error) {
      throw new AssistantUnavailableError('service-unavailable',
        `The assistant service could not be reached: ${error?.message || error}`);
    }

    if (response.status === 404 || response.status === 501) {
      // NO FALLBACK. See the header: an echo here would make an unconnected panel look connected.
      throw new AssistantUnavailableError('service-unavailable',
        'This deployment does not provide the assistant service, so the message was not sent.');
    }
    let body = null;
    let unreadable = false;
    try {
      body = await response.json();
    } catch {
      unreadable = true;
    }
    if (!response.ok) throw failureFromEnvelope(response.status, body);
    // A 2xx WE CANNOT READ IS A FAILURE, NOT AN EMPTY ANSWER. Without this, a success status with
    // an unparseable body fell through to `String(body?.text ?? '')` and produced a turn labelled
    // ASSISTANT with empty text — the only path in this module that could put words in the
    // service's mouth that the service did not author, which is the same defect as echoing wearing
    // a quieter costume. `status()` already guarded the identical case; this is that guard's twin.
    if (unreadable) {
      throw new AssistantUnavailableError(null,
        'The assistant service returned a response this build cannot read, so nothing was answered.');
    }
    // Text remains inert. Structured proposals travel only in the named additive member: JSON-looking
    // prose and unknown response fields can never be promoted into an editor action here.
    const reply = {
      text: String(body?.text ?? ''),
      model: body?.model ? String(body.model) : null,
    };
    if (body?.proposal && typeof body.proposal === 'object' && !Array.isArray(body.proposal)) {
      reply.proposal = body.proposal;
    }
    return reply;
  }

  // ── THE CONNECTION, WHICH THIS CLIENT ASKS FOR AND NEVER CONDUCTS ───────────────────────
  //
  // Both methods below talk to Ravenroot and to nothing else. The provider's authorization server is
  // reached by the JVM, under the operator's egress policy, with the device code held server-side —
  // and that arrangement is the reason this panel can offer a sign-in at all without breaking the
  // rule in this file's header. A browser-conducted device flow would put a provider token in page
  // script, which is the injection risk the header refuses.
  //
  // WHAT THE PANEL NEVER RECEIVES: the device code. RFC 8628 gives the exchange two secrets — the
  // user code, which is meant to be read aloud and typed, and the device code, which redeems the
  // grant. Only the first is displayed. The route does not send the second, and this client has no
  // field to put it in, so a server change that started sending it would still not reach the DOM.

  /**
   * Asks the deployment to begin a connection for the signed-in author.
   *
   * Returns what the author has to be shown and how to wait: the code, where to type it, and the
   * provider's own polling interval. Rejects with an `AssistantUnavailableError` when the deployment
   * cannot begin one — including the case that matters most today, a deployment in this mode whose
   * operator has not supplied the provider's endpoints.
   */
  async beginConnection({ signal } = {}) {
    const response = await this.#connectionCall('POST', signal);
    const body = await readJson(response);
    if (!response.ok) throw failureFromEnvelope(response.status, body);
    if (!body || typeof body !== 'object') {
      throw new AssistantUnavailableError(null,
        'The connection could not be started: the service returned a response this build cannot read.');
    }
    const userCode = typeof body.userCode === 'string' ? body.userCode : '';
    const verificationUri = typeof body.verificationUri === 'string' ? body.verificationUri : '';
    // Both or neither. A code with nowhere to type it, or an address with no code, is not a usable
    // instruction, and rendering half of one is how an author is left staring at a dead panel.
    if (!userCode || !verificationUri) {
      throw new AssistantUnavailableError(null,
        'The connection could not be started: the service did not return a code and an address.');
    }
    return {
      userCode,
      // Carried as text and rendered as text. `app.js` decides whether it is safe to make into a
      // link; this module does not build one, for the same reason it does not build a destination
      // out of `provider`.
      verificationUri,
      verificationUriComplete: typeof body.verificationUriComplete === 'string'
        ? body.verificationUriComplete : null,
      // Seconds, clamped by the caller. Absent means "the provider named none", which RFC 8628
      // gives a default for — that default belongs to the server, which speaks the protocol.
      interval: positiveNumber(body.interval),
      expiresIn: positiveNumber(body.expiresIn),
    };
  }

  /**
   * Asks once whether the author has finished. Once, not in a loop: the loop belongs to the panel,
   * which is where the decisions about how long to wait and what to show meanwhile actually live.
   *
   * Answers `{ state, reason, retryAfter }` where `state` is one of `linked`, `waiting` or `none`.
   * A `reason` accompanies `waiting` and is one of the tokens `assistant-session.js` has sentences
   * for; an unrecognised one is carried, never mapped onto a neighbour.
   */
  async connectionProgress({ signal } = {}) {
    const response = await this.#connectionCall('GET', signal);
    const body = await readJson(response);
    if (!response.ok) throw failureFromEnvelope(response.status, body);
    const source = body && typeof body === 'object' ? body : {};
    const state = source.state === 'linked' || source.state === 'waiting' ? source.state : 'none';
    return {
      state,
      reason: typeof source.reason === 'string' && source.reason ? source.reason : null,
      retryAfter: positiveNumber(source.retryAfter),
    };
  }

  /** Abandons a connection in progress. What the panel's cancel does, and what closing it does. */
  async abandonConnection({ signal } = {}) {
    await this.#connectionCall('DELETE', signal);
  }

  async #connectionCall(method, signal) {
    try {
      return await this.#fetch(ASSISTANT_PATHS.connection, {
        method,
        headers: { Accept: 'application/json' },
        signal,
      });
    } catch (error) {
      if (error?.name === 'AbortError') throw error;
      throw new AssistantUnavailableError('service-unavailable',
        `The assistant service could not be reached: ${error?.message || error}`);
    }
  }

  #fetch(path, options) {
    if (!this.fetchImpl) throw new Error('Fetch API is not supported by this browser');
    const token = String(this.tokenProvider?.getAccessToken?.() || '');
    return this.fetchImpl(assistantUrl(this.baseUrl, path), {
      ...options,
      credentials: 'omit',
      cache: 'no-store',
      headers: token
        ? { ...options.headers, Authorization: `Bearer ${token}` }
        : { ...options.headers },
    });
  }

}

// A body, or null when there is not one this build can read. The callers decide what that means:
// on a failed response it is normal (an envelope may be absent), on a successful one it is itself a
// failure — the same distinction `send()` draws, kept in one place now that a third route needs it.
async function readJson(response) {
  try {
    return await response.json();
  } catch {
    return null;
  }
}

// Seconds off the wire, or null. Rejects zero, negatives and non-numbers rather than coercing:
// a poll interval of 0 read from a malformed field is a request loop, and `Number('')` is 0.
function positiveNumber(value) {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : null;
}

function unreachable(detail) {
  return {
    reachable: false, configured: false, insecureRefused: false, allowlisted: false, signedIn: false,
    provider: null, detail: String(detail),
  };
}

// Absent or non-boolean fields read FALSE, never true: an unparseable status must leave the panel
// inert, not optimistically ready.
function normalizeStatus(body) {
  const source = body && typeof body === 'object' && !Array.isArray(body) ? body : {};
  return {
    reachable: true,
    configured: source.configured === true,
    // Positive and additive: an older server omits it and keeps the preexisting state ordering.
    insecureRefused: source.insecureRefused === true,
    allowlisted: source.allowlisted === true,
    signedIn: source.signedIn === true,
    // Also `=== true`, and for a different reason from the three above. Those are
    // prerequisites, where false is the safe reading. This one is a MODE, and its false reading is
    // the pre-existing behaviour: a deployment that says nothing about connections is one where the
    // operator's own credential serves every author, which remains the default.
    // See `inertReason` in `assistant-session.js` for why that is not the fail-closed rule bending.
    linkRequired: source.linkRequired === true,
    // A display name only. It is rendered as escaped text and is never used to build a URL —
    // `assistantUrl` would refuse it anyway, which is why every request is routed through it.
    provider: typeof source.provider === 'string' ? source.provider : null,
    detail: typeof source.detail === 'string' ? source.detail : '',
  };
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
