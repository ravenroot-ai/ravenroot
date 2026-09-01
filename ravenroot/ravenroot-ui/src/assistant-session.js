// What the authoring assistant panel IS at any moment: which state, why, what the composer may do,
// and what the transcript holds (the assistant-availability contract).
//
// ── INERT IS A FIRST-CLASS STATE, AND ITS REASONS DO NOT POOL ────────────────────────────────────
//
// With no model configured the panel is rendered, not hidden, and it says WHICH thing is missing.
// The reasons are actionable by different people: `no-profile` is the operator's configuration,
// `host-not-allowlisted` is the operator's egress policy — a user cannot widen it and must not
// be told to try), `not-signed-in` is the reader's own Ravenroot session, and `service-unavailable`
// is a deployment that does not offer the assistant at all. Pooling them into "assistant
// unavailable" would send every one of them to ask the wrong person — "the backend is not deployed"
// is not "you are not signed in", and a panel that reported the second while the first were true
// would send a user hunting for a sign-in button that is not missing.
//
// `not-linked` is the fifth reason, and it is the only one whose remedy is IN THIS PANEL: the
// deployment is whole, the reader is authenticated to Ravenroot, and what is outstanding is their
// own connection to the model provider. It arrived with the control that resolves it, because a
// distinguished reason whose remedy the panel does not offer is a more precise dead end, not a
// better one.
//
// ── ORDERING IS PART OF THE CONTRACT ─────────────────────────────────────────────────────────────
//
// The reasons are evaluated most-fundamental-first. Being signed in is meaningless if no provider
// profile is configured, and a profile is meaningless if the service is not there — so the panel
// always names the thing that must be fixed FIRST, rather than the last check that happened to
// fail. The order is asserted in tests because it is a product behaviour, not an implementation
// accident.
//
// DOM-free and network-free on purpose: app.js renders this, `assistant-client.js` feeds it.

// The disclosure's role name comes FROM the disclosure module rather than being restated here.
// This module owns the panel's role vocabulary, so it has to admit the role; but if the two files
// each declared the string, a rename in one would silently turn admitted turns into `notice` in the
// other — which is the disclosure being dropped, expressed as a typo.
import { DISCLOSURE_ROLE } from './assistant-disclosure.js';

export const INERT = 'inert';
export const READY = 'ready';
export const DEGRADED = 'degraded';
export const ERROR = 'error';

export const SERVICE_UNAVAILABLE = 'service-unavailable';
export const NO_PROFILE = 'no-profile';
export const INSECURE_REFUSED = 'insecure-refused';
export const HOST_NOT_ALLOWLISTED = 'host-not-allowlisted';
export const NOT_SIGNED_IN = 'not-signed-in';

// ── THE FIFTH REASON, AND WHY IT IS NOT THE FOURTH WEARING A NEW NAME ─────────────────────
//
// `not-linked` is the deployment saying: everything I own is in place, you are authenticated to me,
// and the one outstanding thing is YOUR OWN connection to the model provider. It is the only reason
// in this list an author can resolve from inside this panel, which is exactly what makes it worth
// distinguishing — the control that resolves it is offered here and nowhere else.
//
// It is separate from `not-signed-in` because the two send the reader to different places. The
// panel's `not-signed-in` means the RAVENROOT session is unauthenticated, reached by a 401/403 from
// Ravenroot itself; this one means the Ravenroot session is fine and something further along is
// missing. Before the server projected both onto the same wire state, so an author in this
// state read "Sign in to Ravenroot and try again" while already signed in to Ravenroot — a sentence
// that is not merely unhelpful but false, and one that names a remedy that changes nothing.
export const NOT_LINKED = 'not-linked';

// Most fundamental first. See the header — this order is a contract.
//
// `not-linked` is LAST, and that placement is the same argument the other four already make: a
// connection is worthless while the service is absent, no profile is configured, the host is
// refused, or the author is not authenticated to Ravenroot. Naming it before any of those would put
// a working control in front of an author whose problem it cannot touch.
export const INERT_REASON_ORDER = Object.freeze([
  SERVICE_UNAVAILABLE, NO_PROFILE, INSECURE_REFUSED, HOST_NOT_ALLOWLISTED, NOT_SIGNED_IN, NOT_LINKED,
]);

// Each sentence says what is true and who can change it. None of them invites the user to do
// something only an operator can do.
//
// ── AND NONE OF THEM SAYS WHERE THE PROVIDER CREDENTIAL LIVES ────────────────────────────────────
//
// `not-signed-in` used to read "Sign in to your own model subscription… Ravenroot never holds a
// provider key on your behalf." Both clauses were false against the server that shipped: the
// credential is operator-configured (`AssistantCredential`, read from configuration), so Ravenroot
// does hold a key, and there is no user sign-in to perform.
//
// THE PANEL HAS NO OPINION ABOUT CUSTODY because it cannot observe it. Nothing the panel receives
// tells it whether the key
// came from operator configuration or a user's own device flow, so any sentence it writes on the
// subject is a guess that depends on a decision made elsewhere. The per-author credential contract
// and the shipped server currently use different custody models, so these texts must remain true for
// either model. `assistant-session.test.js` enforces this: no
// reason text may make a custody claim.
//
// ── WHAT `not-signed-in` ACTUALLY MEANS HERE ─────────────────────────────────────────────────────
//
// The server never constructs its `NOT_SIGNED_IN` inert reason — `AssistantConfiguration` and
// `AssistantService` produce only `NO_PROFILE` and `HOST_NOT_ALLOWLISTED`, because an absent
// credential is an operator gap. The reason is reachable in the CLIENT, and by exactly one route: a
// 401 or 403 from Ravenroot itself, which `status()` maps to signed-out. So it means the author's
// RAVENROOT session is not authenticated — not that they have failed to sign in to a provider — and
// it now says that. Every reason below is provoked from a concrete server response in the tests,
// because a state nobody can provoke is a state nobody has tested.
export const INERT_REASON_TEXT = Object.freeze({
  [SERVICE_UNAVAILABLE]:
    'This deployment does not provide the assistant service. Nothing is configured to answer, so nothing is sent.',
  [NO_PROFILE]:
    'No model provider is configured. An operator registers a provider profile before this panel can be used.',
  [INSECURE_REFUSED]:
    'The configured provider uses plaintext outside the credential-free local exception. Only an operator can replace it with HTTPS or explicitly permit an eligible local endpoint.',
  [HOST_NOT_ALLOWLISTED]:
    'The configured provider is not permitted by this deployment’s outbound policy. Only an operator can widen it.',
  [NOT_SIGNED_IN]:
    'Your Ravenroot session is not authenticated, so the assistant cannot be reached. Sign in to Ravenroot and try again.',
  // The one sentence here that describes something the reader can do from this panel, and it says
  // so. It names no credential, no storage and no protocol: see the custody note above — the panel
  // still cannot observe where anything is kept, and this state does not change that. What it CAN
  // observe is that the deployment asked for this author's connection, because the deployment said
  // so on the wire.
  [NOT_LINKED]:
    'This deployment expects each author to connect to the model provider themselves, and this account is not connected yet. You can start that from here.',
});

// ── NAMED SEND FAILURES AS THEY CROSS THE WIRE ──────────────────────────────────────────────────
//
// The server distinguishes nine ways a turn can fail and carries the reason in the error envelope's
// optional `assistantReason` field, as a bare `ASSISTANT_`-prefixed token. Before that field existed
// the seven then known collapsed onto four HTTP-level codes and the panel could say only which of
// four things happened; these sentences are what the distinction is FOR.
//
// ASSISTANT_ADAPTER_DEFECT is not a provider fault at all: it is what the server
// reports when its own adapter code throws something it did not already have a name for. It gets the
// same treatment as every other reason -- a distinct sentence, never pooled into a generic fallback --
// specifically because pooling THIS one would be the most misleading pool of all: it would tell an
// author to wait out a provider that was never at fault.
//
// ── THE SENTENCES ARE WRITTEN AGAINST WHAT EACH TOKEN MEANS, NOT AGAINST ITS NAME ────────────────
//
// Two rules, both learned the hard way on this panel:
//
// * NO SENTENCE TELLS THE AUTHOR TO DO AN OPERATOR'S JOB. The server's own default message for
// PROVIDER_REJECTED is "Check the configured profile" — accurate for whoever reads the server
// log, wrong for the author reading this panel, who cannot check it. Same reason the inert
// `host-not-allowlisted` text says only an operator can widen the allowlist.
// * EACH SAYS WHETHER RETRYING IS WORTH IT, because that is the author's actual next decision and
// it differs per token. PROVIDER_REJECTED is terminal by design (ADR 0018 §4 — an
// authentication rejection is never blindly retried, because retrying a rejected credential is
// how a subscription gets locked out), and EGRESS_REFUSED cannot change without an operator, so
// both say so rather than inviting a pointless retry.
//
// And, as with the inert reasons: NONE OF THEM SAYS WHERE THE PROVIDER CREDENTIAL LIVES. The panel
// still cannot observe custody, and `assistant-session.test.js` applies the same custody rule to
// this table — the hole that rule would otherwise have acquired the moment new sentences arrived.
export const ASSISTANT_FAILURE_TEXT = Object.freeze({
  ASSISTANT_PROVIDER_REFUSED:
    'The model declined to answer this request. Rephrasing the question may help — nothing is wrong with the graph or with Ravenroot.',
  ASSISTANT_PROVIDER_UNREADABLE:
    'The model provider returned a response this build cannot read, so nothing was answered. Trying again may succeed.',
  ASSISTANT_PROVIDER_REJECTED:
    'The model provider rejected this request. That is a configuration problem an operator has to resolve, so retrying will not clear it.',
  ASSISTANT_PROVIDER_UNAVAILABLE:
    'The model provider could not be reached, or did not answer in time. Trying again may succeed.',
  ASSISTANT_EGRESS_REFUSED:
    'This deployment’s outbound policy refused the provider destination. Only an operator can change that, so retrying will not help.',
  ASSISTANT_TOOL_LOOP_EXHAUSTED:
    'The model kept requesting context without reaching an answer, so the turn was stopped. A narrower question usually resolves this.',
  ASSISTANT_INVALID_TURN:
    'The message could not be read, so nothing was sent to the model. Sending it again unchanged will fail the same way — try rewording it.',
  ASSISTANT_MODEL_PROPOSAL_INVALID:
    'The request reached the model, but its graph proposal was still invalid after Ravenroot asked it to correct the proposal. Nothing was applied. Retrying will not help unless the request changes — rephrase it or add more graph detail.',
  ASSISTANT_ADAPTER_DEFECT:
    'Ravenroot’s assistant connection failed because of a defect in Ravenroot itself, not in the provider. Retrying will do the same thing. This needs a fix in Ravenroot — please report it.',
});

// The exact vocabulary this build understands. Exported so a test can assert it against the
// server's own `Reason.wireToken()` set: a token added on one side and not the other must FAIL
// rather than quietly degrade to the generic sentence and collapse four client meanings into seven
// server meanings.
export const ASSISTANT_FAILURE_TOKENS = Object.freeze(Object.keys(ASSISTANT_FAILURE_TEXT));

// An unrecognised token is NOT mapped to a neighbour. The server's vocabulary can grow before this
// build learns the word, and a token that merely looks like one we know is not one we know —
// `ASSISTANT_PROVIDER_REFUSED` and a future `ASSISTANT_PROVIDER_REFUSED_BY_POLICY` would mean
// different things and share a prefix. Returning null lets the caller fall back to the envelope's
// own prose, which is the same rule `send()` follows when it refuses to echo: say what is actually
// known, never the nearest plausible thing.
export function assistantFailureText(token) {
  if (typeof token !== 'string' || !token) return null;
  return Object.hasOwn(ASSISTANT_FAILURE_TEXT, token) ? ASSISTANT_FAILURE_TEXT[token] : null;
}

export const TRANSCRIPT_LIMIT = 200;

export const AUTHOR = 'author';
export const ASSISTANT = 'assistant';
export const NOTICE = 'notice';

// `availability` is what the server said about itself, normalized by `assistant-client.js`.
// Missing/unknown is treated as unavailable rather than as ready: a panel that assumed READY and
// discovered otherwise on send would have already told the user it was working.
// ── THE FIFTH CHECK READS A POSITIVE FIELD, AND THAT POLARITY IS THE DECISION ─────────────
//
// The four checks above are prerequisites: each reads a flag that must be TRUE, and a missing or
// unparseable field reads false and leaves the panel inert. That is fail-closed and right for them.
//
// `linkRequired` is not a prerequisite, it is a MODE — "this deployment expects each author to
// bring their own connection" — and naming it for the new state rather than for the old one is what
// keeps the default intact. The obvious alternative field, `linked`, inverts the danger: every
// operator-key deployment, and every status body written before the field existed, would read
// `linked: false` and go inert behind a control that resolves nothing. The operator-key path remains
// operator-key path the default, and a default that depends on a field being present is not one.
//
// This is not the fail-closed rule being bent. The three flags above are all read first, so this
// line is reached only from a body that was parsed and that answered true three times; an
// unreadable status still lands on `no-profile` exactly as before, never on READY.
export function inertReason(availability) {
  const status = availability || {};
  if (status.reachable === false) return SERVICE_UNAVAILABLE;
  if (!status.configured) return NO_PROFILE;
  if (status.insecureRefused === true) return INSECURE_REFUSED;
  if (!status.allowlisted) return HOST_NOT_ALLOWLISTED;
  if (!status.signedIn) return NOT_SIGNED_IN;
  if (status.linkRequired === true) return NOT_LINKED;
  return null;
}

// ── WHAT THE CONNECTION CAN DO WHILE THE AUTHOR IS AWAY AT THE PROVIDER'S PAGE ────────────
//
// The server-side mechanism already distinguishes these five and refuses to pool them (RFC 8628
// §3.5). They reach the panel as bare tokens on the connection route, and each needs a genuinely
// different thing from the author, which is why the sentences differ rather than sharing a stem:
//
// * AUTHORIZATION_PENDING is not a failure at all — it is the normal answer for as long as the
// author has not finished, and the only correct response is to keep waiting.
// * SLOW_DOWN is the provider rate-limiting the poll. The author does nothing; the interval
// lengthens. Telling them to retry would make them act on a message about our own timing.
// * ACCESS_DENIED is an answer, not an error: the author refused on the provider's page. It is
// terminal, and a panel that kept polling after it would be arguing with a decision.
// * EXPIRED_TOKEN means the grant timed out. Also terminal, and unlike a denial it usually means
// the author never got there — so the remedy is a fresh start rather than a reconsideration.
// * UNAVAILABLE covers transport, a refused host and an unreadable answer, pooled here because
// the mechanism itself pools them: it declines to guess which, and so does this.
//
// AND NONE OF THESE SENTENCES SAYS WHERE THE CREDENTIAL LIVES. That rule does not relax because the
// subject is a sign-in; it is at its most tempting here, which is why `assistant-connection.test.js`
// applies the same matchers to this table that `assistant-session.test.js` applies to the two above.
export const CONNECTION_FAILURE_TEXT = Object.freeze({
  AUTHORIZATION_PENDING:
    'Waiting for you to finish on the provider’s page. This panel will notice on its own — nothing else is needed here.',
  SLOW_DOWN:
    'The provider asked for fewer checks, so this panel is now asking less often. Nothing has gone wrong and there is nothing to retry.',
  ACCESS_DENIED:
    'The request was declined on the provider’s page, so no connection was made. Nothing further will happen unless you start again.',
  EXPIRED_TOKEN:
    'The code ran out before it was used, so this attempt is over. Starting again issues a new code.',
  UNAVAILABLE:
    'The provider could not be reached, or answered something this build cannot read, so the attempt stopped. Starting again may succeed.',
});

// The exact vocabulary this build understands, exported so a test can pin it against the server's
// own `AssistantDeviceAuthorization.Failure` enum — the same pinning `ASSISTANT_FAILURE_TOKENS`
// gets, and for the same reason: a token added on one side and not the other must fail loudly
// rather than degrade into a sentence about a different outcome.
export const CONNECTION_FAILURE_TOKENS = Object.freeze(Object.keys(CONNECTION_FAILURE_TEXT));

// Unrecognised is null, never the nearest neighbour. Same rule as `assistantFailureText`.
export function connectionFailureText(token) {
  if (typeof token !== 'string' || !token) return null;
  return Object.hasOwn(CONNECTION_FAILURE_TEXT, token) ? CONNECTION_FAILURE_TEXT[token] : null;
}

// ── THE ONE PREDICATE THAT DECIDES WHETHER THE CONTROL IS OFFERED ──────────────────────────
//
// One function, consulted by the renderer, rather than a condition spelled out at the call site.
// The distinction is easy to widen by accident — `state === INERT` alone
// would offer the control on all five reasons — and a widening has to happen HERE, where the test
// table sits, instead of inside a render function nobody reads as policy.
//
// It takes a derived panel state rather than an availability, so it cannot disagree with what the
// panel is showing: the control appears if and only if the sentence beside it is the one that says
// connecting is the remedy.
export function offersConnection(panelState) {
  return panelState?.state === INERT && panelState?.reason === NOT_LINKED;
}

// The panel's whole state in one value, so a renderer never has to combine three flags and get the
// precedence wrong. ERROR outranks everything: a failed exchange is what the user is looking at.
export function deriveState({ availability, context, error } = {}) {
  if (error) {
    return {
      state: ERROR,
      reason: error.reason || null,
      // Sanitized upstream by the client; carried, never interpreted, and never rendered as HTML.
      message: String(error.message || 'The assistant request failed.'),
      canCompose: false,
    };
  }
  const reason = inertReason(availability);
  if (reason) {
    return { state: INERT, reason, message: INERT_REASON_TEXT[reason], canCompose: false };
  }
  if (context?.degraded) {
    return {
      state: DEGRADED,
      reason: null,
      message: 'Some context is unavailable. Check the chips below before relying on the answer.',
      canCompose: true,
    };
  }
  return { state: READY, reason: null, message: '', canCompose: true };
}

// Like `#activity-log`, this transcript is an append-only live region: a turn is appended, never
// re-parented, and the transcript never regroups. Its structure must not mutate as content arrives.
// Flat, ordered, capped. The cap drops the OLDEST, so the
// thing that just arrived is never the thing that disappears.
// THE CAP DOES NOT DROP THE DISCLOSURE, and that exception is load-bearing rather than tidy.
// The cap trims from the OLDEST, and the disclosure is by construction the oldest thing in any
// transcript that has one — so the unexceptional cap deletes it first. Two things break when it
// goes: `isDisclosed` reports the user has not been told and a second disclosure is emitted mid
// conversation, and every AI turn still on screen keeps an `aria-describedby` pointing at an id
// that no longer exists, which is a dangling reference a screen reader resolves to nothing. The
// disclosure is not conversational content competing for the 200 slots; it is a standing statement
// about all of them, so it is held out of the trim and the cap applies to the rest.
//
// The drop is BY POSITION, not by partition. Partitioning as `[...disclosure, ...rest]` would trim
// correctly but quietly REORDER the transcript by moving the disclosure ahead of the author turn it
// followed. A live region's structure must not mutate as content arrives, and the array must stay in
// sync with a DOM that only ever appends. The oldest NON-disclosure entry is therefore removed where
// it stands while everything else keeps its index.
export function appendTurn(transcript, turn) {
  const entries = Array.isArray(transcript) ? transcript : [];
  const next = [...entries, normalizeTurn(turn, entries.length)];
  if (next.length <= TRANSCRIPT_LIMIT) return next;
  const oldest = next.findIndex(entry => entry.role !== DISCLOSURE_ROLE);
  if (oldest < 0) return next;
  return [...next.slice(0, oldest), ...next.slice(oldest + 1)];
}

function normalizeTurn(turn, index) {
  const role = turn?.role === AUTHOR || turn?.role === ASSISTANT || turn?.role === NOTICE
    || turn?.role === DISCLOSURE_ROLE
    ? turn.role
    : NOTICE;
  return {
    id: String(turn?.id || `turn-${index}-${Date.now()}`),
    role,
    // Always a string. The renderer escapes it; keeping it a string here means the renderer never
    // has to decide what a non-string turn looks like.
    text: String(turn?.text ?? ''),
    at: turn?.at || new Date().toISOString(),
    // Which classes were actually attached to the prompt that produced this turn — the per-message
    // half of the disclosure. Copied, so a later recompose cannot rewrite history.
    attached: Array.isArray(turn?.attached) ? [...turn.attached] : [],
  };
}

// Why a send is refused, in the words shown next to the composer. Returned rather than thrown: the
// caller associates the message with the control through `aria-errormessage`, and an exception has
// nowhere to be associated to.
export function validateDraft(draft, panelState) {
  if (!panelState?.canCompose) {
    return { ok: false, message: 'The assistant is not available, so nothing can be sent.' };
  }
  const text = String(draft ?? '').trim();
  if (!text) return { ok: false, message: 'Type a question before sending.' };
  return { ok: true, message: '', text };
}

// THERE IS NO `EFFECTORS` CONSTANT HERE, AND THAT IS DELIBERATE.
//
// An exported frozen empty array would not enforce this boundary: no production code would read it,
// so effectors could be added without changing the array. A control reachable by no caller has the
// same defect as a guard reachable only through callers that already obey it; it declares a property
// it cannot enforce.
//
// What actually enforces the absence, and can fail:
// * `assistant-client.js` `send()` projects a reply to exactly `{ text, model }`, asserted
// against a fixture body that carries `toolCalls` and `actions`;
// * `deriveState` returns exactly `{ canCompose, message, reason, state }`, asserted key-by-key;
// * the shipped panel's `data-action` inventory is asserted as an exact set, and it contains no
// command control at all.
// Adding an effector has to defeat all three.
