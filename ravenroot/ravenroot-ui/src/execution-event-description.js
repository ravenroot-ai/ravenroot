export const MAX_PUBLIC_DESCRIPTION_UTF8_BYTES = 256;
export const PUBLIC_DESCRIPTION_TRUNCATION_MARKER = ' [truncated]';
export const UNKNOWN_EXECUTION_DESCRIPTION = 'Execution activity was reported.';

/** Mirrors `ExecutionEvent.MAX_PUBLIC_REASON_LENGTH`. */
export const MAX_PUBLIC_REASON_LENGTH = 64;

/** Mirrors `ExecutionEvent.DEFAULT_ROUTED_OUTCOME`: the one outcome that means plain success. */
export const DEFAULT_ROUTED_OUTCOME = 'continue';

/**
 * Mirrors `ExecutionEvent.BYPASS_REASON_COMMAND` / `BYPASS_REASON_AUTHORED`.
 *
 * Two different facts share `NODE_BYPASSED`. One
 * says the whole run is a rehearsal -- an inbound `command=passthrough`, or a submission in
 * play/test mode, under which nothing executes from that point on. The other says one node is
 * switched off in the saved document while the rest of the run is real. A reader deciding whether a
 * result is trustworthy has to tell those apart, and `detail` cannot help: it is an in-process
 * diagnostic that `RavenrootServer` never serializes, by design, because on other event types it can
 * carry exception text.
 */
export const BYPASS_REASON_COMMAND = 'command.passthrough';
export const BYPASS_REASON_AUTHORED = 'authored';

/**
 * The classifier, or null when the value is not one.
 *
 * Mirrors the server rule character for character, and rejects rather than repairs for the same
 * reason: a trimmed token reads exactly like a real one. This runs on data off the wire, so it is a
 * boundary check and not a duplicate of a check already made -- a fixture, a proxy or an older peer
 * can send anything under this key, and it lands inside a sentence.
 */
export function conformingPublicReason(value) {
  if (typeof value !== 'string' || !value || value.length > MAX_PUBLIC_REASON_LENGTH) return null;
  return /^[A-Za-z0-9._:-]+$/.test(value) ? value : null;
}

/**
 * The sentence for an event whose classifier is known, or null when the type does not use one.
 *
 * Kept in step with `PublicExecutionDescription.forType(type, publicReason)` on the server. This is
 * the fallback path -- a current server already sends the composed sentence in `description` -- but it
 * has to exist and has to agree, otherwise a peer that predates `publicReason`, or one that sends the
 * classifier without the sentence, silently reverts the panel to the false copy.
 */
function describeWithReason(type, publicReason, text = uiText) {
  const reason = conformingPublicReason(publicReason);
  if (!reason) return null;
  switch (String(type || '')) {
    case 'NODE_COMPLETED':
      return reason === DEFAULT_ROUTED_OUTCOME
        ? text('executionEvent.nodeCompletedSuccess')
        : text('executionEvent.nodeCompletedOutcome', { reason });
    case 'NODE_FAILED':
      return text('executionEvent.nodeFailedReason', { reason });
    case 'EXECUTION_FAILED':
      return text('executionEvent.executionFailedReason', { reason });
    case 'JOIN_FAILED':
      return text('executionEvent.joinFailedReason', { reason });
    // Kept in step with `PublicExecutionDescription.forType`'s NODE_RETRY_SCHEDULED branch. The
    // classifier here is the failure's retry classification, a fixed four-member vocabulary rather
    // than a Java type name, and it is named rather than characterised for the same reason a routed
    // outcome is.
    case 'NODE_RETRY_SCHEDULED':
      return text('executionEvent.retryReason', { reason });
    // Kept in step with `PublicExecutionDescription.forType`'s NODE_BYPASSED branch -- INCLUDING
    // the thing that branch deliberately does NOT do. The cases above interpolate the classifier into
    // the sentence; this one SELECTS among sentences written here, so an unrecognised token falls
    // through to the plain 'Node was bypassed.' in DESCRIPTION_BY_TYPE rather than producing a
    // sentence built around a string neither side can vouch for. Durable replay, which never captured
    // a classifier, lands on that same bare sentence.
    case 'NODE_BYPASSED':
      if (reason === BYPASS_REASON_AUTHORED) {
        return text('executionEvent.bypassedAuthored');
      }
      if (reason === BYPASS_REASON_COMMAND) {
        return text('executionEvent.bypassedCommand');
      }
      return null;
    default:
      return null;
  }
}

const DESCRIPTION_KEY_BY_TYPE = Object.freeze({
  EXECUTION_STARTED: 'executionEvent.executionStarted',
  NODE_STARTED: 'executionEvent.nodeStarted',
  NODE_BYPASSED: 'executionEvent.nodeBypassed',
  NODE_DEFAULTED: 'executionEvent.nodeDefaulted',
  // NOT "successfully". This table is the no-classifier fallback, and a node that routed its
  // `failed` outcome emits NODE_COMPLETED too -- it completed, and the traversal went down the
  // failure route its author declared. Claiming success here was false exactly where the reader was
  // looking to find out what went wrong. Success is asserted only in `describeWithReason` below,
  // which knows the outcome.
  NODE_COMPLETED: 'executionEvent.nodeCompleted',
  SOURCE_BOUNDARY: 'executionEvent.sourceBoundary',
  // Found by the structural check in this module's test, not by review: this arm had been missing
  // since the type was introduced, so every routed edge rendered as generic activity. Same defect
  // class as the cancellation entry below -- a positive allow-list degrades silently when the
  // server's vocabulary grows.
  EDGE_TRAVERSED: 'executionEvent.edgeTraversed',
  NODE_FAILED: 'executionEvent.nodeFailed',
  // The no-classifier fallback, so it names neither the classification nor the wait. Both are on the
  // event for a reader entitled to them; `describeWithReason` below adds the classification when the
  // server sent one.
  NODE_RETRY_SCHEDULED: 'executionEvent.retryScheduled',
  JOIN_SATISFIED: 'executionEvent.joinSatisfied',
  JOIN_ITERATION_BACKLOG: 'executionEvent.joinBacklog',
  JOIN_ARRIVAL_DISCARDED: 'executionEvent.joinDiscarded',
  JOIN_FAILED: 'executionEvent.joinFailed',
  // "Holding" rather than "stopped", matching `PublicExecutionDescription.forType`'s own choice: a
  // paused execution has not stopped -- it keeps its state, it is still listed live and it is still
  // cancellable. A reader told an execution had stopped would go looking for a result that is not
  // coming.
  EXECUTION_PAUSED: 'executionEvent.executionPaused',
  EXECUTION_RESUMED: 'executionEvent.executionResumed',
  EXECUTION_COMPLETED: 'executionEvent.executionCompleted',
  EXECUTION_FAILED: 'executionEvent.executionFailed',
  // No "failed", and no pointer to protected diagnostics either -- there are none to read. Matching
  // `PublicExecutionDescription.forType`'s own choice: a cancelled execution stopped because
  // somebody with the authority to stop it did, and copy that sent an operator looking for a fault
  // would recreate, in words, the confusion this event type exists to remove. Without this entry a
  // cancellation rendered as UNKNOWN_EXECUTION_DESCRIPTION -- generic activity, in the one view an
  // operator uses to find out what happened to a run.
  //
  // Deliberately NO `describeWithReason` case above, and the omission mirrors the server exactly.
  // The event does arrive carrying a conforming `publicReason` -- the deepest cause's class name --
  // so a classifier branch looks like it belongs; the server's own classifier switch has no arm for
  // this type and falls through to the copy here. Adding one on this side would put a Java type name
  // into a sentence the server never composes.
  EXECUTION_CANCELLED: 'executionEvent.executionCancelled',
  // Durable-journal types. They reach this table only as the fallback for a peer that sent no
  // `description`; the server composes the same sentences from its own source-authored copy. They
  // live here rather than being left to UNKNOWN because a handler event that rendered as generic
  // activity would be indistinguishable from a node event in the one view an operator uses to find
  // out why a process has not moved.
  HANDLER_REGISTERED: 'executionEvent.handlerRegistered',
  HANDLER_ESCALATED: 'executionEvent.handlerEscalated',
  HANDLER_EXPIRED: 'executionEvent.handlerExpired',
  HANDLER_DENIED: 'executionEvent.handlerDenied',
  HANDLER_RESOLVED: 'executionEvent.handlerResolved',
});

/**
 * Resolves the one public human-readable field used by the activity log.
 *
 * Legacy payloads did not carry `description`; their diagnostic `detail` is deliberately ignored
 * because it may contain an exception message or graph-authored value. Unknown event codes still get
 * useful fixed copy. The transport field is normalized defensively even though the server applies the
 * same contract, so a fixture, proxy or older peer cannot introduce controls or an oversized DOM row.
 *
 * The three-step preference is ordered by how much each source knows: the server's composed
 * sentence, then one composed here from the classifier, then the type alone. The middle step is what
 * keeps a peer that sends `publicReason` without `description` from falling all the way through to
 * copy that would call a routed failure a success.
 */
export function publicExecutionDescription(description, type, publicReason) {
  const fallbackKey = DESCRIPTION_KEY_BY_TYPE[String(type || '')];
  const supplied = typeof description === 'string' && description.trim()
    ? normalizePublicDescription(description, '') : '';
  const englishProductDescription = describeWithReason(type, publicReason, englishText)
    || (fallbackKey && englishText(fallbackKey)) || '';
  // Current servers send their English product fallback in `description`, while providers may send
  // authored public copy in the same bounded field. Translate only the known product sentence;
  // preserve every distinct authored value literally in every selected locale.
  const authored = supplied && supplied !== normalizePublicDescription(englishProductDescription, '')
    ? supplied : '';
  const candidate = authored
    || describeWithReason(type, publicReason)
    || (fallbackKey && uiText(fallbackKey))
    || uiText('executionEvent.unknown');
  return normalizePublicDescription(candidate, uiText('executionEvent.unknown'));
}

export function normalizePublicDescription(value, emptyFallback = UNKNOWN_EXECUTION_DESCRIPTION) {
  let normalized = '';
  let previousSpace = true;
  for (const character of String(value || '')) {
    const codePoint = character.codePointAt(0);
    const replace = /[\p{Cc}\p{Cf}\p{Zl}\p{Zp}\s]/u.test(character)
      || (codePoint >= 0xd800 && codePoint <= 0xdfff);
    if (replace) {
      if (!previousSpace) {
        normalized += ' ';
        previousSpace = true;
      }
    } else {
      normalized += character;
      previousSpace = false;
    }
  }
  normalized = normalized.trim();
  if (!normalized) return emptyFallback;

  const encoder = new TextEncoder();
  if (encoder.encode(normalized).byteLength <= MAX_PUBLIC_DESCRIPTION_UTF8_BYTES) return normalized;
  const budget = MAX_PUBLIC_DESCRIPTION_UTF8_BYTES
    - encoder.encode(PUBLIC_DESCRIPTION_TRUNCATION_MARKER).byteLength;
  let bounded = '';
  let used = 0;
  for (const character of normalized) {
    const encoded = encoder.encode(character).byteLength;
    if (used + encoded > budget) break;
    bounded += character;
    used += encoded;
  }
  return bounded + PUBLIC_DESCRIPTION_TRUNCATION_MARKER;
}
import { createUiText, uiText } from './ui-text.js';

const englishText = createUiText();
