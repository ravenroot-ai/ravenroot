// The Article 50 disclosure: the guarantee that no model-generated turn reaches a natural person
// before that person has been told the output is artificially generated (the disclosure contract).
//
// ── WHY THIS IS A GATE AND NOT A SENTENCE ────────────────────────────────────────────────────────
//
// The obvious implementation is a line of text the renderer draws above the transcript. It reads
// correctly and it is worthless as a control, for the reason `assistant-session.js` already gives
// about the deleted `EFFECTORS` constant: a declaration no code path is forced through cannot enforce
// behavior. A disclosure that the renderer
// must REMEMBER to draw is one refactor away from not being drawn, and the failure is silent —
// the panel still works, the answers still arrive, and the only thing missing is the thing the
// Regulation actually requires.
//
// So the disclosure is placed on the ADMISSION PATH of the turn it discloses. `admitTurn` is the
// only way an AI-origin turn enters a transcript, and it emits the disclosure ahead of that turn
// when the transcript does not already carry one. Ordering is not a convention the caller keeps;
// it is the return value's shape. Deleting the disclosure means deleting the function that appends
// the reply, which cannot be done silently.
//
// ── WHY IT DOES NOT LIVE IN `app.js`, AND WHAT THAT BUYS ─────────────────────────────────────────
//
// Article 50(1) binds whoever builds and places on the market an interactive AI system. On the
// current qualification (ADR 0017, and the integrator note's paragraph-by-paragraph breakdown) that
// is NOT this project — Ravenroot self-hosts, ships no adapter, and operates no endpoint. It is the
// integrator who supplies a `ModelProvider` and puts a conversational surface in front of a natural
// person.
//
// That is precisely why the capability cannot be hardwired into this panel. A disclosure living
// inside `app.js` discharges an obligation this project does not carry, for the one surface this
// project does not ship a model behind, and leaves the party who DOES carry it with nothing to
// reuse. So this module is DOM-free, network-free, framework-free and imports nothing from the
// editor: a transcript is any array at all — `options.disclosed` says how to recognise a disclosure
// inside it — and appending is whatever function the caller passes. The one thing still fixed is
// the shape of the INCOMING turn: `disclosurePlan` reads `turn.role` with no seam for it, so a
// caller with different role field naming maps at the call, not through an option.
// The panel is one consumer of it. An integrator's own endpoint is another, and
// gets the identical ordering guarantee without reimplementing any part of it — which is the
// property `test/assistant-disclosure.test.js` demonstrates by building a second endpoint that
// shares no code with this UI.
//
// ── WHAT THIS MODULE DOES NOT DO ─────────────────────────────────────────────────────────────────
//
// It does not gate egress and it is not consent. Consent pairs first-use consent with per-class
// toggles over the context classes; that is a separate capability, and adding an "I agree"
// affordance here would conflate disclosure with consent.
// Nothing here has a control, a state to persist, or an answer to record. It states a fact; it
// never asks for one.

// The roles whose content originates from a model. `assistant-session.js` owns the role vocabulary;
// this set is the subset of it that Article 50(1) attaches to, and it is deliberately NOT imported
// from there — an integrator with its own role names composes `admitTurn` by passing its own
// `roles`, and this module must stay usable without adopting the panel's vocabulary wholesale.
//
// ── THE SCOPE LIMIT, WHICH IS AN ALLOWLIST AND THEREFORE FAILS SILENT ────────────────────────────
//
// The guarantee holds for turns whose role is IN this set. It says nothing about a model-origin
// role outside it, and the failure is not abstract in this wiring: `disclosurePlan` reads the raw
// role BEFORE `normalizeTurn` sees it, and `normalizeTurn` coerces an unrecognised role to
// `NOTICE`. So a future model-origin role — a tool result, a summariser, an effector's narration —
// added to `app.js` without also being added here renders as a Ravenroot notice, attributed to the
// product, with no disclosure and no test going red. Under-disclosure is the regulatory failure and
// this polarity puts the silence on exactly that side.
//
// Adding a model-origin role to the panel means adding it here and to `assistant-session.js`, and
// nothing enforces that pairing today. Inverting the polarity — disclose unless the role is in a
// declared human/product set — is filed as follow-up to land before a provider is connected.
export const AI_ORIGIN_ROLES = Object.freeze(['assistant']);

// The role the disclosure turn itself carries. It is attributed to the product, not to the model:
// a disclosure that the model appeared to author would be a statement about AI output made BY AI
// output, which is not a disclosure at all.
export const DISCLOSURE_ROLE = 'disclosure';

// The stable id. It is what the rendered element is keyed by, and therefore what an AI-origin turn
// points `aria-describedby` at — see `describedById`.
export const DISCLOSURE_TURN_ID = 'assistant-disclosure';

// The disclosed fact, in the terms Article 50(1) requires: that the person is interacting with an
// AI system and that what follows is machine-generated.
//
// Two things it deliberately does NOT say. It does not name a provider, because none is configured
// at the time this text is fixed and a disclosure that guessed would be wrong in the deployment
// that matters. And it does not claim the output is accurate, safe or reviewed — a disclosure that
// reassures is doing the opposite of disclosing.
export const DISCLOSURE_TEXT =
  'You are interacting with an AI system. The replies below are generated by an AI model, '
  + 'may be inaccurate, and are not reviewed by a person before you see them.';

// A short form for surfaces with no room for the full sentence — a composer hint, a collapsed
// header. It is an ADDITION to the full disclosure and never a replacement: `admitTurn` always
// emits the full text, and nothing here lets a caller substitute this for it.
export const DISCLOSURE_SHORT_TEXT = 'Replies are generated by an AI model.';

export function isAiOriginRole(role, roles = AI_ORIGIN_ROLES) {
  return roles.includes(role);
}

// Has the disclosure already been delivered in this transcript?
//
// Read off the transcript itself rather than held in a flag beside it. A flag and a transcript can
// disagree — a cleared conversation with a stale `alreadyDisclosed = true` is exactly the shape of
// bug that produces an undisclosed first reply, and it would be invisible because the flag says the
// work was done. The transcript is the thing the user actually sees, so it is the only honest
// authority on what the user has actually been told.
export function isDisclosed(transcript) {
  const entries = Array.isArray(transcript) ? transcript : [];
  return entries.some(entry => entry?.role === DISCLOSURE_ROLE);
}

export function disclosureTurn() {
  return {
    id: DISCLOSURE_TURN_ID,
    role: DISCLOSURE_ROLE,
    text: DISCLOSURE_TEXT,
    at: new Date().toISOString(),
    attached: [],
  };
}

// The ordered turns to append so that `turn` is admitted with its disclosure obligation met.
//
// Exported separately from `admitTurn` so the ordering can be exercised as a value — a test reads
// the plan and asserts the disclosure is at index 0, without needing a transcript implementation
// or an append function to observe it through. `assistant-client.js` exports `assistantUrl` for the
// same reason: a control reachable only through the callers that already obey it is a control whose
// failure mode no test can reach.
// `options.disclosed` overrides how "already disclosed" is recognised, and it exists because the
// demonstration in `assistant-disclosure.test.js` FAILED without it.
//
// The default reads `entry.role`, which is this project's transcript shape. An integrator storing
// turns as `{ who, body }` maps the disclosure into their own vocabulary on the way in, the `role`
// field does not survive, and the default predicate then reports "not yet disclosed" on every
// subsequent reply — emitting a fresh disclosure before each one. That is not a cosmetic bug: a
// capability that only works for callers who adopt this panel's field names is hardwired to this
// panel. Ordering tests alone do not expose that integration failure.
//
// So recognition is a seam, like `append` is. What the integrator supplies is a description of
// THEIR OWN STORAGE — one line saying which of their entries is the disclosure. They still do not
// write the text, the ordering, or the once-only rule.
export function disclosurePlan(transcript, turn, options = {}) {
  const { roles = AI_ORIGIN_ROLES, disclosed = isDisclosed } = options;
  const owed = isAiOriginRole(turn?.role, roles) && !disclosed(transcript);
  return { owed, turns: owed ? [disclosureTurn(), turn] : [turn] };
}

// THE ADMISSION PATH. Every AI-origin turn goes through here or the guarantee does not hold.
//
// `append` is the caller's own transcript-append — `appendTurn` for this panel, `Array.push` for a
// plain-array integrator, an immutable insert for whoever wants one. It is a parameter rather than
// an import because the ordering guarantee is independent of how a transcript is stored, and
// requiring this project's storage in order to get this project's disclosure is the hardwiring this
// module exists to avoid.
export function admitTurn(transcript, turn, append, options = {}) {
  if (typeof append !== 'function') {
    throw new TypeError('admitTurn requires an append function');
  }
  return disclosurePlan(transcript, turn, options).turns
    .reduce((carry, entry) => append(carry, entry), transcript);
}

// What an AI-origin turn must point `aria-describedby` at, or `null` for every other turn.
//
// Article 50(5) requires the disclosure be provided in a "clear and distinguishable manner" and
// conform to applicable accessibility requirements. Rendering it above the reply satisfies neither
// on its own: a screen-reader user who arrives at a reply by navigating the transcript reaches the
// text with nothing attached to it, and adjacency in the DOM is not something assistive technology
// reports. The association has to be PROGRAMMATIC, which is what this returns — so the disclosure
// is reachable FROM the disclosed content, at the moment that content is read, however the user
// got there.
export function describedById(role, roles = AI_ORIGIN_ROLES) {
  return isAiOriginRole(role, roles) ? DISCLOSURE_TURN_ID : null;
}
