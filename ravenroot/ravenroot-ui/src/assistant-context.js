// The authoring assistant's context attachment claim, and the one place it can be falsified
// (the assistant-tool boundary).
//
// ── WHY THIS MODULE EXISTS AT ALL ────────────────────────────────────────────────────────────────
//
// A panel that SAYS it attached the graph and did not is worse than a panel that attached nothing:
// the user reads the answer as informed when it was not, and there is no moment at which the
// mistake becomes visible. The failure mode has a name in the design record — "the false-context
// defect" — and the countermeasure is not a review habit. It is this:
//
// THE CHIP AND THE PAYLOAD ARE PRODUCED BY ONE PASS OVER ONE ARRAY.
//
// `composeContext` reads each source exactly once, records what happened in a class entry, and then
// DERIVES the payload from those same entries by projection. There is no second traversal in which
// the two could disagree, so "the chip says attached but the payload is empty" is not a bug to be
// caught in review — it is a state this function cannot construct. `attachmentClaimViolations`
// states that equivalence explicitly so a test asserts the property rather than re-deriving it, and
// so a future change that splits the pass in two reds immediately.
//
// ── WHY UNAVAILABLE IS NOT ONE REASON ────────────────────────────────────────────────────────────
//
// "No graph is open" and "the catalog source threw" both leave a class unattached, but they are not
// the same fact and must not collapse into one chip. Nothing is broken in the first; something is
// broken in the second, and only the second may put the panel into DEGRADED. Pooling them would
// make DEGRADED either permanently on (every fresh session has no execution yet) or permanently
// useless — the same "distinguished, not pooled" rule the panel's INERT reasons follow.
//
// This module is deliberately free of DOM and of network, like `panel-layout.js` and `panes.js`:
// what a context IS and what makes a claim false are rules, and rules are testable without a
// browser.

export const ATTACHED = 'attached';
export const UNAVAILABLE = 'unavailable';

// Why a class is not attached. Only `SOURCE_ERROR` degrades the panel — see the header.
export const NOT_PROVIDED = 'not-provided';
export const NO_DATA = 'no-data';
export const SOURCE_ERROR = 'source-error';

// The context classes are the open graph, the node catalog, validation errors, the last
// execution's result, logs and the event stream.
//
// `tier` IS FORWARD-LOOKING LABELLING, NOT A DESCRIPTION OF THIS BUILD. the assistant-tool boundary
// designs tier 0 as the product's own APIs reached in-process through
// `AuthorizedRavenrootApplication` under the author's own SecurityContext, with internal tools
// holding no HTTP client. **None of that exists yet** — there is no server-side assistant service.
// In this build `catalog` and `events` come from browser state the editor already has, and `logs`
// is not wired at all precisely because its tier-0 path is part of that absent service. The field
// records which tier a class WILL come from, so the wiring can be checked against the design when
// the backend lands; it grants nothing and enforces nothing today.
//
// The weaker property that IS true now is worth stating in its own words, because it is easy to
// read the stronger one into it: the panel cannot see what the user's own UI cannot — not because
// of the type-level split ADR 0025 designs, but because it has no independent read path at all.
// Every class here is composed from state the editor already holds, fetched under the user's own
// session by the same client the rest of the UI uses.
export const CONTEXT_CLASSES = Object.freeze([
  Object.freeze({ id: 'graph', label: 'Open graph', tier: 1 }),
  Object.freeze({ id: 'catalog', label: 'Node catalog', tier: 0 }),
  Object.freeze({ id: 'validation', label: 'Validation errors', tier: 1 }),
  Object.freeze({ id: 'execution', label: 'Last execution', tier: 1 }),
  Object.freeze({ id: 'logs', label: 'Logs', tier: 0 }),
  Object.freeze({ id: 'events', label: 'Event stream', tier: 0 }),
]);

export const CONTEXT_CLASS_IDS = Object.freeze(CONTEXT_CLASSES.map(entry => entry.id));

// Per class, not per payload: one enormous graph must not silently evict the validation errors that
// would have explained it. Truncation is always SHOWN (`truncated` reaches the chip) because a
// silent drop is the same defect as a false attachment one level down.
export const DEFAULT_CLASS_BUDGET_BYTES = 32 * 1024;

// `sources` maps a class id to a zero-argument function returning that class's value. A class with
// no function is `not-provided` — the honest answer for a build or a deployment where the source is
// not wired, and distinct from a wired source that failed.
export function composeContext(sources = {}, options = {}) {
  const budget = Number.isFinite(options.budgetBytes) && options.budgetBytes > 0
    ? Math.trunc(options.budgetBytes)
    : DEFAULT_CLASS_BUDGET_BYTES;

  // Pass one, and the only pass: read every source, record what happened.
  const classes = CONTEXT_CLASSES.map(descriptor =>
    readClass(descriptor, sources?.[descriptor.id], budget));

  // The payload is a PROJECTION of the entries above, never an independent build. This single line
  // is what makes the claim structural: an entry cannot read ATTACHED without landing in the
  // payload, because the payload is made out of the entries that read ATTACHED.
  const payload = Object.fromEntries(classes
    .filter(entry => entry.state === ATTACHED)
    .map(entry => [entry.id, entry.value]));

  return {
    classes,
    payload,
    // Something that was supposed to work did not. `no-data` and `not-provided` are ordinary, so
    // they never light this: see the header.
    degraded: classes.some(entry => entry.reason === SOURCE_ERROR),
    truncated: classes.some(entry => entry.truncated),
  };
}

function readClass(descriptor, source, budget) {
  const base = { id: descriptor.id, label: descriptor.label, tier: descriptor.tier, truncated: false };

  if (typeof source !== 'function') {
    return { ...base, state: UNAVAILABLE, reason: NOT_PROVIDED, detail: 'not available in this build' };
  }

  let raw;
  try {
    raw = source();
  } catch (error) {
    // A source that throws is a source that was LOST, which is the case DEGRADED exists for.
    return {
      ...base,
      state: UNAVAILABLE,
      reason: SOURCE_ERROR,
      detail: String(error?.message || error || 'source failed'),
    };
  }

  if (raw === null || raw === undefined) {
    return { ...base, state: UNAVAILABLE, reason: NO_DATA, detail: 'nothing to attach yet' };
  }

  const clamped = clampToBudget(raw, budget);
  return {
    ...base,
    state: ATTACHED,
    reason: null,
    truncated: clamped.truncated,
    detail: clamped.truncated ? `truncated · ${clamped.omittedBytes} bytes omitted` : '',
    value: clamped.value,
  };
}

// Over budget the class still attaches — dropping it entirely would turn a size problem into a
// false `unavailable` — but it attaches AS A TRUNCATION, saying so in the value and in the chip.
function clampToBudget(raw, budget) {
  let serialized;
  try {
    serialized = JSON.stringify(raw) ?? '';
  } catch (error) {
    // A cyclic or unserializable value cannot be sent; that is a source defect, reported as one
    // rather than silently attached in some partial form.
    throw new Error(`context value is not serializable: ${error?.message || error}`);
  }
  if (serialized.length <= budget) return { value: raw, truncated: false, omittedBytes: 0 };
  return {
    value: { truncated: true, omittedBytes: serialized.length - budget, preview: serialized.slice(0, budget) },
    truncated: true,
    omittedBytes: serialized.length - budget,
  };
}

// THE CLAIM, stated once so tests assert it instead of re-deriving it: a class reads ATTACHED
// exactly when the payload carries it. Returns the offending class ids rather than a boolean, so a
// failing assertion names which class lied.
export function attachmentClaimViolations(context) {
  const classes = context?.classes || [];
  const payload = context?.payload || {};
  return classes
    .filter(entry => (entry.state === ATTACHED) !== Object.hasOwn(payload, entry.id))
    .map(entry => entry.id);
}

// The longest a chip's reason may be. A chip is a label in a wrapping row, not a log line: a
// multi-line stack trace in one would push the composer off the bottom of the column, which is the
// same scarce-height problem measured. The full text still reaches the reader through the
// payload inspector and the console; this is the summary.
export const CHIP_DETAIL_LIMIT = 72;

// What a chip says out loud. Kept here rather than in the renderer so the sentence a screen reader
// announces is covered by this module's tests, not only by a DOM test that may not exist.
export function contextChipDescription(entry) {
  const detail = chipDetail(entry.detail);
  if (entry.state === ATTACHED) {
    return detail ? `${entry.label}: attached, ${detail}` : `${entry.label}: attached`;
  }
  return detail ? `${entry.label}: unavailable, ${detail}` : `${entry.label}: unavailable`;
}

function chipDetail(value) {
  // One line: an embedded newline would break the row layout and, in a live region, be announced
  // as a separate utterance.
  const text = String(value ?? '').replace(/\s+/g, ' ').trim();
  if (text.length <= CHIP_DETAIL_LIMIT) return text;
  return `${text.slice(0, CHIP_DETAIL_LIMIT - 1).trimEnd()}…`;
}
